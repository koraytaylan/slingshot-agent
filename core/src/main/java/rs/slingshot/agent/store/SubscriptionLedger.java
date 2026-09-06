// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.List;
import java.util.Optional;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
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

    /** The operation whose cursor this subscription follows. */
    public static final String OPERATION = "agent_operation_identifier";

    /** How much of a row one subscription costs, which is one row. */
    private static final long ONE_ROW = 1;

    private static final String BYTE_COUNT = "byte_count";

    private SubscriptionLedger() {
    }

    /** Why a subscription is not held. */
    public enum Refusal {
        /** It names an incarnation of the store this side is not serving. */
        FOREIGN_GENERATION,
        /** Its identifier is not one this build will write down. */
        IDENTIFIER_REFUSED,
        /** Its record has not moved for longer than anything this side keeps. */
        EXPIRED,
        /** The name belongs to a different caller, operation, or generation, or has no valid binding. */
        BINDING_DIFFERS
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
     * @param operation the only operation it follows
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @return what subscribing did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome subscribe(Session session, StatePath.Caller caller, String subscription,
                                    EventStoreGeneration generation, AgentOperationIdentifier operation,
                                    long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
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
        final SubscriptionRecord record = new SubscriptionRecord(identifier, generation,
                new SubscriptionRecord.Binding(caller, operation),
                SubscriptionRecord.Unread.NOTHING_SHOWN_YET, nowUnixMilliseconds);
        return againstWhatIsHeld(session, record, contract);
    }

    private static Outcome againstWhatIsHeld(Session session, SubscriptionRecord record,
                                             AgentContract contract) throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(record.identifier());
        if (session.nodeExists(path.path())) {
            return resumed(session, record, contract);
        }
        return admitted(session, record, contract);
    }

    private static Outcome resumed(Session session, SubscriptionRecord asked, AgentContract contract)
            throws RepositoryException {
        final Optional<SubscriptionRecord> current = readBack(session.getNode(
                SubscriptionRecord.pathOf(asked.identifier()).path()), asked.identifier(), contract);
        if (current.isEmpty() || !current.get().binding().equals(asked.binding())
                || !current.get().generation().equals(asked.generation())) {
            return new Refused(Refusal.BINDING_DIFFERS,
                    "the subscription name is not bound to this caller, operation, and generation");
        }
        final SubscriptionRecord held = current.get();
        return expired(held, asked.lastAdvancedAtUnixMilliseconds(), contract)
                ? new Refused(Refusal.EXPIRED, "the subscription retention elapsed") : new Resumed(held);
    }

    private static Outcome admitted(Session session, SubscriptionRecord record, AgentContract contract)
            throws RepositoryException {
        final StatePath.Caller caller = record.binding().caller();
        final CapacityLedger.ReservationAdmission admission = CapacityLedger.take(session, caller,
                List.of(new CapacityReservation.Charge(AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, ONE_ROW),
                        new CapacityReservation.Charge(AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES,
                                record.bytes())), contract);
        if (!(admission instanceof final CapacityLedger.Reserved reserved)) {
            return of(admission);
        }
        return claimed(session, caller, record, contract, reserved.reservation());
    }

    private static Outcome claimed(Session session, StatePath.Caller caller,
                                   SubscriptionRecord record, AgentContract contract,
                                   CapacityReservation reservation) throws RepositoryException {
        try (CapacityReservation.Guard guard = reservation.guard(session, contract)) {
            ClaimByCreation.claim(session, StatePath.deployment(SubscriptionRecord.NODE),
                    "nt:unstructured", node -> { });
            final WriteOutcome claimed = ClaimByCreation.claim(session,
                    SubscriptionRecord.pathOf(record.identifier()), "nt:unstructured",
                    node -> write(node, record, caller, guard.reservation()));
            if (claimed == WriteOutcome.CLAIMED) {
                return new Subscribed(record);
            }
            if (claimed == WriteOutcome.ALREADY_HELD) {
                return resumed(session, record, contract);
            }
            return new NotCounted(new CapacityLedger.NotCounted(
                    AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, claimed));
        }
    }

    private static void write(Node node, SubscriptionRecord record, StatePath.Caller caller,
                                CapacityReservation reservation) throws RepositoryException {
        node.setProperty(SubscriptionRecord.IDENTIFIER, record.identifier().rendered());
        node.setProperty(SubscriptionRecord.GENERATION, record.generation().number());
        node.setProperty(SubscriptionRecord.EVENTS_SHOWN, record.eventsShown());
        node.setProperty(SubscriptionRecord.LAST_ADVANCED_AT,
                record.lastAdvancedAtUnixMilliseconds());
        node.setProperty(SUBSCRIBER, caller.name());
        node.setProperty(OPERATION, record.binding().operation().rendered());
        node.setProperty(BYTE_COUNT, record.bytes());
        CapacityReservation.retain(node.getSession(), reservation, node);
    }

    /**
     * Stages a new binding in the enclosing operation acceptance transaction.
     *
     * @param session the publication session, with a prepared subscription parent
     * @param record the new immutable binding
     * @param reservation the capacity retained by the new row
     * @throws RepositoryException if staging fails or the name is already held
     */
    public static void stage(Session session, SubscriptionRecord record, CapacityReservation reservation)
            throws RepositoryException {
        final Node parent = session.getNode(StatePath.deployment(SubscriptionRecord.NODE).path());
        CompareAndSet.stamp(parent);
        final StatePath path = SubscriptionRecord.pathOf(record.identifier());
        if (session.nodeExists(path.path())) {
            throw new InvalidItemStateException("the subscription name was claimed concurrently");
        }
        final String name = path.path().substring(path.path().lastIndexOf('/') + 1);
        final Node node = parent.addNode(name, "nt:unstructured");
        write(node, record, record.binding().caller(), reservation);
        CompareAndSet.stamp(node);
    }

    /**
     * Fences and rechecks an existing binding in the enclosing acceptance transaction.
     *
     * @param session the publication session
     * @param record the binding that admission requires
     * @param contract the authenticated bounds
     * @throws RepositoryException if the binding disappeared, changed, expired, or cannot be read
     */
    public static void stageHeld(Session session, SubscriptionRecord record, AgentContract contract)
            throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(record.identifier());
        if (!session.nodeExists(path.path())) {
            throw new InvalidItemStateException("the subscription binding disappeared");
        }
        CompareAndSet.stamp(session.getNode(path.path()));
        if (!(resumed(session, record, contract) instanceof Resumed)) {
            throw new InvalidItemStateException("the subscription binding changed");
        }
    }

    /**
     * Reads a complete durable subscription and its immutable assignment.
     *
     * @param session the internal state session
     * @param identifier the subscription name
     * @param contract the authenticated bounds
     * @return the subscription, or absence for a missing record or invalid binding
     * @throws RepositoryException if the repository fails
     */
    public static Optional<SubscriptionRecord> read(Session session, SubscriptionRecord.Identifier identifier,
                                                     AgentContract contract) throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(identifier);
        return session.nodeExists(path.path())
                ? readBack(session.getNode(path.path()), identifier, contract) : Optional.empty();
    }

    private static Optional<SubscriptionRecord> readBack(Node held, SubscriptionRecord.Identifier identifier,
                                                          AgentContract contract) throws RepositoryException {
        if (!held.hasProperty(SUBSCRIBER) || !held.hasProperty(OPERATION)
                || !held.hasProperty(SubscriptionRecord.GENERATION)
                || !held.hasProperty(SubscriptionRecord.LAST_ADVANCED_AT)) {
            return Optional.empty();
        }
        return decoded(held, identifier, contract);
    }

    private static Optional<SubscriptionRecord> decoded(Node held, SubscriptionRecord.Identifier identifier,
                                                         AgentContract contract) throws RepositoryException {
        final StatePath.Outcome caller = StatePath.caller(held.getProperty(SUBSCRIBER).getString());
        final AgentOperationIdentifier.Outcome operation = AgentOperationIdentifier.of(
                held.getProperty(OPERATION).getString(), contract);
        final EventStoreGeneration.Outcome generation = EventStoreGeneration.of(
                held.getProperty(SubscriptionRecord.GENERATION).getLong());
        if (!(caller instanceof final StatePath.Held owner)
                || !(operation instanceof final AgentOperationIdentifier.Held named)
                || !(generation instanceof final EventStoreGeneration.Held stored)) {
            return Optional.empty();
        }
        return Optional.of(new SubscriptionRecord(identifier, stored.generation(),
                new SubscriptionRecord.Binding(owner.caller(), named.identifier()),
                SubscriptionRecord.cursorFor(CompareAndSet.held(held, SubscriptionRecord.EVENTS_SHOWN)),
                held.getProperty(SubscriptionRecord.LAST_ADVANCED_AT).getLong()));
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
        CapacityLedger.retireResource(session, SubscriptionRecord.pathOf(record.identifier()), caller,
                new CapacityLedger.ResourceCharge(AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                        AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, BYTE_COUNT, record.bytes()));
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

    private static Outcome of(CapacityLedger.ReservationAdmission admission) {
        return admission instanceof final CapacityLedger.Refused refused
                ? new AtCapacity(refused)
                : new NotCounted((CapacityLedger.NotCounted) admission);
    }
}
