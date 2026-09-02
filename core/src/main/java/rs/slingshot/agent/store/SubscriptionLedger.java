// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Every live subscription this store holds, admitted against what the store can afford to hold.
 *
 * <p>A durable subscription is a row somebody else cannot have, so it is admitted the way every
 * other durable thing is: through the one capacity authority, against the generation's own bound
 * and against the subscribing caller's share of it. One caller holding every subscription row is
 * one caller deciding that nobody else may follow anything, and a refusal that does not say which
 * of the two bounds was reached leaves an operator guessing whether to raise a limit or to go and
 * find the client that is misbehaving.</p>
 *
 * <p>Subscribing twice under one name is resuming, not a second subscription: the record is claimed
 * by creation, so two daemons racing under one name leave one row and both are told where it
 * stands.</p>
 */
public final class SubscriptionLedger {

    /** The property the caller whose share holds this subscription is written in. */
    public static final String SUBSCRIBER = "subscribing_caller";

    /** How much of a row one subscription costs, which is one row. */
    private static final long ONE_ROW = 1;

    private SubscriptionLedger() {
    }

    /** Why a subscription is not held. */
    public enum Refusal {
        /** It names an incarnation of the store this side is not serving. */
        FOREIGN_GENERATION,
        /** Its identifier is not one this build will write down. */
        IDENTIFIER_REFUSED,
        /** Its record has not moved for longer than anything this side keeps. */
        EXPIRED
    }

    /** What subscribing did. */
    public sealed interface Outcome permits Subscribed, Resumed, Refused, AtCapacity, NotCounted {
    }

    /**
     * A subscription this store now holds, and did not before.
     *
     * @param record the record
     */
    public record Subscribed(SubscriptionRecord record) implements Outcome {
    }

    /**
     * A subscription this store already held, with the mark it already stood at.
     *
     * @param record the record
     */
    public record Resumed(SubscriptionRecord record) implements Outcome {
    }

    /**
     * A subscription that is not held, for a reason about the subscription itself.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * A subscription the store has no room for, with the bound that was reached.
     *
     * @param refusal what the capacity authority said, naming the bound and which of the two it was
     */
    public record AtCapacity(CapacityLedger.Refused refusal) implements Outcome {
    }

    /**
     * A store that counted nothing, which is one never prepared or one under contention.
     *
     * @param notCounted what the capacity authority said
     */
    public record NotCounted(CapacityLedger.NotCounted notCounted) implements Outcome {
    }

