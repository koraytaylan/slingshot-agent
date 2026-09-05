// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The one place work is admitted against what this store may hold.
 *
 * <p>It sits with the store primitives rather than beside its callers because everything after it
 * admits against these counts — the operation record, the event ledger, the subscription ledger,
 * the artifact store, and the intake a manifest declares. An authority that arrived after its
 * callers would be an authority several of them had already written their own version of, and two
 * admission paths over one count is exactly the arrangement in which the total stops meaning
 * anything.</p>
 *
 * <p>Each decision fences the total and caller counter before reading either, then saves both
 * changes together. A conflicting commit retries the whole decision from fresh state. Neither
 * an interrupted request nor a refused admission can publish half an accounting transition.</p>
 *
 * <p>Reads include every supported legacy shard. Successful transitions consolidate their exact
 * sum into the first shard and remove the others in the same commit. Configured bounds decide
 * admission thresholds; changing a bound cannot hide existing capacity or redirect its release.
 * A transfer may preserve or reduce a previously paid quantity after its bound is lowered, while
 * any increase must still fit the new bound.</p>
 */
public final class CapacityLedger {

    private static final String RESOURCE_RELEASED = "capacity_released";

    private CapacityLedger() {
    }

    /** Which bound an admission was refused at. */
    public enum Reached {
        /** The whole generation's bound. */
        THE_TOTAL,
        /** One submitting caller's share of it. */
        THE_CALLERS_SHARE
    }

    /** The result of admitting: it was admitted, or the one reason it was not. */
    public sealed interface Admission permits Admitted, Refused, NotCounted {
    }

    /** The result of allocating and activating a fresh reservation identity. */
    public sealed interface ReservationAdmission permits Reserved, Refused, NotCounted {
    }

    /**
     * The complete reservation now owned by its caller.
     *
     * @param reservation the identity that must accompany publication or release
     */
    public record Reserved(CapacityReservation reservation) implements ReservationAdmission {
    }

    /**
     * How a retained legacy record was counted before it carried a reservation identity.
     *
     * @param rows the quantity charged for its row
     * @param bytes the quantity charged for its bytes
     * @param sizeProperty where the record persists its charged byte size
     * @param absentSize the immutable legacy record size when no size property was persisted
     */
    public record ResourceCharge(AccountedQuantity rows, AccountedQuantity bytes, String sizeProperty,
                                  long absentSize) {

        /**
         * Describes a resource whose missing size means zero bytes.
         *
         * @param rows its row quantity
         * @param bytes its byte quantity
         * @param sizeProperty its persisted size property
         */
        public ResourceCharge(AccountedQuantity rows, AccountedQuantity bytes, String sizeProperty) {
            this(rows, bytes, sizeProperty, 0);
        }
    }

