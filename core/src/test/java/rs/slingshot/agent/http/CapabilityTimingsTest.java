// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Durations belong to phases and requests, never to wall-clock instants or request identities. */
final class CapabilityTimingsTest {

    private static final long MILLION = 1_000_000;

    @Test
    void distinctPhasesAccountOnlyTheirOwnIntervals() {
        final var clock = new AtomicLong(19 * MILLION);
        final var timings = new CapabilityTimings(clock::get);
        clock.addAndGet(5 * MILLION);
        timings.authenticating();
        clock.addAndGet(11 * MILLION);
        timings.rendering();
        clock.addAndGet(7 * MILLION);
        assertEquals("cap_shape;dur=5,cap_identity;dur=11,cap_document;dur=7", header(timings));
    }

    @Test
    void shapeOnlyObservationHasNoIdentityOrDocumentInterval() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        clock.set(23 * MILLION);
        assertEquals("cap_shape;dur=23,cap_identity;dur=0,cap_document;dur=0", header(timings));
    }

    @Test
    void skippingIdentityMeasuresOnlyDocumentInterval() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        clock.set(3 * MILLION);
        timings.rendering();
        clock.set(13 * MILLION);
        assertEquals("cap_shape;dur=3,cap_identity;dur=0,cap_document;dur=10", header(timings));
    }

    @Test
    void enteringDocumentTwiceRetainsBothIntervals() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        timings.rendering();
        clock.set(7 * MILLION);
        timings.rendering();
        clock.set(19 * MILLION);
        assertEquals("cap_shape;dur=0,cap_identity;dur=0,cap_document;dur=19", header(timings));
    }

    @Test
    void repeatedObservationsDoNotDoubleCountAnInterval() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        timings.authenticating();
        clock.set(7 * MILLION);
        assertEquals("cap_shape;dur=0,cap_identity;dur=7,cap_document;dur=0", header(timings));
        assertEquals("cap_shape;dur=0,cap_identity;dur=7,cap_document;dur=0", header(timings));
        clock.set(9 * MILLION);
        assertEquals("cap_shape;dur=0,cap_identity;dur=9,cap_document;dur=0", header(timings));
    }

    @Test
    void interleavedRequestsHaveIndependentStartsAndPhases() {
        final var clock = new AtomicLong();
        final var first = new CapabilityTimings(clock::get);
        clock.set(3 * MILLION);
        first.authenticating();
        final var second = new CapabilityTimings(clock::get);
        clock.set(8 * MILLION);
        second.rendering();
        clock.set(13 * MILLION);
        assertEquals("cap_shape;dur=3,cap_identity;dur=10,cap_document;dur=0", header(first));
        assertEquals("cap_shape;dur=5,cap_identity;dur=0,cap_document;dur=5", header(second));
    }

    @Test
    void signedCounterWrapPreservesTheShortDuration() {
        final var clock = new AtomicLong(Long.MAX_VALUE - 2 * MILLION);
        final var timings = new CapabilityTimings(clock::get);
        clock.addAndGet(5 * MILLION);
        assertEquals("cap_shape;dur=5,cap_identity;dur=0,cap_document;dur=0", header(timings));
    }

    @Test
    void backwardsReadingsNeitherEmitNegativeDurationsNorCountRecoveryTwice() {
        final var clock = new AtomicLong(10 * MILLION);
        final var timings = new CapabilityTimings(clock::get);
        clock.set(8 * MILLION);
        timings.authenticating();
        clock.set(12 * MILLION);
        assertEquals("cap_shape;dur=0,cap_identity;dur=2,cap_document;dur=0", header(timings));
    }

    @Test
    void submillisecondRemaindersAreRetainedAcrossObservationsWithinOnePhase() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        clock.set(600_000);
        assertEquals("cap_shape;dur=0,cap_identity;dur=0,cap_document;dur=0", header(timings));
        clock.set(1_200_000);
        assertEquals("cap_shape;dur=1,cap_identity;dur=0,cap_document;dur=0", header(timings));
    }

    @Test
    void saturationKeepsOutputFiniteNonnegativeAndBounded() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        clock.set(Long.MAX_VALUE);
        timings.authenticating();
        clock.addAndGet(Long.MAX_VALUE);
        timings.rendering();
        clock.addAndGet(Long.MAX_VALUE);
        timings.rendering();
        clock.addAndGet(Long.MAX_VALUE);
        final String header = header(timings);
        assertEquals("cap_shape;dur=9223372036854,cap_identity;dur=9223372036854,"
                + "cap_document;dur=9223372036854", header);
        assertTrue(header.length() < 100);
    }

    @Test
    void theRuntimeClockProducesOnlyTheFixedNumericGrammar() {
        assertTrue(header(new CapabilityTimings()).matches(
                "cap_shape;dur=[0-9]+,cap_identity;dur=0,cap_document;dur=0"));
    }

    @Test
    void theOwningTypeWritesExactlyOneNumericHeader() {
        final var clock = new AtomicLong();
        final var timings = new CapabilityTimings(clock::get);
        final var response = new org.apache.sling.servlethelpers.MockSlingHttpServletResponse();
        clock.set(3 * MILLION);
        timings.writeTo(response);
        assertEquals("cap_shape;dur=3,cap_identity;dur=0,cap_document;dur=0",
                response.getHeader(CapabilityServlet.SERVER_TIMING));
        assertEquals(1, response.getHeaders(CapabilityServlet.SERVER_TIMING).size());
    }

    @Test
    void absoluteClockReadingsNeverBecomeHeaderValues() {
        final var clock = new AtomicLong(Long.MIN_VALUE + MILLION);
        final var timings = new CapabilityTimings(clock::get);
        clock.addAndGet(3 * MILLION);
        assertEquals("cap_shape;dur=3,cap_identity;dur=0,cap_document;dur=0", header(timings));
    }

    private static String header(CapabilityTimings timings) {
        final var response = new org.apache.sling.servlethelpers.MockSlingHttpServletResponse();
        timings.writeTo(response);
        return response.getHeader(CapabilityServlet.SERVER_TIMING);
    }
}
