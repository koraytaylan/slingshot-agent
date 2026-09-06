// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.ArrayList;
import java.util.List;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * The bounded, resumable pass that removes what retention already permits removing.
 *
 * <p>Bounded because a pass that must finish is a pass that never does on a full store; resumable
 * because a bounded pass that restarted would sweep the first bucket forever; deterministic because
 * an operator comparing two reports is asking whether anything changed.</p>
 *
 * <p>What it may collect is the interesting part. Unreferenced is not enough on its own: bytes that
 * are written and not yet named are exactly what a worker halfway through ending an operation has
 * just produced, and collecting those is how a sweep becomes the thing that breaks an answer. So an
 * artifact goes only when its operation holds no live lease, when it is older than the lease a
 * worker could still be holding, and when no manifest still declares its slot. A sweep that is a
 * moment early is worse than one that is an hour late.</p>
 */
public final class MaintenanceSweep {

    /** The property a worker's lease expiry is written in, spelled where the fence spells it. */
    public static final String LEASE_HELD_UNTIL = "held_until_unix_milliseconds";

    /** The child of an operation a worker's lease lives at. */
    public static final String LEASE = "lease";

    /** The child of an operation the slots an inbound manifest declared live under. */
    public static final String INTAKE = "intake";

    /** The property a terminal commit's published answer names its slot in. */
    public static final String RESULT_SLOT = "result_slot";

    /** The property the caller whose share a record's belongings came out of is written in. */
    public static final String CALLER = "caller";

    /** What base a bucket's name is written in. */
    private static final int HEXADECIMAL = 16;

    /** The operation child retaining completion evidence before terminal publication. */
    public static final String COMPLETION = "completion";

    private MaintenanceSweep() {
    }

    /**
     * Runs one bounded pass, starting where the last one stopped.
     *
     * @param session the session to write under
     * @param generation the incarnation to sweep
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the work bound and every retention
     * @return what the pass did
     * @throws RepositoryException if the repository fails
     */
    public static SweepReport run(Session session, EventStoreGeneration generation,
                                  long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final SweepCursor from = SweepCursor.read(session);
        final Pass pass = new Pass(generation, nowUnixMilliseconds, contract);
        final long bound = contract.value(ContractLimit.MAINTENANCE_SWEEP_WORK_BOUND_ROWS);
        long examined = 0;
        long next = SweepCursor.BUCKETS;
        String nextRecord = "";
        final List<Long> present = buckets(session, generation);
        final int start = present.stream().takeWhile(bucket -> bucket < from.bucket()).toList().size();
        for (int visited = 0; visited < present.size(); visited++) {
            final int index = (start + visited) % present.size();
            final long bucket = present.get(index);
            if (examined >= bound) {
                next = bucket;
                break;
            }
            final SweepProgress progress = sweep(session, pass, bucket,
                    visited == 0 ? from.cycleStartRecord() : "", bound - examined);
            examined = examined + progress.examined();
            if (!progress.complete()) {
                next = visited > 0 && index < start ? SweepCursor.FIRST : bucket;
                nextRecord = progress.nextRecord();
                break;
            }
            if (examined >= bound) {
                next = visited > 0 && index < start ? SweepCursor.FIRST
                        : index + 1 < present.size() ? present.get(index + 1) : SweepCursor.BUCKETS;
                break;
            }
        }
        if (examined > 0) {
            session.save();
        }
        SweepCursor.advance(session, from, next, nextRecord, nowUnixMilliseconds);
        return new SweepReport(from.bucket(),
                next >= SweepCursor.BUCKETS ? SweepCursor.FIRST : next, examined, pass.removed(),
                pass.collected(), pass.released());
    }

    /**
     * Every bucket this generation actually has, in the order their numbers run.
     *
     * <p>Walking the buckets that exist rather than every number a bucket could have is what makes
     * a pass over a mostly empty store cheap. The order is the numbers' own, so a cursor into it
     * means the same thing whichever pass reads it.</p>
     *
     * @param session the session to read under
     * @param generation the incarnation being swept
     * @return the bucket numbers, ascending
     * @throws RepositoryException if the repository fails
     */
    private static List<Long> buckets(Session session, EventStoreGeneration generation)
            throws RepositoryException {
        final StatePath root = StatePath.deployment(StatePath.OPERATIONS)
                .child("g" + generation.number());
        final List<Long> held = new ArrayList<>();
        if (!session.nodeExists(root.path())) {
            return held;
        }
        final NodeIterator first = session.getNode(root.path()).getNodes();
        while (first.hasNext()) {
            final Node level = first.nextNode();
            final NodeIterator second = level.getNodes();
            while (second.hasNext()) {
                numbered(level.getName(), second.nextNode().getName()).ifPresent(held::add);
            }
        }
        java.util.Collections.sort(held);
        return held;
    }

