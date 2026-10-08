// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.log.AgentLog;

/** Phase attribution remains request-local, finite, numeric and gated by the flushed refusal. */
final class LookupTimingsTest {

    private static final long MILLION = 1_000_000;

    @Test
    void eachServerStageReceivesOnlyItsOwnInterval() {
        final var clock = new AtomicLong();
        final var timing = new LookupTimings(clock::get);
        clock.addAndGet(11 * MILLION);
        timing.dispatching();
        clock.addAndGet(6626 * MILLION);
        timing.generating();
        clock.addAndGet(23 * MILLION);
        timing.owning();
        clock.addAndGet(31 * MILLION);
        timing.snapshotting();
        clock.addAndGet(43 * MILLION);
        timing.responding();
        clock.addAndGet(53 * MILLION);
        timing.refused();
        timing.completedWork();
        timing.returning();
        clock.addAndGet(61 * MILLION);
        final var event = AgentLog.event("slow logical lookup refusal", timing.slowFields().orElseThrow());
        assertEquals(java.util.Map.of("lookup_setup_ms", "11", "lookup_dispatch_ms", "6626",
                "lookup_generation_ms", "23", "lookup_owner_ms", "31", "lookup_snapshot_ms", "43",
                "lookup_response_ms", "53", "lookup_return_ms", "61", "lookup_work_entered", "1",
                "lookup_work_completed", "1"), event.fields());
        final String line = AgentLog.lineOf(event, value -> false, 1024);
        assertFalse(AgentLog.carriesAnOperation(line));
        assertTrue(line.matches("slow logical lookup refusal( lookup_[a-z_]+=[0-9]+){9}"));
    }

    @Test
    void anUnflushedOrSuccessfulAnswerNeverCreatesAnEventEvenWhenSlow() {
        final var clock = new AtomicLong();
        final var timing = new LookupTimings(clock::get);
        clock.set(20_000 * MILLION);
        assertTrue(timing.slowFields().isEmpty());
    }

    @Test
    void oneSecondBoundaryIsInclusiveAndOneNanosecondBeforeItIsQuiet() {
        final var clock = new AtomicLong();
        final var timing = new LookupTimings(clock::get);
        timing.refused();
        clock.set(1000 * MILLION - 1);
        assertTrue(timing.slowFields().isEmpty());
        clock.incrementAndGet();
        assertEquals("1000", timing.slowFields().orElseThrow().get("lookup_setup_ms"));
    }

    @Test
    void repeatedObservationsRetainRemaindersWithoutCountingTwice() {
        final var clock = new AtomicLong();
        final var timing = new LookupTimings(clock::get);
        timing.refused();
        clock.set(1000 * MILLION + 600_000);
        assertEquals("1000", timing.slowFields().orElseThrow().get("lookup_setup_ms"));
        assertEquals("1000", timing.slowFields().orElseThrow().get("lookup_setup_ms"));
        clock.addAndGet(600_000);
        assertEquals("1001", timing.slowFields().orElseThrow().get("lookup_setup_ms"));
    }

    @Test
    void independentRequestsHaveIndependentStartsAndIntervals() {
        final var clock = new AtomicLong();
        final var first = new LookupTimings(clock::get);
        clock.set(1000 * MILLION);
        final var second = new LookupTimings(clock::get);
        first.refused();
        second.refused();
        clock.set(3000 * MILLION);
        assertEquals("3000", first.slowFields().orElseThrow().get("lookup_setup_ms"));
        assertEquals("2000", second.slowFields().orElseThrow().get("lookup_setup_ms"));
    }

    @Test
    void signedCounterWrapRetainsTheShortDuration() {
        final var clock = new AtomicLong(Long.MAX_VALUE - 1000 * MILLION);
        final var timing = new LookupTimings(clock::get);
        timing.refused();
        clock.addAndGet(2000 * MILLION);
        assertEquals("2000", timing.slowFields().orElseThrow().get("lookup_setup_ms"));
    }

    @Test
    void backwardsClockReadDoesNotMoveTheAcceptedObservation() {
        final var clock = new AtomicLong(10 * MILLION);
        final var timing = new LookupTimings(clock::get);
        timing.refused();
        clock.set(8 * MILLION);
        assertTrue(timing.slowFields().isEmpty());
        clock.set(2010 * MILLION);
        assertEquals("2000", timing.slowFields().orElseThrow().get("lookup_setup_ms"));
    }

    @Test
    void durationSaturationKeepsTheOperatorLineFiniteAndBounded() {
        final var clock = new AtomicLong();
        final var timing = new LookupTimings(clock::get);
        timing.refused();
        clock.set(Long.MAX_VALUE);
        timing.dispatching();
        clock.addAndGet(Long.MAX_VALUE);
        timing.dispatching();
        final var event = AgentLog.event("slow logical lookup refusal", timing.slowFields().orElseThrow());
        assertEquals("9223372036854", event.fields().get("lookup_dispatch_ms"));
        assertTrue(AgentLog.lineOf(event, value -> false, 1024).length() < 400);
    }

    @Test
    void exceptionalWorkDoesNotClaimNormalCompletion() {
        final var clock = new AtomicLong();
        final var timing = new LookupTimings(clock::get);
        timing.generating();
        timing.responding();
        timing.refused();
        timing.returning();
        clock.set(2000 * MILLION);
        final var fields = timing.slowFields().orElseThrow();
        assertEquals("1", fields.get("lookup_work_entered"));
        assertEquals("0", fields.get("lookup_work_completed"));
        assertEquals("2000", fields.get("lookup_return_ms"));
    }
}
