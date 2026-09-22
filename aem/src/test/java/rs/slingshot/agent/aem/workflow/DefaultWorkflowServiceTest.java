// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.collection.util.ResultSet;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.model.WorkflowModel;
import com.adobe.granite.workflow.model.WorkflowNode;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.platform.SuspensionState;
import rs.slingshot.agent.command.platform.WorkflowInstanceState;
import rs.slingshot.agent.command.platform.WorkflowService;

/**
 * The workflow commands' adapter, driven over a workflow engine whose answers the suite scripts.
 *
 * <p>The engine is an interface the platform implements, so what is proved here is the
 * translation: which of the engine's calls each command makes, that a control's outcome is read
 * back rather than assumed, and that an instance that has already ended is refused rather than
 * acted on.</p>
 */
final class DefaultWorkflowServiceTest {

    private final DefaultWorkflowService service = new DefaultWorkflowService();

    @Test
    @DisplayName("models are listed by title prefix, with their version where they carry one")
    void modelsAreListedByPrefix() {
        final Engine engine = new Engine();
        engine.models.add(model("/var/workflow/models/publish", "Publish", "1.0"));
        engine.models.add(model("/var/workflow/models/review", "Review", null));
        final List<WorkflowService.Model> listed = assertInstanceOf(WorkflowService.Models.class,
                service.models("Pub", engine.resolver())).models();
        assertEquals(List.of(new WorkflowService.Model("/var/workflow/models/publish", "Publish",
                "1.0")), listed);
        assertEquals(WorkflowService.UNVERSIONED, assertInstanceOf(WorkflowService.Models.class,
                service.models("Rev", engine.resolver())).models().getFirst().version());
    }

    @Test
    @DisplayName("a start runs the model on the payload with its metadata, and an unknown model is refused")
    void astartRunsTheModelOnThePayload() {
        final Engine engine = new Engine();
        engine.models.add(model("/var/workflow/models/publish", "Publish", "1.0"));
        final java.util.SequencedMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("comment", "go");
        final WorkflowService.Instance started = assertInstanceOf(WorkflowService.Started.class,
                service.start("/var/workflow/models/publish", "/content/site",
                        metadata, engine.resolver())).instance();
        assertEquals("/content/site", started.payloadPath());
        assertEquals(WorkflowInstanceState.RUNNING, started.state());
        assertEquals(Map.of("comment", "go"), engine.startedWith);
        assertEquals("model_not_found", assertInstanceOf(WorkflowService.Refused.class,
                service.start("/var/workflow/models/none", "/content/site", metadata,
                        engine.resolver())).category());
    }

    @Test
    @DisplayName("a search narrows by model and payload, and asks the engine for the states named")
    void asearchNarrowsByModelAndPayload() {
        final Engine engine = new Engine();
        engine.instances.put("a", instance("a", "/m/one", "/content/site/a", "RUNNING"));
        engine.instances.put("b", instance("b", "/m/two", "/content/site/b", "RUNNING"));
        engine.instances.put("c", instance("c", "/m/one", "/content/other", "RUNNING"));
        final List<WorkflowService.Instance> found = assertInstanceOf(
                WorkflowService.Instances.class, service.instances(
                        new WorkflowService.InstanceQuery("/m/one", "/content/site",
                                List.of(WorkflowInstanceState.RUNNING)), engine.resolver()))
                .instances();
        assertEquals(List.of("a"), found.stream()
                .map(WorkflowService.Instance::instanceIdentifier).toList());
        assertEquals(List.of("RUNNING"), engine.askedStates);
    }

    @Test
    @DisplayName("an inspection lists the open work items with their assignees")
    void aninspectionListsWorkItems() {
        final Engine engine = new Engine();
        engine.instances.put("a", instance("a", "/m/one", "/content/site/a", "RUNNING"));
        final WorkflowService.Detail detail = assertInstanceOf(WorkflowService.Inspected.class,
                service.inspect("a", engine.resolver())).detail();
        assertEquals(List.of(new WorkflowService.WorkItem("item-1", "Approve", "reviewers")),
                detail.workItems());
        assertEquals("instance_not_found", assertInstanceOf(WorkflowService.Refused.class,
                service.inspect("none", engine.resolver())).category());
    }

