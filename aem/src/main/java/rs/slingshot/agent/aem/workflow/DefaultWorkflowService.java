// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.workflow;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.model.WorkflowModel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import rs.slingshot.agent.command.platform.SuspensionState;
import rs.slingshot.agent.command.platform.WorkflowInstanceState;
import rs.slingshot.agent.command.platform.WorkflowService;

/**
 * The platform's own workflow engine, reached through the caller's own session.
 *
 * <p>The workflow session is adapted from the caller's resolver on every call and never held, so
 * a model or an instance the caller cannot see is one this answers as not there. Whether the
 * caller could change a payload is decided before a start reaches here; what this decides is how
 * the engine's own vocabulary reads in the client's.</p>
 */
@Component(service = WorkflowService.class)
public final class DefaultWorkflowService implements WorkflowService {

    private static final String INVENTORY_FAILED = "workflow_inventory_failed";
    private static final String MODEL_NOT_FOUND = "model_not_found";
    private static final String MODEL_INVALID = "model_invalid";
    private static final String INSTANCE_NOT_FOUND = "instance_not_found";
    private static final String NOT_TERMINABLE = "instance_not_terminable";
    private static final String NOT_SUSPENDABLE = "instance_not_suspendable";
    private static final String CONTROL_REJECTED = "platform_control_rejected";

    /** The kind of payload a workflow started here runs on, which is a repository path. */
    private static final String PATH_PAYLOAD = "JCR_PATH";

    /** The engine's own spellings of every state, which a search asks for by name. */
    private static final List<String> ENGINE_STATES =
            List.of("RUNNING", "SUSPENDED", "COMPLETED", "ABORTED", "STALE");

    /** The most instances one search reads: one more than a caller may examine. */
    private static final long SEARCH_LIMIT = 100_001;

    /** Holds the service; it keeps nothing between calls. */
    public DefaultWorkflowService() {
        // Stateless: the caller's workflow session arrives with each call.
    }

    @Override
    public Outcome models(String titlePrefix, ResourceResolver session) {
        final Optional<WorkflowSession> workflows = sessionOf(session);
        if (workflows.isEmpty()) {
            return unavailable(INVENTORY_FAILED);
        }
        try {
            return new Models(Arrays.stream(workflows.get().getModels())
                    .filter(model -> String.valueOf(model.getTitle()).startsWith(titlePrefix))
                    .map(model -> new Model(model.getId(), String.valueOf(model.getTitle()),
                            model.getVersion() == null ? UNVERSIONED : model.getVersion()))
                    .toList());
        } catch (final WorkflowException failed) {
            return new Refused(INVENTORY_FAILED, "the workflow engine did not list its models: "
                    + failed.getMessage());
        }
    }

    @Override
    public Outcome start(String modelIdentifier, String payloadPath,
                         SequencedMap<String, String> metadata, ResourceResolver session) {
        final Optional<WorkflowSession> workflows = sessionOf(session);
        if (workflows.isEmpty()) {
            return unavailable(CONTROL_REJECTED);
        }
        final WorkflowModel model;
        try {
            model = workflows.get().getModel(modelIdentifier);
        } catch (final WorkflowException invalid) {
            return new Refused(MODEL_INVALID, modelIdentifier + " could not be read as a model: "
                    + invalid.getMessage());
        }
        if (model == null) {
            return new Refused(MODEL_NOT_FOUND, modelIdentifier + " names no model this caller"
                    + " can see");
        }
        try {
            final WorkflowData data = workflows.get().newWorkflowData(PATH_PAYLOAD, payloadPath);
            final Map<String, Object> recorded = new LinkedHashMap<>(metadata);
            return new Started(instanceOf(workflows.get().startWorkflow(model, data, recorded)));
        } catch (final WorkflowException refused) {
            return new Refused(CONTROL_REJECTED, "the workflow engine did not start "
                    + modelIdentifier + " on " + payloadPath + ": " + refused.getMessage());
        }
    }

    @Override
    public Outcome instances(InstanceQuery query, ResourceResolver session) {
        final Optional<WorkflowSession> workflows = sessionOf(session);
        if (workflows.isEmpty()) {
            return unavailable(INVENTORY_FAILED);
        }
        try {
            final String[] states = query.states().stream()
                    .map(state -> state.name())
                    .filter(ENGINE_STATES::contains)
                    .toArray(String[]::new);
            final List<Workflow> found = Arrays.asList(workflows.get()
                    .getWorkflows(states, 0, SEARCH_LIMIT).getItems());
            return new Instances(found.stream()
                    .map(DefaultWorkflowService::instanceOf)
                    .filter(instance -> query.modelIdentifier().isEmpty()
                            || query.modelIdentifier().equals(instance.modelIdentifier()))
                    .filter(instance -> instance.payloadPath().startsWith(query.payloadPrefix()))
                    .toList());
        } catch (final WorkflowException failed) {
            return new Refused(INVENTORY_FAILED, "the workflow engine did not search its"
                    + " instances: " + failed.getMessage());
        }
    }

