// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Durations have no dependency on an epoch or wall-clock correction. */
final class ElapsedTimeTest {

    private static final long BEFORE_ORIGIN = -10_000;
    private static final long ADVANCE = 5_000;

    @Test
    void aNegativeMonotonicOriginStillMeasuresTheDifference() {
        final AtomicLong monotonic = new AtomicLong(BEFORE_ORIGIN);
        final ElapsedTime elapsed = ElapsedTime.start(monotonic::get);
        assertEquals(0, elapsed.milliseconds());
        monotonic.addAndGet(ADVANCE);
        assertEquals(ADVANCE, elapsed.milliseconds());
        monotonic.addAndGet(ADVANCE);
        assertEquals(ADVANCE + ADVANCE, elapsed.milliseconds());
    }

    @Test
    void aDurationCanCrossTheSignedSourceBoundary() {
        final AtomicLong monotonic = new AtomicLong(Long.MAX_VALUE);
        final ElapsedTime elapsed = ElapsedTime.start(monotonic::get);
        monotonic.incrementAndGet();
        assertEquals(1, elapsed.milliseconds());
    }
}
