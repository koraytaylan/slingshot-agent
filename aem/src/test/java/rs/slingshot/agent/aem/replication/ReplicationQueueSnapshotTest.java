// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.AgentManager;
import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationQueue;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.platform.ReplicationInventory;

/** Proves that a moving platform queue is sampled once for each response. */
final class ReplicationQueueSnapshotTest {

    @Test
    @DisplayName("listed flow and count describe the same queue snapshot")
    void listingSamplesOnce() {
        final Platform platform = new Platform();
        final List<ReplicationInventory.Agent> listed = assertInstanceOf(
                ReplicationInventory.Agents.class, platform.inventory().agents()).agents();
        assertEquals(1, listed.size());
        assertEquals(ReplicationInventory.Flow.BLOCKED, listed.getFirst().flow());
        assertEquals(1, listed.getFirst().queuedEntryCount());
        platform.assertSampledOnce();
    }

    @Test
    @DisplayName("inspected flow and count describe the same queue snapshot")
    void inspectionSamplesOnce() {
        final Platform platform = new Platform();
        final ReplicationInventory.Inspected inspected = assertInstanceOf(
                ReplicationInventory.Inspected.class, platform.inventory().inspect("synthetic"));
        assertEquals(ReplicationInventory.Flow.BLOCKED, inspected.agent().flow());
        assertEquals(1, inspected.agent().queuedEntryCount());
        platform.assertSampledOnce();
    }

    @Test
    @DisplayName("queue flow and returned entries describe the same queue snapshot")
    void queueSamplesOnce() {
        final Platform platform = new Platform();
        final ReplicationInventory.Queue queue = assertInstanceOf(ReplicationInventory.Queue.class,
                platform.inventory().queue("synthetic"));
        assertEquals(ReplicationInventory.Flow.BLOCKED, queue.flow());
        assertEquals(List.of(new ReplicationInventory.Entry("synthetic-entry",
                ReplicationInventory.Action.ACTIVATE, "/content/synthetic", 1,
                "delivery_failed")), queue.entries());
        platform.assertSampledOnce();
    }

    @Test
    @DisplayName("changing attempts cannot disagree with queue flow or failure category")
    void attemptsAreSampledOnce() {
        final Platform platform = new Platform(true);
        final ReplicationInventory.Queue queue = assertInstanceOf(ReplicationInventory.Queue.class,
                platform.inventory().queue("synthetic"));
        assertEquals(ReplicationInventory.Flow.BLOCKED, queue.flow());
        assertEquals(1, queue.entries().getFirst().attemptCount());
        assertEquals("delivery_failed", queue.entries().getFirst().lastFailureCategory());
        assertEquals(1, platform.attemptReads);
        platform.assertSampledOnce();
    }

    /** A queue drains between reads; every unplanned platform call refuses the test. */
    private static final class Platform {

        private int queueReads;
        private int entryReads;
        private int attemptReads;
        private final boolean changingAttempts;

        Platform() {
            this(false);
        }

        Platform(boolean changingAttempts) {
            this.changingAttempts = changingAttempts;
        }

        DefaultReplicationInventory inventory() {
            final ReplicationQueue.Entry entry = proxy(ReplicationQueue.Entry.class,
                    (method, arguments) -> switch (method) {
                        case "getId" -> "synthetic-entry";
                        case "getAction" -> new ReplicationAction(ReplicationActionType.ACTIVATE,
                                "/content/synthetic");
                        case "getNumProcessed" -> {
                            attemptReads++;
                            yield changingAttempts && attemptReads > 1 ? 0 : 1;
                        }
                        default -> throw new UnsupportedOperationException(method);
                    });
            final ReplicationQueue queue = proxy(ReplicationQueue.class, (method, arguments) -> {
                if (!"entries".equals(method)) {
                    throw new UnsupportedOperationException(method);
                }
                entryReads++;
                return entryReads == 1 ? List.of(entry) : List.of();
            });
            final AgentConfig configuration = proxy(AgentConfig.class, (method, arguments) ->
                    switch (method) {
                        case "getName" -> "Synthetic";
                        case "getConfigPath" -> "/etc/synthetic";
                        case "getSerializationType" -> "durbo";
                        case "usedForReverseReplication" -> false;
                        case "getRetryDelay" -> 1L;
                        default -> throw new UnsupportedOperationException(method);
                    });
            final Agent agent = proxy(Agent.class, (method, arguments) -> switch (method) {
                case "getId" -> "synthetic";
                case "isEnabled" -> true;
                case "isCacheInvalidator" -> false;
                case "getConfiguration" -> configuration;
                case "getQueue" -> {
                    queueReads++;
                    yield queue;
                }
                default -> throw new UnsupportedOperationException(method);
            });
            final AgentManager manager = proxy(AgentManager.class, (method, arguments) -> {
                if (!"getAgents".equals(method)) {
                    throw new UnsupportedOperationException(method);
                }
                return Map.of("synthetic", agent);
            });
            return new DefaultReplicationInventory(manager);
        }

        void assertSampledOnce() {
            assertEquals(1, queueReads, "one response acquired more than one platform queue");
            assertEquals(1, entryReads, "one response acquired more than one entry snapshot");
        }
    }

    /** Answers one scripted method by name. */
    @FunctionalInterface
    private interface Answering {

        /**
         * The answer to one call.
         *
         * @param method the method's name
         * @param arguments what it was called with
         * @return the answer
         */
        Object answer(String method, Object... arguments);
    }

    private static <T> T proxy(Class<T> type, Answering answering) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type}, (proxy, method, arguments) ->
                        answering.answer(method.getName(), arguments)));
    }
}