    @Test
    @DisplayName("a control acts on a live instance, reads its state back, and refuses an ended one")
    void acontrolActsOnALiveInstance() {
        final Engine engine = new Engine();
        engine.instances.put("live", instance("live", "/m", "/content/a", "RUNNING"));
        engine.instances.put("done", instance("done", "/m", "/content/b", "COMPLETED"));
        assertEquals(WorkflowInstanceState.SUSPENDED, assertInstanceOf(WorkflowService.Moved.class,
                service.suspend("live", SuspensionState.SUSPENDED, engine.resolver()))
                .observed());
        assertEquals(WorkflowInstanceState.SUSPENDED, assertInstanceOf(WorkflowService.Moved.class,
                service.suspend("live", SuspensionState.SUSPENDED, engine.resolver()))
                .observed(), "holding a held instance was not answered as already so");
        assertEquals(WorkflowInstanceState.RUNNING, assertInstanceOf(WorkflowService.Moved.class,
                service.suspend("live", SuspensionState.RUNNING, engine.resolver()))
                .observed());
        assertEquals(WorkflowInstanceState.ABORTED, assertInstanceOf(WorkflowService.Moved.class,
                service.terminate("live", engine.resolver())).observed());
        assertEquals(List.of("suspend:live", "resume:live", "terminate:live"), engine.controls);
        assertEquals("instance_not_terminable", assertInstanceOf(WorkflowService.Refused.class,
                service.terminate("done", engine.resolver())).category());
        assertEquals("instance_not_suspendable", assertInstanceOf(WorkflowService.Refused.class,
                service.suspend("done", SuspensionState.SUSPENDED, engine.resolver()))
                .category());
        assertEquals("instance_not_found", assertInstanceOf(WorkflowService.Refused.class,
                service.terminate("none", engine.resolver())).category());
    }

