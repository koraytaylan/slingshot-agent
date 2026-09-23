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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.platform.ReplicationInventory;

/**
 * The replication agent commands' adapter, driven over agents the suite scripts.
 *
 * <p>The agent manager, its agents and their queues are interfaces the platform implements, so
 * what is proved here is the translation: that no transport address is read, how an agent's kind
 * and state read in the client's words, that a flush with a wrong expectation removes nothing, and
 * that only the entry a queue tries next can be retried.</p>
 */
final class DefaultReplicationInventoryTest {

    @Test
    @DisplayName("agents are listed in identifier order, each with its kind, switch and flow")
    void agentsAreListedInOrder() {
        final Platform platform = new Platform();
        final List<ReplicationInventory.Agent> listed = assertInstanceOf(
                ReplicationInventory.Agents.class, platform.inventory().agents()).agents();
        assertEquals(List.of(
                new ReplicationInventory.Agent("flush", "Dispatcher Flush", "/etc/flush",
                        ReplicationInventory.TransportKind.FLUSH,
                        ReplicationInventory.Switch.DISABLED, ReplicationInventory.Flow.MOVING, 0),
                new ReplicationInventory.Agent("publish", "Publish", "/etc/publish",
                        ReplicationInventory.TransportKind.PUBLISH,
                        ReplicationInventory.Switch.ENABLED, ReplicationInventory.Flow.BLOCKED, 3),
                new ReplicationInventory.Agent("reverse", "reverse", "",
                        ReplicationInventory.TransportKind.REVERSE,
                        ReplicationInventory.Switch.ENABLED, ReplicationInventory.Flow.MOVING, 0),
                new ReplicationInventory.Agent("static", "Static", "/etc/static",
                        ReplicationInventory.TransportKind.STATIC,
                        ReplicationInventory.Switch.ENABLED, ReplicationInventory.Flow.MOVING, 0)),
                listed);
        assertEquals(List.of(), platform.transportRead, "a transport address was read");
    }

    @Test
    @DisplayName("an agent and its queue are read in full, and an unknown one is not found")
    void anAgentAndItsQueueAreRead() {
        final Platform platform = new Platform();
        final ReplicationInventory.Inspected inspected = assertInstanceOf(
                ReplicationInventory.Inspected.class, platform.inventory().inspect("publish"));
        assertEquals(RETRY_DELAY, inspected.retryDelayMilliseconds());
        final ReplicationInventory.Queue queue = assertInstanceOf(ReplicationInventory.Queue.class,
                platform.inventory().queue("publish"));
        assertEquals(List.of(
                new ReplicationInventory.Entry("head", ReplicationInventory.Action.ACTIVATE,
                        "/content/a", 3, "delivery_failed"),
                new ReplicationInventory.Entry("tail", ReplicationInventory.Action.DELETE,
                        "/content/b", 0, ReplicationInventory.NEVER_FAILED),
                new ReplicationInventory.Entry("probe", ReplicationInventory.Action.TEST, "/", 0,
                        ReplicationInventory.NEVER_FAILED)), queue.entries());
        assertEquals(List.of(), assertInstanceOf(ReplicationInventory.Queue.class,
                platform.inventory().queue("flush")).entries());
        for (final ReplicationInventory.Outcome refused : List.of(
                platform.inventory().inspect("none"), platform.inventory().queue("none"),
                platform.inventory().flush("none", ReplicationInventory.ANY_COUNT),
                platform.inventory().retry("none", "head"))) {
            assertEquals("agent_not_found", assertInstanceOf(ReplicationInventory.Refused.class,
                    refused).category());
        }
    }

    @Test
    @DisplayName("a flush removes nothing when the caller expected another count")
    void aflushChecksItsExpectation() {
        final Platform platform = new Platform();
        assertEquals("queue_expectation_mismatch", assertInstanceOf(
                ReplicationInventory.Refused.class,
                platform.inventory().flush("publish", 1)).category());
        assertEquals(List.of(), platform.controls);
        assertEquals(0, assertInstanceOf(ReplicationInventory.Flushed.class,
                platform.inventory().flush("flush", 0)).removedEntryCount(),
                "an agent that keeps no queue has nothing to flush");
        assertEquals(3, assertInstanceOf(ReplicationInventory.Flushed.class,
                platform.inventory().flush("publish", 3)).removedEntryCount());
        assertEquals(0, assertInstanceOf(ReplicationInventory.Flushed.class,
                platform.inventory().flush("static", ReplicationInventory.ANY_COUNT))
                .removedEntryCount());
        assertEquals(List.of("clear:publish", "clear:static"), platform.controls);
    }

