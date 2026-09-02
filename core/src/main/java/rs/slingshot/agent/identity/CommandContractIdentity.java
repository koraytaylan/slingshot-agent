// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The five fields that say exactly which command contract a submission means.
 *
 * <p>All five or none: a value of this type cannot exist with a member missing, out of shape, or
 * past its bound, and there is no sixth member to carry. A document with a member nobody declared
 * is refused rather than read around it, because an ignored member is a caller believing something
 * is being honoured.</p>
 *
 * <p>Comparison is total. Two identities are the same identity when all five members are the same,
 * and this type exposes no other comparison — a partial one would be a way for two builds that
 * disagree about a command's arguments to agree about its name and get on with it.</p>
 */
public final class CommandContractIdentity {

    /** The member the command's own wire name is carried in. */
    public static final String WIRE_NAME = "command_wire_name";

    /** The member the command's semantic contract version is carried in. */
    public static final String CONTRACT_VERSION = "command_semantic_contract_version";

    /** The member the digest of the command's declared limits is carried in. */
    public static final String LIMITS_DIGEST = "command_contract_limits_digest";

    /** The member the digest of the command's argument schema is carried in. */
    public static final String ARGUMENT_DIGEST = "argument_schema_digest";

    /** The member the digest of the command's result schema is carried in. */
    public static final String RESULT_DIGEST = "result_schema_digest";

    /** Every member this document has, and there is no sixth. */
    public static final List<String> MEMBERS =
            List.of(ARGUMENT_DIGEST, LIMITS_DIGEST, CONTRACT_VERSION, WIRE_NAME, RESULT_DIGEST);

    private final String wireName;
    private final String contractVersion;
    private final DigestValue limitsDigest;
    private final DigestValue argumentSchemaDigest;
    private final DigestValue resultSchemaDigest;

    private CommandContractIdentity(String wireName, String contractVersion,
                                    DigestValue limitsDigest, DigestValue argumentSchemaDigest,
                                    DigestValue resultSchemaDigest) {
        this.wireName = wireName;
        this.contractVersion = contractVersion;
        this.limitsDigest = limitsDigest;
        this.argumentSchemaDigest = argumentSchemaDigest;
        this.resultSchemaDigest = resultSchemaDigest;
    }

    /**
     * The two length bounds an identity is read under.
     *
     * @param wireNameBytes how long a command's wire name may be
     * @param contractVersionBytes how long a command's semantic contract version may be
     */
    public record Bounds(long wireNameBytes, long contractVersionBytes) {

        /**
         * The bounds the contract declares, which is where both of them live.
         *
         * @param contract the authenticated contract
         * @return the bounds
         */
        public static Bounds from(AgentContract contract) {
            return new Bounds(contract.value(ContractLimit.MAXIMUM_COMMAND_WIRE_NAME_BYTES),
                    contract.value(ContractLimit.MAXIMUM_COMMAND_SEMANTIC_CONTRACT_VERSION_BYTES));
        }
    }

    /** The result of reading one: the identity, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that carried all five fields, each in shape.
     *
     * @param identity the identity it carried
     */
    public record Held(CommandContractIdentity identity) implements Outcome {
    }

    /**
     * A document that is not an identity.
     *
     * @param refusal why it is not one, naming the member
     */
    public record Refused(IdentityRefusal refusal) implements Outcome {
    }

