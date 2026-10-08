// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.JobState;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.stream.StreamTicker;

/** Cancellation completion is observed after one request, rather than assumed from admission. */
final class JobCancellationConfirmationTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    private static final String IDENTIFIER = "synthetic-owned-job";

    @Test
    @DisplayName("an active acknowledgement is followed by fresh snapshots and one control only")
    void anactiveAcknowledgementWaitsForFreshTerminalState() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker,
                List.of(snapshot(IDENTIFIER, JobState.ACTIVE),
                        snapshot(IDENTIFIER, JobState.CANCELLED)));
        terminal(run(inventory, ticker, deadline()), JobState.CANCELLED);
        assertEquals(1, inventory.cancellations);
        assertEquals(2, inventory.inspections);
        assertEquals(CONTRACT.value(ContractLimit.RETRY_BASE_MILLISECONDS) * 2,
                ticker.elapsed);
    }

    @Test
    @DisplayName("an already terminal observation needs no further read or pause")
    void aterminalObservationDoesNotWait() {
        for (final JobState state : List.of(JobState.CANCELLED, JobState.DROPPED,
                JobState.SUCCEEDED, JobState.ERROR)) {
            final Ticker ticker = new Ticker();
            final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, state)));
            inventory.observed = state;
            terminal(run(inventory, ticker, deadline()), state);
            assertEquals(1, inventory.cancellations);
            assertEquals(0, inventory.inspections);
            assertEquals(0, ticker.elapsed);
        }
    }

    @Test
    @DisplayName("a job remaining active or queued expires without repeating cancellation")
    void anunfinishedJobIsUncertainAtTheExistingDeadline() {
        for (final JobState state : List.of(JobState.ACTIVE, JobState.QUEUED)) {
            final Ticker ticker = new Ticker();
            final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, state)));
            inventory.observed = state;
            unknown(run(inventory, ticker, deadline()));
            assertEquals(1, inventory.cancellations);
            assertEquals(deadline(), ticker.elapsed);
            assertTrue(inventory.inspections > 0 && inventory.inspections
                    <= deadline() / CONTRACT.value(ContractLimit.RETRY_BASE_MILLISECONDS));
        }
    }

    @Test
    @DisplayName("the per-call budget clips a pause rather than extending the wait")
    void aperCallBudgetClipsTheLastPause() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, JobState.ACTIVE)));
        unknown(run(inventory, ticker, 1));
        assertEquals(1, inventory.cancellations);
        assertEquals(1, ticker.elapsed);
        assertEquals(0, inventory.inspections);
    }

    @Test
    @DisplayName("time spent inside the initial control consumes the confirmation deadline")
    void thecontrolCallDoesNotGetASecondDeadline() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, JobState.CANCELLED)));
        inventory.controlMilliseconds = deadline();
        unknown(run(inventory, ticker, deadline()));
        assertEquals(1, inventory.cancellations);
        assertEquals(0, inventory.inspections);
        assertEquals(deadline(), ticker.elapsed);
    }

    @Test
    @DisplayName("absence after the owned control confirms cancellation without a second control")
    void disappearanceAfterTheControlIsConfirmed() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker,
                List.of(new JobInventory.Refused(JobCommands.JOB_NOT_FOUND, "synthetic absence")));
        terminal(run(inventory, ticker, deadline()), JobState.CANCELLED);
        assertEquals(1, inventory.cancellations);
        assertEquals(1, inventory.inspections);
    }

    @Test
    @DisplayName("a fresh snapshot naming another job cannot confirm this cancellation")
    void anotherJobsTerminalStateCannotConfirmThisRequest() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker,
                List.of(snapshot("synthetic-other-job", JobState.CANCELLED)));
        unknown(run(inventory, ticker, deadline()));
        assertEquals(1, inventory.cancellations);
        assertEquals(1, inventory.inspections);
    }

    @Test
    @DisplayName("an unavailable read after cancellation is uncertainty rather than non-execution")
    void anunavailableReadDoesNotProveNoEffect() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker,
                List.of(new JobInventory.Refused(JobCommands.INVENTORY_FAILED, "synthetic failure")));
        unknown(run(inventory, ticker, deadline()));
        assertEquals(1, inventory.cancellations);
        assertEquals(1, inventory.inspections);
    }

    @Test
    @DisplayName("a control exception after admission remains uncertain and is not retried")
    void acontrolExceptionIsNotRetried() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, JobState.ACTIVE)));
        inventory.controlThrows = true;
        unknown(run(inventory, ticker, deadline()));
        assertEquals(1, inventory.cancellations);
        assertEquals(0, inventory.inspections);
    }

    @Test
    @DisplayName("an observation exception after admission remains uncertain and is not retried")
    void anobservationExceptionIsUncertain() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, JobState.ACTIVE)));
        inventory.inspectionThrows = true;
        unknown(run(inventory, ticker, deadline()));
        assertEquals(1, inventory.cancellations);
        assertEquals(1, inventory.inspections);
    }

    @Test
    @DisplayName("interruption during the pause is preserved and stops further observations")
    void interruptionAfterAdmissionPreservesUncertainty() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, JobState.ACTIVE)));
        ticker.interrupt = true;
        try {
            unknown(run(inventory, ticker, deadline()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, inventory.cancellations);
            assertEquals(0, inventory.inspections);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a worker already interrupted before admission issues no control")
    void interruptionBeforeAdmissionHasNoEffect() {
        final Ticker ticker = new Ticker();
        final Inventory inventory = new Inventory(ticker, List.of(snapshot(IDENTIFIER, JobState.ACTIVE)));
        try {
            Thread.currentThread().interrupt();
            assertEquals(JobCommands.CONTROL_REJECTED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            run(inventory, ticker, deadline())).category());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, inventory.cancellations);
        } finally {
            Thread.interrupted();
        }
    }

    private static long deadline() {
        return CONTRACT.value(ContractLimit.PLATFORM_CALL_DEADLINE_MILLISECONDS);
    }

    private static CommandHandler.Answer run(Inventory inventory, Ticker ticker, long milliseconds) {
        final CallerContext context = new CallerContext(
                assertInstanceOf(AgentOperationIdentifier.Held.class,
                        AgentOperationIdentifier.of("a".repeat(64), CONTRACT)).identifier(),
                Budget.discovery(CONTRACT), new Budget(Budget.Kind.TIME, milliseconds),
                new Budget(Budget.Kind.RESULT, CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobCommands.JOB_IDENTIFIER, new DocumentValue.Text(IDENTIFIER));
        return new JobHandler(CONTRACT, JobHandler.Kind.CANCELLATION, inventory,
                PlatformControl.of("synthetic", Set.of(ControlCapability.JOB_CONTROL)), ticker)
                .run(new DocumentValue.Mapping(members), null, context);
    }

    private static void unknown(CommandHandler.Answer answer) {
        assertEquals(SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN,
                assertInstanceOf(CommandHandler.Failed.class, answer).category());
    }

    private static void terminal(CommandHandler.Answer answer, JobState state) {
        final DocumentValue.Mapping result = assertInstanceOf(CommandHandler.Produced.class, answer).result();
        assertEquals(new DocumentValue.Text(IDENTIFIER),
                result.member(JobResults.JOB_IDENTIFIER).orElseThrow());
        assertEquals(new DocumentValue.Text(state.spelling()),
                result.member(JobResults.OBSERVED_STATE).orElseThrow());
    }

    private static JobInventory.Outcome snapshot(String identifier, JobState state) {
        return new JobInventory.Inspected(new JobInventory.JobDetail(
                new JobInventory.Job(identifier, "synthetic/owned/topic", "synthetic-queue", state, 0),
                List.of(), 0));
    }

    /** A scripted platform snapshot reader whose control may finish asynchronously. */
    private static final class Inventory implements JobInventory {

        private final Ticker ticker;
        private final List<Outcome> snapshots;
        private JobState observed = JobState.ACTIVE;
        private int cancellations;
        private int inspections;
        private long controlMilliseconds;
        private boolean controlThrows;
        private boolean inspectionThrows;

        Inventory(Ticker ticker, List<Outcome> snapshots) {
            this.ticker = ticker;
            this.snapshots = List.copyOf(snapshots);
        }

        @Override
        public Outcome queues() {
            throw new UnsupportedOperationException("synthetic unused listing");
        }

        @Override
        public Outcome jobs(String topic, List<JobState> states) {
            throw new UnsupportedOperationException("synthetic unused search");
        }

        @Override
        public Outcome inspect(String jobIdentifier) {
            assertEquals(IDENTIFIER, jobIdentifier);
            inspections++;
            if (inspectionThrows) {
                throw new IllegalStateException("synthetic unavailable observation");
            }
            return snapshots.get(Math.min(inspections - 1, snapshots.size() - 1));
        }

        @Override
        public Outcome cancel(String jobIdentifier) {
            assertEquals(IDENTIFIER, jobIdentifier);
            cancellations++;
            ticker.elapsed += controlMilliseconds;
            if (controlThrows) {
                throw new IllegalStateException("synthetic unavailable control outcome");
            }
            return new Cancelled(observed);
        }
    }

    /** A monotonic clock advanced by pauses; consulting a wall clock would fail the suite. */
    private static final class Ticker implements StreamTicker {

        private static final long serialVersionUID = 1L;
        private long elapsed;
        private boolean interrupt;

        @Override
        public long milliseconds() {
            throw new UnsupportedOperationException("synthetic wall clock must not be consulted");
        }

        @Override
        public long elapsedMilliseconds() {
            return elapsed;
        }

        @Override
        public void pause(long milliseconds) {
            assertTrue(milliseconds > 0);
            elapsed += milliseconds;
            if (interrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
