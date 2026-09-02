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
 * Which operation, at which incarnation of the store, against which target, at which revision.
 *
 * <p>Four values that together mean one durable thing. Any one of them changing means a different
 * durable thing — the same command submitted against a different environment revision is not a
 * resend of the first, and treating it as one would be the agent quietly deciding that a
 * deployment's own configuration does not matter.</p>
 *
 * <p>The target digest and the environment revision are opaque here. This side compares them and
 * never parses them, because what they mean belongs to the client's configuration and a parser here
 * would be this repository having an opinion about somebody else's naming.</p>
 */
public final class OperationIdentity {

    /** The member the store's own incarnation is carried in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The member the operation's own name is carried in. */
    public static final String IDENTIFIER = "agent_operation_identifier";

    /** The member the digest of the target this operation runs against is carried in. */
    public static final String TARGET_DIGEST = "author_target_identity_digest";

    /** The member the environment revision this operation was selected against is carried in. */
    public static final String ENVIRONMENT_REVISION = "selected_environment_revision";

    /** Every member this document has, and there is no fifth. */
    public static final List<String> MEMBERS =
            List.of(GENERATION, IDENTIFIER, TARGET_DIGEST, ENVIRONMENT_REVISION);

    private final EventStoreGeneration generation;
    private final AgentOperationIdentifier identifier;
    private final DigestValue targetDigest;
    private final String environmentRevision;

    private OperationIdentity(EventStoreGeneration generation, AgentOperationIdentifier identifier,
                              DigestValue targetDigest, String environmentRevision) {
        this.generation = generation;
        this.identifier = identifier;
        this.targetDigest = targetDigest;
        this.environmentRevision = environmentRevision;
    }

    /** The result of reading one: the identity, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that carried all four members, each in shape.
     *
     * @param identity the identity it carried
     */
    public record Held(OperationIdentity identity) implements Outcome {
    }

    /**
     * A document that is not an operation identity.
     *
     * @param refusal why it is not one, naming the member
     */
    public record Refused(IdentityRefusal refusal) implements Outcome {
    }

