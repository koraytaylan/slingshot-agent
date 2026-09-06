// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/**
 * One durable thing per submission: what it holds, what may happen to it, and when this side will
 * not believe the instant a client says its request began.
 *
 * <p>The transition matrix is exercised one pair at a time across every state rather than along the
 * path a command actually takes. The path is the case that works; the matrix is where the fourth
 * case nobody wrote lives.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class LogicalOperationTest {
    private static final AgentContract CONTRACT = contract();
    private static final long NOW = 1788000000000L;
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void aMissingRetainedCapacityIdentityCannotBecomeAnUnfundedCompletion() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        ExecutionJournal.start(session, accepted, NOW, CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        final var journal = session.getNode(path.child(ExecutionJournal.NODE).path());
        final String property = rs.slingshot.agent.store.CapacityReservation.RESOURCE_RESERVATION;
        final String original = journal.getProperty(property).getString();
        journal.setProperty(property, java.util.UUID.randomUUID().toString());
        session.save();
        org.junit.jupiter.api.Assertions.assertThrows(RepositoryException.class,
                () -> ExecutionJournal.completed(session, path, completion(), CONTRACT));
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        session.getNode(path.child(ExecutionJournal.NODE).path()).setProperty(property, original);
        session.save();
        assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(session, path, completion(), CONTRACT));
        completionChargesMatch(session, path);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void interruptedRetentionKeepsCompletionCapacityWithItsOperation(boolean committed)
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        ExecutionJournal.start(session, accepted, NOW, CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        ExecutionJournal.completed(session, path, completion(), CONTRACT);
        final long expired = NOW
                + CONTRACT.value(ContractLimit.MINIMUM_OPERATION_DETAIL_RETENTION_MILLISECONDS);
        final var inserted = new java.util.concurrent.atomic.AtomicBoolean();
        final Session interrupted = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session, () -> {
            if (!session.nodeExists(path.path()) && inserted.compareAndSet(false, true)) {
                if (committed) {
                    session.save();
                }
                throw new RepositoryException("retirement response was interrupted");
            }
        });
        org.junit.jupiter.api.Assertions.assertThrows(RepositoryException.class,
                () -> rs.slingshot.agent.store.MaintenanceSweep.run(interrupted, identity().generation(),
                        expired, CONTRACT));
        final var factory = java.util.Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class));
        try (var resolver = factory.getResourceResolver(java.util.Map.of())) {
            final Session observer = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            assertTrue(inserted.get());
            assertEquals(!committed, observer.nodeExists(path.path()));
            assertEquals(committed ? 0 : 1, rs.slingshot.agent.store.CapacityLedger.held(observer,
                    rs.slingshot.agent.store.AccountedQuantity.RESULT_ROWS, CONTRACT));
            rs.slingshot.agent.store.MaintenanceSweep.run(observer, identity().generation(),
                    expired, CONTRACT);
            assertEquals(0, rs.slingshot.agent.store.CapacityLedger.held(observer,
                    rs.slingshot.agent.store.AccountedQuantity.RESULT_BYTES, CONTRACT));
            assertEquals(0, rs.slingshot.agent.store.CapacityLedger.held(observer,
                    rs.slingshot.agent.store.AccountedQuantity.SNAPSHOT_BYTES, CONTRACT));
            assertEquals(0, rs.slingshot.agent.store.CapacityLedger.held(observer,
                    rs.slingshot.agent.store.AccountedQuantity.EVENT_BYTES, CONTRACT));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void interruptedFundedCompletionPreservesTheRecordedResultAndItsExactReservation(int boundary,
                                                                                   boolean committed)
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        ExecutionJournal.start(session, accepted, NOW, CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        final Session interrupted = rs.slingshot.agent.store.SaveInterleaving.interruptSave(session,
                boundary, committed);
        org.junit.jupiter.api.Assertions.assertThrows(RepositoryException.class,
                () -> ExecutionJournal.completed(interrupted, path, completion(), CONTRACT));
        final var factory = java.util.Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class));
        try (var resolver = factory.getResourceResolver(java.util.Map.of())) {
            final Session observer = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            final var pending = ExecutionJournal.pending(observer, path, CONTRACT);
            assertEquals(boundary == 2 && committed, pending.isPresent());
            completionChargesMatch(observer, path);
            final WriteOutcome retried = ExecutionJournal.completed(observer, path, completion(), CONTRACT);
            assertEquals(pending.isPresent() ? WriteOutcome.VALUE_CHANGED : WriteOutcome.WRITTEN, retried);
            assertEquals(completion(), ExecutionJournal.pending(observer, path, CONTRACT).orElseThrow());
            completionChargesMatch(observer, path);
        }
    }

    private static void completionChargesMatch(Session session, StatePath operation)
            throws RepositoryException {
        final var reservation = rs.slingshot.agent.store.CapacityReservation.ofResource(session,
                session.getNode(operation.child(ExecutionJournal.NODE).path())).orElseThrow();
        for (final var charge : reservation.charges()) {
            assertEquals(charge.amount(), rs.slingshot.agent.store.CapacityLedger.held(session,
                    charge.quantity(), CONTRACT));
            assertEquals(charge.amount(), rs.slingshot.agent.store.CapacityLedger.heldBy(session,
                    charge.quantity(), caller(), CONTRACT));
        }
        final var pending = ExecutionJournal.pending(session, operation, CONTRACT);
        assertEquals(pending.isPresent() ? pending.get().inlineBytes()
                : CONTRACT.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES),
                rs.slingshot.agent.store.CapacityLedger.held(session,
                        rs.slingshot.agent.store.AccountedQuantity.RESULT_BYTES, CONTRACT));
    }

    @Test
    void completionCapacityShrinksToItsRetainedValuesAndIsReleasedWithTheOperation()
            throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        final LogicalOperation running = ExecutionJournal.start(session, accepted, NOW,
                CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        assertEquals(CONTRACT.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES),
                rs.slingshot.agent.store.CapacityLedger.held(session,
                        rs.slingshot.agent.store.AccountedQuantity.RESULT_BYTES, CONTRACT));
        assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(session, path, completion(), CONTRACT));
        assertEquals(completion().inlineBytes(), rs.slingshot.agent.store.CapacityLedger.held(session,
                rs.slingshot.agent.store.AccountedQuantity.RESULT_BYTES, CONTRACT));
        assertEquals(rs.slingshot.agent.store.SnapshotStore.bytesFor(completion().state().kind()),
                rs.slingshot.agent.store.CapacityLedger.held(session,
                        rs.slingshot.agent.store.AccountedQuantity.SNAPSHOT_BYTES, CONTRACT));
        assertInstanceOf(TerminalCommit.Committed.class, TerminalCommit.commitReserved(session,
                caller(), running,
                completion(), CONTRACT, path.child(rs.slingshot.agent.store.EventLedger.TERMINAL_BUDGET)));
        final long expired = NOW
                + CONTRACT.value(ContractLimit.MINIMUM_OPERATION_DETAIL_RETENTION_MILLISECONDS);
        rs.slingshot.agent.store.MaintenanceSweep.run(session, identity().generation(), expired, CONTRACT);
        for (final var quantity : List.of(rs.slingshot.agent.store.AccountedQuantity.RESULT_ROWS,
                rs.slingshot.agent.store.AccountedQuantity.RESULT_BYTES,
                rs.slingshot.agent.store.AccountedQuantity.SNAPSHOT_ROWS,
                rs.slingshot.agent.store.AccountedQuantity.SNAPSHOT_BYTES)) {
            assertEquals(0, rs.slingshot.agent.store.CapacityLedger.held(session, quantity, CONTRACT));
            assertEquals(0, rs.slingshot.agent.store.CapacityLedger.heldBy(session, quantity,
                    caller(), CONTRACT));
        }
    }

    @Test
    void anUnavailableUncertaintyWritePreservesTheHandlerErrorAndRemainsRecoverable()
            throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        ExecutionJournal.start(session, accepted, NOW, CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        final Session unavailable = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session,
                () -> {
                    throw new RepositoryException("state storage is unavailable");
                });
        final IllegalStateException primary = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> {
                    try (var attempt = ExecutionJournal.attempt(unavailable, path, CONTRACT)) {
                        attempt.complete(crashedHandler());
                    }
                });
        assertEquals("handler failed after its effect", primary.getMessage());
        assertEquals(1, primary.getSuppressed().length);
        assertInstanceOf(RepositoryException.class, primary.getSuppressed()[0]);
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        final long expired = NOW + CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS)
                + CONTRACT.value(ContractLimit.RECOVERY_UNDETERMINED_MARGIN_MILLISECONDS);
        assertEquals(1, RestartRecovery.reconcile(session, identity().generation(), expired, CONTRACT)
                .with(RecoveryDisposition.FINISHED).size());
        assertEquals(ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED.result(),
                TerminalCommit.answerIn(session, path).orElseThrow());
    }

    private static ExecutionOutcome.Completion crashedHandler() {
        throw new IllegalStateException("handler failed after its effect");
    }

    @Test
    void removingAnInterruptedExecutionReleasesItsUnusedTerminalCapacity() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        ExecutionJournal.start(session, accepted, NOW, CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        final long expired = NOW
                + CONTRACT.value(ContractLimit.MINIMUM_OPERATION_DETAIL_RETENTION_MILLISECONDS);
        rs.slingshot.agent.store.MaintenanceSweep.run(session, identity().generation(), expired, CONTRACT);
        assertFalse(session.nodeExists(path.path()));
        assertEquals(0, rs.slingshot.agent.store.CapacityLedger.held(session,
                rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS, CONTRACT));
        assertEquals(0, rs.slingshot.agent.store.CapacityLedger.held(session,
                rs.slingshot.agent.store.AccountedQuantity.EVENT_BYTES, CONTRACT));
        rs.slingshot.agent.store.MaintenanceSweep.run(session, identity().generation(), expired, CONTRACT);
        assertEquals(0, rs.slingshot.agent.store.CapacityLedger.heldBy(session,
                rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS, caller(), CONTRACT));
    }

    @Test
    void terminalPublicationCannotReplaceTheCompletionSelectedByTheJournal() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        final LogicalOperation running = ExecutionJournal.start(session, accepted, NOW,
                CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        final StatePath budget = path.child(rs.slingshot.agent.store.EventLedger.TERMINAL_BUDGET);
        assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(session, path, completion(), CONTRACT));
        final ExecutionOutcome different = assertInstanceOf(ExecutionOutcome.Held.class, ExecutionOutcome.of(
                new ExecutionOutcome.Failed(new ExecutionOutcome.Inline("{}")), NOW, CONTRACT)).outcome();
        assertInstanceOf(TerminalCommit.Refused.class,
                TerminalCommit.commitReserved(session, caller(), running, different, CONTRACT, budget));
        assertTrue(session.nodeExists(budget.path()));
        assertTrue(TerminalCommit.answerIn(session, path).isEmpty());
        assertEquals(completion(), ExecutionJournal.pending(session, path, CONTRACT).orElseThrow());
        assertInstanceOf(TerminalCommit.Committed.class,
                TerminalCommit.commitReserved(session, caller(), running, completion(), CONTRACT, budget));
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
    }

    @Test
    void recoveryReadsOnlyRecordsWhoseIdentityNamesTheCandidatePath() throws RepositoryException {
        final Session session = prepared();
        final StatePath path = OperationStore.pathOf(identity());
        assertEquals(OperationStore.Refusal.NO_RECORD, assertInstanceOf(OperationStore.Refused.class,
                OperationStore.readAt(session, identity().generation(), path)).refusal());
        created(session, accepted(NOW));
        final EventStoreGeneration other = assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(identity().generation().number() + 1)).generation();
        assertEquals(OperationStore.Refusal.UNREADABLE, assertInstanceOf(OperationStore.Refused.class,
                OperationStore.readAt(session, other, path)).refusal());
        session.getNode(path.path()).setProperty(OperationStore.TARGET_DIGEST, "not-a-digest");
        session.save();
        assertEquals(OperationStore.Refusal.UNREADABLE, assertInstanceOf(OperationStore.Refused.class,
                OperationStore.readAt(session, identity().generation(), path)).refusal());
    }

    @Test
    void recoveryPublishesARecordedCompletionWithoutWaitingForTheExecutionBudget()
            throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        ExecutionJournal.start(session, accepted, NOW, CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(session, path, completion(), CONTRACT));
        final RestartRecovery.Reconciliation recovered = RestartRecovery.reconcile(session,
                identity().generation(), NOW + 1, CONTRACT);
        assertEquals(1, recovered.with(RecoveryDisposition.FINISHED).size());
        assertEquals(completion().result(), TerminalCommit.answerIn(session, path).orElseThrow());
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        assertFalse(session.getNode(path.child(ExecutionJournal.NODE).path())
                .hasProperty(TerminalCommit.RESULT_DOCUMENT));
        assertEquals(OperationState.SUCCEEDED, assertInstanceOf(OperationStore.Held.class,
                OperationStore.readAt(session, identity().generation(), path)).operation().state());
        assertEquals(1, RestartRecovery.reconcile(session, identity().generation(), NOW + 2, CONTRACT)
                .with(RecoveryDisposition.FINISHED).size());
        assertEquals(1, rs.slingshot.agent.store.EventLedger.events(session,
                path.child(rs.slingshot.agent.store.EventLedger.NODE)));
    }

    @Test
    void recoveryUsesTheServerStartAndPublishesUncertaintyAfterAnUnfinishedExecution()
            throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        final long started = NOW + CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS);
        ExecutionJournal.start(session, accepted, started, CONTRACT).orElseThrow();
        final long boundary = started + CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS)
                + CONTRACT.value(ContractLimit.RECOVERY_UNDETERMINED_MARGIN_MILLISECONDS);
        assertEquals(1, RestartRecovery.reconcile(session, identity().generation(), boundary - 1, CONTRACT)
                .with(RecoveryDisposition.STILL_RUNNING).size());
        final StatePath path = OperationStore.pathOf(identity());
        assertTrue(TerminalCommit.answerIn(session, path).isEmpty());
        assertEquals(1, RestartRecovery.reconcile(session, identity().generation(), boundary, CONTRACT)
                .with(RecoveryDisposition.FINISHED).size());
        assertEquals(ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED.result(),
                TerminalCommit.answerIn(session, path).orElseThrow());
        assertEquals(WriteOutcome.VALUE_CHANGED, ExecutionJournal.completed(session, path,
                completion(), CONTRACT));
        assertEquals(OperationState.FAILED, assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation().state());
    }

    @Test
    void executionReservesItsTerminalEventBeforeStartingAndSpendsItOnCompletion()
            throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        assertTrue(ExecutionJournal.start(session, accepted, NOW, CONTRACT).isEmpty());
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        final LogicalOperation running = ExecutionJournal.start(session, accepted, NOW,
                CONTRACT).orElseThrow();
        final StatePath path = OperationStore.pathOf(identity());
        assertEquals(NOW, session.getNode(path.child(ExecutionJournal.NODE).path())
                .getProperty(ExecutionJournal.STARTED_AT).getLong());
        assertEquals(1, rs.slingshot.agent.store.CapacityLedger.held(session,
                rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS, CONTRACT));
        assertEquals(TerminalCommit.maximumEventBytes(accepted), rs.slingshot.agent.store.CapacityLedger.held(
                session, rs.slingshot.agent.store.AccountedQuantity.EVENT_BYTES, CONTRACT));
        assertTrue(ExecutionJournal.start(session, accepted, NOW + 1, CONTRACT).isEmpty());
        assertEquals(1, rs.slingshot.agent.store.CapacityLedger.held(session,
                rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS, CONTRACT));
        assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(session, path, completion(), CONTRACT));
        assertInstanceOf(TerminalCommit.Committed.class, TerminalCommit.commitReserved(session,
                caller(), running,
                ExecutionJournal.pending(session, path, CONTRACT).orElseThrow(), CONTRACT,
                path.child(rs.slingshot.agent.store.EventLedger.TERMINAL_BUDGET)));
        assertFalse(session.nodeExists(path.child(
                rs.slingshot.agent.store.EventLedger.TERMINAL_BUDGET).path()));
        assertEquals(completion().result(), TerminalCommit.answerIn(session, path).orElseThrow());
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        assertFalse(session.getNode(path.child(ExecutionJournal.NODE).path())
                .hasProperty(TerminalCommit.RESULT_DOCUMENT));
        assertEquals(rs.slingshot.agent.store.EventLedger.bytes(session,
                path.child(rs.slingshot.agent.store.EventLedger.NODE)),
                rs.slingshot.agent.store.CapacityLedger.held(session,
                        rs.slingshot.agent.store.AccountedQuantity.EVENT_BYTES, CONTRACT));
    }

    @Test
    void completionEvidenceSurvivesAnInterruptedWriteAndRejectsASecondAnswer() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final StatePath path = OperationStore.pathOf(identity());
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        assertEquals(WriteOutcome.VALUE_CHANGED, ExecutionJournal.completed(session, path,
                completion(), CONTRACT));
        assertInstanceOf(OperationStore.Held.class, OperationStore.start(session, accepted,
                node -> ExecutionJournal.stageStart(node, NOW)));
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        final Session interrupted = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session,
                () -> {
                    throw new RepositoryException("completion publication interrupted");
                });
        org.junit.jupiter.api.Assertions.assertThrows(RepositoryException.class,
                () -> ExecutionJournal.completed(interrupted, path, completion(), CONTRACT));
        assertFalse(session.hasPendingChanges());
        assertTrue(ExecutionJournal.pending(session, path, CONTRACT).isEmpty());
        assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(session, path, completion(), CONTRACT));
        session.refresh(false);
        assertEquals(completion(), ExecutionJournal.pending(session, path, CONTRACT).orElseThrow());
        assertEquals(OperationState.RUNNING, assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation().state());
        final ExecutionOutcome unknown = assertInstanceOf(ExecutionOutcome.Held.class,
                ExecutionOutcome.of(ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED, NOW + 1,
                        CONTRACT)).outcome();
        assertEquals(WriteOutcome.VALUE_CHANGED, ExecutionJournal.completed(session, path, unknown,
                CONTRACT));
        assertEquals(completion(), ExecutionJournal.pending(session, path, CONTRACT).orElseThrow());
    }

    @Test
    void recoveryCompletionWinsAgainstAnAlreadyStagedHandlerAnswer() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final StatePath path = OperationStore.pathOf(identity());
        assertInstanceOf(OperationStore.Held.class, OperationStore.start(session, accepted,
                node -> ExecutionJournal.stageStart(node, NOW)));
        final ExecutionOutcome unknown = assertInstanceOf(ExecutionOutcome.Held.class,
                ExecutionOutcome.of(ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED, NOW + 1,
                        CONTRACT)).outcome();
        final var factory = java.util.Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class));
        try (var resolver = factory.getResourceResolver(java.util.Map.of())) {
            final Session competing = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            final var inserted = new java.util.concurrent.atomic.AtomicBoolean();
            final Session watched = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session, () -> {
                if (inserted.compareAndSet(false, true)) {
                    assertEquals(WriteOutcome.WRITTEN, ExecutionJournal.completed(competing, path,
                            unknown, CONTRACT));
                }
            });
            assertEquals(WriteOutcome.CONTENDED, ExecutionJournal.completed(watched, path,
                    completion(), CONTRACT));
            assertTrue(inserted.get());
            assertFalse(session.hasPendingChanges());
            assertEquals(unknown, ExecutionJournal.pending(session, path, CONTRACT).orElseThrow());
            assertEquals(WriteOutcome.VALUE_CHANGED, ExecutionJournal.completed(session, path,
                    completion(), CONTRACT));
        } catch (final org.apache.sling.api.resource.LoginException refused) {
            throw new IllegalStateException("the competing resolver could not be opened", refused);
        }
    }

    private static ExecutionOutcome completion() {
        return assertInstanceOf(ExecutionOutcome.Held.class, ExecutionOutcome.of(
                new ExecutionOutcome.Succeeded(new ExecutionOutcome.Inline("{\"result\":1}")), NOW, CONTRACT))
                .outcome();
    }

    @Test
    void executionStartPublishesItsResourcesOnlyWithTheWinningTransition() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final var publications = new java.util.concurrent.atomic.AtomicInteger();
        final var starting = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session, () -> {
            final var record = session.getNode(OperationStore.pathOf(identity()).path());
            assertEquals(OperationState.RUNNING.spelling(),
                    record.getProperty(OperationStore.STATE).getString());
            assertTrue(record.hasNode("completion"));
        });
        final LogicalOperation running = assertInstanceOf(OperationStore.Held.class,
                OperationStore.start(starting, accepted, node -> {
                    node.addNode("completion", "nt:unstructured");
                    publications.incrementAndGet();
                })).operation();
        assertEquals(OperationState.RUNNING, running.state());
        assertEquals(accepted.attempts(), running.attempts());
        assertInstanceOf(OperationStore.Refused.class, OperationStore.start(session, accepted,
                node -> publications.incrementAndGet()));
        assertInstanceOf(OperationStore.Refused.class, OperationStore.start(session, running,
                node -> publications.incrementAndGet()));
        assertEquals(1, publications.get());
    }

    @Test
    void aCompetingStartCannotOverwriteTheWinnersCompletionResources() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final var factory = java.util.Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class));
        try (var resolver = factory.getResourceResolver(java.util.Map.of())) {
            final Session competing = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            final var inserted = new java.util.concurrent.atomic.AtomicBoolean();
            final Session watched = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session, () -> {
                if (inserted.compareAndSet(false, true)) {
                    assertInstanceOf(OperationStore.Held.class, OperationStore.start(competing, accepted,
                            node -> node.addNode("completion", "nt:unstructured").setProperty("owner",
                                    "winner")));
                }
            });
            assertEquals(OperationStore.Refusal.CONTENDED, assertInstanceOf(OperationStore.Refused.class,
                    OperationStore.start(watched, accepted,
                            node -> node.addNode("completion", "nt:unstructured").setProperty("owner",
                                    "loser")))
                    .refusal());
            assertTrue(inserted.get());
            assertFalse(session.hasPendingChanges());
            assertEquals("winner",
                    session.getNode(OperationStore.pathOf(identity()).child("completion").path())
                    .getProperty("owner").getString());
        } catch (final org.apache.sling.api.resource.LoginException refused) {
            throw new IllegalStateException("the competing resolver could not be opened", refused);
        }
    }

    @Test
    void interruptedExecutionStartLeavesNeitherRunningStateNorCompletionResources()
            throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final Session interrupted = rs.slingshot.agent.store.SaveInterleaving.beforeEverySave(session,
                () -> {
                    throw new RepositoryException("start publication interrupted");
                });
        org.junit.jupiter.api.Assertions.assertThrows(RepositoryException.class,
                () -> OperationStore.start(interrupted, accepted,
                        node -> node.addNode("completion", "nt:unstructured")));
        assertFalse(session.hasPendingChanges());
        assertEquals(OperationState.ACCEPTED, assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation().state());
        assertFalse(session.nodeExists(OperationStore.pathOf(identity()).child("completion").path()));
        assertInstanceOf(OperationStore.Held.class, OperationStore.start(session, accepted,
                node -> node.addNode("completion", "nt:unstructured")));
    }

    @Test
    @DisplayName("every permitted move is accepted from its own state and refused from every other")
    void thewholeTransitionMatrixHolds() {
        Arrays.stream(OperationState.values()).forEach(from ->
                Arrays.stream(OperationState.values()).forEach(to -> {
                    final boolean declared = OperationState.transitions().contains(List.of(from, to))
                            || from == to && from.finality() == rs.slingshot.agent.wire
                                    .JobEventKind.Finality.ENDS;
                    assertEquals(declared, from.permits(to),
                            from.spelling() + " to " + to.spelling() + " is permitted where it is"
                                    + " not declared, or refused where it is");
                }));
        assertEquals(4, OperationState.transitions().size(), "a transition was added or lost");
    }

    @Test
    @DisplayName("a terminal state accepts nothing else, and accepts itself again unchanged")
    void aterminalStateIsFinalAndIdempotent() {
        assertTrue(OperationState.SUCCEEDED.permits(OperationState.SUCCEEDED),
                "a worker that lost its answer cannot say the same thing again");
        assertFalse(OperationState.SUCCEEDED.permits(OperationState.FAILED),
                "a job that succeeded then failed");
        assertFalse(OperationState.SUCCEEDED.permits(OperationState.RUNNING),
                "a job that finished started again");
        assertEquals(rs.slingshot.agent.wire.JobEventKind.Finality.ENDS,
                OperationState.FAILED.finality());
        assertEquals(rs.slingshot.agent.wire.JobEventKind.ACCEPTED, OperationState.ACCEPTED.kind(),
                "a state and the event kind that announces it disagree");
        assertEquals(java.util.Optional.of(OperationState.RUNNING),
                OperationState.named("running"));
        assertTrue(OperationState.named("teleporting").isEmpty());
    }

    @Test
    @DisplayName("a record is created once, and a second writer reads the first's record")
    void arecordIsCreatedOnce() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation operation = accepted(NOW);
        final OperationStore.Created first = created(session, operation);
        assertEquals(WriteOutcome.CLAIMED, first.outcome());
        final OperationStore.Created second = created(session, accepted(NOW));
        assertEquals(WriteOutcome.ALREADY_HELD, second.outcome(),
                "a second submission created a second durable thing");
        assertEquals(first.operation().submissionDigest().rendered(),
                second.operation().submissionDigest().rendered());
    }

    @Test
    @DisplayName("a record holds the target, the revision, and the caller, and reads them back")
    void arecordHoldsWhatALaterPlanWillNeed() throws RepositoryException {
        final Session session = prepared();
        created(session, accepted(NOW));
        final LogicalOperation read = assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity()), "the record could not be read back")
                .operation();
        assertEquals("revision-2026-09-01", read.identity().environmentRevision());
        assertEquals(digest("a target").rendered(), read.identity().targetDigest().rendered());
        assertEquals("the-submitting-caller", read.caller().name());
        assertEquals(NOW, read.requestStartUnixMilliseconds());
        assertEquals(OperationState.ACCEPTED, read.state());
        assertEquals(0, read.attempts());
        assertEquals("query_paths", read.commandContract().wireName());
    }

    @Test
    @DisplayName("a move from the state the caller read happens, and one from another does not")
    void amoveHappensOnlyFromWhatWasRead() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final LogicalOperation running = assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, accepted, OperationState.RUNNING),
                "a move from the state that was read did not happen").operation();
        assertEquals(OperationState.RUNNING, running.state());
        final OperationStore.Refused stale = assertInstanceOf(OperationStore.Refused.class,
                OperationStore.move(session, accepted, OperationState.RUNNING),
                "a move from a state the record is no longer in happened anyway");
        assertEquals(OperationStore.Refusal.NOT_THE_STATE_THAT_WAS_READ, stale.refusal());
        assertEquals(OperationState.RUNNING, assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation().state(),
                "a refused move changed the record");
        assertEquals(OperationStore.Refusal.NOT_A_PERMITTED_MOVE,
                assertInstanceOf(OperationStore.Refused.class,
                        OperationStore.move(session, running, OperationState.ACCEPTED)).refusal());
    }

    @Test
    @DisplayName("a record nobody wrote is not read, and reading for one does not create it")
    void anabsentRecordIsNotCreatedByReading() throws RepositoryException {
        final Session session = prepared();
        final OperationStore.Refused refused = assertInstanceOf(OperationStore.Refused.class,
                OperationStore.read(session, identity()), "a record appeared out of nothing");
        assertEquals(OperationStore.Refusal.NO_RECORD, refused.refusal());
        assertFalse(session.nodeExists(OperationStore.pathOf(identity()).path()),
                "reading for a record created one");
    }

    @Test
    @DisplayName("retention runs from the instant the client says its request began")
    void retentionIsAnchoredAtRequestStart() {
        final long began = NOW - 60000;
        final LogicalOperation operation = accepted(began);
        assertEquals(began + 3600000, operation.retainedUntil(3600000),
                "retention is measured from something other than the request's own start");
        assertFalse(operation.retainedUntil(3600000) == NOW + 3600000,
                "retention is measured from when the record was written, which lengthens a window"
                        + " the client is budgeting against");
    }

    @Test
    @DisplayName("a request-start instant outside this side's allowance is refused, naming both")
    void theclockAllowanceHoldsAtBothSidesAndBothDirections() {
        final long allowance = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        assertInstanceOf(LogicalOperation.Held.class, accepting(NOW - allowance),
                "a request exactly at the allowance behind was refused");
        assertInstanceOf(LogicalOperation.Held.class, accepting(NOW + allowance),
                "a request exactly at the allowance ahead was refused");
        final LogicalOperation.Refused behind = assertInstanceOf(LogicalOperation.Refused.class,
                accepting(NOW - allowance - 1), "a request from before the allowance was recorded");
        assertEquals(LogicalOperation.ClockRefusal.TOO_FAR_BEHIND, behind.refusal());
        assertTrue(behind.detail().contains(String.valueOf(NOW)), behind.detail());
        final LogicalOperation.Refused ahead = assertInstanceOf(LogicalOperation.Refused.class,
                accepting(NOW + allowance + 1), "a request from after the allowance was recorded");
        assertEquals(LogicalOperation.ClockRefusal.TOO_FAR_AHEAD, ahead.refusal());
        assertTrue(ahead.detail().contains(String.valueOf(NOW + allowance + 1)), ahead.detail());
    }

    @Test
    @DisplayName("nothing is written for a request this side will not believe")
    void arefusedInstantWritesNothing() throws RepositoryException {
        final Session session = prepared();
        final long allowance = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        assertInstanceOf(LogicalOperation.Refused.class, accepting(NOW + allowance + 1));
        assertFalse(session.nodeExists(OperationStore.pathOf(identity()).path()),
                "a refused request left a record behind");
    }

    @Test
    @DisplayName("an attempt counted against a record is counted once")
    void anattemptIsCounted() {
        assertEquals(1, accepted(NOW).attempted().attempts());
        assertEquals(2, accepted(NOW).attempted().attempted().attempts());
        assertEquals(OperationState.ACCEPTED, accepted(NOW).attempted().state(),
                "counting an attempt moved the record");
    }

    private OperationStore.Created created(Session session, LogicalOperation operation)
            throws RepositoryException {
        final Object outcome = OperationStore.create(session, operation);
        assertInstanceOf(OperationStore.Created.class, outcome, "the record was not created: "
                + outcome);
        return (OperationStore.Created) outcome;
    }

    private static LogicalOperation accepted(long requestStart) {
        return assertInstanceOf(LogicalOperation.Held.class, accepting(requestStart),
                "the submission was refused").operation();
    }

    private static LogicalOperation.Outcome accepting(long requestStart) {
        return LogicalOperation.accepted(identity(), digest("a submission"), commandContract(),
                caller(), requestStart, NOW, CONTRACT);
    }

    private static OperationIdentity identity() {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(operationDocument(), CONTRACT),
                "the operation identity was refused").identity();
    }

    private static DocumentValue operationDocument() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION, new DocumentValue.Whole(
                EventStoreGeneration.FIRST));
        members.put(OperationIdentity.IDENTIFIER,
                new DocumentValue.Text(digest("one operation").rendered()));
        members.put(OperationIdentity.TARGET_DIGEST,
                new DocumentValue.Text(digest("a target").rendered()));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new DocumentValue.Text("revision-2026-09-01"));
        return new DocumentValue.Mapping(members);
    }

    private static CommandContractIdentity commandContract() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(CommandContractIdentity.WIRE_NAME, new DocumentValue.Text("query_paths"));
        members.put(CommandContractIdentity.CONTRACT_VERSION, new DocumentValue.Text("1.0.0"));
        members.put(CommandContractIdentity.LIMITS_DIGEST,
                new DocumentValue.Text(digest("limits").rendered()));
        members.put(CommandContractIdentity.ARGUMENT_DIGEST,
                new DocumentValue.Text(digest("arguments").rendered()));
        members.put(CommandContractIdentity.RESULT_DIGEST,
                new DocumentValue.Text(digest("result").rendered()));
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(new DocumentValue.Mapping(members),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-submitting-caller"),
                "the caller was refused").caller();
    }

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        final String path = OperationStore.pathOf(identity()).path();
        final String[] segments = path.substring(1).split("/");
        javax.jcr.Node walked = session.getRootNode();
        int index = 0;
        while (index < segments.length - 1) {
            walked = walked.hasNode(segments[index])
                    ? walked.getNode(segments[index])
                    : walked.addNode(segments[index], "nt:unstructured");
            index = index + 1;
        }
        session.save();
        rs.slingshot.agent.store.CapacityLedger.prepare(session,
                rs.slingshot.agent.store.AccountedQuantity.RESULT_ROWS, caller());
        rs.slingshot.agent.store.CapacityLedger.prepare(session,
                rs.slingshot.agent.store.AccountedQuantity.RESULT_BYTES, caller());
        rs.slingshot.agent.store.CapacityLedger.prepare(session,
                rs.slingshot.agent.store.AccountedQuantity.SNAPSHOT_ROWS, caller());
        rs.slingshot.agent.store.CapacityLedger.prepare(session,
                rs.slingshot.agent.store.AccountedQuantity.SNAPSHOT_BYTES, caller());
        return session;
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
