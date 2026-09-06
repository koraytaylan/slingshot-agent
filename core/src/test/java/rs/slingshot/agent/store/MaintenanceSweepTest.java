// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.ExecutionFence;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.TerminalCommit;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A pass that stops where it said it would, resumes where it stopped, and takes only what is safe.
 *
 * <p>The three artifacts that are left alone are the point of the collection tests: bytes a worker
 * has just written and not yet named look exactly like garbage, and a sweep that cannot tell the
 * difference is the thing that breaks an answer. So the test drives each protection separately —
 * a live lease, an artifact younger than a lease, a slot a manifest still declares — and then
 * proves the same artifact is collected once none of them holds.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class MaintenanceSweepTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/maintenance-sweep");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> OPERATIONS =
            List.of("operation-0.json", "operation-1.json", "operation-2.json");

    private static final long REQUEST_START = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true", "3,false", "3,true",
        "4,false", "4,true", "5,false", "5,true"})
    void interruptedCollectionKeepsArtifactAccountingExact(int boundary, boolean committed)
            throws RepositoryException, LoginException {
        interruptedCleanup(REQUEST_START + CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS),
                boundary, committed);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true", "3,false", "3,true",
        "4,false", "4,true", "5,false", "5,true"})
    void interruptedOperationRemovalKeepsArtifactAccountingExact(int boundary, boolean committed)
            throws RepositoryException, LoginException {
        interruptedCleanup(past(), boundary, committed);
    }

    private void interruptedCleanup(long now, int boundary, boolean committed)
            throws RepositoryException, LoginException {
        final Session session = recorded();
        assertThrows(RepositoryException.class,
                () -> MaintenanceSweep.run(SaveInterleaving.interruptSave(session, boundary, committed),
                        generation(), now, CONTRACT));
        try (ResourceResolver resolver = Objects.requireNonNull(
                sling.getService(ResourceResolverFactory.class)).getResourceResolver(Map.of())) {
            final Session observer = Objects.requireNonNull(resolver.adaptTo(Session.class));
            artifactCountsMatchData(observer);
            MaintenanceSweep.run(observer, generation(), now, CONTRACT);
            MaintenanceSweep.run(observer, generation(), now, CONTRACT);
            artifactCountsMatchData(observer);
            assertEquals(0, CapacityLedger.held(observer, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        }
    }

    @Test
    void overlappingSweepsReleaseEveryResourceOnce() throws RepositoryException, LoginException {
        final Session session = recorded();
        try (ResourceResolver resolver = Objects.requireNonNull(
                sling.getService(ResourceResolverFactory.class)).getResourceResolver(Map.of())) {
            final Session competing = Objects.requireNonNull(resolver.adaptTo(Session.class));
            final SweepReport report = MaintenanceSweep.run(SaveInterleaving.before(session,
                    () -> MaintenanceSweep.run(competing, generation(), past(), CONTRACT)), generation(),
                    past(), CONTRACT);
            assertEquals(0, report.recordsRemoved());
            assertEquals(0, report.bytesReleased());
            artifactCountsMatchData(competing);
            assertEquals(0, CapacityLedger.held(competing, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        }
    }

    private static void artifactCountsMatchData(Session session) throws RepositoryException {
        artifactCountsMatchData(session, OPERATIONS);
    }

    private static void artifactCountsMatchData(Session session, List<String> operations)
            throws RepositoryException {
        session.refresh(false);
        long rows = 0;
        long bytes = 0;
        for (final String fixture : operations) {
            if (session.nodeExists(slotOf(fixture).path())) {
                rows = rows + 1;
                bytes = bytes + session.getNode(slotOf(fixture).path())
                        .getProperty(ArtifactStore.BYTE_COUNT).getLong();
            }
        }
        assertEquals(rows, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        assertEquals(rows, CapacityLedger.heldBy(session, AccountedQuantity.ARTIFACT_ROWS,
                caller(), CONTRACT));
        assertEquals(bytes, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT));
        assertEquals(bytes, CapacityLedger.heldBy(session, AccountedQuantity.ARTIFACT_BYTES,
                caller(), CONTRACT));
    }

    @Test
    void removingAnOperationRetiresItsUnfinishedIntakeVector() throws RepositoryException {
        final Session session = recorded();
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, caller());
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, caller());
        final List<CapacityReservation.Charge> charges = List.of(
                new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_BYTES, 17),
                new CapacityReservation.Charge(AccountedQuantity.OPERATION_RESERVATION_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.OPERATION_RESERVATION_BYTES, 17));
        final CapacityReservation reservation = assertInstanceOf(CapacityLedger.Reserved.class,
                CapacityLedger.take(session, caller(), charges, CONTRACT)).reservation();
        final Node declaration = session.getNode(operation(OPERATIONS.getFirst()).path())
                .addNode(MaintenanceSweep.INTAKE, "nt:unstructured").addNode("incoming", "nt:unstructured");
        declaration.setProperty("declared_byte_count", 17);
        CapacityReservation.retain(session, reservation, declaration);
        session.save();
        MaintenanceSweep.run(session, generation(), past(), CONTRACT);
        assertFalse(session.nodeExists(reservation.path().path()));
        for (final CapacityReservation.Charge charge : charges) {
            assertEquals(0, CapacityLedger.held(session, charge.quantity(), CONTRACT));
            assertEquals(0, CapacityLedger.heldBy(session, charge.quantity(), caller(), CONTRACT));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMissingCallerCannotDeleteChargedData(boolean expired) throws RepositoryException {
        final Session session = recorded();
        session.getNode(operation(OPERATIONS.getFirst()).path())
                .getProperty(MaintenanceSweep.CALLER).remove();
        session.save();
        final long now = expired ? past()
                : REQUEST_START + CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        assertThrows(RepositoryException.class,
                () -> MaintenanceSweep.run(session, generation(), now, CONTRACT));
        assertTrue(session.nodeExists(slotOf(OPERATIONS.getFirst()).path()));
        artifactCountsMatchData(session);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedCleanupContentionPreservesDataAndCounters(boolean expired) throws RepositoryException {
        final Session session = recorded();
        final var attempts = new java.util.concurrent.atomic.AtomicInteger();
        final Session contended = SaveInterleaving.beforeEverySave(session, () -> {
            attempts.incrementAndGet();
            throw new javax.jcr.InvalidItemStateException("cleanup lost to another writer");
        });
        final long now = expired ? past()
                : REQUEST_START + CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        assertThrows(RepositoryException.class,
                () -> MaintenanceSweep.run(contended, generation(), now, CONTRACT));
        assertEquals(CompareAndSet.ATTEMPTS, attempts.get());
        assertFalse(session.hasPendingChanges());
        artifactCountsMatchData(session);
        assertEquals(OPERATIONS.size(), CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS,
                CONTRACT));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eventAndArtifactRetirementShareTheOperationCommit(boolean committed)
            throws RepositoryException, LoginException {
        final Session session = recorded();
        for (final String fixture : OPERATIONS) {
            final LedgerAdmission.Admitted admission = assertInstanceOf(LedgerAdmission.Admitted.class,
                    LedgerAdmission.admit(session, caller(), 7, CONTRACT));
            final Node operation = session.getNode(operation(fixture).path());
            final Node ledger = operation.hasNode(EventLedger.NODE) ? operation.getNode(EventLedger.NODE)
                    : operation.addNode(EventLedger.NODE, "nt:unstructured");
            final Node event = ledger.addNode("1", "nt:unstructured");
            event.setProperty(EventLedger.BYTES, 7);
            CapacityReservation.retain(session, admission.reservation(), event);
            session.save();
        }
        assertThrows(RepositoryException.class, () -> MaintenanceSweep.run(
                SaveInterleaving.interruptSave(session, 1, committed), generation(), past(), CONTRACT));
        try (ResourceResolver resolver = Objects.requireNonNull(
                sling.getService(ResourceResolverFactory.class)).getResourceResolver(Map.of())) {
            final Session observer = Objects.requireNonNull(resolver.adaptTo(Session.class));
            eventCountsMatchData(observer);
            artifactCountsMatchData(observer);
            MaintenanceSweep.run(observer, generation(), past(), CONTRACT);
            MaintenanceSweep.run(observer, generation(), past(), CONTRACT);
            eventCountsMatchData(observer);
            assertEquals(0, CapacityLedger.held(observer, AccountedQuantity.EVENT_ROWS, CONTRACT));
        }
    }

    private static void eventCountsMatchData(Session session) throws RepositoryException {
        session.refresh(false);
        long rows = 0;
        long bytes = 0;
        for (final String fixture : OPERATIONS) {
            if (session.nodeExists(operation(fixture).child(EventLedger.NODE).child("1").path())) {
                rows = rows + 1;
                bytes = bytes + session.getNode(operation(fixture).child(EventLedger.NODE).child("1").path())
                        .getProperty(EventLedger.BYTES).getLong();
            }
        }
        assertEquals(rows, CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT));
        assertEquals(rows, CapacityLedger.heldBy(session, AccountedQuantity.EVENT_ROWS, caller(), CONTRACT));
        assertEquals(bytes, CapacityLedger.held(session, AccountedQuantity.EVENT_BYTES, CONTRACT));
        assertEquals(bytes, CapacityLedger.heldBy(session, AccountedQuantity.EVENT_BYTES,
                caller(), CONTRACT));
    }

    @Test
    @DisplayName("a sweep with nothing to do removes nothing and ends at the start again")
    void asweepWithNothingToDoRemovesNothing() throws RepositoryException {
        final Session session = recorded();
        final SweepReport report = MaintenanceSweep.run(session, generation(), REQUEST_START,
                CONTRACT);
        assertEquals(0, report.recordsRemoved(), "a sweep removed something inside its retention");
        assertEquals(0, report.artifactsCollected());
        assertEquals(OPERATIONS.size(), report.examined(),
                "a sweep did not look at every record there is");
        assertEquals(SweepCursor.FIRST, report.to(),
                "a sweep that reached the end did not come back to the beginning");
        for (final String operation : OPERATIONS) {
            assertTrue(session.nodeExists(operation(operation).path()),
                    operation + " was removed inside its own retention");
        }
    }

    @Test
    @DisplayName("a sweep past retention removes the record and gives back exactly what it held")
    void asweepPastRetentionGivesBackWhatItHeld() throws RepositoryException {
        final Session session = recorded();
        final long held = CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT);
        assertTrue(held > 0, "the suite wrote no artifact bytes, so it releases nothing");
        final SweepReport report = MaintenanceSweep.run(session, generation(), past(), CONTRACT);
        assertEquals(OPERATIONS.size(), report.recordsRemoved(),
                "a sweep past every retention left records behind");
        assertEquals(held, report.bytesReleased(),
                "what the sweep gave back is not what the store was holding");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT),
                "the counted bytes are not the bytes the store now holds");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT),
                "the counted events are not the events the store now holds");
        for (final String operation : OPERATIONS) {
            assertFalse(session.nodeExists(operation(operation).path()),
                    operation + " outlived its own retention");
        }
    }

    @Test
    void cursorReportsContentionWhenItsInitialClaimCannotCommit() throws RepositoryException {
        final Session session = prepared();
        final SweepCursor initial = SweepCursor.read(session);
        assertEquals(WriteOutcome.CONTENDED,
                SweepCursor.advance(SaveInterleaving.beforeEverySave(session, () -> {
                    throw new javax.jcr.InvalidItemStateException("the cursor claim lost its race");
                }), initial, 0, "first", REQUEST_START));
        assertEquals(initial, SweepCursor.read(session));
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void cursorRejectsStaleProgressInsideTheSameBucket() throws RepositoryException {
        final Session session = prepared();
        final SweepCursor initial = SweepCursor.read(session);
        assertEquals(WriteOutcome.WRITTEN,
                SweepCursor.advance(session, initial, 0, "first", REQUEST_START));
        final SweepCursor first = SweepCursor.read(session);
        assertEquals("first", first.cycleStartRecord());
        assertEquals(WriteOutcome.WRITTEN,
                SweepCursor.advance(session, first, 0, "second", REQUEST_START));
        assertEquals(WriteOutcome.VALUE_CHANGED,
                SweepCursor.advance(session, first, 0, "stale", REQUEST_START));
        assertEquals("second", SweepCursor.read(session).cycleStartRecord());
    }

    @Test
    void cursorVersionRejectsAnIdenticalPositionFromAnEarlierPass() throws RepositoryException {
        final Session session = prepared();
        final SweepCursor initial = SweepCursor.read(session);
        assertEquals(WriteOutcome.WRITTEN,
                SweepCursor.advance(session, initial, 0, "same", REQUEST_START));
        final SweepCursor first = SweepCursor.read(session);
        assertEquals(WriteOutcome.WRITTEN,
                SweepCursor.advance(session, first, 0, "same", REQUEST_START));
        assertEquals(WriteOutcome.VALUE_CHANGED,
                SweepCursor.advance(session, first, 0, "stale", REQUEST_START));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cursorInterruptionKeepsPositionAndTimestampTogether(boolean committed)
            throws RepositoryException {
        final Session session = prepared();
        ClaimByCreation.claim(session, StatePath.deployment(SweepCursor.NODE),
                "nt:unstructured", node -> { });
        final SweepCursor initial = SweepCursor.read(session);
        assertThrows(RepositoryException.class,
                () -> SweepCursor.advance(SaveInterleaving.interruptSave(session, 1, committed),
                        initial, 1, "first", REQUEST_START));
        final SweepCursor held = SweepCursor.read(session);
        assertEquals(committed ? 1 : 0, held.bucket());
        assertEquals(committed ? "first" : "", held.cycleStartRecord());
        assertEquals(committed ? REQUEST_START : 0, held.advancedAtUnixMilliseconds());
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void iteratorObserverCountsSkippedRecords() throws RepositoryException {
        final Session session = recorded(List.of("dense-0.json", "dense-1.json", "dense-2.json"));
        final String path = operation("dense-0.json").path();
        final String bucket = path.substring(0, path.lastIndexOf('/'));
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        final var records = SweepReads.count(session, bucket, reads).getNode(bucket).getNodes();
        records.skip(1);
        records.nextNode();
        assertEquals(2, reads.get(), "iterator skipping hid a repository read");
    }

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true", "3,false", "3,true",
        "4,false", "4,true", "5,false", "5,true"})
    void interruptedDenseRotationPreservesProgressAndAccounting(int boundary, boolean committed)
            throws RepositoryException, LoginException {
        final List<String> dense = List.of("dense-0.json", "dense-1.json", "dense-2.json");
        final Session session = retainedFirst(dense);
        assertThrows(RepositoryException.class,
                () -> MaintenanceSweep.run(SaveInterleaving.interruptSave(session, boundary, committed),
                        generation(), past(), CONTRACT));
        try (ResourceResolver resolver = Objects.requireNonNull(
                sling.getService(ResourceResolverFactory.class)).getResourceResolver(Map.of())) {
            final Session observer = Objects.requireNonNull(resolver.adaptTo(Session.class));
            artifactCountsMatchData(observer, dense);
            MaintenanceSweep.run(observer, generation(), past(), CONTRACT);
            MaintenanceSweep.run(observer, generation(), past(), CONTRACT);
            artifactCountsMatchData(observer, dense);
            assertEquals(1, CapacityLedger.held(observer, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
            assertTrue(observer.nodeExists(operation(dense.get(0)).path()));
            final Node bucket = observer.getNode(operation(dense.get(0)).path()).getParent();
            final var children = bucket.getNodes();
            assertEquals(observer.getNode(operation(dense.get(0)).path()).getName(),
                    children.nextNode().getName());
            assertFalse(children.hasNext(), "a temporary ordering marker survived the commit");
        }
    }

    @Test
    void overlappingDenseRotationsDoNotSkipEligibleRecords() throws RepositoryException, LoginException {
        final List<String> dense = List.of("dense-0.json", "dense-1.json", "dense-2.json");
        final Session session = retainedFirst(dense);
        ClaimByCreation.claim(session, StatePath.deployment(SweepCursor.NODE),
                "nt:unstructured", node -> { });
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        try (ResourceResolver resolver = Objects.requireNonNull(
                sling.getService(ResourceResolverFactory.class)).getResourceResolver(Map.of())) {
            final Session competing = Objects.requireNonNull(resolver.adaptTo(Session.class));
            final SweepReport lost = MaintenanceSweep.run(SaveInterleaving.before(session,
                    () -> MaintenanceSweep.run(competing, generation(), past(), bounded)),
                    generation(), past(), bounded);
            assertEquals(0, lost.recordsRemoved());
            assertEquals(0, lost.bytesReleased());
            for (final String position : dense) {
                final SweepReport next = MaintenanceSweep.run(competing, generation(), past(), bounded);
                assertTrue(next.examined() <= 1, position + " exceeded its work bound");
                artifactCountsMatchData(competing, dense);
            }
            assertEquals(1, CapacityLedger.held(competing, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        }
    }

    @Test
    void retainedDenseCycleWrapsAndLaterCollectsEveryRecord() throws RepositoryException {
        final List<String> dense = List.of("dense-0.json", "dense-1.json", "dense-2.json");
        final Session session = recorded(dense);
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        for (final String position : dense) {
            final SweepReport report = MaintenanceSweep.run(session, generation(), REQUEST_START, bounded);
            assertEquals(1, report.examined(), position + " did not consume exactly one record");
            assertEquals(0, report.recordsRemoved());
            artifactCountsMatchData(session, dense);
        }
        final SweepReport wrapped = MaintenanceSweep.run(session, generation(), REQUEST_START, bounded);
        assertEquals(1, wrapped.examined());
        assertEquals(SweepCursor.FIRST, SweepCursor.read(session).bucket());
        for (final String position : dense) {
            final SweepReport report = MaintenanceSweep.run(session, generation(), past(), bounded);
            assertEquals(1, report.recordsRemoved(), position + " was missed after retention expired");
            artifactCountsMatchData(session, dense);
        }
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
    }

    @Test
    void contentionCannotSpendTheRecordBudgetAgain() throws RepositoryException {
        final List<String> dense = List.of("dense-0.json", "dense-1.json", "dense-2.json");
        final Session session = recorded(dense);
        ClaimByCreation.claim(session, StatePath.deployment(SweepCursor.NODE),
                "nt:unstructured", node -> { });
        final String path = operation(dense.get(0)).path();
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        final Session observed = SweepReads.count(session, path.substring(0, path.lastIndexOf('/')), reads);
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        assertThrows(RepositoryException.class,
                () -> MaintenanceSweep.run(SaveInterleaving.beforeEverySave(observed, () -> {
                    throw new javax.jcr.InvalidItemStateException("the cleanup commit lost its race");
                }), generation(), past(), bounded));
        assertEquals(1, reads.get(), "fresh retries exceeded the per-pass record budget");
        assertFalse(session.hasPendingChanges());
        artifactCountsMatchData(session, dense);
        assertEquals(dense.size(), CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
    }

    private Session retainedFirst(List<String> dense) throws RepositoryException {
        final Session session = recorded(dense);
        session.getNode(operation(dense.get(0)).path()).setProperty(RetentionPolicy.REQUEST_START, past());
        session.save();
        ExecutionFence.take(session, identity(dense.get(0)), "a-worker", past(), CONTRACT);
        return session;
    }

    @Test
    void denseBucketRespectsExaminedBound() throws RepositoryException {
        final Session session = recorded(List.of("dense-0.json", "dense-1.json", "dense-2.json"));
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final SweepReport report = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(1, report.examined(), "one dense bucket exceeded the work bound");
        assertEquals(1, report.recordsRemoved());
    }

    @Test
    void denseBucketRespectsIteratorBound() throws RepositoryException {
        final Session session = recorded(List.of("dense-0.json", "dense-1.json", "dense-2.json"));
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final String path = operation("dense-0.json").path();
        final var reads = new java.util.concurrent.atomic.AtomicInteger();
        MaintenanceSweep.run(SweepReads.count(session, path.substring(0, path.lastIndexOf('/')), reads),
                generation(), past(), bounded);
        assertTrue(reads.get() <= 1, "a bound-one pass advanced " + reads.get() + " operation nodes");
    }

    @Test
    void denseBucketResumesBeyondRetainedFirstRecord() throws RepositoryException {
        final List<String> dense = List.of("dense-0.json", "dense-1.json", "dense-2.json");
        final Session session = retainedFirst(dense);
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final String path = operation(dense.get(0)).path();
        long removed = 0;
        for (final String position : dense) {
            final var reads = new java.util.concurrent.atomic.AtomicInteger();
            final SweepReport report = MaintenanceSweep.run(SweepReads.count(session,
                    path.substring(0, path.lastIndexOf('/')), reads), generation(), past(), bounded);
            assertTrue(reads.get() <= 1, position + " resumption reread " + reads.get() + " operation nodes");
            assertTrue(report.examined() <= 1, "resumption exceeded its examined-record bound");
            removed = removed + report.recordsRemoved();
        }
        assertEquals(dense.size() - 1, removed, "retained data prevented progress to later records");
        assertTrue(session.nodeExists(operation(dense.get(0)).path()));
        for (final String fixture : dense.subList(1, dense.size())) {
            assertFalse(session.nodeExists(operation(fixture).path()));
        }
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        assertEquals(1, CapacityLedger.heldBy(session, AccountedQuantity.ARTIFACT_ROWS, caller(), CONTRACT));
    }

    @Test
    @DisplayName("a bounded pass stops where it said and a resumed one covers exactly the rest")
    void aboundedPassResumesWithNoGapAndNoOverlap() throws RepositoryException {
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final Session session = recorded();
        final SweepReport first = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(1, first.examined(), "a bounded pass looked at more than its bound");
        assertEquals(1, first.recordsRemoved());
        assertTrue(first.to() > first.from(), "a bounded pass did not advance its cursor");
        assertEquals(first.to(), SweepCursor.read(session).bucket(),
                "where the pass stopped is not where the store says it stopped");
        final SweepReport second = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(first.to(), second.from(),
                "a resumed pass started somewhere other than where the last one stopped");
        assertEquals(1, second.recordsRemoved(), "a resumed pass covered the wrong region");
        final SweepReport third = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(1, third.recordsRemoved(), "the last record was never reached");
        assertEquals(SweepCursor.FIRST, third.to(),
                "a pass that reached the end did not come back to the beginning");
        for (final String operation : OPERATIONS) {
            assertFalse(session.nodeExists(operation(operation).path()),
                    operation + " was never swept, so the passes had a gap in them");
        }
    }

    @Test
    @DisplayName("an artifact is left alone while a lease is live, while it is young, and while declared")
    void anartifactIsLeftAloneWhileAnythingHoldsIt() throws RepositoryException {
        final Session session = recorded();
        final long lease = CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        final long young = REQUEST_START + lease - 1;
        assertEquals(0, MaintenanceSweep.run(session, generation(), young, CONTRACT)
                        .artifactsCollected(),
                "an artifact younger than a lease was collected");
        ExecutionFence.take(session, identity(OPERATIONS.get(0)), "a-worker",
                REQUEST_START + lease, CONTRACT);
        assertEquals(2, MaintenanceSweep.run(session, generation(), REQUEST_START + lease,
                        CONTRACT).artifactsCollected(),
                "an artifact under a live lease was collected, or one under none was not");
        assertTrue(session.nodeExists(slotOf(OPERATIONS.get(0)).path()),
                "the artifact under a live lease is gone");
        declare(session, OPERATIONS.get(0));
        assertEquals(0, MaintenanceSweep.run(session, generation(),
                        REQUEST_START + lease + lease, CONTRACT).artifactsCollected(),
                "an artifact whose slot a manifest still declares was collected");
        session.getNode(operation(OPERATIONS.get(0)).child(MaintenanceSweep.INTAKE).path())
                .remove();
        session.save();
        assertEquals(1, MaintenanceSweep.run(session, generation(),
                        REQUEST_START + lease + lease, CONTRACT).artifactsCollected(),
                "an artifact nothing holds any more was not collected");
    }

    @Test
    @DisplayName("a referenced artifact is never collected, however the reference was written")
    void areferencedArtifactIsNeverCollected() throws RepositoryException {
        final Session session = recorded();
        final Node record = session.getNode(operation(OPERATIONS.get(0)).path());
        record.setProperty(MaintenanceSweep.RESULT_SLOT, "result");
        session.save();
        final long lease = CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        assertEquals(OPERATIONS.size() - 1, MaintenanceSweep.run(session, generation(),
                        REQUEST_START + lease, CONTRACT).artifactsCollected(),
                "a referenced artifact was collected");
        assertTrue(session.nodeExists(slotOf(OPERATIONS.get(0)).path()),
                "the artifact an answer names is gone, so that answer is broken forever");
        assertEquals(TerminalCommit.RESULT_SLOT, MaintenanceSweep.RESULT_SLOT,
                "an answer names its slot in one property and the sweep reads another");
        assertEquals(ExecutionFence.HELD_UNTIL, MaintenanceSweep.LEASE_HELD_UNTIL,
                "a lease is written in one property and the sweep reads another");
        assertEquals(ExecutionFence.NODE, MaintenanceSweep.LEASE,
                "a lease lives at one node and the sweep looks at another");
        assertEquals(OperationStore.CALLER, MaintenanceSweep.CALLER,
                "a caller is written in one property and the sweep reads another");
    }

    @Test
    @DisplayName("two passes over one store state produce byte-identical reports")
    void twopassesOverOneStateProduceIdenticalReports() throws RepositoryException {
        final Session session = recorded();
        final String first = MaintenanceSweep.run(session, generation(), REQUEST_START, CONTRACT)
                .rendered();
        SweepCursor.advance(session, SweepCursor.read(session), SweepCursor.FIRST, REQUEST_START);
        final String second = MaintenanceSweep.run(session, generation(), REQUEST_START + 1,
                CONTRACT).rendered();
        assertEquals(first, second,
                "two passes over one store state disagree, so a difference means nothing");
        assertTrue(first.contains("examined=" + OPERATIONS.size()), first);
    }

    @Test
    @DisplayName("a store the sweep has interrupted still says the same thing everywhere")
    void aninterruptedSweepLeavesTheStoreConsistent() throws RepositoryException {
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final Session session = recorded();
        MaintenanceSweep.run(session, generation(), past(), bounded);
        for (final String operation : OPERATIONS) {
            if (!session.nodeExists(operation(operation).path())) {
                continue;
            }
            assertInstanceOf(SnapshotStore.Agrees.class, SnapshotStore.verify(session,
                    operation(operation), SnapshotStore.NoRecord.NOTHING_HOLDS_THIS_OPERATION),
                    operation + " does not say the same thing everywhere after an interruption");
        }
    }

    @Test
    @DisplayName("a bucket is a number, a name that is not one is not a bucket, and the two agree")
    void abucketIsAnumber() {
        assertEquals(List.of("00", "00"), SweepCursor.segments(0));
        assertEquals(List.of("ff", "ff"), SweepCursor.segments(SweepCursor.BUCKETS - 1));
        assertEquals(List.of("3a", "6a"), SweepCursor.segments(0x3a6a));
        assertTrue(SweepCursor.holds(SweepCursor.FIRST));
        assertFalse(SweepCursor.holds(SweepCursor.BUCKETS));
        assertTrue(SweepCursor.at(SweepCursor.BUCKETS, REQUEST_START).isEmpty());
        assertEquals(0x3a6a, SweepCursor.at(0x3a6a, REQUEST_START).orElseThrow().bucket());
        assertEquals(REQUEST_START,
                SweepCursor.at(0, REQUEST_START).orElseThrow().advancedAtUnixMilliseconds());
        assertEquals(SweepCursor.at(0, REQUEST_START).orElseThrow(),
                SweepCursor.at(0, REQUEST_START).orElseThrow());
        assertEquals(SweepCursor.at(0, REQUEST_START).orElseThrow().hashCode(),
                SweepCursor.at(0, REQUEST_START).orElseThrow().hashCode());
        assertTrue(SweepCursor.at(0, REQUEST_START).orElseThrow().toString().contains("0"));
    }

    private void declare(Session session, String operation) throws RepositoryException {
        final Node record = session.getNode(operation(operation).path());
        final Node intake = record.hasNode(MaintenanceSweep.INTAKE)
                ? record.getNode(MaintenanceSweep.INTAKE)
                : record.addNode(MaintenanceSweep.INTAKE, "nt:unstructured");
        intake.addNode("result", "nt:unstructured");
        session.save();
    }

    private static StatePath slotOf(String operation) {
        return operation(operation).child(ArtifactStore.NODE).child("result");
    }

    private static long past() {
        return REQUEST_START + RetentionPolicy.Kind.OPERATION_DETAIL.minimum(CONTRACT);
    }

    private Session recorded() throws RepositoryException {
        return recorded(OPERATIONS);
    }

    private Session recorded(List<String> operations) throws RepositoryException {
        final Session session = prepared();
        for (final String operation : operations) {
            OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                    LogicalOperation.accepted(identity(operation),
                            Digest.of(operation.getBytes(StandardCharsets.UTF_8)),
                            commandContract(), caller(), REQUEST_START, REQUEST_START, CONTRACT),
                    operation + " was refused").operation());
            final byte[] content = ("the bytes of " + operation).getBytes(StandardCharsets.UTF_8);
            assertInstanceOf(ArtifactStore.Published.class, ArtifactStore.publish(session, caller(),
                    operation(operation), new ArtifactStore.Publication(slot(), content.length,
                            new ByteArrayInputStream(content)), REQUEST_START, CONTRACT),
                    operation + "'s artifact was not published");
        }
        return session;
    }

    private static ArtifactSlot slot() {
        return assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of("result"),
                "the slot was refused").slot();
    }

    private static OperationIdentity identity(String fixture) {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document(fixture), CONTRACT),
                fixture + " is not an operation identity").identity();
    }

    private static CommandContractIdentity commandContract() {
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(document("command-contract.json"),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static StatePath operation(String fixture) {
        return OperationStore.pathOf(identity(fixture));
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-swept-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        for (final String operation : OPERATIONS) {
            final String path = operation(operation).path();
            walked(session, path.substring(0, path.lastIndexOf('/')));
        }
        ArtifactStore.prepare(session, caller());
        LedgerAdmission.prepare(session, caller());
        return session;
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }

    private static AgentContract contractWith(String bound, long value) {
        final Map<String, Long> overrides = Map.of(bound, value);
        final StringBuilder rewritten = new StringBuilder();
        read(REPOSITORY.resolve("support/agent-contract.toml")).lines().forEach(line -> {
            final String name = line.contains("=") ? line.substring(0, line.indexOf('=')).strip()
                    : "";
            rewritten.append(overrides.containsKey(name) ? name + " = " + overrides.get(name)
                            : line)
                    .append('\n');
        });
        final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        return assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load(document, AgentContract.digestOf(document)),
                "the shrunken contract is not one this build reads").contract();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
