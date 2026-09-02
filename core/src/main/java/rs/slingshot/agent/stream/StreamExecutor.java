// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The threads a deployment's streams are written from, which are this bundle's own.
 *
 * <p>Not the platform's shared scheduler. That scheduler exists for periodic work, every other
 * feature on the instance is also using it, and one entry per open stream is this agent spending
 * somebody else's capacity — an agent that saturated it would take out things nobody connected to
 * this product.</p>
 *
 * <p>Bounded by the same number that bounds how many streams this instance admits, so the pool
 * cannot grow with subscribers: past that number there is no admission, and therefore nothing to
 * hand to a thread. One pool per bundle rather than one per component, because the bound is about
 * how much of somebody's instance this agent occupies and two pools would be twice that.</p>
 */
public final class StreamExecutor {

    /** What a thread writing a stream is called, so an operator reading a dump knows whose it is. */
    public static final String THREAD_NAME = "slingshot-agent-event-stream";

    /** How many threads a build that cannot read its own contract will write streams from. */
    public static final int WITHOUT_A_CONTRACT = 1;

    /**
     * The pool this bundle is writing streams from at the moment.
     *
     * <p>Replaced rather than emptied when a component goes, so there is never a moment where this
     * bundle has no pool: a reference that could be absent is a case every caller would have to
     * handle to reach the one that is always true.</p>
     */
    private static final AtomicReference<ExecutorService> HELD = new AtomicReference<>(bounded());

    private StreamExecutor() {
    }

    /**
     * The pool a stream is written from.
     *
     * @return the pool
     */
    public static ExecutorService open() {
        return HELD.get();
    }

    /**
     * Gives back the threads this bundle was holding, and stands a fresh pool in their place.
     *
     * <p>A pool that outlived its bundle would be threads nobody can reach running code nobody can
     * replace. The replacement costs nothing until somebody opens a stream, because a fixed pool
     * starts no thread until there is something for one to do.</p>
     */
    public static void closed() {
        HELD.getAndSet(bounded()).shutdownNow();
    }

    /**
     * A pool bounded by what a contract declares, for a caller that has already read one.
     *
     * @param contract the authenticated contract, which declares how many streams there may be
     * @return the pool
     */
    public static ExecutorService bounded(AgentContract contract) {
        return Executors.newFixedThreadPool(
                (int) Math.max(WITHOUT_A_CONTRACT,
                        contract.value(ContractLimit.MAXIMUM_CONCURRENT_EVENT_STREAMS)),
                factory());
    }

    /**
     * A pool bounded by the contract this build carries.
     *
     * <p>A build that cannot read its own contract writes from one thread rather than from none:
     * the bound it cannot read is the one thing it must not guess upward.</p>
     *
     * @return the pool
     */
    public static ExecutorService bounded() {
        final AgentContract.Outcome loaded = AgentContract.load();
        return loaded instanceof final AgentContract.Loaded held
                ? bounded(held.contract())
                : Executors.newFixedThreadPool(WITHOUT_A_CONTRACT, factory());
    }

    private static ThreadFactory factory() {
        final AtomicLong numbered = new AtomicLong();
        return runnable -> {
            final Thread thread = new Thread(runnable,
                    THREAD_NAME + "-" + numbered.incrementAndGet());
            // A stream is not work an instance owes anybody at shutdown: a daemon thread lets a
            // container stop when it decides to rather than when the last subscriber leaves.
            thread.setDaemon(true);
            return thread;
        };
    }
}