    /**
     * Reads an operation identity out of a document.
     *
     * @param document the document
     * @param contract the authenticated contract, which declares both bounds
     * @return the identity, or the one reason there is none
     */
    public static Outcome of(DocumentValue document, AgentContract contract) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DOCUMENT, "",
                    "an operation identity is an object with four members"));
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.MEMBER_UNKNOWN,
                    unknown.get(), "nobody declared this member"));
        }
        return assembled(mapping, contract);
    }

    private static Outcome assembled(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<IdentityRefusal> absent = MEMBERS.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .map(member -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_ABSENT, member,
                        "an operation identity is all four members or none"))
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(absent.get());
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final DocumentValue carried = mapping.member(GENERATION).orElseThrow();
        if (!(carried instanceof final DocumentValue.Whole whole)) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_TEXT, GENERATION,
                    "a generation is a whole number"));
        }
        final EventStoreGeneration.Outcome held = EventStoreGeneration.of(whole.value());
        if (held instanceof final EventStoreGeneration.Refused refused) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.OUT_OF_RANGE, GENERATION,
                    refused.refusal() + ": " + refused.detail()));
        }
        return withGeneration(((EventStoreGeneration.Held) held).generation(), mapping, contract);
    }

    /** One member as it was read: a value of the kind it has to be, or the reason it is not. */
    private sealed interface Member permits NamedMember, DigestMember, TextMember, WrongMember {
    }

    private record NamedMember(AgentOperationIdentifier value) implements Member {
    }

    private record DigestMember(DigestValue value) implements Member {
    }

    private record TextMember(String value) implements Member {
    }

    private record WrongMember(IdentityRefusal refusal) implements Member {
    }

    private static Outcome withGeneration(EventStoreGeneration generation,
                                          DocumentValue.Mapping mapping, AgentContract contract) {
        final Member identifier = identifier(mapping, contract);
        final Member target = targetDigest(mapping);
        final Member revision = revision(mapping, contract);
        final Optional<IdentityRefusal> refusal =
                java.util.stream.Stream.of(identifier, target, revision)
                        .filter(WrongMember.class::isInstance)
                        .map(member -> ((WrongMember) member).refusal())
                        .findFirst();
        if (refusal.isPresent()) {
            return new Refused(refusal.get());
        }
        return new Held(new OperationIdentity(generation, ((NamedMember) identifier).value(),
                ((DigestMember) target).value(), ((TextMember) revision).value()));
    }

    private static Member identifier(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> written = text(mapping, IDENTIFIER);
        if (written.isEmpty()) {
            return wrong(IdentityRefusal.Failure.NOT_TEXT, IDENTIFIER,
                    "an operation identifier is text");
        }
        final AgentOperationIdentifier.Outcome held =
                AgentOperationIdentifier.of(written.get(), contract);
        if (held instanceof final AgentOperationIdentifier.Refused refused) {
            return wrong(IdentityRefusal.Failure.NOT_A_DIGEST, IDENTIFIER,
                    refused.refusal() + ": " + refused.detail());
        }
        return new NamedMember(((AgentOperationIdentifier.Held) held).identifier());
    }

    private static Member targetDigest(DocumentValue.Mapping mapping) {
        final Optional<String> written = text(mapping, TARGET_DIGEST);
        if (written.isEmpty()) {
            return wrong(IdentityRefusal.Failure.NOT_TEXT, TARGET_DIGEST,
                    "a target digest is text");
        }
        final DigestValue.Outcome held = DigestValue.of(written.get());
        if (held instanceof final DigestValue.Refused refused) {
            return wrong(IdentityRefusal.Failure.NOT_A_DIGEST, TARGET_DIGEST,
                    refused.refusal() + ": a digest is sixty-four lower-case hexadecimal"
                            + " characters");
        }
        return new DigestMember(((DigestValue.Held) held).digest());
    }

    private static Member revision(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<String> written = text(mapping, ENVIRONMENT_REVISION);
        if (written.isEmpty()) {
            return wrong(IdentityRefusal.Failure.NOT_TEXT, ENVIRONMENT_REVISION,
                    "an environment revision is text");
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_SELECTED_ENVIRONMENT_REVISION_BYTES);
        final int length = written.get().getBytes(StandardCharsets.UTF_8).length;
        if (length == 0) {
            return wrong(IdentityRefusal.Failure.EMPTY, ENVIRONMENT_REVISION,
                    "an empty revision names nothing");
        }
        if (length > bound) {
            return wrong(IdentityRefusal.Failure.TOO_LONG, ENVIRONMENT_REVISION,
                    length + " bytes is past the bound of " + bound);
        }
        return new TextMember(written.get());
    }

    private static Member wrong(IdentityRefusal.Failure failure, String member,
                                String detail) {
        return new WrongMember(new IdentityRefusal(failure, member, detail));
    }

    private static Optional<String> text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
    }

    /**
     * Which incarnation of the store this operation belongs to.
     *
     * @return the generation
     */
    public EventStoreGeneration generation() {
        return generation;
    }

    /**
     * The operation's own name.
     *
     * @return the identifier
     */
    public AgentOperationIdentifier identifier() {
        return identifier;
    }

    /**
     * The digest of the target this operation runs against, which this side never parses.
     *
     * @return the digest
     */
    public DigestValue targetDigest() {
        return targetDigest;
    }

    /**
     * The environment revision this operation was selected against, which this side never parses.
     *
     * @return the revision, as it was written
     */
    public String environmentRevision() {
        return environmentRevision;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final OperationIdentity identity
                && generation.equals(identity.generation)
                && identifier.equals(identity.identifier)
                && targetDigest.matches(identity.targetDigest)
                && environmentRevision.equals(identity.environmentRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(generation, identifier, targetDigest.rendered(), environmentRevision);
    }

    @Override
    public String toString() {
        return identifier + "@" + generation;
    }
}
