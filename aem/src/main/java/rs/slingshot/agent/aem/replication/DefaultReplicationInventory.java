// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.replication;

import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.AgentManager;
import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationQueue;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import rs.slingshot.agent.command.platform.ReplicationInventory;

/**
 * The platform's own replication agents, answering the five agent and queue commands.
 *
 * <p>An agent's transport address, user and password are never read here, not even to decide
 * what kind of transport it is: the kind is read from what the agent says it does. A flush and a
 * retry reach here only after the deployment's control gate has permitted them.</p>
 */
@Component(service = ReplicationInventory.class)
public final class DefaultReplicationInventory implements ReplicationInventory {

    /** What an identifier naming no agent is reported as. */
    private static final String AGENT_NOT_FOUND = "agent_not_found";

    /** What an identifier naming no entry in the agent's queue is reported as. */
    private static final String ENTRY_NOT_FOUND = "entry_not_found";

    /** What a flush whose caller believed a different count is reported as. */
    private static final String QUEUE_EXPECTATION_MISMATCH = "queue_expectation_mismatch";

    /** What a control the platform itself will not perform is reported as. */
    private static final String CONTROL_REJECTED = "platform_control_rejected";

    /** What an entry that has been tried and is still waiting is reported as having failed with. */
    private static final String DELIVERY_FAILED = "delivery_failed";

    /** The serialization a static agent writes with, which is how it says it is one. */
    private static final String STATIC_SERIALIZATION = "static";

    /** The position of the entry a queue tries next, which is the only one a retry can move. */
    private static final int HEAD = 0;

    private final AgentManager agents;

    /**
     * Holds the inventory the platform's agent manager answers.
     *
     * @param agents the platform's own agent manager
     */
    @Activate
    public DefaultReplicationInventory(@Reference AgentManager agents) {
        this.agents = agents;
    }

    @Override
    public Outcome agents() {
        return new Agents(agents.getAgents().values().stream()
                .sorted(Comparator.comparing(com.day.cq.replication.Agent::getId))
                .map(DefaultReplicationInventory::agentOf)
                .toList());
    }

    @Override
    public Outcome inspect(String agentIdentifier) {
        return named(agentIdentifier)
                .<Outcome>map(agent -> new Inspected(agentOf(agent),
                        agent.getConfiguration().getRetryDelay()))
                .orElseGet(() -> unknown(agentIdentifier));
    }

    @Override
    public Outcome queue(String agentIdentifier) {
        return named(agentIdentifier)
                .<Outcome>map(DefaultReplicationInventory::queueOf)
                .orElseGet(() -> unknown(agentIdentifier));
    }

    @Override
    public Outcome flush(String agentIdentifier, long expectation) {
        final Optional<com.day.cq.replication.Agent> named = named(agentIdentifier);
        if (named.isEmpty()) {
            return unknown(agentIdentifier);
        }
        final ReplicationQueue queue = named.get().getQueue();
        final long held = entriesIn(queue).size();
        if (expectation != ANY_COUNT && expectation != held) {
            return new Refused(QUEUE_EXPECTATION_MISMATCH, agentIdentifier + " holds " + held
                    + " entries, not the " + expectation + " the caller expected, so nothing was"
                    + " removed");
        }
        if (held == 0) {
            return new Flushed(held);
        }
        try {
            queue.clear();
        } catch (final UnsupportedOperationException | IllegalStateException refused) {
            return new Refused(CONTROL_REJECTED, agentIdentifier + "'s queue is one this platform"
                    + " does not let be emptied from inside; its own distribution console does");
        }
        return new Flushed(held);
    }

    @Override
    public Outcome retry(String agentIdentifier, String entryIdentifier) {
        final Optional<com.day.cq.replication.Agent> named = named(agentIdentifier);
        if (named.isEmpty()) {
            return unknown(agentIdentifier);
        }
        final ReplicationQueue queue = named.get().getQueue();
        final Optional<ReplicationQueue.Entry> entry = entriesIn(queue).stream()
                .filter(held -> entryIdentifier.equals(held.getId()))
                .findFirst();
        if (entry.isEmpty()) {
            return new Refused(ENTRY_NOT_FOUND, entryIdentifier + " names no entry waiting in "
                    + agentIdentifier + "'s queue");
        }
        if (entry.get().getQueuePosition() != HEAD) {
            return new Resubmitted(Resubmission.DECLINED);
        }
        try {
            queue.forceRetry();
        } catch (final UnsupportedOperationException | IllegalStateException refused) {
            return new Refused(CONTROL_REJECTED, agentIdentifier + "'s queue is one this platform"
                    + " does not let be retried from inside; its own distribution console does");
        }
        return new Resubmitted(Resubmission.TAKEN);
    }

