// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.discovery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The capability document as bytes: ordered, bounded, and refusing what it cannot answer honestly.
 *
 * <p>The contracts are emitted in wire order so that two builds comparing the same set compare it
 * in the same order, and a repeated wire name is refused rather than emitted — a second contract
 * under a name a client has already read would shadow the first, and which of the two shadowed the
 * other would depend on how the client's own reader was written.</p>
 *
 * <p>A document past the protocol document bound is refused here rather than truncated at the
 * transport, naming how many contracts it was carrying: a client reading a truncated capability
 * document would compare a set that is not the set this agent holds.</p>
 */
public final class CapabilityDocument {

    /** The member the store's incarnation is carried in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The member the canonical-form contract digest is carried in. */
    public static final String CANONICAL_DIGEST = "canonical_json_contract_digest";

    /** The member the command contracts are carried in. */
    public static final String CONTRACTS = "command_contracts";

    /** The member the authority's readiness is carried in. */
    public static final String AUTHORITY_READY = "continuation_authority_ready";

    /** The member the transport contract digest is carried in. */
    public static final String TRANSPORT_DIGEST = "transport_contract_digest";

    /** Every member this document has, and there is no sixth. */
    public static final List<String> MEMBERS =
            List.of(GENERATION, CANONICAL_DIGEST, CONTRACTS, AUTHORITY_READY, TRANSPORT_DIGEST);

    private final AdvertisedCapabilities capabilities;
    private final byte[] bytes;

    private CapabilityDocument(AdvertisedCapabilities capabilities, byte[] bytes) {
        this.capabilities = capabilities;
        this.bytes = bytes;
    }

    /** Why this agent cannot answer a capability document. */
    public enum Refusal {
        /** Two contracts answer to one wire name, and one would shadow the other. */
        DUPLICATE_WIRE_NAME,
        /** The document is larger than the protocol document bound. */
        PAST_THE_DOCUMENT_BOUND,
        /** A value in it cannot be written in the canonical form at all. */
        NOT_WRITABLE
    }

    /** The result of building one: the document, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document this agent can answer with.
     *
     * @param document the document
     */
    public record Held(CapabilityDocument document) implements Outcome {
    }

    /**
     * A document it cannot.
     *
     * @param refusal why it cannot
     * @param detail what was observed, naming the contract count where the size is why
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Builds the document one set of capabilities renders to.
     *
     * @param capabilities what this agent is
     * @param documentBound how large a protocol document may be, from the contract
     * @return the document, or the one reason there is none
     */
    public static Outcome of(AdvertisedCapabilities capabilities, long documentBound) {
        final List<CommandContractIdentity> ordered = capabilities.commandContracts().stream()
                .sorted(Comparator.comparing(CommandContractIdentity::wireName))
                .toList();
        final Optional<String> duplicate = duplicateWireName(ordered);
        if (duplicate.isPresent()) {
            return new Refused(Refusal.DUPLICATE_WIRE_NAME, duplicate.get()
                    + " is answered by two contracts, and one would shadow the other");
        }
        final CanonicalByteWriter.Outcome written =
                CanonicalByteWriter.write(value(capabilities, ordered));
        if (written instanceof final CanonicalByteWriter.Refused refused) {
            return new Refused(Refusal.NOT_WRITABLE, refused.refusal().rendered());
        }
        final byte[] bytes = ((CanonicalByteWriter.Written) written).bytes();
        if (bytes.length > documentBound) {
            return new Refused(Refusal.PAST_THE_DOCUMENT_BOUND, "the document carrying "
                    + ordered.size() + " contracts is " + bytes.length
                    + " bytes, past the bound of " + documentBound);
        }
        return new Held(new CapabilityDocument(capabilities, bytes));
    }

    private static Optional<String> duplicateWireName(List<CommandContractIdentity> ordered) {
        final List<String> names = ordered.stream()
                .map(CommandContractIdentity::wireName)
                .toList();
        return names.stream()
                .filter(name -> names.indexOf(name) != names.lastIndexOf(name))
                .findFirst();
    }

    private static DocumentValue value(AdvertisedCapabilities capabilities,
                                       List<CommandContractIdentity> ordered) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(GENERATION, new DocumentValue.Whole(capabilities.generation().number()));
        members.put(CANONICAL_DIGEST,
                new DocumentValue.Text(capabilities.canonicalContractDigest().rendered()));
        members.put(CONTRACTS, new DocumentValue.Sequence(contracts(ordered)));
        members.put(AUTHORITY_READY, new DocumentValue.Flag(capabilities.authorityIsReady()
                ? DocumentValue.Truth.TRUE
                : DocumentValue.Truth.FALSE));
        members.put(TRANSPORT_DIGEST,
                new DocumentValue.Text(capabilities.transportContractDigest().rendered()));
        return new DocumentValue.Mapping(members);
    }

    private static List<DocumentValue> contracts(List<CommandContractIdentity> ordered) {
        final List<DocumentValue> written = new ArrayList<>();
        ordered.forEach(identity -> {
            final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
            members.put(CommandContractIdentity.ARGUMENT_DIGEST,
                    new DocumentValue.Text(identity.argumentSchemaDigest().rendered()));
            members.put(CommandContractIdentity.LIMITS_DIGEST,
                    new DocumentValue.Text(identity.limitsDigest().rendered()));
            members.put(CommandContractIdentity.CONTRACT_VERSION,
                    new DocumentValue.Text(identity.contractVersion()));
            members.put(CommandContractIdentity.WIRE_NAME,
                    new DocumentValue.Text(identity.wireName()));
            members.put(CommandContractIdentity.RESULT_DIGEST,
                    new DocumentValue.Text(identity.resultSchemaDigest().rendered()));
            written.add(new DocumentValue.Mapping(members));
        });
        return written;
    }

    /**
     * What this agent is, as the document says it.
     *
     * @return the capabilities
     */
    public AdvertisedCapabilities capabilities() {
        return capabilities;
    }

    /**
     * The document's own bytes, in the canonical form.
     *
     * @return the bytes, as a copy nothing else holds
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * The document, as the text the route answers with.
     *
     * @return the rendering
     */
    public String render() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