    /**
     * Reads an identity out of a document.
     *
     * @param document the document
     * @param bounds the bounds to read it under
     * @return the identity, or the one reason there is none
     */
    public static Outcome of(DocumentValue document, Bounds bounds) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DOCUMENT, "",
                    "an identity is an object with five members and this is not an object"));
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.MEMBER_UNKNOWN,
                    unknown.get(), "nobody declared this member, and reading around it would be a"
                            + " caller believing it is being honoured"));
        }
        return assembled(mapping, bounds);
    }

    /** One member as it was read: a value of the kind it has to be, or the reason it is not. */
    private sealed interface Member permits TextMember, DigestMember, WrongMember {
    }

    private record TextMember(String value) implements Member {
    }

    private record DigestMember(DigestValue value) implements Member {
    }

    private record WrongMember(IdentityRefusal refusal) implements Member {
    }

    private static Outcome assembled(DocumentValue.Mapping mapping, Bounds bounds) {
        final Member wireName = text(mapping, WIRE_NAME, bounds.wireNameBytes());
        final Member version = text(mapping, CONTRACT_VERSION, bounds.contractVersionBytes());
        final Member limits = digest(mapping, LIMITS_DIGEST);
        final Member argument = digest(mapping, ARGUMENT_DIGEST);
        final Member result = digest(mapping, RESULT_DIGEST);
        final Optional<IdentityRefusal> refusal =
                java.util.stream.Stream.of(wireName, version, limits, argument, result)
                        .filter(WrongMember.class::isInstance)
                        .map(member -> ((WrongMember) member).refusal())
                        .findFirst();
        if (refusal.isPresent()) {
            return new Refused(refusal.get());
        }
        return new Held(new CommandContractIdentity(((TextMember) wireName).value(),
                ((TextMember) version).value(), ((DigestMember) limits).value(),
                ((DigestMember) argument).value(), ((DigestMember) result).value()));
    }

    private static Member text(DocumentValue.Mapping mapping, String member, long bound) {
        final Optional<DocumentValue> carried = mapping.member(member);
        if (carried.isEmpty()) {
            return wrong(IdentityRefusal.Failure.MEMBER_ABSENT, member,
                    "an identity is all five fields or none");
        }
        if (!(carried.get() instanceof final DocumentValue.Text value)) {
            return wrong(IdentityRefusal.Failure.NOT_TEXT, member,
                    "every member of an identity is text");
        }
        return bounded(value.value(), member, bound);
    }

    private static Member bounded(String value, String member, long bound) {
        final int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length == 0) {
            return wrong(IdentityRefusal.Failure.EMPTY, member, "an empty value names nothing");
        }
        if (length > bound) {
            return wrong(IdentityRefusal.Failure.TOO_LONG, member,
                    length + " bytes is past the bound of " + bound);
        }
        return new TextMember(value);
    }

    private static Member digest(DocumentValue.Mapping mapping, String member) {
        final Member read = text(mapping, member, DigestValue.RENDERED_LENGTH);
        if (!(read instanceof final TextMember rendered)) {
            return read;
        }
        final DigestValue.Outcome held = DigestValue.of(rendered.value());
        if (held instanceof final DigestValue.Refused refused) {
            return wrong(IdentityRefusal.Failure.NOT_A_DIGEST, member,
                    refused.refusal() + ": a digest is sixty-four lower-case hexadecimal"
                            + " characters");
        }
        return new DigestMember(((DigestValue.Held) held).digest());
    }

    private static Member wrong(IdentityRefusal.Failure failure, String member, String detail) {
        return new WrongMember(new IdentityRefusal(failure, member, detail));
    }

    /**
     * The command's own wire name.
     *
     * @return the name
     */
    public String wireName() {
        return wireName;
    }

    /**
     * The command's semantic contract version.
     *
     * @return the version
     */
    public String contractVersion() {
        return contractVersion;
    }

    /**
     * The digest of the command's declared limits.
     *
     * @return the digest
     */
    public DigestValue limitsDigest() {
        return limitsDigest;
    }

    /**
     * The digest of the command's argument schema.
     *
     * @return the digest
     */
    public DigestValue argumentSchemaDigest() {
        return argumentSchemaDigest;
    }

    /**
     * The digest of the command's result schema.
     *
     * @return the digest
     */
    public DigestValue resultSchemaDigest() {
        return resultSchemaDigest;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final CommandContractIdentity identity
                && wireName.equals(identity.wireName)
                && contractVersion.equals(identity.contractVersion)
                && limitsDigest.matches(identity.limitsDigest)
                && argumentSchemaDigest.matches(identity.argumentSchemaDigest)
                && resultSchemaDigest.matches(identity.resultSchemaDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wireName, contractVersion, limitsDigest.rendered(),
                argumentSchemaDigest.rendered(), resultSchemaDigest.rendered());
    }

    @Override
    public String toString() {
        return wireName + "@" + contractVersion;
    }
}