    @Override
    public Outcome inspect(String instanceIdentifier, ResourceResolver session) {
        final Optional<WorkflowSession> workflows = sessionOf(session);
        if (workflows.isEmpty()) {
            return unavailable(INVENTORY_FAILED);
        }
        final Optional<Workflow> found = workflow(workflows.get(), instanceIdentifier);
        if (found.isEmpty()) {
            return notFound(instanceIdentifier);
        }
        return new Inspected(new Detail(instanceOf(found.get()), found.get().getWorkItems()
                .stream()
                .map(item -> new WorkItem(item.getId(),
                        item.getNode() == null ? "" : String.valueOf(item.getNode().getTitle()),
                        item.getCurrentAssignee() == null ? UNASSIGNED
                                : item.getCurrentAssignee()))
                .toList()));
    }

    @Override
    public Outcome terminate(String instanceIdentifier, ResourceResolver session) {
        final Optional<WorkflowSession> workflows = sessionOf(session);
        if (workflows.isEmpty()) {
            return unavailable(CONTROL_REJECTED);
        }
        final Optional<Workflow> found = workflow(workflows.get(), instanceIdentifier);
        if (found.isEmpty()) {
            return notFound(instanceIdentifier);
        }
        final WorkflowInstanceState state = stateOf(found.get().getState());
        if (state != WorkflowInstanceState.RUNNING && state != WorkflowInstanceState.SUSPENDED) {
            return new Refused(NOT_TERMINABLE, instanceIdentifier + " has already ended as "
                    + state.spelling() + ", so there is nothing to terminate");
        }
        try {
            workflows.get().terminateWorkflow(found.get());
        } catch (final WorkflowException refused) {
            return new Refused(CONTROL_REJECTED, "the workflow engine did not terminate "
                    + instanceIdentifier + ": " + refused.getMessage());
        }
        return observed(workflows.get(), instanceIdentifier, WorkflowInstanceState.ABORTED);
    }

    @Override
    public Outcome suspend(String instanceIdentifier, SuspensionState requested,
                           ResourceResolver session) {
        final Optional<WorkflowSession> workflows = sessionOf(session);
        if (workflows.isEmpty()) {
            return unavailable(CONTROL_REJECTED);
        }
        final Optional<Workflow> found = workflow(workflows.get(), instanceIdentifier);
        if (found.isEmpty()) {
            return notFound(instanceIdentifier);
        }
        final WorkflowInstanceState state = stateOf(found.get().getState());
        final WorkflowInstanceState wanted = requested == SuspensionState.SUSPENDED
                ? WorkflowInstanceState.SUSPENDED : WorkflowInstanceState.RUNNING;
        if (state == wanted) {
            return new Moved(state);
        }
        if (state != WorkflowInstanceState.RUNNING && state != WorkflowInstanceState.SUSPENDED) {
            return new Refused(NOT_SUSPENDABLE, instanceIdentifier + " has already ended as "
                    + state.spelling() + ", so it can neither be held nor let go");
        }
        try {
            if (wanted == WorkflowInstanceState.SUSPENDED) {
                workflows.get().suspendWorkflow(found.get());
            } else {
                workflows.get().resumeWorkflow(found.get());
            }
        } catch (final WorkflowException refused) {
            return new Refused(CONTROL_REJECTED, "the workflow engine did not change "
                    + instanceIdentifier + ": " + refused.getMessage());
        }
        return observed(workflows.get(), instanceIdentifier, wanted);
    }

    /** The state an instance is in after a control, read back rather than assumed. */
    private static Outcome observed(WorkflowSession workflows, String instanceIdentifier,
                                    WorkflowInstanceState expected) {
        return new Moved(workflow(workflows, instanceIdentifier)
                .map(workflow -> stateOf(workflow.getState()))
                .orElse(expected));
    }

    private static Optional<Workflow> workflow(WorkflowSession workflows, String identifier) {
        try {
            return Optional.ofNullable(workflows.getWorkflow(identifier));
        } catch (final WorkflowException unreadable) {
            return Optional.empty();
        }
    }

    private static Instance instanceOf(Workflow workflow) {
        final WorkflowData data = workflow.getWorkflowData();
        return new Instance(workflow.getId(),
                workflow.getWorkflowModel() == null ? "" : workflow.getWorkflowModel().getId(),
                data == null ? "" : String.valueOf(data.getPayload()),
                stateOf(workflow.getState()),
                workflow.getTimeStarted() == null ? NOT_RECORDED
                        : workflow.getTimeStarted().toInstant().toString());
    }

    /**
     * The client's word for one of the engine's states.
     *
     * @param state the engine's spelling
     * @return the client's state, which is stale for anything the engine calls otherwise
     */
    static WorkflowInstanceState stateOf(String state) {
        return switch (String.valueOf(state)) {
            case "RUNNING" -> WorkflowInstanceState.RUNNING;
            case "SUSPENDED" -> WorkflowInstanceState.SUSPENDED;
            case "COMPLETED" -> WorkflowInstanceState.COMPLETED;
            case "ABORTED" -> WorkflowInstanceState.ABORTED;
            default -> WorkflowInstanceState.STALE;
        };
    }

    private static Optional<WorkflowSession> sessionOf(ResourceResolver session) {
        return Optional.ofNullable(session.adaptTo(WorkflowSession.class));
    }

    private static Refused notFound(String instanceIdentifier) {
        return new Refused(INSTANCE_NOT_FOUND, instanceIdentifier + " names no workflow instance"
                + " this caller can see");
    }

    private static Refused unavailable(String category) {
        return new Refused(category, "this caller's resolver has no workflow session, so the"
                + " workflow engine could not be reached");
    }
}
