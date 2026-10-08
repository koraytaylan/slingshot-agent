// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Durations belong to phases and requests, never to wall-clock instants or request identities. */
final class SubmissionTimingsTest {

    private static final long MILLION = 1_000_000;

    @Test
    void distinctPhasesAccountOnlyTheirOwnIntervals() {
        final var clock = new AtomicLong(19 * MILLION);
        final var timings = new SubmissionTimings(clock::get);
        clock.addAndGet(5 * MILLION);
        timings.executing();
        clock.addAndGet(11 * MILLION);
        timings.persisting();
        clock.addAndGet(7 * MILLION);
        assertEquals("admission;dur=5,execution;dur=11,persistence;dur=7", header(timings));
    }

    @Test
    void aRecognisedTerminalRequestHasNoExecutionOrPersistenceInterval() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        clock.set(23 * MILLION);
        assertEquals("admission;dur=23,execution;dur=0,persistence;dur=0", header(timings));
    }

    @Test
    void aPendingCompletionResumesPersistenceWithoutClaimingExecution() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        clock.set(3 * MILLION);
        timings.persisting();
        clock.set(13 * MILLION);
        assertEquals("admission;dur=3,execution;dur=0,persistence;dur=10", header(timings));
    }

    @Test
    void enteringPersistenceTwiceRetainsBothIntervals() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        timings.persisting();
        clock.set(7 * MILLION);
        timings.persisting();
        clock.set(19 * MILLION);
        assertEquals("admission;dur=0,execution;dur=0,persistence;dur=19", header(timings));
    }

    @Test
    void repeatedObservationsDoNotDoubleCountAnInterval() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        timings.executing();
        clock.set(7 * MILLION);
        assertEquals("admission;dur=0,execution;dur=7,persistence;dur=0", header(timings));
        assertEquals("admission;dur=0,execution;dur=7,persistence;dur=0", header(timings));
        clock.set(9 * MILLION);
        assertEquals("admission;dur=0,execution;dur=9,persistence;dur=0", header(timings));
    }

    @Test
    void interleavedRequestsHaveIndependentStartsAndPhases() {
        final var clock = new AtomicLong();
        final var first = new SubmissionTimings(clock::get);
        clock.set(3 * MILLION);
        first.executing();
        final var second = new SubmissionTimings(clock::get);
        clock.set(8 * MILLION);
        second.persisting();
        clock.set(13 * MILLION);
        assertEquals("admission;dur=3,execution;dur=10,persistence;dur=0", header(first));
        assertEquals("admission;dur=5,execution;dur=0,persistence;dur=5", header(second));
    }

    @Test
    void signedCounterWrapPreservesTheShortDuration() {
        final var clock = new AtomicLong(Long.MAX_VALUE - 2 * MILLION);
        final var timings = new SubmissionTimings(clock::get);
        clock.addAndGet(5 * MILLION);
        assertEquals("admission;dur=5,execution;dur=0,persistence;dur=0", header(timings));
    }

    @Test
    void backwardsReadingsNeitherEmitNegativeDurationsNorCountRecoveryTwice() {
        final var clock = new AtomicLong(10 * MILLION);
        final var timings = new SubmissionTimings(clock::get);
        clock.set(8 * MILLION);
        timings.executing();
        clock.set(12 * MILLION);
        assertEquals("admission;dur=0,execution;dur=2,persistence;dur=0", header(timings));
    }

    @Test
    void submillisecondRemaindersAreRetainedAcrossObservationsWithinOnePhase() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        clock.set(600_000);
        assertEquals("admission;dur=0,execution;dur=0,persistence;dur=0", header(timings));
        clock.set(1_200_000);
        assertEquals("admission;dur=1,execution;dur=0,persistence;dur=0", header(timings));
    }

    @Test
    void saturationKeepsOutputFiniteNonnegativeAndBounded() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        clock.set(Long.MAX_VALUE);
        timings.executing();
        clock.addAndGet(Long.MAX_VALUE);
        timings.persisting();
        clock.addAndGet(Long.MAX_VALUE);
        timings.persisting();
        clock.addAndGet(Long.MAX_VALUE);
        final String header = header(timings);
        assertEquals("admission;dur=9223372036854,execution;dur=9223372036854,"
                + "persistence;dur=9223372036854", header);
        assertTrue(header.length() < 100);
    }

    @Test
    void theRuntimeClockProducesOnlyTheFixedNumericGrammar() {
        assertTrue(header(new SubmissionTimings()).matches(
                "admission;dur=[0-9]+,execution;dur=0,persistence;dur=0"));
    }

    @Test
    void theOwningTypeWritesExactlyOneNumericHeader() {
        final var clock = new AtomicLong();
        final var timings = new SubmissionTimings(clock::get);
        final var response = new org.apache.sling.servlethelpers.MockSlingHttpServletResponse();
        clock.set(3 * MILLION);
        timings.writeTo(response);
        assertEquals("admission;dur=3,execution;dur=0,persistence;dur=0",
                response.getHeader(SubmitServlet.SERVER_TIMING));
        assertEquals(1, response.getHeaders(SubmitServlet.SERVER_TIMING).size());
    }

    @Test
    void absoluteClockReadingsNeverBecomeHeaderValues() {
        final var clock = new AtomicLong(Long.MIN_VALUE + MILLION);
        final var timings = new SubmissionTimings(clock::get);
        clock.addAndGet(3 * MILLION);
        assertEquals("admission;dur=3,execution;dur=0,persistence;dur=0", header(timings));
    }

    private static String header(SubmissionTimings timings) {
        final var response = new org.apache.sling.servlethelpers.MockSlingHttpServletResponse();
        timings.writeTo(response);
        return response.getHeader(SubmitServlet.SERVER_TIMING);
    }
}