    @Test
    @DisplayName("only the entry a queue tries next is retried, and an unknown one is not found")
    void onlyTheHeadIsRetried() {
        final Platform platform = new Platform();
        assertEquals(ReplicationInventory.Resubmission.TAKEN, assertInstanceOf(
                ReplicationInventory.Resubmitted.class,
                platform.inventory().retry("publish", "head")).resubmission());
        assertEquals(ReplicationInventory.Resubmission.DECLINED, assertInstanceOf(
                ReplicationInventory.Resubmitted.class,
                platform.inventory().retry("publish", "tail")).resubmission());
        assertEquals("entry_not_found", assertInstanceOf(ReplicationInventory.Refused.class,
                platform.inventory().retry("publish", "none")).category());
        assertEquals("entry_not_found", assertInstanceOf(ReplicationInventory.Refused.class,
                platform.inventory().retry("flush", "head")).category(),
                "an agent that keeps no queue holds no entry");
        assertEquals(List.of("retry:publish"), platform.controls);
    }

    @Test
    @DisplayName("every platform action reads as one of the client's")
    void everyActionReadsAsTheClients() {
        final Map<ReplicationActionType, ReplicationInventory.Action> expected =
                new LinkedHashMap<>();
        expected.put(ReplicationActionType.ACTIVATE, ReplicationInventory.Action.ACTIVATE);
        expected.put(ReplicationActionType.valueOf("REVERSE"), ReplicationInventory.Action.ACTIVATE);
        expected.put(ReplicationActionType.DEACTIVATE, ReplicationInventory.Action.DEACTIVATE);
        expected.put(ReplicationActionType.DELETE, ReplicationInventory.Action.DELETE);
        expected.put(ReplicationActionType.TEST, ReplicationInventory.Action.TEST);
        expected.put(ReplicationActionType.INTERNAL_POLL, ReplicationInventory.Action.TEST);
        expected.forEach((platform, client) -> assertEquals(client,
                DefaultReplicationInventory.actionOf(platform), platform.name()));
        assertEquals(java.util.Set.of(ReplicationActionType.values()), expected.keySet());
    }

    /** How long the publish agent waits before trying a failed entry again. */
    private static final long RETRY_DELAY = 60_000;

    /** An agent manager holding four agents, recording what was controlled and what was read. */
    private static final class Platform {

        private final List<String> controls = new ArrayList<>();
        private final List<String> transportRead = new ArrayList<>();
        private final Map<String, Agent> agents = new LinkedHashMap<>();

        Platform() {
            agents.put("static", agent("static", "Static", "/etc/static", "static", false, false,
                    true, queue("static", false)));
            agents.put("publish", agent("publish", "Publish", "/etc/publish", "durbo", false,
                    false, true, queue("publish", true,
                            entry("head", ReplicationActionType.ACTIVATE, "/content/a", 0, 3),
                            entry("tail", ReplicationActionType.DELETE, "/content/b", 1, 0),
                            entry("probe", ReplicationActionType.TEST, "", 2, 0))));
            agents.put("flush", agent("flush", "Dispatcher Flush", "/etc/flush", "flush", false,
                    true, false, null));
            agents.put("reverse", agent("reverse", null, null, "durbo", true, false, true,
                    queue("reverse", false)));
        }

        DefaultReplicationInventory inventory() {
            return new DefaultReplicationInventory(proxy(AgentManager.class,
                    (method, arguments) -> agents));
        }

        private Agent agent(String identifier, String name, String path, String serialization,
                            boolean reverse, boolean invalidator, boolean enabled,
                            ReplicationQueue queue) {
            final AgentConfig configuration = proxy(AgentConfig.class, (method, arguments) ->
                    switch (method) {
                        case "getName" -> name;
                        case "getConfigPath" -> path;
                        case "getSerializationType" -> serialization;
                        case "usedForReverseReplication" -> reverse;
                        case "getRetryDelay" -> RETRY_DELAY;
                        default -> {
                            transportRead.add(method);
                            throw new UnsupportedOperationException(method);
                        }
                    });
            return proxy(Agent.class, (method, arguments) -> switch (method) {
                case "getId" -> identifier;
                case "isEnabled" -> enabled;
                case "isCacheInvalidator" -> invalidator;
                case "getConfiguration" -> configuration;
                case "getQueue" -> queue;
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private ReplicationQueue queue(String agent, boolean blocked,
                                       ReplicationQueue.Entry... held) {
            final List<ReplicationQueue.Entry> entries = new ArrayList<>(List.of(held));
            return proxy(ReplicationQueue.class, (method, arguments) -> switch (method) {
                case "isBlocked" -> blocked;
                case "entries" -> List.copyOf(entries);
                case "clear" -> {
                    controls.add("clear:" + agent);
                    entries.clear();
                    yield null;
                }
                case "forceRetry" -> {
                    controls.add("retry:" + agent);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method);
            });
        }
    }

    private static ReplicationQueue.Entry entry(String identifier, ReplicationActionType type,
                                                String path, int position, int processed) {
        final ReplicationAction action = new ReplicationAction(type, path);
        return proxy(ReplicationQueue.Entry.class, (method, arguments) -> switch (method) {
            case "getId" -> identifier;
            case "getAction" -> action;
            case "getQueuePosition" -> position;
            case "getNumProcessed" -> processed;
            default -> throw new UnsupportedOperationException(method);
        });
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
