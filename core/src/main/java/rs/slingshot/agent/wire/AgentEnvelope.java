// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.DocumentProvenance;
import rs.slingshot.agent.identity.IdentityRefusal;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What every agent document says before it says anything of its own: which contracts it means, and
 * which operation it is about.
 *
 * <p>There is no body here, and that is deliberate. A document's own members are declared and typed
 * by the kind of document it is; an envelope carrying something free-form would be a place for
 * members nobody declared to travel, which is the arrangement this repository refuses everywhere
 * else. A member nobody declared is refused here too.</p>
 */
public final class AgentEnvelope {

    /** The member the provenance is carried in. */
    public static final String PROVENANCE = "provenance";

    /** The member the operation identity is carried in. */
    public static final String OPERATION = "operation";

    /** Every member an envelope has, and there is no third. */
    public static final List<String> MEMBERS = List.of(OPERATION, PROVENANCE);

    private final DocumentProvenance provenance;
    private final OperationIdentity operation;

    private AgentEnvelope(DocumentProvenance provenance, OperationIdentity operation) {
        this.provenance = provenance;
        this.operation = operation;
    }

    /** The result of reading one: the envelope, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document carrying both members, each in shape.
     *
     * @param envelope what the document says before it says anything of its own
     */
    public record Held(AgentEnvelope envelope) implements Outcome {
    }

    /**
     * A document that is not an envelope this build accepts.
     *
     * @param refusal why it is not, naming the member
     */
    public record Refused(IdentityRefusal refusal) implements Outcome {
    }

    /**
     * Reads an envelope, comparing what it claims with what this build means.
     *
     * @param document the document
     * @param build the contracts this build itself means
     * @param contract the authenticated contract, which declares every bound
     * @return the envelope, or the one reason there is none
     */
    public static Outcome read(DocumentValue document, DocumentProvenance.ThisBuild build,
                               AgentContract contract) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DOCUMENT, "",
                    "an envelope is an object with two members"));
        }
        final Optional<IdentityRefusal> shape = shapeOf(mapping);
        if (shape.isPresent()) {
            return new Refused(shape.get());
        }
        final DocumentProvenance.Outcome provenance = DocumentProvenance.of(
                mapping.member(PROVENANCE).orElseThrow(), build,
                CommandContractIdentity.Bounds.from(contract));
        if (provenance instanceof final DocumentProvenance.Refused refused) {
            return new Refused(refused.refusal());
        }
        final OperationIdentity.Outcome operation = OperationIdentity.of(
                mapping.member(OPERATION).orElseThrow(), contract);
        if (operation instanceof final OperationIdentity.Refused refused) {
            return new Refused(refused.refusal());
        }
        return new Held(new AgentEnvelope(((DocumentProvenance.Held) provenance).provenance(),
                ((OperationIdentity.Held) operation).identity()));
    }

    private static Optional<IdentityRefusal> shapeOf(DocumentValue.Mapping mapping) {
        final Optional<IdentityRefusal> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .map(name -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_UNKNOWN, name,
                        "an envelope carries no member nobody declared"))
                .findFirst();
        if (unknown.isPresent()) {
            return unknown;
        }
        return MEMBERS.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .map(member -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_ABSENT, member,
                        "an envelope is both members or neither"))
                .findFirst();
    }

    /**
     * Which contracts this document means.
     *
     * @return the provenance
     */
    public DocumentProvenance provenance() {
        return provenance;
    }

    /**
     * Which operation this document is about.
     *
     * @return the operation identity
     */
    public OperationIdentity operation() {
        return operation;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final AgentEnvelope envelope
                && provenance.equals(envelope.provenance)
                && operation.equals(envelope.operation);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(provenance, operation);
    }

    @Override
    public String toString() {
        return operation.toString();
    }
}
