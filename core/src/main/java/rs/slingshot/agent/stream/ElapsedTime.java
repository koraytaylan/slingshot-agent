// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.function.LongSupplier;

/** A local duration measured only against a monotonic source. */
public final class ElapsedTime {

    private final LongSupplier source;
    private final long started;

    private ElapsedTime(LongSupplier source) {
        this.source = source;
        this.started = source.getAsLong();
    }

    /**
     * Starts a duration against the runtime's monotonic clock.
     *
     * @return a duration independent of wall-clock corrections
     */
    public static ElapsedTime start() {
        return start(new DefaultStreamTicker()::elapsedMilliseconds);
    }

    /**
     * Starts a duration against an injected monotonic millisecond source.
     *
     * @param source the monotonic source, whose origin has no epoch meaning
     * @return a duration using only differences from that source
     */
    public static ElapsedTime start(LongSupplier source) {
        return new ElapsedTime(source);
    }

    /**
     * The nonnegative duration since this timer started.
     *
     * @return elapsed milliseconds
     */
    public long milliseconds() {
        return Math.max(0, source.getAsLong() - started);
    }
}
