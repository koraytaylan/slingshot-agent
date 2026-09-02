// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.List;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.WorkflowService;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The six commands about workflows: two listings, one inspection, and three controls.
 *
 * <p>The payload check is what this class is really for. A workflow runs afterwards under the
 * platform's own identity, which is usually more privileged than the caller's, so starting one on
 * content the caller cannot change would be a way to have the platform make a change they were
 * refused. The check asks their own session whether they could write there, before the platform is
 * asked to do anything — and it is a check about writing rather than reading, because a workflow
 * whose steps only read the payload is indistinguishable from one whose steps rewrite it.</p>
 */
public final class WorkflowHandler implements CommandHandler {

    /** Which of the six this handler answers. */
    public enum Kind {
        /** Lists the models. */
        MODELS,
        /** Starts one. */
        START,
        /** Finds running instances. */
        INSTANCES,
        /** Reads one instance. */
        INSPECTION,
        /** Ends one. */
        TERMINATION,
        /** Holds one or lets it go. */
        SUSPENSION
    }

    private final AgentContract contract;
    private final Kind kind;
    private final WorkflowService workflows;
    private final PlatformControl control;

    /**
     * Holds one handler for one of the six.
     *
     * @param contract the authenticated contract
     * @param kind which of the six commands this handler answers
     * @param workflows what answers questions about workflows and starts or ends them
     * @param control what this deployment permits, asked before any of the three controls proceeds
     */
    public WorkflowHandler(AgentContract contract, Kind kind, WorkflowService workflows,
                           PlatformControl control) {
        this.contract = contract;
        this.kind = kind;
        this.workflows = workflows;
        this.control = control;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        return switch (kind) {
            case MODELS -> models(arguments, resolver, context);
            case INSTANCES -> instances(arguments, resolver, context);
            case INSPECTION -> inspected(arguments, resolver);
            case START -> guarded(() -> started(arguments, resolver));
            case TERMINATION -> guarded(() -> terminated(arguments, resolver));
            case SUSPENSION -> guarded(() -> suspended(arguments, resolver));
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
        final PlatformControl.Verdict verdict = control.permits(ControlCapability.WORKFLOW_CONTROL);
        return verdict instanceof final PlatformControl.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : guarded.run();
    }

    private Answer models(DocumentValue.Mapping arguments, ResourceResolver resolver,
                          CallerContext context) {
        final ListWorkflowModelsCommand.Outcome asked =
                ListWorkflowModelsCommand.of(arguments, contract);
        if (asked instanceof final ListWorkflowModelsCommand.Refused refused) {
            return new Failed(WorkflowHandlers.INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final ListWorkflowModelsCommand command =
                ((ListWorkflowModelsCommand.Held) asked).command();
        final WorkflowService.Outcome found =
                workflows.models(command.titlePrefix(), resolver);
        if (found instanceof final WorkflowService.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<WorkflowService.Model> models = ((WorkflowService.Models) found).models();
        return models.size() > context.discovery().limit()
                ? new Failed(WorkflowHandlers.DISCOVERY_BUDGET_EXCEEDED, models.size()
                        + " models is more than the " + context.discovery().limit()
                        + " this caller may examine")
                : new Produced(WorkflowResults.modelsOf(pageOf(models, command.window()),
                        WorkflowResults.NO_MORE_PAGES));
    }

    private Answer instances(DocumentValue.Mapping arguments, ResourceResolver resolver,
                             CallerContext context) {
        final FindWorkflowInstancesCommand.Outcome asked =
                FindWorkflowInstancesCommand.of(arguments, contract);
        if (asked instanceof final FindWorkflowInstancesCommand.Refused refused) {
            return new Failed(WorkflowHandlers.INVENTORY_FAILED,
                    refused.refusal() + ": " + refused.detail());
        }
        final FindWorkflowInstancesCommand command =
                ((FindWorkflowInstancesCommand.Held) asked).command();
        final WorkflowService.Outcome found = workflows.instances(
                new WorkflowService.InstanceQuery(command.modelIdentifier(),
                        command.payloadPrefix(), command.states()), resolver);
        if (found instanceof final WorkflowService.Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final List<WorkflowService.Instance> instances =
                ((WorkflowService.Instances) found).instances();
        return instances.size() > context.discovery().limit()
                ? new Failed(WorkflowHandlers.DISCOVERY_BUDGET_EXCEEDED, instances.size()
                        + " instances is more than the " + context.discovery().limit()
                        + " this caller may examine")
                : new Produced(WorkflowResults.instancesOf(pageOf(instances, command.window()),
                        WorkflowResults.NO_MORE_PAGES));
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

    private Answer inspected(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final WorkflowInstanceCommand.Outcome asked =
                WorkflowInstanceCommand.of(arguments, contract);
        if (asked instanceof final WorkflowInstanceCommand.Refused refused) {
            return new Failed(WorkflowHandlers.INSTANCE_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        final WorkflowService.Outcome read = workflows.inspect(
                ((WorkflowInstanceCommand.Held) asked).command().instanceIdentifier(), resolver);
        return read instanceof final WorkflowService.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(WorkflowResults.detailOf(
                        ((WorkflowService.Inspected) read).detail()));
    }

    private Answer started(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final StartWorkflowCommand.Outcome asked = StartWorkflowCommand.of(arguments, contract);
        if (asked instanceof final StartWorkflowCommand.Refused refused) {
            return new Failed(categoryFor(refused.refusal()),
                    refused.refusal() + ": " + refused.detail());
        }
        final StartWorkflowCommand command = ((StartWorkflowCommand.Held) asked).command();
        final String reachable = payloadRefusal(command.payloadPath(), resolver);
        if (!PAYLOAD_REACHABLE.equals(reachable)) {
            return new Failed(reachable, command.payloadPath() + " is not content this caller"
                    + " could change themselves. A workflow runs afterwards under the platform's"
                    + " own identity, so starting one here would be a way to make a change that"
                    + " was refused in the foreground.");
        }
        final WorkflowService.Outcome running = workflows.start(command.modelIdentifier(),
                command.payloadPath(), command.metadata(), resolver);
        return running instanceof final WorkflowService.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(WorkflowResults.startedOf(
                        ((WorkflowService.Started) running).instance()));
    }

    /** What the payload check says when the caller could change the payload themselves. */
    public static final String PAYLOAD_REACHABLE = "";

    /** The permission a caller needs on a payload before a workflow may be started on it. */
    private static final String WRITE_ACTIONS = Session.ACTION_SET_PROPERTY;

    /**
     * Whether this caller could change the payload themselves, and why not where they could not.
     *
     * <p>Asked about writing rather than about reading. A workflow whose steps only read its
     * payload is indistinguishable, from here, from one whose steps rewrite it — the model is
     * somebody else's and its steps can do anything the platform can. So the question is the
     * stronger one, and a caller who can read a page but not change it cannot start a workflow on
     * it.</p>
     *
     * <p>Asked of the repository rather than of a resource, because the repository is the thing
     * that actually holds the answer. A caller whose session cannot be obtained at all is refused:
     * no session means no permission was demonstrated, and this is not a check to be lenient
     * about.</p>
     *
     * @param payloadPath what the workflow would run on
     * @param resolver the caller's own session
     * @return {@link #PAYLOAD_REACHABLE}, or the category to refuse under
     */
    public static String payloadRefusal(String payloadPath, ResourceResolver resolver) {
        final Resource payload = resolver.getResource(payloadPath);
        if (payload == null) {
            return WorkflowHandlers.PAYLOAD_NOT_FOUND;
        }
        final Optional<Session> session = Optional.ofNullable(resolver.adaptTo(Session.class));
        if (session.isEmpty()) {
            return WorkflowHandlers.PAYLOAD_ACCESS_DENIED;
        }
        try {
            return session.orElseThrow().hasPermission(payloadPath, WRITE_ACTIONS)
                    ? PAYLOAD_REACHABLE : WorkflowHandlers.PAYLOAD_ACCESS_DENIED;
        } catch (final RepositoryException unreadable) {
            return WorkflowHandlers.PAYLOAD_ACCESS_DENIED;
        }
    }

    /**
     * Which declared category one start refusal is reported under.
     *
     * @param refusal why the argument was refused
     * @return the category the row declares for it
     */
    public static String categoryFor(StartWorkflowCommand.Refusal refusal) {
        return switch (refusal) {
            case METADATA_REJECTED, TEXT_TOO_LONG -> WorkflowHandlers.METADATA_REJECTED;
            case PAYLOAD_REJECTED -> WorkflowHandlers.PAYLOAD_NOT_FOUND;
            case NOT_A_DOCUMENT, MEMBER_ABSENT, MEMBER_UNKNOWN, MODEL_REJECTED ->
                    WorkflowHandlers.MODEL_NOT_FOUND;
        };
    }

    private Answer terminated(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final WorkflowInstanceCommand.Outcome asked =
                WorkflowInstanceCommand.of(arguments, contract);
        if (asked instanceof final WorkflowInstanceCommand.Refused refused) {
            return new Failed(WorkflowHandlers.INSTANCE_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        final String identifier =
                ((WorkflowInstanceCommand.Held) asked).command().instanceIdentifier();
        return moved(workflows.terminate(identifier, resolver), identifier);
    }

    private Answer suspended(DocumentValue.Mapping arguments, ResourceResolver resolver) {
        final WorkflowInstanceCommand.SuspensionOutcome asked =
                WorkflowInstanceCommand.suspension(arguments, contract);
        if (asked instanceof final WorkflowInstanceCommand.SuspensionRefused refused) {
            return new Failed(refused.refusal() == WorkflowInstanceCommand.Refusal.STATE_REJECTED
                    ? WorkflowHandlers.INSTANCE_NOT_SUSPENDABLE
                    : WorkflowHandlers.INSTANCE_NOT_FOUND,
                    refused.refusal() + ": " + refused.detail());
        }
        final WorkflowInstanceCommand.Suspension request =
                (WorkflowInstanceCommand.Suspension) asked;
        return moved(workflows.suspend(request.instanceIdentifier(), request.requested(), resolver),
                request.instanceIdentifier());
    }

    private static Answer moved(WorkflowService.Outcome outcome, String identifier) {
        return outcome instanceof final WorkflowService.Refused refused
                ? new Failed(refused.category(), refused.detail())
                : new Produced(WorkflowResults.controlledOf(identifier,
                        ((WorkflowService.Moved) outcome).observed()));
    }

    @Override
    public List<String> categories() {
        return switch (kind) {
            case MODELS, INSTANCES -> WorkflowHandlers.listingCategories();
            case START -> WorkflowHandlers.startCategories();
            case INSPECTION -> WorkflowHandlers.inspectionCategories();
            case TERMINATION ->
                    WorkflowHandlers.controlCategories(WorkflowHandlers.INSTANCE_NOT_TERMINABLE);
            case SUSPENSION ->
                    WorkflowHandlers.controlCategories(WorkflowHandlers.INSTANCE_NOT_SUSPENDABLE);
        };
    }
}
