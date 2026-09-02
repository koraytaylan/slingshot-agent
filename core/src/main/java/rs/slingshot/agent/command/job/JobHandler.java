// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.job;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The four commands about the work the platform is holding: three listings and one cancellation.
 *
 * <p>The three that read pass through no gate. "What is not happening" is the question an operator
 * has on an environment they cannot change at all, and it is the one this surface answers best.</p>
 *
 * <p>Cancelling passes the gate, and it is the control with the least reversible effect on this
 * whole plan: a cancelled job is work that will now never happen, and nothing in the platform
 * remembers what it was going to do.</p>
 */
public final class JobHandler implements CommandHandler {

    /** Which of the four this handler answers. */
    public enum Kind {
        /** Lists the queues. */
        QUEUES,
        /** Finds jobs. */
        JOBS,
        /** Reads one job. */
        INSPECTION,
        /** Cancels one. */
        CANCELLATION
    }

    private final AgentContract contract;
    private final Kind kind;
    private final JobInventory inventory;
    private final PlatformControl control;

    /**
     * Holds one handler for one of the four.
     *
     * @param contract the authenticated contract
     * @param kind which of the four commands this handler answers
     * @param inventory what answers questions about the queues and cancels work in them
     * @param control what this deployment permits, asked before the cancellation proceeds
     */
    public JobHandler(AgentContract contract, Kind kind, JobInventory inventory,
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
            case QUEUES -> queues(arguments, context);
            case JOBS -> jobs(arguments, context);
            case INSPECTION -> inspected(arguments);
            case CANCELLATION -> cancelled(arguments);
        };
    }

    private Answer queues(DocumentValue.Mapping arguments, CallerContext context) {
        final JobCommands.QueueOutcome asked = JobCommands.queues(arguments, contract);
        if (asked instanceof final JobCommands.QueueRefused refused) {
            return new Failed(JobCommands.INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final JobInventory.Outcome found = inventory.queues();
        if (found instanceof final JobInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<JobInventory.Queue> queues = ((JobInventory.Queues) found).queues();
        return queues.size() > context.discovery().limit()
                ? new Failed(JobCommands.DISCOVERY_BUDGET_EXCEEDED, queues.size() + " queues is"
                        + " more than the " + context.discovery().limit()
                        + " this caller may examine")
                : new Produced(JobResults.queuesOf(
                        pageOf(queues, ((JobCommands.QueueWindow) asked).window()),
                        JobResults.NO_MORE_PAGES));
    }

    private Answer jobs(DocumentValue.Mapping arguments, CallerContext context) {
        final JobCommands.SearchOutcome asked = JobCommands.search(arguments, contract);
        if (asked instanceof final JobCommands.SearchRefused refused) {
            return new Failed(JobCommands.INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final JobCommands.Search search = (JobCommands.Search) asked;
        final JobInventory.Outcome found = inventory.jobs(search.topic(), search.states());
        if (found instanceof final JobInventory.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<JobInventory.Job> jobs = ((JobInventory.Jobs) found).jobs();
        return jobs.size() > context.discovery().limit()
                ? new Failed(JobCommands.DISCOVERY_BUDGET_EXCEEDED, jobs.size() + " jobs is more"
                        + " than the " + context.discovery().limit() + " this caller may examine")
                : new Produced(JobResults.jobsOf(pageOf(jobs, search.window()),
                        JobResults.NO_MORE_PAGES));
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

    private Answer inspected(DocumentValue.Mapping arguments) {
        final JobCommands.IdentifierOutcome asked = JobCommands.identifier(arguments, contract);
        if (asked instanceof final JobCommands.IdentifierRefused refused) {
            return new Failed(JobCommands.JOB_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        final JobInventory.Outcome read =
                inventory.inspect(((JobCommands.Identifier) asked).jobIdentifier());
        return read instanceof final JobInventory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(JobResults.detailOf(((JobInventory.Inspected) read).detail()));
    }

    private Answer cancelled(DocumentValue.Mapping arguments) {
        final PlatformControl.Verdict verdict = control.permits(ControlCapability.JOB_CONTROL);
        if (verdict instanceof final PlatformControl.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final JobCommands.IdentifierOutcome asked = JobCommands.identifier(arguments, contract);
        if (asked instanceof final JobCommands.IdentifierRefused refused) {
            return new Failed(JobCommands.JOB_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        final String identifier = ((JobCommands.Identifier) asked).jobIdentifier();
        final JobInventory.Outcome gone = inventory.cancel(identifier);
        return gone instanceof final JobInventory.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(JobResults.cancelledOf(identifier,
                        ((JobInventory.Cancelled) gone).observed()));
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case QUEUES, JOBS -> JobCommands.listingCategories();
            case INSPECTION -> JobCommands.inspectionCategories();
            case CANCELLATION -> JobCommands.cancellationCategories();
        };
    }
}
