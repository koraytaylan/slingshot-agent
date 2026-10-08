// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.apache.sling.api.SlingHttpServletResponse;
import rs.slingshot.agent.stream.DefaultStreamTicker;

/**
 * Numeric durations owned by one authenticated submission request.
 *
 * <p>The only output is three fixed metric names and nonnegative millisecond durations. No
 * request, repository value or operation identifier is retained. These measurements begin after
 * authorization and bounded body intake and end before response writing and request cleanup.</p>
 */
final class SubmissionTimings {

    private static final long NANOSECONDS_IN_A_MILLISECOND = 1_000_000;
    private static final int PHASE_COUNT = Phase.values().length;
    private static final int HEADER_CHARACTER_CAPACITY = 99;

    private final LongSupplier source;
    private final long[] durations = new long[PHASE_COUNT];
    private final AtomicLong previousReading;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.ADMISSION);

    /** Starts a request's measurement against the product's existing monotonic clock seam. */
    SubmissionTimings() {
        this(DefaultStreamTicker::monotonicNanoseconds);
    }

    /**
     * Starts an independently measured request against a monotonic nanosecond source.
     *
     * @param source the duration source, whose absolute readings are never emitted
     */
    SubmissionTimings(LongSupplier source) {
        this.source = source;
        previousReading = new AtomicLong(source.getAsLong());
    }

    /** Ends admission immediately before the command runtime is invoked. */
    void executing() {
        advance(Phase.EXECUTION);
    }

    /** Ends command execution before its outcome is journalled and terminal state is published. */
    void persisting() {
        advance(Phase.PERSISTENCE);
    }

    /**
     * Samples the current phase and emits the fixed, bounded numeric diagnostic header.
     *
     * <p>A phase that this request never entered has duration zero. Repeated observations charge
     * only the time since the previous observation. Integer milliseconds truncate each phase's
     * submillisecond remainder. The emission is composed here from fixed literals and primitive
     * counters; no string supplied by a caller can enter the header.</p>
     *
     * @param response the accepted response being prepared
     */
    void writeTo(SlingHttpServletResponse response) {
        advance(phase.get());
        final long admissionMilliseconds =
                durations[Phase.ADMISSION.ordinal()] / NANOSECONDS_IN_A_MILLISECOND;
        final long executionMilliseconds =
                durations[Phase.EXECUTION.ordinal()] / NANOSECONDS_IN_A_MILLISECOND;
        final long persistenceMilliseconds =
                durations[Phase.PERSISTENCE.ordinal()] / NANOSECONDS_IN_A_MILLISECOND;
        final StringBuilder header = new StringBuilder(HEADER_CHARACTER_CAPACITY)
                .append("admission;dur=").append(admissionMilliseconds)
                .append(",execution;dur=").append(executionMilliseconds)
                .append(",persistence;dur=").append(persistenceMilliseconds);
        response.setHeader(SubmitServlet.SERVER_TIMING, header.toString());
    }

    private void advance(Phase next) {
        final long reading = source.getAsLong();
        // Subtraction preserves a bounded duration across a signed monotonic-counter wrap. A
        // backwards test source contributes nothing and does not move the last valid reading.
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
        ADMISSION,
        EXECUTION,
        PERSISTENCE
    }
}