    private static java.util.Optional<Long> numbered(String first, String second) {
        try {
            return java.util.Optional.of(Long.parseLong(first + second, HEXADECIMAL));
        } catch (final NumberFormatException outside) {
            // A name that is not a bucket is something nothing here wrote, and a sweep that walked
            // into it would be a sweep deciding about somebody else's tree.
            return java.util.Optional.empty();
        }
    }

    private record SweepProgress(long examined, boolean complete, String nextRecord) {
    }

    private static SweepProgress sweep(Session session, Pass pass, long bucket, String startRecord,
                                       long bound)
            throws RepositoryException {
        final List<String> segments = SweepCursor.segments(bucket);
        final StatePath path = StatePath.deployment(StatePath.OPERATIONS)
                .child("g" + pass.generation().number())
                .child(segments.get(0))
                .child(segments.get(1));
        if (!session.nodeExists(path.path())) {
            return new SweepProgress(0, true, "");
        }
        long examined = 0;
        String cursor = startRecord;
        while (examined < bound) {
            final Node record = nextRecord(session, path, cursor);
            if (record == null) {
                return new SweepProgress(examined, true, "");
            }
            cursor = record.getName();
            examined = examined + 1;
            examine(session, pass, path.child(cursor));
        }
        return new SweepProgress(examined, nextRecord(session, path, cursor) == null, cursor);
    }

    private static Node nextRecord(Session session, StatePath bucket, String after)
            throws RepositoryException {
        if (after.isEmpty()) {
            final NodeIterator records = session.getNode(bucket.path()).getNodes();
            return records.hasNext() ? records.nextNode() : null;
        }
        final NodeIterator records = session.getNode(bucket.path()).getNodes();
        Node successor = null;
        while (records.hasNext()) {
            final Node candidate = records.nextNode();
            if (candidate.getName().compareTo(after) > 0) {
                if (successor == null || candidate.getName().compareTo(successor.getName()) < 0) {
                    successor = candidate;
                }
            }
        }
        return successor;
    }

