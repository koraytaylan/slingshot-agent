// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

/**
 * The clock a stream runs on when it is running on an instance rather than in a suite.
 */
public final class DefaultStreamTicker implements StreamTicker {

    private static final long serialVersionUID = 1L;

    /** How the runtime's monotonic source is reduced to the unit every bound is stated in. */
    private static final long NANOSECONDS_IN_A_MILLISECOND = 1_000_000;

    /** Holds a ticker with nothing in it. */
    public DefaultStreamTicker() {
    }

    /**
     * What this side's clock says.
     *
     * @return the milliseconds since the epoch
     */
    @Override
    public long milliseconds() {
        return System.currentTimeMillis();
    }

    /**
     * How much time has passed, from the runtime's own monotonic source.
     *
     * <p>Nanoseconds reduced to milliseconds, because every bound this stream is held to is stated
     * in milliseconds and a comparison between two different units is the other way this goes
     * wrong.</p>
     *
     * @return a reading whose difference from an earlier one is the time between them
     */
    @Override
    public long elapsedMilliseconds() {
        return System.nanoTime() / NANOSECONDS_IN_A_MILLISECOND;
    }

    /**
     * Waits before looking again.
     *
     * <p>An interruption is the instance taking this thread back, which is an ending rather than
     * something to keep waiting through, so the flag is restored and the caller sees the pause is
     * over.</p>
     *
     * @param milliseconds how long to wait for
     */
    @Override
    public void pause(long milliseconds) {
        try {
            Thread.sleep(Math.max(0, milliseconds));
        } catch (final InterruptedException taken) {
            Thread.currentThread().interrupt();
        }
    }
}
