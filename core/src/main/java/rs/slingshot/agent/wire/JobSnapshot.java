// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.List;
import java.util.Objects;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the store holds about one job: the record, rather than the news an event stream carries.
 *
 * <p>A disconnected stream is incomplete unless something can be asked what is currently true, and
 * that something has to answer in the same vocabulary the stream used — so a snapshot carries the
 * very kind and sequence types an event does rather than copies of them. Reconciling two
 * vocabularies would be guesswork, and guesswork about whether a job finished is the one thing a
 * client cannot do.</p>
 *
 * <p>A snapshot at sequence <em>n</em> asserts that everything up to <em>n</em> has happened. So a
 * snapshot below a sequence a client has already seen is a refusal rather than a retraction, and a
 * snapshot after a terminal one saying something else is refused outright: what has finished has
 * finished, and the store saying otherwise is the store being wrong rather than the job changing
 * its mind.</p>
 */
public final class JobSnapshot {

    /** Every member a snapshot has, which are the event document's own four. */
    public static final List<String> MEMBERS = JobEvent.MEMBERS;

    private final JobEvent held;

    private JobSnapshot(JobEvent held) {
        this.held = held;
    }

    /** Why one snapshot cannot follow another. */
    public enum Succession {
        /** It is behind a sequence already seen, which a snapshot never is. */
        BEHIND_WHAT_WAS_SEEN,
        /** It names another incarnation of the store, which is a different durable thing. */
        FOREIGN_GENERATION,
        /** It is about another operation entirely. */
        ANOTHER_OPERATION,
        /** Something terminal has already been said about this job, and this says otherwise. */
        AFTER_A_TERMINAL_ONE
    }

    /** The result of reading one: the snapshot, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that is a snapshot of the operation and incarnation being served.
     *
     * @param snapshot the snapshot it carried
     */
    public record Held(JobSnapshot snapshot) implements Outcome {
    }

    /**
     * A document that is not one, for a reason a job document can have.
     *
     * @param refusal why it is not
     * @param detail what was observed, naming both values where two were compared
     */
    public record Refused(JobEvent.Refusal refusal, String detail) implements Outcome {
    }

    /** The result of following another snapshot: this one, or the one reason it cannot. */
    public sealed interface Succeeded permits Follows, Rejected {
    }

    /**
     * A snapshot that may follow the one already held.
     *
     * @param snapshot this snapshot
     */
    public record Follows(JobSnapshot snapshot) implements Succeeded {
    }

    /**
     * A snapshot that may not.
     *
     * @param succession why it may not
     * @param detail what was observed, naming both values
     */
    public record Rejected(Succession succession, String detail) implements Succeeded {
    }

    /**
     * Reads a snapshot, against the incarnation of the store currently being served.
     *
     * @param document the document
     * @param serving the incarnation this store is serving
     * @param contract the authenticated contract, which declares every bound
     * @return the snapshot, or the one reason there is none
     */
    public static Outcome read(DocumentValue document, EventStoreGeneration serving,
                               AgentContract contract) {
        final JobEvent.Outcome read = JobEvent.read(document, serving, contract);
        if (read instanceof final JobEvent.Refused refused) {
            return new Refused(refused.refusal(), refused.detail());
        }
        return new Held(new JobSnapshot(((JobEvent.Held) read).event()));
    }

    /**
     * Whether this snapshot may follow one a client already holds.
     *
     * <p>A repeat of the same terminal snapshot is accepted: asking twice what is currently true
     * and being told the same thing twice is the store working, not a conflict.</p>
     *
     * @param previous the snapshot already held
     * @return this snapshot, or the one reason it cannot follow that one
     */
    public Succeeded following(JobSnapshot previous) {
        if (!identifier().equals(previous.identifier())) {
            return new Rejected(Succession.ANOTHER_OPERATION, "this snapshot is about "
                    + identifier() + " and the one held is about " + previous.identifier());
        }
        if (!generation().equals(previous.generation())) {
            return new Rejected(Succession.FOREIGN_GENERATION, "this snapshot names generation "
                    + generation() + " and the one held names " + previous.generation());
        }
        if (sequence().compareTo(previous.sequence()) < 0) {
            return new Rejected(Succession.BEHIND_WHAT_WAS_SEEN, "this snapshot is at sequence "
                    + sequence() + " and " + previous.sequence() + " has already been seen");
        }
        return afterTerminal(previous);
    }

    private Succeeded afterTerminal(JobSnapshot previous) {
        if (previous.kind().finality() == JobEventKind.Finality.CONTINUES) {
            return new Follows(this);
        }
        if (kind() == previous.kind() && sequence().equals(previous.sequence())) {
            return new Follows(this);
        }
        return new Rejected(Succession.AFTER_A_TERMINAL_ONE, "this job already ended as "
                + previous.kind().spelling() + " and this snapshot says " + kind().spelling());
    }

    /**
     * The operation this snapshot is about.
     *
     * @return the identifier
     */
    public AgentOperationIdentifier identifier() {
        return held.identifier();
    }

    /**
     * The incarnation of the store this snapshot belongs to.
     *
     * @return the generation
     */
    public EventStoreGeneration generation() {
        return held.generation();
    }

    /**
     * What the store currently holds about this job.
     *
     * @return the kind, which is the event document's own type
     */
    public JobEventKind kind() {
        return held.kind();
    }

    /**
     * Everything up to and including this sequence has happened.
     *
     * @return the sequence, which is the event document's own type
     */
    public EventSequence sequence() {
        return held.sequence();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final JobSnapshot snapshot && held.equals(snapshot.held);
    }

    @Override
    public int hashCode() {
        return Objects.hash(held);
    }

    @Override
    public String toString() {
        return "snapshot " + held;
    }
}
