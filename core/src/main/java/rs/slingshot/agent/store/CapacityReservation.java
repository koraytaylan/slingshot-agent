// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;

/**
 * A durable admission identity, created pending and consumed by one release.
 *
 * <p>Only {@link #create} creates a pending record, always with a fresh identifier. Admission
 * requires that record to exist. Removing it therefore also fences delayed admission retries;
 * released identities need no tombstone and cannot be recreated through the admission API.</p>
 *
 * @param identifier the unique reservation identity
 * @param owner the process incarnation responsible for pending or active work
 * @param caller whose capacity is charged
 * @param charges all quantities charged in one transaction
 */
public record CapacityReservation(UUID identifier, UUID owner, StatePath.Caller caller,
                                  List<Charge> charges) {

    /** The bucketed reservation subtree under the capacity authority. */
    public static final String NODE = "reservations";

    private static final UUID PROCESS_OWNER = UUID.randomUUID();

    /**
     * Identifies this bundle incarnation for durable reservation ownership.
     *
     * @return the same owner for this incarnation, distinct after a restart or bundle reload
     */
    public static UUID processOwner() {
        return PROCESS_OWNER;
    }

    /**
     * Cancels unfinished ownership on scope exit, preserving suppressed cleanup failures.
     * A successful transfer explicitly hands the reservation to its next owner.
     */
    public static final class Guard implements AutoCloseable {

        private final Session session;
        private final CapacityReservation reservation;
        private final AgentContract contract;
        private final AtomicBoolean handedOff = new AtomicBoolean();

        /**
         * Opens a cleanup scope for a known identity.
         *
         * @param session the cleanup session
         * @param reservation the identity owned by this scope
         * @param contract the authenticated counter layout
         */
        private Guard(Session session, CapacityReservation reservation, AgentContract contract) {
            this.session = session;
            this.reservation = reservation;
            this.contract = contract;
        }

        /** Transfers cleanup responsibility after the next owner has accepted it. */
        public void handoff() {
            handedOff.set(true);
        }

        /**
         * Returns the identity owned by this cleanup scope.
         *
         * @return the guarded reservation
         */
        public CapacityReservation reservation() {
            return reservation;
        }

        /**
         * Cancels abandoned capacity while retaining the charge for successfully published data.
         *
         * @throws RepositoryException if cleanup fails
         */
        @Override
        public void close() throws RepositoryException {
            if (!handedOff.get()) {
                CapacityLedger.cancel(session, reservation, contract);
            }
        }
    }

    /**
     * Borrows a session for a lexical cleanup scope without exposing that session through the guard.
     *
     * @param session the session borrowed for cleanup
     * @param contract the authenticated counter layout
     * @return the scope guarding this reservation until handoff or close
     */
    public Guard guard(Session session, AgentContract contract) {
        return new Guard(session, this, contract);
    }

    /** Owns all identities allocated while a manifest is being prepared and published. */
    public static final class Batch implements AutoCloseable {

        private final Session session;
        private final AgentContract contract;
        private final List<CapacityReservation> reservations = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Batch(Session session, AgentContract contract) {
            this.session = session;
            this.contract = contract;
        }

        /**
         * Allocates and tracks a pending identity before the next manifest slot is prepared.
         *
         * @param caller the manifest owner
         * @param charges the slot's complete vector
         * @return the new identity, or absence after bounded allocation contention
         * @throws RepositoryException if allocation fails
         */
        public Optional<CapacityReservation> create(StatePath.Caller caller, List<Charge> charges)
                throws RepositoryException {
            if (closed.get()) {
                throw new IllegalStateException("a closed reservation batch cannot allocate identities");
            }
            final Optional<CapacityReservation> created =
                    CapacityReservation.create(session, processOwner(), caller, charges);
            created.ifPresent(reservations::add);
            return created;
        }

        /**
         * Returns a read-only view of the identities allocated by this batch.
         *
         * @return the manifest's identities in allocation order, including subsequent allocations
         */
        public List<CapacityReservation> reservations() {
            return Collections.unmodifiableList(reservations);
        }

        /**
         * Cancels every unfinished identity, preserving published data and all cleanup failures.
         *
         * @throws RepositoryException if any cancellation fails, with later failures suppressed
         */
        @Override
        public void close() throws RepositoryException {
            closed.set(true);
            final List<RepositoryException> failures = new ArrayList<>();
            for (final CapacityReservation reservation : reservations) {
                try {
                    CapacityLedger.cancel(session, reservation, contract);
                } catch (final RepositoryException failed) {
                    failures.add(failed);
                }
            }
            if (!failures.isEmpty()) {
                final RepositoryException first = failures.getFirst();
                failures.stream().skip(1).filter(failed -> !first.equals(failed))
                        .forEach(first::addSuppressed);
                throw first;
            }
        }
    }

    /**
     * Borrows the publication session for a manifest's complete allocation and cleanup scope.
     *
     * @param session the session used to allocate and cancel identities
     * @param contract the authenticated counter layout
     * @return the empty batch, which must be closed after manifest publication or refusal
     */
    public static Batch batch(Session session, AgentContract contract) {
        return new Batch(session, contract);
    }

    /** Whether the reservation has committed its charges. */
    static final String ACTIVE = "active";

    /** The reservation identifier stored with retained data in its publication commit. */
    public static final String RESOURCE_RESERVATION = "capacity_reservation";

    private static final String RETAINED_AT = "retained_at";

    private static final String OWNER = "owner";

    private static final String CALLER = "caller";

    private static final String AMOUNT_PREFIX = "amount_";

    /**
     * One nonnegative quantity in the reservation.
     *
     * @param quantity what is charged
     * @param amount how much is charged
     */
    public record Charge(AccountedQuantity quantity, long amount) {
        /** Validates the amount before any store mutation. */
        public Charge {
            if (amount < 0) {
                throw new IllegalArgumentException("a reservation charge cannot be negative");
            }
        }
    }

    /** Validates and takes ownership of the immutable charge vector. */
    public CapacityReservation {
        charges = charges.stream().sorted(Comparator.comparing(Charge::quantity)).toList();
        if (charges.isEmpty() || charges.stream().map(Charge::quantity).distinct().count()
                != charges.size()) {
            throw new IllegalArgumentException("reservation quantities must be nonempty and unique");
        }
    }

    /**
     * Returns the immutable, canonical charge vector.
     *
     * @return all charges in quantity order
     */
    @Override
    public List<Charge> charges() {
        return Collections.unmodifiableList(charges);
    }

    /**
     * Creates a fresh pending identity without charging capacity yet.
     *
     * @param session the session to write under
     * @param owner the responsible process incarnation
     * @param caller whose capacity will be charged
     * @param charges the complete charge vector
     * @return the pending reservation, or absence after bounded creation contention
     * @throws RepositoryException if the pending identity cannot be committed
     */
    public static Optional<CapacityReservation> create(Session session, UUID owner, StatePath.Caller caller,
                                              List<Charge> charges) throws RepositoryException {
        final CapacityReservation reservation =
                new CapacityReservation(UUID.randomUUID(), owner, caller, charges);
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            if (creating(session, reservation)) {
                return Optional.of(reservation);
            }
            attempt = attempt + 1;
        }
        return Optional.empty();
    }

    private static boolean creating(Session session, CapacityReservation reservation)
            throws RepositoryException {
        session.refresh(false);
        try {
            final Node capacity = session.getNode(StatePath.deployment(StatePath.CAPACITY).path());
            CompareAndSet.stamp(capacity);
            final Optional<CapacityReservation> existing = read(session, reservation.path());
            if (existing.isPresent()) {
                return existing.get().equals(reservation);
            }
            final StatePath path = reservation.path();
            final String name = path.path().substring(path.path().lastIndexOf('/') + 1);
            final StatePath rootPath = StatePath.deployment(StatePath.CAPACITY).child(NODE);
            final Node root = child(capacity, rootPath);
            final StatePath firstPath = rootPath.child(name.substring(0, StatePath.BUCKET_CHARACTERS));
            final Node first = child(root, firstPath);
            final Node second = child(first, firstPath.child(name.substring(StatePath.BUCKET_CHARACTERS,
                    StatePath.BUCKET_CHARACTERS * StatePath.BUCKET_DEPTH)));
            final Node record = second.addNode(name, "nt:unstructured");
            reservation.fill(record);
            session.save();
            return true;
        } catch (final InvalidItemStateException contended) {
            return false;
        } finally {
            session.refresh(false);
        }
    }

    private static Node child(Node parent, StatePath path) throws RepositoryException {
        final String name = path.path().substring(path.path().lastIndexOf('/') + 1);
        return parent.hasNode(name) ? parent.getNode(name) : parent.addNode(name, "nt:unstructured");
    }

    private void fill(Node node) throws RepositoryException {
        node.setProperty(OWNER, owner.toString());
        node.setProperty(CALLER, caller.name());
        node.setProperty(ACTIVE, false);
        for (final Charge charge : charges) {
            node.setProperty(AMOUNT_PREFIX + charge.quantity().spelling(), charge.amount());
        }
        CompareAndSet.stamp(node);
    }

    /**
     * Reads the durable identity without refreshing an enclosing transaction.
     *
     * @param session the session to read under
     * @param path the reservation record's path
     * @return the persisted reservation, or absence after release
     * @throws RepositoryException if the record is unreadable or malformed
     */
    public static Optional<CapacityReservation> read(Session session, StatePath path)
            throws RepositoryException {
        if (!session.nodeExists(path.path())) {
            return Optional.empty();
        }
        try {
            return Optional.of(decoded(session.getNode(path.path())));
        } catch (final IllegalArgumentException malformed) {
            throw new RepositoryException("the capacity reservation has invalid identity data", malformed);
        }
    }

    private static CapacityReservation decoded(Node node) throws RepositoryException {
        final StatePath.Outcome caller = StatePath.caller(node.getProperty(CALLER).getString());
        if (!(caller instanceof final StatePath.Held held)) {
            throw new RepositoryException("the capacity reservation has an invalid caller");
        }
        final List<Charge> charges = new ArrayList<>();
        for (final AccountedQuantity quantity : AccountedQuantity.values()) {
            if (node.hasProperty(AMOUNT_PREFIX + quantity.spelling())) {
                charges.add(new Charge(quantity,
                        node.getProperty(AMOUNT_PREFIX + quantity.spelling()).getLong()));
            }
        }
        return new CapacityReservation(UUID.fromString(node.getName()),
                UUID.fromString(node.getProperty(OWNER).getString()), held.caller(), charges);
    }

    /**
     * Reads the reservation identities in the current session view without refreshing or saving.
     *
     * @param session the fenced inventory session
     * @return the durable identities in the supported bucket layout
     * @throws RepositoryException if a reservation record or bucket cannot be read
     */
    static List<CapacityReservation> inventory(Session session) throws RepositoryException {
        final StatePath root = StatePath.deployment(StatePath.CAPACITY).child(NODE);
        final List<CapacityReservation> reservations = new ArrayList<>();
        if (session.nodeExists(root.path())) {
            try {
                collect(session.getNode(root.path()), StatePath.BUCKET_DEPTH, reservations);
            } catch (final IllegalArgumentException malformed) {
                throw new RepositoryException("capacity inventory contains a malformed identity", malformed);
            }
        }
        return reservations;
    }

    private static void collect(Node parent, int depth, List<CapacityReservation> reservations)
            throws RepositoryException {
        final javax.jcr.NodeIterator children = parent.getNodes();
        while (children.hasNext()) {
            final Node child = children.nextNode();
            if (depth == 0) {
                final CapacityReservation reservation = decoded(child);
                if (!reservation.path().path().equals(child.getPath())) {
                    throw new RepositoryException("capacity identity is outside its declared bucket");
                }
                reservations.add(reservation);
            } else {
                collect(child, depth - 1, reservations);
            }
        }
    }

    /**
     * Stages retained-data ownership in the same session as the data being published.
     *
     * <p>This method neither saves nor refreshes. Its caller must commit the resource and this
     * ownership change together, or discard both. A cancelled reservation cannot publish data.</p>
     *
     * @param session the publication session
     * @param reservation the active reservation that paid for the resource
     * @param resource the resource being published in this session
     * @throws RepositoryException if ownership is absent, inactive, or already bound elsewhere
     */
    public static void retain(Session session, CapacityReservation reservation, Node resource)
            throws RepositoryException {
        if (!resource.getPath().startsWith(StatePath.ROOT + "/")) {
            throw new RepositoryException("capacity ownership cannot be written outside the agent store");
        }
        CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
        final Optional<CapacityReservation> held = read(session, reservation.path());
        if (held.isEmpty() || !held.get().equals(reservation)) {
            throw new RepositoryException("publication requires its original capacity identity");
        }
        final Node record = session.getNode(reservation.path().path());
        if (!record.getProperty(ACTIVE).getBoolean()) {
            throw new RepositoryException("publication requires an active capacity reservation");
        }
        if (record.hasProperty(RETAINED_AT)
                && !record.getProperty(RETAINED_AT).getString().equals(resource.getPath())) {
            throw new RepositoryException("the capacity reservation already belongs to other data");
        }
        record.setProperty(RETAINED_AT, resource.getPath());
        session.getNode(resource.getPath()).setProperty(RESOURCE_RESERVATION,
                reservation.identifier().toString());
    }

    /**
     * Determines whether the identity still pays for its published data, without refreshing.
     *
     * @param session the recovery or cancellation transaction
     * @param reservation the identity to inspect
     * @return whether matching retained data still exists
     * @throws RepositoryException if the ownership record is unreadable or inconsistent
     */
    public static boolean retained(Session session, CapacityReservation reservation)
            throws RepositoryException {
        final Node record = session.getNode(reservation.path().path());
        if (!record.hasProperty(RETAINED_AT)) {
            return false;
        }
        final String path = record.getProperty(RETAINED_AT).getString();
        if (!path.startsWith(StatePath.ROOT + "/")) {
            throw new RepositoryException("retained capacity points outside the agent store");
        }
        if (!session.nodeExists(path)) {
            return false;
        }
        final Node resource = session.getNode(path);
        if (!resource.hasProperty(RESOURCE_RESERVATION)
                || !reservation.identifier().toString().equals(
                        resource.getProperty(RESOURCE_RESERVATION).getString())) {
            throw new RepositoryException("retained data has a different capacity identity");
        }
        return true;
    }

    /**
     * Reads the identity recorded on published data without refreshing the session.
     *
     * @param session the release transaction
     * @param resource the published data
     * @return its still-held reservation, or absence for legacy data or an already released identity
     * @throws RepositoryException if the recorded identity is malformed or belongs elsewhere
     */
    public static Optional<CapacityReservation> ofResource(Session session, Node resource)
            throws RepositoryException {
        if (!resource.hasProperty(RESOURCE_RESERVATION)) {
            return Optional.empty();
        }
        final UUID identifier;
        try {
            identifier = UUID.fromString(resource.getProperty(RESOURCE_RESERVATION).getString());
        } catch (final IllegalArgumentException malformed) {
            throw new RepositoryException("the resource has an invalid capacity identity", malformed);
        }
        final Optional<CapacityReservation> reservation = read(session, path(identifier));
        if (reservation.isEmpty()) {
            return reservation;
        }
        final Node record = session.getNode(path(identifier).path());
        if (!record.hasProperty(RETAINED_AT)
                || !record.getProperty(RETAINED_AT).getString().equals(resource.getPath())
                || !record.getProperty(ACTIVE).getBoolean()) {
            throw new RepositoryException("the resource does not own its recorded capacity");
        }
        return reservation;
    }

    /**
     * Derives the reservation record from its identifier alone.
     *
     * @return the bucketed path under the capacity authority
     */
    public StatePath path() {
        return path(identifier);
    }

    /**
     * Derives a record path from a persisted UUID, without inventing a reservation to recreate it.
     *
     * @param identifier the persisted identity
     * @return its bucketed reservation path
     */
    public static StatePath path(UUID identifier) {
        final String name = identifier.toString();
        return StatePath.deployment(StatePath.CAPACITY).child(NODE)
                .child(name.substring(0, StatePath.BUCKET_CHARACTERS))
                .child(name.substring(StatePath.BUCKET_CHARACTERS,
                        StatePath.BUCKET_CHARACTERS * StatePath.BUCKET_DEPTH)).child(name);
    }
}
