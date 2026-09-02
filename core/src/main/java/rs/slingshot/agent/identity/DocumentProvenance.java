// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What an operation-bearing document says about which contracts it means.
 *
 * <p>This is a claim the document makes, and nothing more. It is not evidence about the document's
 * bytes: whether those bytes are canonical was decided before this is consulted, by the reader and
 * the canonical authority. Keeping the two apart is what stops a provenance block from looking like
 * an authentication — a document that says it was written under a contract has said something, and
 * a document whose bytes authenticate against one has proved something.</p>
 *
 * <p>The format is compared to one exact constant and to nothing else. There is no range, no prefix,
 * and no version comparison anywhere on this type, because a range is a way to accept a document
 * written under a contract nobody here has read.</p>
 */
public final class DocumentProvenance {

    /** The one format value this build accepts, compared exactly. */
    public static final String FORMAT = "slingshot.agent/1";

    /** The member the format is carried in. */
    public static final String FORMAT_MEMBER = "format";

    /** The member the digest of the transport contract is carried in. */
    public static final String TRANSPORT_DIGEST = "transport_contract_digest";

    /** The member the digest of the canonical-form contract is carried in. */
    public static final String CANONICAL_DIGEST = "canonical_json_contract_digest";

    /** The member the five-field command contract identity is carried in. */
    public static final String COMMAND_CONTRACT = "command_contract";

    /** Every member a provenance block has, and there is no fifth. */
    public static final List<String> MEMBERS =
            List.of(CANONICAL_DIGEST, COMMAND_CONTRACT, FORMAT_MEMBER, TRANSPORT_DIGEST);

    private final DigestValue transportContractDigest;
    private final DigestValue canonicalContractDigest;
    private final CommandContractIdentity commandContract;

    private DocumentProvenance(DigestValue transportContractDigest,
                               DigestValue canonicalContractDigest,
                               CommandContractIdentity commandContract) {
        this.transportContractDigest = transportContractDigest;
        this.canonicalContractDigest = canonicalContractDigest;
        this.commandContract = commandContract;
    }

    /**
     * What this build itself means by the two contracts a document may claim.
     *
     * @param transportContractDigest the transport contract this build speaks
     * @param canonicalContractDigest the canonical-form contract this build writes under
     */
    public record ThisBuild(DigestValue transportContractDigest,
                            DigestValue canonicalContractDigest) {
    }

    /** The result of reading one: the provenance, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document whose claim matches what this build means.
     *
     * @param provenance the provenance it carried
     */
    public record Held(DocumentProvenance provenance) implements Outcome {
    }

    /**
     * A document whose claim is absent, malformed, or about other contracts.
     *
     * @param refusal why it is not provenance this build accepts
     */
    public record Refused(IdentityRefusal refusal) implements Outcome {
    }

    /**
     * Reads a provenance block and compares its claim with what this build means.
     *
     * @param document the provenance block
     * @param build the digests this build itself means
     * @param bounds the bounds the command contract identity is read under
     * @return the provenance, or the one reason there is none
     */
    public static Outcome of(DocumentValue document, ThisBuild build,
                             CommandContractIdentity.Bounds bounds) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DOCUMENT, "",
                    "a provenance block is an object with four members"));
        }
        final Optional<IdentityRefusal> shape = shapeOf(mapping);
        if (shape.isPresent()) {
            return new Refused(shape.get());
        }
        return compared(mapping, build, bounds);
    }

    private static Optional<IdentityRefusal> shapeOf(DocumentValue.Mapping mapping) {
        final Optional<IdentityRefusal> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .map(name -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_UNKNOWN, name,
                        "nobody declared this member"))
                .findFirst();
        if (unknown.isPresent()) {
            return unknown;
        }
        return MEMBERS.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .map(member -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_ABSENT, member,
                        "provenance is all four members or none"))
                .findFirst();
    }

    private static Outcome compared(DocumentValue.Mapping mapping, ThisBuild build,
                                    CommandContractIdentity.Bounds bounds) {
        final Optional<String> format = text(mapping, FORMAT_MEMBER);
        if (format.isEmpty() || !FORMAT.equals(format.get())) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.FORMAT_NOT_EXACT,
                    FORMAT_MEMBER, "this build means " + FORMAT + " and the document says "
                            + format.orElse("something that is not text")));
        }
        final Optional<IdentityRefusal> transport = matching(mapping, TRANSPORT_DIGEST,
                build.transportContractDigest(),
                IdentityRefusal.Failure.TRANSPORT_CONTRACT_MISMATCH);
        if (transport.isPresent()) {
            return new Refused(transport.get());
        }
        final Optional<IdentityRefusal> canonical = matching(mapping, CANONICAL_DIGEST,
                build.canonicalContractDigest(),
                IdentityRefusal.Failure.CANONICAL_CONTRACT_MISMATCH);
        if (canonical.isPresent()) {
            return new Refused(canonical.get());
        }
        return withContract(mapping, build, bounds);
    }

    private static Outcome withContract(DocumentValue.Mapping mapping, ThisBuild build,
                                        CommandContractIdentity.Bounds bounds) {
        final CommandContractIdentity.Outcome read = CommandContractIdentity.of(
                mapping.member(COMMAND_CONTRACT).orElseThrow(), bounds);
        if (read instanceof final CommandContractIdentity.Refused refused) {
            return new Refused(refused.refusal());
        }
        return new Held(new DocumentProvenance(build.transportContractDigest(),
                build.canonicalContractDigest(),
                ((CommandContractIdentity.Held) read).identity()));
    }

    private static Optional<IdentityRefusal> matching(DocumentValue.Mapping mapping, String member,
                                                      DigestValue meant,
                                                      IdentityRefusal.Failure failure) {
        final Optional<String> claimed = text(mapping, member);
        if (claimed.isEmpty()) {
            return Optional.of(new IdentityRefusal(IdentityRefusal.Failure.NOT_TEXT, member,
                    "a contract digest is text"));
        }
        final DigestValue.Outcome held = DigestValue.of(claimed.get());
        if (held instanceof final DigestValue.Refused refused) {
            return Optional.of(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DIGEST, member,
                    refused.refusal() + ": a digest is sixty-four lower-case hexadecimal"
                            + " characters"));
        }
        if (!((DigestValue.Held) held).digest().matches(meant)) {
            return Optional.of(new IdentityRefusal(failure, member, "the document means "
                    + claimed.get() + " and this build means " + meant.rendered()));
        }
        return Optional.empty();
    }

    private static Optional<String> text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
    }

    /**
     * The digest of the transport contract this document means, which equals this build's own.
     *
     * @return the digest
     */
    public DigestValue transportContractDigest() {
        return transportContractDigest;
    }

    /**
     * The digest of the canonical-form contract this document means, which equals this build's own.
     *
     * @return the digest
     */
    public DigestValue canonicalContractDigest() {
        return canonicalContractDigest;
    }

    /**
     * The command contract this document means.
     *
     * @return the identity
     */
    public CommandContractIdentity commandContract() {
        return commandContract;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final DocumentProvenance provenance
                && transportContractDigest.matches(provenance.transportContractDigest)
                && canonicalContractDigest.matches(provenance.canonicalContractDigest)
                && commandContract.equals(provenance.commandContract);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transportContractDigest.rendered(), canonicalContractDigest.rendered(),
                commandContract);
    }

    @Override
    public String toString() {
        return FORMAT + " " + commandContract;
    }
}
