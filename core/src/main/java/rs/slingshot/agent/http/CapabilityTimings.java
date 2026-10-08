// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.apache.sling.api.SlingHttpServletResponse;
import rs.slingshot.agent.stream.DefaultStreamTicker;

/**
 * Numeric durations owned by one authenticated capability answer.
 *
 * <p>Only three fixed metric names and nonnegative millisecond durations are emitted. The
 * measurement begins inside the servlet's answer method, after the platform has resolved the
 * request. Platform authentication, the base servlet's route check, response writing and resolver
 * cleanup are outside these measurements. Nothing supplied by a caller becomes a metric.</p>
 */
final class CapabilityTimings {

    private static final long NANOSECONDS_IN_A_MILLISECOND = 1_000_000;
    private static final int PHASE_COUNT = Phase.values().length;
    private static final int HEADER_CHARACTER_CAPACITY = 99;

    private final LongSupplier source;
    private final long[] durations = new long[PHASE_COUNT];
    private final AtomicLong previousReading;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SHAPE);

    /** Starts one answer's measurement against the product's existing monotonic clock seam. */
    CapabilityTimings() {
        this(DefaultStreamTicker::monotonicNanoseconds);
    }

    /**
     * Starts one independently measured answer against a monotonic nanosecond source.
     *
     * @param source the duration source, whose absolute readings are never emitted
     */
    CapabilityTimings(LongSupplier source) {
        this.source = source;
        previousReading = new AtomicLong(source.getAsLong());
    }

    /** Ends request-shape checking before inspecting the identity the platform established. */
    void authenticating() {
        advance(Phase.IDENTITY);
    }

    /** Ends established-identity checking before authenticating metadata and building the document. */
    void rendering() {
        advance(Phase.DOCUMENT);
    }

    /**
     * Samples the current phase and prepares exactly one bounded numeric diagnostic header.
     *
     * <p>Only the authenticated success path calls this method. A phase never entered remains
     * zero. Repeated observations count only the new interval and retain submillisecond remainders.
     * Durations use integer milliseconds, so each phase's submillisecond remainder is truncated in
     * the header. The fixed grammar carries no request, document field or repository identifier.</p>
     *
     * @param response the authenticated success response being prepared
     */
    void writeTo(SlingHttpServletResponse response) {
        advance(phase.get());
        final long shapeMilliseconds = durations[Phase.SHAPE.ordinal()] / NANOSECONDS_IN_A_MILLISECOND;
        final long identityMilliseconds =
                durations[Phase.IDENTITY.ordinal()] / NANOSECONDS_IN_A_MILLISECOND;
        final long documentMilliseconds =
                durations[Phase.DOCUMENT.ordinal()] / NANOSECONDS_IN_A_MILLISECOND;
        final StringBuilder header = new StringBuilder(HEADER_CHARACTER_CAPACITY)
                .append("cap_shape;dur=").append(shapeMilliseconds)
                .append(",cap_identity;dur=").append(identityMilliseconds)
                .append(",cap_document;dur=").append(documentMilliseconds);
        response.setHeader(CapabilityServlet.SERVER_TIMING, header.toString());
    }

    private void advance(Phase next) {
        final long reading = source.getAsLong();
        // Signed subtraction retains short durations across a monotonic-counter wrap. A backwards
        // test reading contributes nothing and does not move the last accepted observation.
        final long duration = reading - previousReading.get();
        if (duration >= 0) {
            previousReading.set(reading);
            durations[phase.get().ordinal()] = added(durations[phase.get().ordinal()], duration);
        }
        phase.set(next);
    }

    private static long added(long held, long duration) {
        return held > Long.MAX_VALUE - duration ? Long.MAX_VALUE : held + duration;
    }

    private enum Phase {
        SHAPE,
        IDENTITY,
        DOCUMENT
    }
}