    @Test
    @DisplayName("a caller whose resolver has no workflow session is refused by every call")
    void noWorkflowSessionIsRefused() {
        try (ResourceResolver none = (ResourceResolver) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {ResourceResolver.class}, (proxy, method, arguments) -> null)) {
            final List<WorkflowService.Outcome> refused = List.of(service.models("", none),
                    service.start("/m", "/content", new LinkedHashMap<>(), none),
                    service.instances(new WorkflowService.InstanceQuery("", "",
                            List.of(WorkflowInstanceState.RUNNING)), none),
                    service.inspect("a", none), service.terminate("a", none),
                    service.suspend("a", SuspensionState.SUSPENDED, none));
            refused.forEach(outcome -> assertInstanceOf(WorkflowService.Refused.class, outcome));
        }
    }

    @Test
    @DisplayName("every one of the engine's states reads as one of the client's")
    void everyStateReadsAsTheClients() {
        assertEquals(WorkflowInstanceState.RUNNING, DefaultWorkflowService.stateOf("RUNNING"));
        assertEquals(WorkflowInstanceState.SUSPENDED, DefaultWorkflowService.stateOf("SUSPENDED"));
        assertEquals(WorkflowInstanceState.COMPLETED, DefaultWorkflowService.stateOf("COMPLETED"));
        assertEquals(WorkflowInstanceState.ABORTED, DefaultWorkflowService.stateOf("ABORTED"));
        assertEquals(WorkflowInstanceState.STALE, DefaultWorkflowService.stateOf("STALE"));
    }

    /** A workflow engine whose models and instances the suite holds. */
    private static final class Engine {

        private final List<WorkflowModel> models = new ArrayList<>();
        private final Map<String, String[]> instances = new LinkedHashMap<>();
        private final List<String> askedStates = new ArrayList<>();
        private final List<String> controls = new ArrayList<>();
        private Map<String, Object> startedWith = Map.of();

        ResourceResolver resolver() {
            final WorkflowSession session = (WorkflowSession) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {WorkflowSession.class}, (proxy, method, arguments) ->
                            answer(method.getName(), arguments));
            return (ResourceResolver) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {ResourceResolver.class}, (proxy, method, arguments) ->
                            "adaptTo".equals(method.getName())
                                    && WorkflowSession.class.equals(arguments[0]) ? session : null);
        }

        private Object answer(String method, Object... arguments) throws WorkflowException {
            return switch (method) {
                case "getModels" -> models.toArray(WorkflowModel[]::new);
                case "getModel" -> models.stream()
                        .filter(model -> model.getId().equals(arguments[0])).findFirst()
                        .orElse(null);
                case "newWorkflowData" -> data(String.valueOf(arguments[1]));
                case "startWorkflow" -> started(arguments);
                case "getWorkflows" -> found((String[]) arguments[0]);
                case "getWorkflow" -> workflow((String) arguments[0]);
                case "suspendWorkflow" -> controlled("suspend", arguments[0], "SUSPENDED");
                case "resumeWorkflow" -> controlled("resume", arguments[0], "RUNNING");
                case "terminateWorkflow" -> controlled("terminate", arguments[0], "ABORTED");
                default -> throw new UnsupportedOperationException(method);
            };
        }

        private Workflow started(Object... arguments) {
            final Map<String, Object> recorded = new LinkedHashMap<>();
            ((Map<?, ?>) arguments[2]).forEach((key, value) ->
                    recorded.put(String.valueOf(key), value));
            startedWith = Map.copyOf(recorded);
            final String payload = String.valueOf(((WorkflowData) arguments[1]).getPayload());
            instances.put("started", new String[] {"started",
                    ((WorkflowModel) arguments[0]).getId(), payload, "RUNNING"});
            return workflow("started");
        }

        private ResultSet<Workflow> found(String... states) {
            askedStates.addAll(List.of(states));
            final List<Workflow> matching = instances.keySet().stream()
                    .map(this::workflow)
                    .filter(workflow -> List.of(states).contains(workflow.getState()))
                    .toList();
            return resultSet(matching);
        }

        private Object controlled(String control, Object workflow, String state) {
            final String identifier = ((Workflow) workflow).getId();
            controls.add(control + ":" + identifier);
            instances.get(identifier)[3] = state;
            return null;
        }

        private Workflow workflow(String identifier) {
            final String[] held = instances.get(identifier);
            if (held == null) {
                return null;
            }
            final WorkflowModel model = model(held[1], held[1], "1");
            final WorkflowData data = data(held[2]);
            return (Workflow) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {Workflow.class}, (proxy, method, arguments) ->
                            switch (method.getName()) {
                                case "getId" -> held[0];
                                case "getState" -> held[3];
                                case "getWorkflowModel" -> model;
                                case "getWorkflowData" -> data;
                                case "getTimeStarted" -> Date.from(java.time.Instant.EPOCH);
                                case "getWorkItems" -> List.of(item());
                                default -> throw new UnsupportedOperationException(
                                        method.getName());
                            });
        }
    }

    private static String[] instance(String identifier, String model, String payload,
                                     String state) {
        return new String[] {identifier, model, payload, state};
    }

    private static WorkItem item() {
        final WorkflowNode node = (WorkflowNode) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {WorkflowNode.class}, (proxy, method, arguments) ->
                        "getTitle".equals(method.getName()) ? "Approve" : null);
        return (WorkItem) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {WorkItem.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getId" -> "item-1";
                            case "getNode" -> node;
                            case "getCurrentAssignee" -> "reviewers";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    /** The engine's result set of instances, as one type that is not generic. */
    private interface Instances extends ResultSet<Workflow> {
    }

    private static ResultSet<Workflow> resultSet(List<Workflow> items) {
        return (Instances) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Instances.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getItems" -> items.toArray(Workflow[]::new);
                            case "getTotalSize" -> (long) items.size();
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static WorkflowData data(String payload) {
        return (WorkflowData) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {WorkflowData.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getPayload" -> payload;
                            case "getPayloadType" -> "JCR_PATH";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static WorkflowModel model(String identifier, String title, String version) {
        return (WorkflowModel) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {WorkflowModel.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getId" -> identifier;
                            case "getTitle" -> title;
                            case "getVersion" -> version;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }
}
