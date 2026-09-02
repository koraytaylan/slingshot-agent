// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The five commands about the replication agents: three that read and two that act.
 *
 * <p>These are the commands somebody reaches for when a page has been published and is not there.
 * The three that read exist because that question has three separate answers — the agent is off,
 * the queue is stuck, or the entry keeps failing — and none of them is visible from the content.
 * They pass no control gate, because the deployment that will not let a queue be flushed is exactly
 * the one where an operator most needs to see why it is stuck.</p>
 *
 * <p>The flush carries an expected entry count, and that is the one guard in this plan that stops a
 * genuinely bad outcome: a queue that filled up between reading it and emptying it would otherwise
 * lose work the caller never saw. Naming the count they saw means the platform either finds what
 * they expected or refuses.</p>
 */
public final class AgentHandler implements CommandHandler {

    /** Which of the five this handler answers. */
    public enum Kind {
        /** Lists the agents. */
        LISTING,
        /** Reads one agent. */
        AGENT,
        /** Reads one agent's queue. */
        QUEUE,
        /** Empties one. */
        FLUSH,
        /** Offers one stuck entry again. */
        RETRY
    }

    private final AgentContract contract;
    private final Kind kind;
    private final ReplicationInventory inventory;
    private final PlatformControl control;

    /**
     * Holds one handler for one of the five.
     *
     * @param contract the authenticated contract
     * @param kind which of the five commands this handler answers
     * @param inventory what answers questions about the agents and acts on their queues
     * @param control what this deployment permits, asked before either action proceeds
     */
    public AgentHandler(AgentContract contract, Kind kind, ReplicationInventory inventory,
                        PlatformControl control) {
        this.contract = contract;
        this.kind = kind;
        this.inventory = inventory;
        this.control = control;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case LISTING -> listed(arguments, context);
            case AGENT -> inspected(arguments);
            case QUEUE -> queued(arguments, context);
            case FLUSH -> guarded(() -> flushed(arguments));
            case RETRY -> guarded(() -> retried(arguments));
        };
    }

    /** What one guarded command does once the deployment has permitted it. */
    @FunctionalInterface
    private interface Guarded {

        /**
         * Runs it.
         *
         * @return the answer
         */
        Answer run();
    }

    private Answer guarded(Guarded guarded) {
        final PlatformControl.Verdict verdict =
                control.permits(ControlCapability.REPLICATION_CONTROL);
        return verdict instanceof final PlatformControl.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : guarded.run();
    }

    private Answer listed(DocumentValue.Mapping arguments, CallerContext context) {
        final AgentCommands.WindowedOutcome asked =
                AgentCommands.windowed(arguments, AgentCommands.LISTING_MEMBERS, contract);
        if (asked instanceof final AgentCommands.WindowedRefused refused) {
            return new Failed(AgentCommands.AGENT_INVENTORY_FAILED,
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final ReplicationInventory.Outcome found = inventory.agents();
        if (found instanceof final ReplicationInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<ReplicationInventory.Agent> agents =
                ((ReplicationInventory.Agents) found).agents();
        return agents.size() > context.discovery().limit()
                ? new Failed(AgentCommands.DISCOVERY_BUDGET_EXCEEDED, agents.size() + " agents is"
                        + " more than the " + context.discovery().limit()
                        + " this caller may examine")
                : new Produced(AgentResults.agentsOf(
                        pageOf(agents, ((AgentCommands.Windowed) asked).window()),
                        AgentResults.NO_MORE_PAGES));
    }

    private Answer inspected(DocumentValue.Mapping arguments) {
        final AgentCommands.WindowedOutcome asked =
                AgentCommands.windowed(arguments, AgentCommands.AGENT_MEMBERS, contract);
        if (asked instanceof final AgentCommands.WindowedRefused refused) {
            return new Failed(AgentCommands.AGENT_NOT_FOUND,
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final ReplicationInventory.Outcome read =
                inventory.inspect(((AgentCommands.Windowed) asked).agentIdentifier());
        return read instanceof final ReplicationInventory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(AgentResults.agentOf((ReplicationInventory.Inspected) read));
    }

    private Answer queued(DocumentValue.Mapping arguments, CallerContext context) {
        final AgentCommands.WindowedOutcome asked =
                AgentCommands.windowed(arguments, AgentCommands.QUEUE_MEMBERS, contract);
        if (asked instanceof final AgentCommands.WindowedRefused refused) {
            return new Failed(AgentCommands.AGENT_NOT_FOUND,
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final AgentCommands.Windowed windowed = (AgentCommands.Windowed) asked;
        final ReplicationInventory.Outcome read = inventory.queue(windowed.agentIdentifier());
        if (read instanceof final ReplicationInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final ReplicationInventory.Queue queue = (ReplicationInventory.Queue) read;
        return queue.entries().size() > context.discovery().limit()
                ? new Failed(AgentCommands.DISCOVERY_BUDGET_EXCEEDED, queue.entries().size()
                        + " entries is more than the " + context.discovery().limit()
                        + " this caller may examine")
                : new Produced(AgentResults.queueOf(queue.flow(),
                        pageOf(queue.entries(), windowed.window()), AgentResults.NO_MORE_PAGES));
    }

    /**
     * The window's worth of entries.
     *
     * @param entries every entry the platform holds, in its own order
     * @param window which page is wanted
     * @param <Entry> what kind of entry this is
     * @return the entries that page carries
     */
    public static <Entry> List<Entry> pageOf(List<Entry> entries, ResultWindow window) {
        if (!(window instanceof final ResultWindow.Initial initial)) {
            return entries;
        }
        return entries.stream().skip(initial.offset()).limit(initial.limit()).toList();
    }

    private Answer flushed(DocumentValue.Mapping arguments) {
        final AgentCommands.FlushOutcome asked = AgentCommands.flush(arguments, contract);
        if (asked instanceof final AgentCommands.FlushRefused refused) {
            return new Failed(categoryFor(refused.refusal().refusal()),
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final AgentCommands.Flush flush = (AgentCommands.Flush) asked;
        final ReplicationInventory.Outcome emptied =
                inventory.flush(flush.agentIdentifier(), flush.expectation());
        return emptied instanceof final ReplicationInventory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(AgentResults.flushedOf(flush.agentIdentifier(),
                        ((ReplicationInventory.Flushed) emptied).removedEntryCount()));
    }

    private Answer retried(DocumentValue.Mapping arguments) {
        final AgentCommands.RetryOutcome asked = AgentCommands.retry(arguments, contract);
        if (asked instanceof final AgentCommands.RetryRefused refused) {
            return new Failed(retryCategoryFor(refused.refusal().refusal()),
                    refused.refusal().refusal() + ": " + refused.refusal().detail());
        }
        final AgentCommands.Retry retry = (AgentCommands.Retry) asked;
        final ReplicationInventory.Outcome offered =
                inventory.retry(retry.agentIdentifier(), retry.entryIdentifier());
        return offered instanceof final ReplicationInventory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(AgentResults.retriedOf(retry.agentIdentifier(),
                        retry.entryIdentifier(),
                        ((ReplicationInventory.Resubmitted) offered).resubmission()));
    }

    /**
     * Which declared category one flush refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(AgentCommands.Refusal refusal) {
        return switch (refusal) {
            case EXPECTATION_REJECTED -> AgentCommands.QUEUE_EXPECTATION_MISMATCH;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, ENTRY_ABSENT, MEMBER_UNKNOWN, IDENTIFIER_REJECTED,
                    WINDOW_REFUSED -> AgentCommands.AGENT_NOT_FOUND;
        };
    }

    /**
     * Which declared category one retry refusal is reported under.
     *
     * <p>A missing entry is its own category rather than the agent's, because the two send an
     * operator to different places: one of them typed the wrong agent, and the other is looking at
     * a queue whose entry has already gone.</p>
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String retryCategoryFor(AgentCommands.Refusal refusal) {
        return refusal == AgentCommands.Refusal.ENTRY_ABSENT
                ? AgentCommands.ENTRY_NOT_FOUND : AgentCommands.AGENT_NOT_FOUND;
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case LISTING -> AgentCommands.listingCategories();
            case AGENT -> AgentCommands.agentCategories();
            case QUEUE -> AgentCommands.queueCategories();
            case FLUSH -> AgentCommands.flushCategories();
            case RETRY -> AgentCommands.retryCategories();
        };
    }
}
