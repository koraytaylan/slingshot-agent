// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Map;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * What a node does when the job system hands it one deferred operation.
 *
 * <p>Load the record by the path its identifier derives, record the physical attempt, take the
 * fence, and hand off. Nothing here is the record of the work, and nothing here decides
 * idempotency: a job is a delivery, and the store already knows what the delivery is about. A
 * delivery of work that already finished is recorded like any other and executes nothing, because
 * the job system redelivering a job it never heard the answer to is the ordinary case rather than
 * the strange one.</p>
 *
 * <p>A job for an unknown operation, a foreign generation, malformed properties, or an immediate
 * row is dropped rather than retried, each recorded as its own reason. None of them becomes valid
 * by being tried again, and a job system retrying one forever is a queue that never drains.</p>
 */
public final class CommandJobConsumer {

    private CommandJobConsumer() {
    }

    /** What this node did with one delivery. */
    public enum Verdict {
        /** It was handed to execution, under a fence this node holds. */
        EXECUTED,
        /** Somebody else holds the fence, so this delivery is left for them. */
        ANOTHER_WORKER_HOLDS_IT,
        /** Nothing durable holds this operation, so there is nothing to execute. */
        DROPPED_UNKNOWN_OPERATION,
        /** It names an incarnation of the store this node is not serving. */
        DROPPED_FOREIGN_GENERATION,
        /** Its properties are not ones this build can read. */
        DROPPED_MALFORMED,
        /** It names an immediate row, which never reaches a queue by any legitimate route. */
        DROPPED_NOT_DEFERRED,
        /** It already finished, so the attempt is recorded and nothing is run again. */
        ALREADY_FINISHED,
        /** It has had every delivery it may have, and this one is not another chance. */
        DROPPED_ATTEMPTS_EXHAUSTED,
        /** The store was busy and nothing was decided, so the delivery is worth retrying. */
        RETRY
    }

    /**
     * One delivery as the node that received it saw it.
     *
     * <p>Carried as a record rather than as three arguments because what a delivery is travels
     * together: the job system's name for it, the node that took it, and when. Two of the three are
     * what an attempt is recorded as, and the third is what the fence is taken under.</p>
     *
     * @param jobIdentifier the job system's own name for this delivery
     * @param worker the node that received it
     * @param nowUnixMilliseconds what that node's clock said when it did
     */
    public record Delivery(String jobIdentifier, String worker, long nowUnixMilliseconds) {
    }

    /** What a node does with the record once it holds the fence. */
    @FunctionalInterface
    public interface Execution {

        /**
         * Runs one operation.
         *
         * @param operation the record
         * @param holder the fence this node holds while it runs
         * @return what running it did
         */
        Verdict run(LogicalOperation operation, FenceHolder holder);
    }

    /**
     * Decides what to do with one delivery, and does it.
     *
     * @param session the session to read and write under
     * @param delivery this delivery, as the node that received it saw it
     * @param properties what the job carries
     * @param contract the authenticated contract, which declares every bound
     * @param execution what to do once the fence is held
     * @return what this node did
     * @throws RepositoryException if the repository fails
     */
    public static Verdict consume(Session session, Delivery delivery,
                                  Map<String, Object> properties, AgentContract contract,
                                  Execution execution) throws RepositoryException {
        final Optional<CommandJobTopic.ExecutionClass> executionClass =
                CommandJobTopic.ExecutionClass.named(text(properties,
                        CommandJobTopic.EXECUTION_CLASS));
        if (executionClass.isEmpty()) {
            return Verdict.DROPPED_MALFORMED;
        }
        if (executionClass.get() != CommandJobTopic.ExecutionClass.DEFERRED) {
            return Verdict.DROPPED_NOT_DEFERRED;
        }
        final Optional<OperationIdentity> identity = identityIn(properties, contract);
        if (identity.isEmpty()) {
            return Verdict.DROPPED_MALFORMED;
        }
        return againstTheStore(session, delivery, identity.get(), contract, execution);
    }

    private static Verdict againstTheStore(Session session, Delivery delivery,
                                           OperationIdentity identity, AgentContract contract,
                                           Execution execution) throws RepositoryException {
        if (GenerationStore.membership(session, identity.generation())
                != GenerationStore.Membership.SERVING) {
            return Verdict.DROPPED_FOREIGN_GENERATION;
        }
        final OperationStore.Outcome record = OperationStore.read(session, identity);
        if (record instanceof OperationStore.Refused) {
            return Verdict.DROPPED_UNKNOWN_OPERATION;
        }
        final PhysicalAttempt.Outcome attempt = PhysicalAttempt.of(delivery.jobIdentifier(),
                delivery.worker(), delivery.nowUnixMilliseconds(), contract);
        if (attempt instanceof PhysicalAttempt.Refused) {
            return Verdict.DROPPED_MALFORMED;
        }
        return recorded(session, delivery, ((PhysicalAttempt.Held) attempt).attempt(), identity,
                ((OperationStore.Held) record).operation(), contract, execution);
    }