    /**
     * Takes a subscription under one name, or resumes the one already under it.
     *
     * @param session the session to write under
     * @param caller whose share the row and its bytes come out of
     * @param subscription the following daemon's own name for it
     * @param generation the incarnation it follows
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @return what subscribing did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome subscribe(Session session, StatePath.Caller caller, String subscription,
                                    EventStoreGeneration generation, long nowUnixMilliseconds,
                                    AgentContract contract) throws RepositoryException {
        final SubscriptionRecord.Outcome named =
                SubscriptionRecord.identifier(subscription, contract);
        if (named instanceof final SubscriptionRecord.Refused refused) {
            return new Refused(Refusal.IDENTIFIER_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        final SubscriptionRecord.Identifier identifier =
                ((SubscriptionRecord.Held) named).identifier();
        if (GenerationStore.membership(session, generation)
                != GenerationStore.Membership.SERVING) {
            return new Refused(Refusal.FOREIGN_GENERATION, "this store is not serving generation "
                    + generation + ", and a cursor into an incarnation nothing serves points at"
                    + " nothing");
        }
        return againstWhatIsHeld(session, caller, identifier, generation, nowUnixMilliseconds,
                contract);
    }

    private static Outcome againstWhatIsHeld(Session session, StatePath.Caller caller,
                                             SubscriptionRecord.Identifier identifier,
                                             EventStoreGeneration generation,
                                             long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(identifier);
        if (session.nodeExists(path.path())) {
            final SubscriptionRecord held = readBack(session, identifier, generation);
            return expired(held, nowUnixMilliseconds, contract)
                    ? new Refused(Refusal.EXPIRED, "the mark under " + identifier.rendered()
                            + " last moved at " + held.lastAdvancedAtUnixMilliseconds()
                            + ", longer ago than anything this side keeps")
                    : new Resumed(held);
        }
        return admitted(session, caller, identifier, generation, nowUnixMilliseconds, contract);
    }

    private static Outcome admitted(Session session, StatePath.Caller caller,
                                    SubscriptionRecord.Identifier identifier,
                                    EventStoreGeneration generation, long nowUnixMilliseconds,
                                    AgentContract contract) throws RepositoryException {
        final SubscriptionRecord record = new SubscriptionRecord(identifier, generation,
                SubscriptionRecord.Unread.NOTHING_SHOWN_YET, nowUnixMilliseconds);
        final CapacityLedger.Admission rows = CapacityLedger.admit(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, caller, ONE_ROW, contract);
        if (!(rows instanceof CapacityLedger.Admitted)) {
            return of(rows);
        }
        final CapacityLedger.Admission bytes = CapacityLedger.admit(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, caller, record.bytes(), contract);
        if (!(bytes instanceof CapacityLedger.Admitted)) {
            CapacityLedger.release(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, caller,
                    ONE_ROW, contract);
            return of(bytes);
        }
        return claimed(session, caller, record, contract);
    }

    private static Outcome claimed(Session session, StatePath.Caller caller,
                                   SubscriptionRecord record, AgentContract contract)
            throws RepositoryException {
        ClaimByCreation.claim(session, StatePath.deployment(SubscriptionRecord.NODE),
                "nt:unstructured", node -> { });
        final WriteOutcome claimed = ClaimByCreation.claim(session,
                SubscriptionRecord.pathOf(record.identifier()), "nt:unstructured",
                node -> write(node, record, caller));
        if (claimed == WriteOutcome.CLAIMED) {
            return new Subscribed(record);
        }
        release(session, caller, record, contract);
        return new Resumed(readBack(session, record.identifier(), record.generation()));
    }

    private static void write(Node node, SubscriptionRecord record, StatePath.Caller caller) {
        try {
            node.setProperty(SubscriptionRecord.IDENTIFIER, record.identifier().rendered());
            node.setProperty(SubscriptionRecord.GENERATION, record.generation().number());
            node.setProperty(SubscriptionRecord.EVENTS_SHOWN, record.eventsShown());
            node.setProperty(SubscriptionRecord.LAST_ADVANCED_AT,
                    record.lastAdvancedAtUnixMilliseconds());
            node.setProperty(SUBSCRIBER, caller.name());
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the subscription could not be written", unwritable);
        }
    }

    private static SubscriptionRecord readBack(Session session,
                                               SubscriptionRecord.Identifier identifier,
                                               EventStoreGeneration generation)
            throws RepositoryException {
        final Node held = session.getNode(SubscriptionRecord.pathOf(identifier).path());
        final EventStoreGeneration.Outcome stored =
                EventStoreGeneration.of(held.getProperty(SubscriptionRecord.GENERATION).getLong());
        return new SubscriptionRecord(identifier,
                stored instanceof final EventStoreGeneration.Held known
                        ? known.generation()
                        : generation,
                SubscriptionRecord.cursorFor(CompareAndSet.held(held,
                        SubscriptionRecord.EVENTS_SHOWN)),
                held.getProperty(SubscriptionRecord.LAST_ADVANCED_AT).getLong());
    }

    /**
     * Whether a record has stood still for longer than anything this side keeps.
     *
     * <p>The bound is the longest remaining retention this side will persist for anything at all: a
     * cursor kept past it points into events that are gone, and a row that outlives everything it
     * could point at is a row held for nobody.</p>
     *
     * @param record the record
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the bound
     * @return whether it has expired
     */
    public static boolean expired(SubscriptionRecord record, long nowUnixMilliseconds,
                                  AgentContract contract) {
        return nowUnixMilliseconds - record.lastAdvancedAtUnixMilliseconds()
                > contract.value(ContractLimit.MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS);
    }

    /**
     * Ends one subscription, giving back the row and the bytes it held.
     *
     * @param session the session to write under
     * @param caller whose share it came out of
     * @param record the record to end
     * @param contract the authenticated contract, which decides how the counts are spread
     * @throws RepositoryException if the repository fails
     */
    public static void end(Session session, StatePath.Caller caller, SubscriptionRecord record,
                           AgentContract contract) throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(record.identifier());
        if (session.nodeExists(path.path())) {
            session.getNode(path.path()).remove();
            session.save();
        }
        release(session, caller, record, contract);
    }

    private static void release(Session session, StatePath.Caller caller,
                                SubscriptionRecord record, AgentContract contract)
            throws RepositoryException {
        CapacityLedger.release(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, caller,
                record.bytes(), contract);
        CapacityLedger.release(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, caller,
                ONE_ROW, contract);
    }

    /**
     * Prepares the counters a subscription is admitted against.
     *
     * @param session the session to write under
     * @param caller who will be counted
     * @throws RepositoryException if the repository fails
     */
    public static void prepare(Session session, StatePath.Caller caller)
            throws RepositoryException {
        CapacityLedger.prepare(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, caller);
        CapacityLedger.prepare(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, caller);
    }

    /**
     * The one reason a subscription is not held, where that is why.
     *
     * @param outcome what subscribing did
     * @return the refusal, or nothing where it is held or the store had no room
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }

    private static Outcome of(CapacityLedger.Admission admission) {
        return admission instanceof final CapacityLedger.Refused refused
                ? new AtCapacity(refused)
                : new NotCounted((CapacityLedger.NotCounted) admission);
    }
}