    /**
     * Deletes one resource and retires its complete accounting in the same fenced commit.
     *
     * @param session the cleanup session
     * @param resource the stable path, also usable after a lost deletion response
     * @param caller the owner of legacy capacity
     * @param charge the persisted legacy charge layout
     * @throws RepositoryException if ownership is invalid or deletion cannot commit after fresh retries
     */
    public static void retireResource(Session session, StatePath resource, StatePath.Caller caller,
                                       ResourceCharge charge) throws RepositoryException {
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            if (retiringResource(session, resource.path(), caller, charge) == WriteOutcome.WRITTEN) {
                return;
            }
            attempt = attempt + 1;
        }
        throw new RepositoryException("resource retirement remained contended");
    }

    private static WriteOutcome retiringResource(Session session, String path, StatePath.Caller caller,
                                                 ResourceCharge charge)
            throws RepositoryException {
        session.refresh(false);
        try {
            CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
            if (!session.nodeExists(path)) {
                return WriteOutcome.WRITTEN;
            }
            stageResourceRelease(session, session.getNode(path), caller, charge);
            session.getNode(path).remove();
            session.save();
            return WriteOutcome.WRITTEN;
        } catch (final InvalidItemStateException contended) {
            return WriteOutcome.CONTENDED;
        } finally {
            session.refresh(false);
        }
    }

    /**
     * Stages exact resource retirement without saving or refreshing the enclosing transaction.
     *
     * <p>The caller must delete the resource in that same commit, or discard all staged changes.</p>
     *
     * @param session the enclosing cleanup transaction
     * @param resource the resource that transaction will delete
     * @param caller the owner of any legacy charges
     * @param charge the persisted legacy charge layout
     * @throws RepositoryException if the resource is foreign or its accounting cannot be retired exactly
     */
    static void stageResourceRelease(Session session, Node resource, StatePath.Caller caller,
                                      ResourceCharge charge) throws RepositoryException {
        if (!resource.getPath().startsWith(StatePath.ROOT + "/")) {
            throw new RepositoryException("capacity retirement requires an agent-owned resource");
        }
        CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
        CompareAndSet.stamp(resource);
        if (resource.hasProperty(CapacityReservation.RESOURCE_RESERVATION)) {
            releaseRecorded(session, resource);
        } else if (!resource.hasProperty(RESOURCE_RELEASED)) {
            final long bytes = resource.hasProperty(charge.sizeProperty())
                    ? resource.getProperty(charge.sizeProperty()).getLong() : charge.absentSize();
            returned(session, caller, List.of(new CapacityReservation.Charge(charge.rows(), 1),
                    new CapacityReservation.Charge(charge.bytes(), bytes)));
            resource.setProperty(RESOURCE_RELEASED, true);
        }
    }

    private static void releaseRecorded(Session session, Node resource)
            throws RepositoryException {
        final Optional<CapacityReservation> held = CapacityReservation.ofResource(session, resource);
        if (held.isPresent()) {
            returned(session, held.get());
            session.getNode(held.get().path().path()).remove();
        }
    }

    /**
     * Work this store has room for, and has now counted.
     *
     * @param quantity what was counted
     * @param amount how much of it
     */
    public record Admitted(AccountedQuantity quantity, long amount) implements Admission {
    }

    /**
     * Work this store has no room for, which has not been counted.
     *
     * @param quantity what was being counted
     * @param reached which bound was reached
     * @param bound the number that was reached
     * @param wouldHaveBeen what the count would have come to
     */
    public record Refused(AccountedQuantity quantity, Reached reached, long bound,
                          long wouldHaveBeen) implements Admission, ReservationAdmission {

        /**
         * Renders the refusal the way a failure message states one.
         *
         * @return the rendering, naming the quantity, the bound, and the value that crossed it
         */
        public String rendered() {
            return quantity.spelling() + " would come to " + wouldHaveBeen + ", past " + bound
                    + ", which is " + (reached == Reached.THE_TOTAL
                            ? "what this store may hold"
                            : "one caller's share of what this store may hold");
        }
    }

    /**
     * An admission that could not be decided, because the counting itself did not happen.
     *
     * <p>Distinct from a refusal on purpose: a caller told "there is no room" would stop asking, and
     * a caller told "the count could not be written" knows the store is in trouble rather than
     * full.</p>
     *
     * @param quantity what was being counted
     * @param outcome what the write did instead
     */
    public record NotCounted(AccountedQuantity quantity, WriteOutcome outcome)
            implements Admission, ReservationAdmission {
    }

    /**
     * Allocates and activates one complete reservation for this bundle incarnation.
     *
     * <p>If activation is refused or its response is lost, cleanup cancels this exact identity.
     * A cleanup failure is preserved alongside the original failure for ownership recovery.</p>
     *
     * @param session the session to write under
     * @param caller whose capacity is charged
     * @param charges all quantities needed by the work
     * @param contract the authenticated bounds
     * @return the held identity, the exceeded bound, or the failed counter decision
     * @throws RepositoryException if allocation, activation, or cleanup fails
     */
    public static ReservationAdmission take(Session session, StatePath.Caller caller,
                                             List<CapacityReservation.Charge> charges,
                                             AgentContract contract) throws RepositoryException {
        session.refresh(false);
        for (final CapacityReservation.Charge charge : charges) {
            if (!session.nodeExists(totalPath(charge.quantity()).path())
                    || !session.nodeExists(callerPath(charge.quantity(), caller).path())) {
                return new NotCounted(charge.quantity(), WriteOutcome.VALUE_CHANGED);
            }
        }
        final Optional<CapacityReservation> pending = CapacityReservation.create(session,
                CapacityReservation.processOwner(), caller, charges);
        if (pending.isEmpty()) {
            return new NotCounted(charges.getFirst().quantity(), WriteOutcome.CONTENDED);
        }
        final CapacityReservation reservation = pending.get();
        try (CapacityReservation.Guard guard = reservation.guard(session, contract)) {
            final Admission admission = reserve(session, reservation, contract);
            if (admission instanceof Admitted) {
                guard.handoff();
                return new Reserved(reservation);
            }
            return admission instanceof final Refused refused ? refused : (NotCounted) admission;
        }
    }

    /**
     * Activates a pending identity, committing its entire charge vector together.
     *
     * <p>A retry of an active identity succeeds without charging again. An identity removed by
     * release cannot be admitted again. Successful outcomes name the first charge; the supplied
     * reservation remains the authority for every quantity released later.</p>
     *
     * @param session the session to write under
     * @param reservation the previously created pending identity
     * @param contract the authenticated capacity bounds
     * @return admission of the vector, or the first charge that could not be admitted
     * @throws RepositoryException if the repository fails or the identity differs from its record
     */
    public static Admission reserve(Session session, CapacityReservation reservation,
                                    AgentContract contract) throws RepositoryException {
        return reserve(session, List.of(reservation), contract);
    }

    /**
     * Activates all pending manifest identities in one transaction, or activates none of them.
     *
     * <p>Already active identities are recognised without charging again. Each identity remains
     * independently releasable after the complete manifest has been admitted.</p>
     *
     * @param session the admission session
     * @param reservations the nonempty manifest of distinct durable identities
     * @param contract the authenticated capacity bounds
     * @return admission of the manifest, or the first charge that could not be admitted
     * @throws RepositoryException if the repository fails or an identity differs from its record
     */
    public static Admission reserve(Session session, List<CapacityReservation> reservations,
                                    AgentContract contract) throws RepositoryException {
        final List<CapacityReservation> manifest = List.copyOf(reservations);
        if (manifest.isEmpty() || manifest.stream().map(CapacityReservation::identifier).distinct().count()
                != manifest.size()) {
            throw new IllegalArgumentException("a manifest needs distinct reservation identities");
        }
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            final Admission outcome = reserving(session, manifest, contract);
            if (!(outcome instanceof final NotCounted missed)
                    || missed.outcome() != WriteOutcome.CONTENDED) {
                return outcome;
            }
            attempt = attempt + 1;
        }
        return new NotCounted(manifest.getFirst().charges().getFirst().quantity(), WriteOutcome.CONTENDED);
    }

    private static Admission reserving(Session session, List<CapacityReservation> reservations,
                                       AgentContract contract) throws RepositoryException {
        session.refresh(false);
        final CapacityReservation.Charge first = reservations.getFirst().charges().getFirst();
        try {
            CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
            for (final CapacityReservation reservation : reservations) {
                final Admission outcome = activating(session, reservation, contract);
                if (!(outcome instanceof Admitted)) {
                    return outcome;
                }
            }
            session.save();
            return new Admitted(first.quantity(), first.amount());
        } catch (final InvalidItemStateException contended) {
            return new NotCounted(first.quantity(), WriteOutcome.CONTENDED);
        } finally {
            session.refresh(false);
        }
    }

    private static Admission activating(Session session, CapacityReservation reservation,
                                         AgentContract contract) throws RepositoryException {
        return activating(session, reservation, contract, List.of());
    }

    private static Admission activating(Session session, CapacityReservation reservation,
                                         AgentContract contract, List<CapacityReservation.Charge> credits)
            throws RepositoryException {
        final CapacityReservation.Charge first = reservation.charges().getFirst();
        if (!matches(session, reservation)) {
            return new NotCounted(first.quantity(), WriteOutcome.VALUE_CHANGED);
        }
        final Node record = session.getNode(reservation.path().path());
        if (record.getProperty(CapacityReservation.ACTIVE).getBoolean()) {
            return new Admitted(first.quantity(), first.amount());
        }
        for (final CapacityReservation.Charge charge : reservation.charges()) {
            final long credit = credits.stream().filter(held -> held.quantity() == charge.quantity())
                    .mapToLong(CapacityReservation.Charge::amount).sum();
            final Admission outcome = staged(session, reservation.caller(), charge, contract, credit);
            if (!(outcome instanceof Admitted)) {
                return outcome;
            }
        }
        record.setProperty(CapacityReservation.ACTIVE, true);
        return new Admitted(first.quantity(), first.amount());
    }

    private static Admission staged(Session session, StatePath.Caller caller,
                                     CapacityReservation.Charge charge, AgentContract contract, long credit)
            throws RepositoryException {
        final AccountedQuantity quantity = charge.quantity();
        final StatePath total = totalPath(quantity);
        final StatePath share = callerPath(quantity, caller);
        if (!session.nodeExists(total.path()) || !session.nodeExists(share.path())) {
            return new NotCounted(quantity, WriteOutcome.VALUE_CHANGED);
        }
        final Node totalNode = session.getNode(total.path());
        CompareAndSet.stamp(totalNode);
        final Node shareNode = session.getNode(share.path());
        CompareAndSet.stamp(shareNode);
        return staged(totalNode, shareNode, quantity, charge.amount(), contract, credit);
    }

    /**
     * Stages replacement of retained capacity ownership without an intermediate release commit.
     *
     * <p>The caller must save the destination data and this transition together on admission, or
     * discard the entire session on refusal or failure. This method neither saves nor refreshes.
     * The old identity disappears, fencing delayed releases and transfers of the intake promise.</p>
     *
     * @param session the publication transaction
     * @param source the retained declaration whose capacity is being replaced
     * @param destination the newly staged artifact
     * @param replacement the fresh pending identity that will own the artifact
     * @param contract the authenticated capacity bounds
     * @return admission of the replacement, or the reason no transfer may be committed
     * @throws RepositoryException if persisted ownership is absent, inconsistent, or foreign
     */
    public static Admission transfer(Session session, Node source, Node destination,
                                     CapacityReservation replacement, AgentContract contract)
            throws RepositoryException {
        if (!source.getPath().startsWith(StatePath.ROOT + "/")
                || !destination.getPath().startsWith(StatePath.ROOT + "/")) {
            throw new RepositoryException("capacity transfer requires agent-owned resources");
        }
        CompareAndSet.stamp(source);
        CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
        final CapacityReservation original = CapacityReservation.ofResource(session, source)
                .orElseThrow(() -> new RepositoryException("transfer requires retained source capacity"));
        if (!original.caller().equals(replacement.caller())
                || original.identifier().equals(replacement.identifier())) {
            throw new RepositoryException("transfer requires a fresh identity belonging to the same caller");
        }
        if (!matches(session, replacement)) {
            return new NotCounted(replacement.charges().getFirst().quantity(), WriteOutcome.VALUE_CHANGED);
        }
        if (session.getNode(replacement.path().path()).getProperty(CapacityReservation.ACTIVE).getBoolean()) {
            throw new RepositoryException("transfer requires a pending replacement identity");
        }
        returned(session, original);
        final Admission admitted = activating(session, replacement, contract, original.charges());
        if (!(admitted instanceof Admitted)) {
            return admitted;
        }
        CapacityReservation.retain(session, replacement, destination);
        session.getNode(original.path().path()).remove();
        return admitted;
    }

    /**
     * Reclaims abandoned capacity belonging to a process whose retirement has been established.
     *
     * <p>The caller must prove that this process has stopped before invoking recovery. A different
     * owner identifier or an expired clock-based lease is not that proof. Published resources keep
     * their charges; requests that did not publish are cancelled by their durable identities.
     * Repeating recovery after an interrupted response cannot debit another owner's capacity.</p>
     *
     * <p>Legacy counters do not identify a process and are never attributed to the retired owner.
     * Their resources retain the existing charges until {@link #retireResource} retires them.
     * Historical discrepancies without durable ownership evidence cannot be repaired by inferring
     * identities or replacing the counts with the sum of modern reservations.</p>
     *
     * @param session the recovery session
     * @param retiredOwner the verified stopped process incarnation
     * @param contract the authenticated contract used by the cleanup scope
     * @throws RepositoryException if inventory or cancellation cannot commit
     */
    public static void recoverOwner(Session session, UUID retiredOwner, AgentContract contract)
            throws RepositoryException {
        if (retiredOwner.equals(CapacityReservation.processOwner())) {
            throw new RepositoryException("the running process cannot be recovered as retired");
        }
        final List<CapacityReservation> owned = inventory(session).stream()
                .filter(reservation -> reservation.owner().equals(retiredOwner)).toList();
        for (final CapacityReservation reservation : owned) {
            cancel(session, reservation, contract);
        }
    }

    private static List<CapacityReservation> inventory(Session session) throws RepositoryException {
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            session.refresh(false);
            try {
                CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
                final List<CapacityReservation> reservations = CapacityReservation.inventory(session);
                // Validate that discovery did not miss an owner write hidden by a stale store view.
                session.save();
                return reservations;
            } catch (final InvalidItemStateException contended) {
                attempt = attempt + 1;
            } finally {
                session.refresh(false);
            }
        }
        throw new RepositoryException("capacity inventory remained contended after fresh attempts");
    }

    /**
     * Releases one durable identity at most once, including after an uncertain save response.
     *
     * <p>A pending identity is cancelled without touching counters. An active identity's complete
     * charge vector and its record disappear in the same commit. A missing identity is already
     * released, so a late retry cannot debit another reservation of the same size.</p>
     *
     * @param session the session to write under
     * @param reservation the identity to release or cancel
     * @param contract the authenticated counter layout
     * @throws RepositoryException if release cannot commit or identity data differs
     */
    public static void release(Session session, CapacityReservation reservation,
                               AgentContract contract) throws RepositoryException {
        release(session, reservation, Returning.EVERYTHING);
    }

    /**
     * Cancels abandoned work while preserving charges for data already published by that work.
     *
     * @param session the session to write under
     * @param reservation the abandoned request's durable identity
     * @param contract the authenticated counter layout
     * @throws RepositoryException if cancellation cannot commit or retained ownership is inconsistent
     */
    public static void cancel(Session session, CapacityReservation reservation,
                              AgentContract contract) throws RepositoryException {
        release(session, reservation, Returning.ONLY_ABANDONED);
    }

    /** Whether release is explicit retention cleanup or cancellation of unfinished work. */
    private enum Returning {
        /** The caller explicitly retires the data or ends ephemeral work. */
        EVERYTHING,
        /** Data already published must keep its capacity. */
        ONLY_ABANDONED
    }

    private static void release(Session session, CapacityReservation reservation,
                                 Returning returning) throws RepositoryException {
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            if (releasing(session, reservation, returning) == WriteOutcome.WRITTEN) {
                return;
            }
            attempt = attempt + 1;
        }
        throw new RepositoryException("reservation release remained contended after "
                + CompareAndSet.ATTEMPTS + " fresh attempts");
    }

    private static WriteOutcome releasing(Session session, CapacityReservation reservation,
                                           Returning returning)
            throws RepositoryException {
        session.refresh(false);
        try {
            CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
            if (!matches(session, reservation)) {
                return WriteOutcome.WRITTEN;
            }
            if (returning == Returning.ONLY_ABANDONED && CapacityReservation.retained(session, reservation)) {
                return WriteOutcome.WRITTEN;
            }
            final Node record = session.getNode(reservation.path().path());
            if (record.getProperty(CapacityReservation.ACTIVE).getBoolean()) {
                returned(session, reservation);
            }
            record.remove();
            session.save();
            return WriteOutcome.WRITTEN;
        } catch (final InvalidItemStateException contended) {
            return WriteOutcome.CONTENDED;
        } finally {
            session.refresh(false);
        }
    }

    private static void returned(Session session, CapacityReservation reservation)
            throws RepositoryException {
        returned(session, reservation.caller(), reservation.charges());
    }

    private static void returned(Session session, StatePath.Caller caller,
                                  List<CapacityReservation.Charge> charges)
            throws RepositoryException {
        for (final CapacityReservation.Charge charge : charges) {
            final AccountedQuantity quantity = charge.quantity();
            final Node total = session.getNode(totalPath(quantity).path());
            CompareAndSet.stamp(total);
            final Node share = session.getNode(callerPath(quantity, caller).path());
            CompareAndSet.stamp(share);
            advance(total, -charge.amount());
            advance(share, -charge.amount());
        }
    }

    private static boolean matches(Session session, CapacityReservation reservation)
            throws RepositoryException {
        final Optional<CapacityReservation> persisted =
                CapacityReservation.read(session, reservation.path());
        if (persisted.isPresent() && !persisted.get().equals(reservation)) {
            throw new RepositoryException("the capacity identity differs from its durable record");
        }
        return persisted.isPresent();
    }

    private static Admission staged(Node total, Node share, AccountedQuantity quantity,
                                     long amount, AgentContract contract, long credit)
            throws RepositoryException {
        final long all = Math.addExact(counted(total), amount);
        if (amount > credit && all > quantity.admissibleTotal(contract)) {
            return new Refused(quantity, Reached.THE_TOTAL, quantity.admissibleTotal(contract), all);
        }
        final long mine = Math.addExact(counted(share), amount);
        if (amount > credit && mine > quantity.admissibleCallerShare(contract)) {
            return new Refused(quantity, Reached.THE_CALLERS_SHARE,
                    quantity.admissibleCallerShare(contract), mine);
        }
        advance(total, amount);
        advance(share, amount);
        return new Admitted(quantity, amount);
    }

    private static long counted(Node node) throws RepositoryException {
        long total = 0;
        int index = 0;
        while (index < ShardedCount.SHARDS) {
            final long held = CompareAndSet.held(node, ShardedCount.SHARD_PREFIX + index);
            if (held < 0) {
                throw new RepositoryException("capacity contains a negative persisted shard");
            }
            total = Math.addExact(total, held);
            index = index + 1;
        }
        return total;
    }

    private static void advance(Node node, long amount) throws RepositoryException {
        final long next = Math.addExact(counted(node), amount);
        if (next < 0) {
            throw new RepositoryException("capacity release exceeds the held reservation");
        }
        // The node revision already serialises writers. Consolidate every legacy shard in this
        // same fenced transaction so bounds changes cannot select a different release location.
        node.setProperty(ShardedCount.SHARD_PREFIX + 0, next);
        int index = 1;
        while (index < ShardedCount.SHARDS) {
            final String property = ShardedCount.SHARD_PREFIX + index;
            if (node.hasProperty(property)) {
                node.getProperty(property).remove();
            }
            index = index + 1;
        }
    }

    /**
     * What this store currently holds of one quantity.
     *
     * @param session the session to read under
     * @param quantity what to read
     * @param contract the authenticated contract; persisted counts remain visible across bounds changes
     * @return the total
     * @throws RepositoryException if the repository fails
     */
    public static long held(Session session, AccountedQuantity quantity, AgentContract contract)
            throws RepositoryException {
        session.refresh(false);
        return counted(session.getNode(totalPath(quantity).path()));
    }

    /**
     * What one caller currently holds of one quantity.
     *
     * @param session the session to read under
     * @param quantity what to read
     * @param caller whose share to read
     * @param contract the authenticated contract; persisted counts remain visible across bounds changes
     * @return the caller's total
     * @throws RepositoryException if the repository fails
     */
    public static long heldBy(Session session, AccountedQuantity quantity, StatePath.Caller caller,
                              AgentContract contract) throws RepositoryException {
        session.refresh(false);
        return counted(session.getNode(callerPath(quantity, caller).path()));
    }

    /**
     * Prepares the two counters one quantity is counted on, if they are not there.
     *
     * @param session the session to write under
     * @param quantity what will be counted
     * @param caller who will be counted
     * @throws RepositoryException if the repository fails
     */
    public static void prepare(Session session, AccountedQuantity quantity,
                               StatePath.Caller caller) throws RepositoryException {
        claim(session, StatePath.deployment(StatePath.CAPACITY));
        claim(session, totalPath(quantity));
        claim(session, StatePath.deployment(StatePath.CAPACITY).child(StatePath.CALLERS));
        prepareCaller(session, quantity, caller);
    }

    private static void prepareCaller(Session session, AccountedQuantity quantity,
                                      StatePath.Caller caller) throws RepositoryException {
        final StatePath counters = StatePath.caller(caller);
        final String[] segments = counters.path()
                .substring(StatePath.ROOT.length() + 1)
                .split("/");
        StatePath walked = StatePath.deployment(segments[0]);
        int index = 1;
        while (index < segments.length) {
            walked = walked.child(segments[index]);
            claim(session, walked);
            index = index + 1;
        }
        claim(session, callerPath(quantity, caller));
    }

    private static void claim(Session session, StatePath path) throws RepositoryException {
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            session.refresh(false);
            final WriteOutcome outcome = ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
            if (outcome != WriteOutcome.CONTENDED && session.nodeExists(path.path())) {
                return;
            }
            attempt = attempt + 1;
        }
        throw new RepositoryException("capacity preparation remained contended after fresh attempts");
    }

    /**
     * Where one quantity's own total is counted.
     *
     * @param quantity the quantity
     * @return the path
     */
    public static StatePath totalPath(AccountedQuantity quantity) {
        return StatePath.deployment(StatePath.CAPACITY).child(quantity.spelling());
    }

    /**
     * Where one caller's share of one quantity is counted.
     *
     * @param quantity the quantity
     * @param caller the caller
     * @return the path
     */
    public static StatePath callerPath(AccountedQuantity quantity, StatePath.Caller caller) {
        return StatePath.caller(caller).child(quantity.spelling());
    }

}