    private static void examine(Session session, Pass pass, StatePath record)
            throws RepositoryException {
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            if (examineAttempt(session, pass, record)) {
                return;
            }
            attempt = attempt + 1;
        }
        throw new RepositoryException("retention cleanup remained contended");
    }

    private static boolean examineAttempt(Session session, Pass pass, StatePath record)
            throws RepositoryException {
        session.refresh(false);
        try {
            if (!session.nodeExists(record.path())) {
                return true;
            }
            CompareAndSet.stamp(session.getNode(StatePath.deployment(StatePath.CAPACITY).path()));
            CompareAndSet.stamp(session.getNode(record.path()));
            final Pass staged = new Pass(pass.generation(), pass.nowUnixMilliseconds(), pass.contract());
            examineStaged(session, staged, record);
            if (staged.removed() == 0 && staged.collected() == 0) {
                return true;
            }
            session.save();
            pass.include(staged);
            return true;
        } catch (final InvalidItemStateException contended) {
            return false;
        } finally {
            session.refresh(false);
        }
    }

    private static void examineStaged(Session session, Pass pass, StatePath record)
            throws RepositoryException {
        final RetentionPolicy.Outcome until = RetentionPolicy.until(session, record,
                RetentionPolicy.Kind.OPERATION_DETAIL, pass.contract());
        if (until instanceof RetentionPolicy.Refused) {
            return;
        }
        if (((RetentionPolicy.Held) until).retainedUntil().hasPassed(pass.nowUnixMilliseconds())) {
            remove(session, pass, record);
            return;
        }
        collect(session, pass, record);
    }

    private static void remove(Session session, Pass pass, StatePath record)
            throws RepositoryException {
        final Node held = session.getNode(record.path());
        final StatePath.Outcome caller = StatePath.caller(held.hasProperty(CALLER)
                ? held.getProperty(CALLER).getString() : "");
        release(session, pass, held, caller);
        pass.removedOne();
        held.remove();
    }

    private static void release(Session session, Pass pass, Node held,
                                StatePath.Outcome caller) throws RepositoryException {
        if (!(caller instanceof final StatePath.Held named)) {
            throw new RepositoryException("retention cannot retire data with no valid caller");
        }
        releaseEach(session, pass, held, named.caller(),
                new Counted(ArtifactStore.NODE, AccountedQuantity.ARTIFACT_ROWS,
                        AccountedQuantity.ARTIFACT_BYTES, ArtifactStore.BYTE_COUNT));
        releaseEach(session, pass, held, named.caller(),
                new Counted(EventLedger.NODE, AccountedQuantity.EVENT_ROWS,
                        AccountedQuantity.EVENT_BYTES, EventLedger.BYTES));
        releaseEach(session, pass, held, named.caller(),
                new Counted(INTAKE, AccountedQuantity.OPERATION_RESERVATION_ROWS,
                        AccountedQuantity.OPERATION_RESERVATION_BYTES, "declared_byte_count"));
        releaseTerminalBudget(session, pass, held, named.caller());
        releaseCompletion(session, pass, held, named.caller());
    }

    private static void releaseCompletion(Session session, Pass pass, Node operation,
                                          StatePath.Caller caller) throws RepositoryException {
        if (!operation.hasNode(COMPLETION)
                || !operation.getNode(COMPLETION).hasProperty(CapacityReservation.RESOURCE_RESERVATION)) {
            return;
        }
        final Node completion = operation.getNode(COMPLETION);
        final CapacityReservation reservation = CapacityReservation.ofResource(session, completion)
                .orElseThrow(() -> new RepositoryException("completion has no retained capacity owner"));
        CapacityLedger.stageResourceRelease(session, completion, caller,
                new CapacityLedger.ResourceCharge(AccountedQuantity.RESULT_ROWS,
                        AccountedQuantity.RESULT_BYTES, RESULT_SLOT));
        for (final CapacityReservation.Charge charge : reservation.charges()) {
            if (charge.quantity() == AccountedQuantity.RESULT_BYTES
                    || charge.quantity() == AccountedQuantity.SNAPSHOT_BYTES) {
                pass.releasedSome(charge.amount());
            }
        }
    }

    private static void releaseTerminalBudget(Session session, Pass pass, Node operation,
                                               StatePath.Caller caller) throws RepositoryException {
        if (!operation.hasNode(EventLedger.TERMINAL_BUDGET)) {
            return;
        }
        final Node budget = operation.getNode(EventLedger.TERMINAL_BUDGET);
        final CapacityReservation reservation = CapacityReservation.ofResource(session, budget)
                .orElseThrow(() -> new RepositoryException("terminal budget has no retained capacity owner"));
        CapacityLedger.stageResourceRelease(session, budget, caller,
                new CapacityLedger.ResourceCharge(AccountedQuantity.EVENT_ROWS,
                        AccountedQuantity.EVENT_BYTES, EventLedger.BYTES));
        for (final CapacityReservation.Charge charge : reservation.charges()) {
            if (charge.quantity() == AccountedQuantity.EVENT_BYTES) {
                pass.releasedSome(charge.amount());
            }
        }
    }

    /**
     * One kind of thing an operation holds, and how the store counts it.
     *
     * @param child the child of an operation they live under
     * @param rows the quantity one of them costs a row of
     * @param bytes the quantity their bytes come out of
     * @param property the property each of them writes its own size in
     */
    private record Counted(String child, AccountedQuantity rows, AccountedQuantity bytes,
                           String property) {
    }

    private static void releaseEach(Session session, Pass pass, Node held,
                                    StatePath.Caller caller, Counted counted)
            throws RepositoryException {
        if (!held.hasNode(counted.child())) {
            return;
        }
        final NodeIterator children = held.getNode(counted.child()).getNodes();
        while (children.hasNext()) {
            final Node one = children.nextNode();
            final long size = one.hasProperty(counted.property())
                    ? one.getProperty(counted.property()).getLong()
                    : 0;
            CapacityLedger.stageResourceRelease(session, one, caller,
                    new CapacityLedger.ResourceCharge(counted.rows(), counted.bytes(), counted.property()));
            pass.releasedSome(size);
        }
    }

    private static void collect(Session session, Pass pass, StatePath record)
            throws RepositoryException {
        final Node operation = session.getNode(record.path());
        if (!operation.hasNode(ArtifactStore.NODE)
                || leaseIsLive(operation, pass.nowUnixMilliseconds())) {
            return;
        }
        final long lease = pass.contract().value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        final NodeIterator artifacts = operation.getNode(ArtifactStore.NODE).getNodes();
        final List<String> slots = new ArrayList<>();
        while (artifacts.hasNext()) {
            slots.add(artifacts.nextNode().getName());
        }
        for (final String slot : slots) {
            collectable(session, pass, operation, new Candidate(slot, lease));
        }
    }

    /**
     * One artifact a pass is deciding about.
     *
     * @param slot which slot it is in
     * @param leaseMilliseconds how long a worker's lease lasts
     */
    private record Candidate(String slot, long leaseMilliseconds) {
    }

    private static void collectable(Session session, Pass pass, Node operation,
                                    Candidate candidate) throws RepositoryException {
        final String slot = candidate.slot();
        if (referenced(operation, slot) || declared(operation, slot)) {
            return;
        }
        final Node artifact = operation.getNode(ArtifactStore.NODE).getNode(slot);
        final long published = artifact.hasProperty(ArtifactStore.PUBLISHED_AT)
                ? artifact.getProperty(ArtifactStore.PUBLISHED_AT).getLong()
                : 0;
        if (pass.nowUnixMilliseconds() < published + candidate.leaseMilliseconds()) {
            return;
        }
        final long size = artifact.hasProperty(ArtifactStore.BYTE_COUNT)
                ? artifact.getProperty(ArtifactStore.BYTE_COUNT).getLong() : 0;
        final StatePath.Outcome caller = StatePath.caller(operation.hasProperty(CALLER)
                ? operation.getProperty(CALLER).getString() : "");
        if (!(caller instanceof final StatePath.Held named)) {
            throw new RepositoryException("retention cannot collect data with no valid caller");
        }
        CapacityLedger.stageResourceRelease(session, artifact, named.caller(),
                new CapacityLedger.ResourceCharge(AccountedQuantity.ARTIFACT_ROWS,
                        AccountedQuantity.ARTIFACT_BYTES, ArtifactStore.BYTE_COUNT));
        pass.collectedOne(size);
        artifact.remove();
    }

    private static boolean referenced(Node operation, String slot) throws RepositoryException {
        return operation.hasProperty(RESULT_SLOT)
                && operation.getProperty(RESULT_SLOT).getString().equals(slot)
                || operation.hasNode(COMPLETION) && operation.getNode(COMPLETION).hasProperty(RESULT_SLOT)
                && operation.getNode(COMPLETION).getProperty(RESULT_SLOT).getString().equals(slot);
    }

    private static boolean declared(Node operation, String slot) throws RepositoryException {
        return operation.hasNode(INTAKE) && operation.getNode(INTAKE).hasNode(slot);
    }

    private static boolean leaseIsLive(Node operation, long nowUnixMilliseconds)
            throws RepositoryException {
        if (!operation.hasNode(LEASE)) {
            return false;
        }
        final Node lease = operation.getNode(LEASE);
        return lease.hasProperty(LEASE_HELD_UNTIL)
                && lease.getProperty(LEASE_HELD_UNTIL).getLong() > nowUnixMilliseconds;
    }

    /**
     * What one pass is doing and what it has done so far.
     *
     * <p>Held for the length of one pass rather than between passes: everything that survives a
     * pass is in the store or in the cursor.</p>
     */
    private static final class Pass {

        private final EventStoreGeneration generation;

        private final long nowUnixMilliseconds;

        private final AgentContract contract;

        private final java.util.concurrent.atomic.AtomicLong removed =
                new java.util.concurrent.atomic.AtomicLong();

        private final java.util.concurrent.atomic.AtomicLong collected =
                new java.util.concurrent.atomic.AtomicLong();

        private final java.util.concurrent.atomic.AtomicLong released =
                new java.util.concurrent.atomic.AtomicLong();

        Pass(EventStoreGeneration generation, long nowUnixMilliseconds, AgentContract contract) {
            this.generation = generation;
            this.nowUnixMilliseconds = nowUnixMilliseconds;
            this.contract = contract;
        }

        EventStoreGeneration generation() {
            return generation;
        }

        long nowUnixMilliseconds() {
            return nowUnixMilliseconds;
        }

        AgentContract contract() {
            return contract;
        }

        void include(Pass committed) {
            removed.addAndGet(committed.removed());
            collected.addAndGet(committed.collected());
            released.addAndGet(committed.released());
        }

        void removedOne() {
            removed.incrementAndGet();
        }

        void releasedSome(long bytes) {
            released.addAndGet(bytes);
        }

        void collectedOne(long bytes) {
            collected.incrementAndGet();
            released.addAndGet(bytes);
        }

        long removed() {
            return removed.get();
        }

        long collected() {
            return collected.get();
        }

        long released() {
            return released.get();
        }
    }
}