    private static Verdict recorded(Session session, Delivery delivery, PhysicalAttempt attempt,
                                    OperationIdentity identity, LogicalOperation operation,
                                    AgentContract contract, Execution execution)
            throws RepositoryException {
        final Outbox.Outcome written = Outbox.record(session, identity, attempt, contract);
        if (written instanceof Outbox.NoRecord) {
            return Verdict.DROPPED_UNKNOWN_OPERATION;
        }
        if (((Outbox.Wrote) written).recorded() == Outbox.Recorded.EXHAUSTED_ALREADY) {
            return Verdict.DROPPED_ATTEMPTS_EXHAUSTED;
        }
        if (operation.state().finality() == JobEventKind.Finality.ENDS) {
            return Verdict.ALREADY_FINISHED;
        }
        final FenceOutcome fence = ExecutionFence.take(session, identity, delivery.worker(),
                delivery.nowUnixMilliseconds(), contract);
        return fenced(fence, operation, execution);
    }

    private static Verdict fenced(FenceOutcome fence, LogicalOperation operation,
                                  Execution execution) {
        if (fence instanceof final FenceOutcome.Held held) {
            return execution.run(operation, held.holder());
        }
        if (fence instanceof FenceOutcome.Refused) {
            return Verdict.ANOTHER_WORKER_HOLDS_IT;
        }
        return Verdict.RETRY;
    }

    private static Optional<OperationIdentity> identityIn(Map<String, Object> properties,
                                                          AgentContract contract) {
        final Optional<Long> generation = whole(properties, CommandJobTopic.GENERATION);
        if (generation.isEmpty()) {
            return Optional.empty();
        }
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION, new DocumentValue.Whole(generation.get()));
        members.put(OperationIdentity.IDENTIFIER,
                new DocumentValue.Text(text(properties, CommandJobTopic.IDENTIFIER)));
        members.put(OperationIdentity.TARGET_DIGEST,
                new DocumentValue.Text(text(properties, CommandJobTopic.TARGET_DIGEST)));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new DocumentValue.Text(text(properties, CommandJobTopic.ENVIRONMENT_REVISION)));
        final OperationIdentity.Outcome held =
                OperationIdentity.of(new DocumentValue.Mapping(members), contract);
        return held instanceof final OperationIdentity.Held identity
                ? Optional.of(identity.identity())
                : Optional.empty();
    }

    private static String text(Map<String, Object> properties, String name) {
        final Object held = properties.get(name);
        return held instanceof final String value ? value : "";
    }

    private static Optional<Long> whole(Map<String, Object> properties, String name) {
        final Object held = properties.get(name);
        return held instanceof final Number value
                ? Optional.of(value.longValue())
                : Optional.empty();
    }

    /**
     * Whether the identifier a job carries is one this build reads at all.
     *
     * @param properties what the job carries
     * @param contract the authenticated contract
     * @return the identifier, or nothing where the job names none this build reads
     */
    public static Optional<AgentOperationIdentifier> identifierIn(Map<String, Object> properties,
                                                                 AgentContract contract) {
        final AgentOperationIdentifier.Outcome held = AgentOperationIdentifier.of(
                text(properties, CommandJobTopic.IDENTIFIER), contract);
        return held instanceof final AgentOperationIdentifier.Held identifier
                ? Optional.of(identifier.identifier())
                : Optional.empty();
    }

    /**
     * The incarnation a job names, where it names one this build reads.
     *
     * @param properties what the job carries
     * @return the generation, or nothing where the job names none
     */
    public static Optional<EventStoreGeneration> generationIn(Map<String, Object> properties) {
        return whole(properties, CommandJobTopic.GENERATION)
                .map(EventStoreGeneration::of)
                .filter(EventStoreGeneration.Held.class::isInstance)
                .map(outcome -> ((EventStoreGeneration.Held) outcome).generation());
    }

    /**
     * The target a job names, where it names one this build reads.
     *
     * @param properties what the job carries
     * @return the digest, or nothing where the job names none
     */
    public static Optional<DigestValue> targetIn(Map<String, Object> properties) {
        final DigestValue.Outcome held =
                DigestValue.of(text(properties, CommandJobTopic.TARGET_DIGEST));
        return held instanceof final DigestValue.Held digest
                ? Optional.of(digest.digest())
                : Optional.empty();
    }
}
