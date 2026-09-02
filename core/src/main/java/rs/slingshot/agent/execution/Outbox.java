// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.ArrayList;
import java.util.List;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.store.ClaimByCreation;
import rs.slingshot.agent.store.CompareAndSet;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/**
 * Every physical delivery of one logical operation, each recorded once.
 *
 * <p>An attempt is claimed at a path derived from the delivery's own name, so a duplicate arrival
 * is observed rather than counted twice: the second claim finds the first's node and says so. What
 * this is not is a way of preventing duplicates — the job system will deliver twice, and a store
 * that treated the second delivery as an error would be reporting the job system working.</p>
 *
 * <p>Exhaustion is a fact written on the operation rather than a number somebody recomputes by
 * counting children: a count taken by walking a tree is a count that disagrees with itself the
 * moment two nodes are walking.</p>
 */
public final class Outbox {

    /** The node an operation's attempts sit under. */
    public static final String NODE = "outbox";

    /** The property a delivery's own name is written in. */
    public static final String JOB_IDENTIFIER = "sling_job_identifier";

    /** The property the observing node is written in. */
    public static final String OBSERVED_BY = "observed_by";

    /** The property the instant it was observed is written in. */
    public static final String OBSERVED_AT = "observed_at_unix_milliseconds";

    /** The property that says an operation has had every attempt it may have. */
    public static final String EXHAUSTED = "attempts_exhausted";

    private Outbox() {
    }

    /** What recording an attempt did. */
    public enum Recorded {
        /** It is the first time this delivery has been seen. */
        FIRST_TIME,
        /** This delivery has been seen before, and nothing was written a second time. */
        ALREADY_SEEN,
        /** This operation has had every attempt it may have, and nothing was written. */
        EXHAUSTED_ALREADY
    }

    /** The result of recording: what it did, and what the outbox now holds. */
    public sealed interface Outcome permits Wrote, NoRecord {
    }

    /**
     * An attempt this store now holds a record of.
     *
     * @param recorded what recording it did
     * @param attempts how many attempts this operation now has
     */
    public record Wrote(Recorded recorded, long attempts) implements Outcome {
    }

    /**
     * No record, because there is no operation to record it against.
     *
     * @param detail what was observed
     */
    public record NoRecord(String detail) implements Outcome {
    }

    /**
     * Records one physical delivery of one operation.
     *
     * @param session the session to write under
     * @param identity which operation was delivered
     * @param attempt the delivery
     * @param contract the authenticated contract, which declares the attempt bound
     * @return what recording it did, or the one reason nothing was recorded
     * @throws RepositoryException if the repository fails
     */
    public static Outcome record(Session session, OperationIdentity identity,
                                 PhysicalAttempt attempt, AgentContract contract)
            throws RepositoryException {
        final StatePath operation = OperationStore.pathOf(identity);
        if (!session.nodeExists(operation.path())) {
            return new NoRecord("no operation at " + operation.path() + " to record an attempt"
                    + " against");
        }
        final StatePath outbox = operation.child(NODE);
        ClaimByCreation.claim(session, outbox, "nt:unstructured", node -> { });
        return claimed(session, operation, outbox, attempt, contract);
    }

    private static Outcome claimed(Session session, StatePath operation, StatePath outbox,
                                   PhysicalAttempt attempt, AgentContract contract)
            throws RepositoryException {
        final StatePath path = outbox.child(nameFor(attempt));
        if (session.nodeExists(path.path())) {
            return new Wrote(Recorded.ALREADY_SEEN, attempts(session, outbox));
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_LOGICAL_OUTBOX_ATTEMPTS);
        if (attempts(session, outbox) >= bound) {
            markExhausted(session, operation);
            return new Wrote(Recorded.EXHAUSTED_ALREADY, attempts(session, outbox));
        }
        final WriteOutcome claimed = ClaimByCreation.claim(session, path, "nt:unstructured",
                node -> write(node, attempt));
        final long now = attempts(session, outbox);
        if (now >= bound) {
            markExhausted(session, operation);
        }
        return new Wrote(claimed == WriteOutcome.CLAIMED ? Recorded.FIRST_TIME
                : Recorded.ALREADY_SEEN, now);
    }

    /**
     * How many deliveries this operation has had.
     *
     * @param session the session to read under
     * @param identity which operation
     * @return the count
     * @throws RepositoryException if the repository fails
     */
    public static long attemptsFor(Session session, OperationIdentity identity)
            throws RepositoryException {
        final StatePath outbox = OperationStore.pathOf(identity).child(NODE);
        return session.nodeExists(outbox.path()) ? attempts(session, outbox) : 0;
    }

    /**
     * Every delivery this operation has had, in the order the store holds them.
     *
     * @param session the session to read under
     * @param identity which operation
     * @return the attempts
     * @throws RepositoryException if the repository fails
     */
    public static List<PhysicalAttempt> held(Session session, OperationIdentity identity)
            throws RepositoryException {
        final StatePath outbox = OperationStore.pathOf(identity).child(NODE);
        if (!session.nodeExists(outbox.path())) {
            return List.of();
        }
        final List<PhysicalAttempt> attempts = new ArrayList<>();
        final NodeIterator children = session.getNode(outbox.path()).getNodes();
        while (children.hasNext()) {
            final Node child = children.nextNode();
            attempts.add(new PhysicalAttempt(child.getProperty(JOB_IDENTIFIER).getString(),
                    child.getProperty(OBSERVED_BY).getString(),
                    CompareAndSet.held(child, OBSERVED_AT)));
        }
        return attempts;
    }

    /**
     * Whether this operation has had every attempt it may have.
     *
     * @param session the session to read under
     * @param identity which operation
     * @return whether it is exhausted, as a fact the store holds rather than a count
     * @throws RepositoryException if the repository fails
     */
    public static boolean exhausted(Session session, OperationIdentity identity)
            throws RepositoryException {
        final StatePath operation = OperationStore.pathOf(identity);
        return session.nodeExists(operation.path())
                && session.getNode(operation.path()).hasProperty(EXHAUSTED);
    }

    private static void markExhausted(Session session, StatePath operation)
            throws RepositoryException {
        final Node record = session.getNode(operation.path());
        if (!record.hasProperty(EXHAUSTED)) {
            record.setProperty(EXHAUSTED, true);
            session.save();
        }
    }

    private static long attempts(Session session, StatePath outbox) throws RepositoryException {
        return session.getNode(outbox.path()).getNodes().getSize();
    }

    /**
     * What one delivery's record is called, derived from the delivery's own name.
     *
     * <p>Derived rather than sequential, so the same delivery arriving twice lands on the same
     * node and the second arrival is a claim that finds one.</p>
     *
     * @param attempt the delivery
     * @return the node name
     */
    public static String nameFor(PhysicalAttempt attempt) {
        return "a" + Digest.of(attempt.jobIdentifier()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).rendered();
    }

    private static void write(Node node, PhysicalAttempt attempt) {
        try {
            node.setProperty(JOB_IDENTIFIER, attempt.jobIdentifier());
            node.setProperty(OBSERVED_BY, attempt.observedBy());
            node.setProperty(OBSERVED_AT, attempt.observedAtUnixMilliseconds());
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the attempt could not be written", unwritable);
        }
    }
}
