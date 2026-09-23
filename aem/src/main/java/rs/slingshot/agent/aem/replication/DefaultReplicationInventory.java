// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.replication;

import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.AgentManager;
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
                .<Outcome>map(agent -> new Queue(flowOf(agent.getQueue()),
                        entriesOf(agent.getQueue())))
                .orElseGet(() -> unknown(agentIdentifier));
    }

    @Override
    public Outcome flush(String agentIdentifier, long expectation) {
        final Optional<com.day.cq.replication.Agent> named = named(agentIdentifier);
        if (named.isEmpty()) {
            return unknown(agentIdentifier);
        }
        final ReplicationQueue queue = named.get().getQueue();
        final long held = queue.entries().size();
        if (expectation != ANY_COUNT && expectation != held) {
            return new Refused(QUEUE_EXPECTATION_MISMATCH, agentIdentifier + " holds " + held
                    + " entries, not the " + expectation + " the caller expected, so nothing was"
                    + " removed");
        }
        queue.clear();
        return new Flushed(held);
    }

    @Override
    public Outcome retry(String agentIdentifier, String entryIdentifier) {
        final Optional<com.day.cq.replication.Agent> named = named(agentIdentifier);
        if (named.isEmpty()) {
            return unknown(agentIdentifier);
        }
        final ReplicationQueue queue = named.get().getQueue();
        final Optional<ReplicationQueue.Entry> entry = queue.entries().stream()
                .filter(held -> entryIdentifier.equals(held.getId()))
                .findFirst();
        if (entry.isEmpty()) {
            return new Refused(ENTRY_NOT_FOUND, entryIdentifier + " names no entry waiting in "
                    + agentIdentifier + "'s queue");
        }
        if (entry.get().getQueuePosition() != HEAD) {
            return new Resubmitted(Resubmission.DECLINED);
        }
        queue.forceRetry();
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
        final ReplicationQueue queue = agent.getQueue();
        return new ReplicationInventory.Agent(agent.getId(),
                String.valueOf(Optional.ofNullable(configuration.getName()).orElse(agent.getId())),
                String.valueOf(Optional.ofNullable(configuration.getConfigPath()).orElse("")),
                kindOf(agent, configuration),
                agent.isEnabled() ? Switch.ENABLED : Switch.DISABLED, flowOf(queue),
                queue == null ? 0 : queue.entries().size());
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
    private static Flow flowOf(ReplicationQueue queue) {
        if (queue == null) {
            return Flow.MOVING;
        }
        return queue.entries().stream().findFirst()
                .filter(head -> head.getNumProcessed() > 0)
                .map(head -> Flow.BLOCKED)
                .orElse(Flow.MOVING);
    }

    private static List<Entry> entriesOf(ReplicationQueue queue) {
        if (queue == null) {
            return List.of();
        }
        return queue.entries().stream()
                .map(entry -> new Entry(entry.getId(), actionOf(entry.getAction().getType()),
                        String.valueOf(entry.getAction().getPath()), entry.getNumProcessed(),
                        entry.getNumProcessed() > 0 ? DELIVERY_FAILED : NEVER_FAILED))
                .toList();
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