    private Optional<com.day.cq.replication.Agent> named(String agentIdentifier) {
        return Optional.ofNullable(agents.getAgents().get(agentIdentifier));
    }

    private static Refused unknown(String agentIdentifier) {
        return new Refused(AGENT_NOT_FOUND, agentIdentifier + " names no replication agent this"
                + " instance holds");
    }

    private static ReplicationInventory.Agent agentOf(com.day.cq.replication.Agent agent) {
        final AgentConfig configuration = agent.getConfiguration();
        final List<ReplicationQueue.Entry> entries = entriesIn(agent.getQueue());
        return new ReplicationInventory.Agent(agent.getId(),
                String.valueOf(Optional.ofNullable(configuration.getName()).orElse(agent.getId())),
                String.valueOf(Optional.ofNullable(configuration.getConfigPath()).orElse("")),
                kindOf(agent, configuration),
                agent.isEnabled() ? Switch.ENABLED : Switch.DISABLED, flowOf(entries),
                entries.size());
    }

    /**
     * What kind of thing an agent's transport does, read from what the agent says of itself.
     *
     * @param agent the agent
     * @param configuration its configuration
     * @return the kind
     */
    static TransportKind kindOf(com.day.cq.replication.Agent agent, AgentConfig configuration) {
        if (configuration.usedForReverseReplication()) {
            return TransportKind.REVERSE;
        }
        if (agent.isCacheInvalidator()) {
            return TransportKind.FLUSH;
        }
        if (STATIC_SERIALIZATION.equals(configuration.getSerializationType())) {
            return TransportKind.STATIC;
        }
        return TransportKind.PUBLISH;
    }

    /**
     * Whether a queue has stopped moving, read from what it holds.
     *
     * <p>A queue is stopped exactly when the entry it tries next has already been tried and is
     * still there, which is what the platform's own deprecated flag reported.</p>
     */
    private static Flow flowOf(List<ReplicationQueue.Entry> entries) {
        return entries.stream().findFirst()
                .filter(head -> head.getNumProcessed() > 0)
                .map(head -> Flow.BLOCKED)
                .orElse(Flow.MOVING);
    }

    /** What a queue holds, where an agent that keeps no queue holds nothing. */
    private static List<ReplicationQueue.Entry> entriesIn(ReplicationQueue queue) {
        return queue == null ? List.of() : queue.entries();
    }

    /** Holds flow and entry details from one read of the platform queue. */
    private static Queue queueOf(com.day.cq.replication.Agent agent) {
        final List<Entry> entries = entriesIn(agent.getQueue()).stream()
                .map(DefaultReplicationInventory::entryOf)
                .toList();
        final Flow flow = entries.stream().findFirst()
                .filter(head -> head.attemptCount() > 0)
                .map(head -> Flow.BLOCKED)
                .orElse(Flow.MOVING);
        return new Queue(flow, entries);
    }

    /** Holds the attempt count and its failure category from one read of the entry. */
    private static Entry entryOf(ReplicationQueue.Entry entry) {
        final long attempts = entry.getNumProcessed();
        final ReplicationAction action = entry.getAction();
        return new Entry(entry.getId(), actionOf(action.getType()), pathOf(action), attempts,
                attempts > 0 ? DELIVERY_FAILED : NEVER_FAILED);
    }

    /**
     * The content one entry is about, where an entry that carries none, such as a test, is about
     * the root.
     */
    private static String pathOf(ReplicationAction action) {
        final String path = action.getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /**
     * The client's word for one of the platform's replication actions.
     *
     * <p>A reverse replication brings content in and reads as an activation of it; an internal
     * poll carries no content and reads as a test.</p>
     *
     * @param type the platform's action
     * @return the client's action
     */
    static Action actionOf(ReplicationActionType type) {
        return switch (type) {
            case ACTIVATE, REVERSE -> Action.ACTIVATE;
            case DEACTIVATE -> Action.DEACTIVATE;
            case DELETE -> Action.DELETE;
            default -> Action.TEST;
        };
    }
}
