// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock a stream runs on when nobody is advancing one for it, and the threads it runs from.
 *
 * <p>Small things, and each of them is a place where a stream stops being this product's decision:
 * a clock nobody can move is a suite that has to sleep, and a thread pool that is somebody else's
 * is capacity this agent is spending on their behalf.</p>
 */
final class DefaultStreamTickerTest {

    @Test
    @DisplayName("the clock a stream runs on is this instance's own, and a pause passes time")
    void theclockAstreamRunsOnIsThisInstancesOwn() {
        final StreamTicker ticker = new DefaultStreamTicker();
        final long before = System.currentTimeMillis();
        final long said = ticker.milliseconds();
        assertTrue(said >= before && said <= System.currentTimeMillis(),
                "a stream is running on a clock that is not this instance's");
        final long paused = ticker.milliseconds();
        ticker.pause(1);
        assertTrue(ticker.milliseconds() >= paused, "time went backwards over a pause");
        ticker.pause(-1);
    }

    @Test
    @DisplayName("an interruption ends a pause and leaves the flag where the instance set it")
    void aninterruptionEndsApauseAndLeavesTheFlag() throws InterruptedException {
        final AtomicReference<String> held = new AtomicReference<>("");
        final Thread waiting = new Thread(() -> {
            Thread.currentThread().interrupt();
            new DefaultStreamTicker().pause(THE_REST_OF_THE_SESSION);
            held.set(Thread.currentThread().isInterrupted() ? "kept" : "swallowed");
        });
        waiting.start();
        waiting.join(TimeUnit.SECONDS.toMillis(WHILE_A_SUITE_WAITS));
        assertEquals("kept", held.get(),
                "an interruption was swallowed, so an instance taking its thread back would be"
                        + " waited through");
    }

    /** A pause nothing would ever finish waiting through. */
    private static final long THE_REST_OF_THE_SESSION = 600000;

    /** How long a suite waits for a thread it started. */
    private static final long WHILE_A_SUITE_WAITS = 30;

    @Test
    @DisplayName("a stream's thread is this bundle's own, named for what it is, and a daemon")
    void astreamsThreadIsThisBundlesOwn() {
        final AtomicReference<Thread> ran = new AtomicReference<>();
        try (ExecutorService pool = StreamExecutor.bounded()) {
            pool.execute(() -> ran.set(Thread.currentThread()));
        }
        assertTrue(ran.get() != null, "nothing ran on this bundle's own pool");
        assertTrue(ran.get().getName().startsWith(StreamExecutor.THREAD_NAME),
                "a stream's thread is named " + ran.get().getName()
                        + ", which tells an operator reading a dump nothing about whose it is");
        assertTrue(ran.get().isDaemon(),
                "a stream would keep an instance from stopping when it decided to");
    }
}
