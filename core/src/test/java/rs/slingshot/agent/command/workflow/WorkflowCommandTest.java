// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.SuspensionState;
import rs.slingshot.agent.command.platform.WorkflowInstanceState;
import rs.slingshot.agent.command.platform.WorkflowService;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The six commands about workflows.
 *
 * <p>The one that matters is the payload check. A workflow runs afterwards under the platform's own
 * identity, which is usually more privileged than the caller's, so a caller who cannot change a
 * page but can start a workflow whose steps change it has changed it — and nothing in the audit
 * trail says they did. Everything else here is ordinary; that one is the reason this command is
 * safe to offer at all.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class WorkflowCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String PAGE = "/content/site/article";

    private static final String MODEL = "/var/workflow/models/review";

    private static final String INSTANCE = "/var/workflow/instances/2026-09-02/review_1";

    /**
     * A real repository, because the payload check asks one a permission question.
     *
     * <p>The resource-resolver mock has no repository session at all, and this check's whole
     * meaning is what the repository says about who may write where. Proving it against something
     * that cannot answer would be proving nothing.</p>
     */
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a workflow is started only on a payload the caller could have changed themselves")
    void aworkflowNeedsAWritablePayload() {
        page();
        final Engine engine = new Engine();
        assertInstanceOf(CommandHandler.Produced.class,
                new WorkflowHandler(CONTRACT, WorkflowHandler.Kind.START, engine, permissive())
                        .run(start(MODEL, PAGE), sling.resourceResolver(), context()),
                "a workflow on a payload this caller can change was refused");
        assertEquals(List.of("start"), engine.calls());
        final Engine refusing = new Engine();
        final CommandHandler.Failed denied = assertInstanceOf(CommandHandler.Failed.class,
                new WorkflowHandler(CONTRACT, WorkflowHandler.Kind.START, refusing, permissive())
                        .run(start(MODEL, PAGE), ReadOnlyResolver.around(sling.resourceResolver()),
                                context()),
                "a workflow was started on a payload this caller cannot change, which is a way to"
                        + " have the platform make a change they were refused");
        assertEquals(WorkflowHandlers.PAYLOAD_ACCESS_DENIED, denied.category());
        assertEquals(List.of(), refusing.calls(),
                "the platform was asked to start a workflow on content the caller cannot change");
    }

    @Test
    @DisplayName("a payload that is not there is told apart from one the caller may not change")
    void thetwoPayloadRefusalsAreDistinct() {
        page();
        assertEquals(WorkflowHandlers.PAYLOAD_NOT_FOUND,
                WorkflowHandler.payloadRefusal("/content/site/nothing", sling.resourceResolver()),
                "a payload that is not there and one the caller may not change were reported the"
                        + " same way, and the first is a typo while the second is a permission");
        assertEquals(WorkflowHandler.PAYLOAD_REACHABLE,
                WorkflowHandler.payloadRefusal(PAGE, sling.resourceResolver()));
        assertEquals(WorkflowHandlers.PAYLOAD_ACCESS_DENIED,
                WorkflowHandler.payloadRefusal(PAGE,
                        ReadOnlyResolver.around(sling.resourceResolver())),
                "the check asks whether the caller could read the payload rather than change it,"
                        + " and a workflow whose steps rewrite the payload is indistinguishable"
                        + " from one whose steps only read it");
    }

    @Test
    @DisplayName("a deployment that does not permit workflow control refuses all three controls")
    void adeploymentWithoutWorkflowControlRefusesAllThree() {
        page();
        final Engine engine = new Engine();
        for (final WorkflowHandler.Kind kind : List.of(WorkflowHandler.Kind.START,
                WorkflowHandler.Kind.TERMINATION, WorkflowHandler.Kind.SUSPENSION)) {
            assertEquals(PlatformControl.NOT_PERMITTED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new WorkflowHandler(CONTRACT, kind, engine,
                                    PlatformControl.of("aem-cloud-service", Set.of()))
                                    .run(start(MODEL, PAGE), sling.resourceResolver(), context()),
                            kind + " was carried out on a deployment that does not permit it")
                            .category());
        }
        assertEquals(List.of(), engine.calls(),
                "the platform was asked to do something on a deployment that does not permit it");
    }

    @Test
    @DisplayName("a search must name its states, because a year of history would answer first")
    void asearchMustNameItsStates() {
        assertEquals(FindWorkflowInstancesCommand.Refusal.MEMBER_ABSENT,
                assertInstanceOf(FindWorkflowInstancesCommand.Refused.class,
                        FindWorkflowInstancesCommand.of(
                                new DocumentValue.Mapping(new LinkedHashMap<>()), CONTRACT),
                        "a search naming no state was accepted, and an instance that has been"
                                + " running a year holds hundreds of thousands of completed"
                                + " workflows and a handful of running ones").refusal());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(FindWorkflowInstancesCommand.STATES, new DocumentValue.Sequence(
                List.of(new DocumentValue.Text("paused"))));
        assertEquals(FindWorkflowInstancesCommand.Refusal.STATE_REJECTED,
                assertInstanceOf(FindWorkflowInstancesCommand.Refused.class,
                        FindWorkflowInstancesCommand.of(new DocumentValue.Mapping(unknown),
                                CONTRACT), "a state nobody publishes was accepted").refusal());
        final FindWorkflowInstancesCommand held = assertInstanceOf(
                FindWorkflowInstancesCommand.Held.class,
                FindWorkflowInstancesCommand.of(search(List.of("running")), CONTRACT),
                "a search naming a state was refused").command();
        assertEquals(List.of(WorkflowInstanceState.RUNNING), held.states());
        assertEquals(FindWorkflowInstancesCommand.EVERY_MODEL, held.modelIdentifier());
        assertEquals(FindWorkflowInstancesCommand.EVERY_PAYLOAD, held.payloadPrefix());
    }

    @Test
    @DisplayName("no answer carries a workflow variable, in any of the three that read")
    void noanswerCarriesAWorkflowVariable() {
        for (final var pair : List.of(
                Map.entry(WorkflowHandler.Kind.MODELS,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(WorkflowHandler.Kind.INSTANCES, search(List.of("running"))),
                Map.entry(WorkflowHandler.Kind.INSPECTION, identifier(INSTANCE)))) {
            final DocumentValue.Mapping answered = assertInstanceOf(CommandHandler.Produced.class,
                    run(pair.getKey(), pair.getValue()), pair.getKey() + " was refused").result();
            assertTrue(!String.valueOf(answered).contains("secret-token"),
                    pair.getKey() + " carried a workflow variable, which belongs to whatever"
                            + " created the instance and routinely holds content, addresses and"
                            + " occasionally a token: " + answered);
        }
    }

    @Test
    @DisplayName("an inspection says which model, on what, in what state, and who it waits for")
    void aninspectionSaysWhatAnOperatorNeeds() {
        final DocumentValue.Mapping read = assertInstanceOf(CommandHandler.Produced.class,
                run(WorkflowHandler.Kind.INSPECTION, identifier(INSTANCE)),
                "the inspection was refused").result();
        assertEquals(new DocumentValue.Text(MODEL),
                read.member(WorkflowResults.MODEL_IDENTIFIER).orElseThrow());
        assertEquals(new DocumentValue.Text(PAGE),
                read.member(WorkflowResults.PAYLOAD_PATH).orElseThrow());
        assertEquals(new DocumentValue.Text("running"),
                read.member(WorkflowResults.STATE).orElseThrow());
        final DocumentValue.Mapping item = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Sequence.class,
                        read.member(WorkflowResults.WORK_ITEMS).orElseThrow()).items().getFirst());
        assertEquals(new DocumentValue.Text("Approve"),
                item.member(WorkflowResults.NODE_TITLE).orElseThrow(),
                "an inspection does not say which step the instance is sitting at, which is the"
                        + " first thing anybody asks about a workflow that has not finished");
        assertTrue(item.member(WorkflowResults.ASSIGNEE).isPresent(),
                "an inspection does not say who the outstanding work is with");
    }

    @Test
    @DisplayName("a control reports where the instance ended up, which may be neither answer asked for")
    void acontrolReportsWhereItEndedUp() {
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                run(WorkflowHandler.Kind.SUSPENSION, suspension(INSTANCE, "suspended")),
                "the suspension was refused").result();
        assertEquals(new DocumentValue.Text("completed"),
                moved.member(WorkflowResults.OBSERVED_STATE).orElseThrow(),
                "an instance that finished while the request was in flight was reported as"
                        + " suspended, which is a state it is not in");
        assertTrue(!WorkflowInstanceState.COMPLETED.agreesWith(SuspensionState.SUSPENDED),
                "a completed instance was reported as agreeing with a request to suspend it");
    }

    @Test
    @DisplayName("a state nobody may ask for is refused, even though the platform may report it")
    void onlyTheTwoRequestableStatesAreAccepted() {
        assertEquals(WorkflowInstanceCommand.Refusal.STATE_REJECTED,
                assertInstanceOf(WorkflowInstanceCommand.SuspensionRefused.class,
                        WorkflowInstanceCommand.suspension(suspension(INSTANCE, "completed"),
                                CONTRACT),
                        "a caller asked the platform to make an instance completed, which is"
                                + " something the platform reports and nobody requests").refusal());
        assertEquals(WorkflowInstanceCommand.Refusal.MEMBER_ABSENT,
                assertInstanceOf(WorkflowInstanceCommand.SuspensionRefused.class,
                        WorkflowInstanceCommand.suspension(identifier(INSTANCE), CONTRACT),
                        "an argument naming no state was accepted").refusal());
        assertEquals(WorkflowInstanceCommand.Refusal.MEMBER_UNKNOWN,
                assertInstanceOf(WorkflowInstanceCommand.Refused.class,
                        WorkflowInstanceCommand.of(suspension(INSTANCE, "suspended"), CONTRACT),
                        "the command that names one instance accepted a requested state").refusal(),
                "the two identifier-only commands accepted the member only the third takes");
        assertEquals(WorkflowInstanceCommand.Refusal.NOT_A_DOCUMENT,
                assertInstanceOf(WorkflowInstanceCommand.Refused.class,
                        WorkflowInstanceCommand.of(new DocumentValue.Text(INSTANCE), CONTRACT),
                        "text was accepted as an argument").refusal());
    }

    @Test
    @DisplayName("metadata is text by name and nothing else, because somebody else's steps read it")
    void metadataIsTextByName() {
        final SequencedMap<String, DocumentValue> structured = new LinkedHashMap<>();
        structured.put("reviewer", new DocumentValue.Mapping(new LinkedHashMap<>()));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(StartWorkflowCommand.MODEL_IDENTIFIER, new DocumentValue.Text(MODEL));
        members.put(StartWorkflowCommand.PAYLOAD_PATH, new DocumentValue.Text(PAGE));
        members.put(StartWorkflowCommand.METADATA, new DocumentValue.Mapping(structured));
        assertEquals(StartWorkflowCommand.Refusal.METADATA_REJECTED,
                assertInstanceOf(StartWorkflowCommand.Refused.class,
                        StartWorkflowCommand.of(new DocumentValue.Mapping(members), CONTRACT),
                        "a structured metadata value was accepted, which would be a way to send a"
                                + " document to steps somebody else wrote").refusal());
        final StartWorkflowCommand held = assertInstanceOf(StartWorkflowCommand.Held.class,
                StartWorkflowCommand.of(start(MODEL, PAGE), CONTRACT),
                "a start naming no metadata was refused").command();
        assertEquals(Map.of(), held.metadata());
        assertEquals(StartWorkflowCommand.NO_TITLE, held.title());
        assertEquals(StartWorkflowCommand.NO_COMMENT, held.comment());
    }

    @Test
    @DisplayName("a platform that could not be asked is reported as it saying so, in all six")
    void aplatformFailureReachesEverySixCommand() {
        page();
        final Refusing refusing = new Refusing();
        for (final var pair : List.of(
                Map.entry(WorkflowHandler.Kind.MODELS,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(WorkflowHandler.Kind.INSTANCES, search(List.of("running"))),
                Map.entry(WorkflowHandler.Kind.INSPECTION, identifier(INSTANCE)),
                Map.entry(WorkflowHandler.Kind.START, start(MODEL, PAGE)),
                Map.entry(WorkflowHandler.Kind.TERMINATION, identifier(INSTANCE)),
                Map.entry(WorkflowHandler.Kind.SUSPENSION, suspension(INSTANCE, "suspended")))) {
            assertEquals(WorkflowHandlers.INVENTORY_FAILED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new WorkflowHandler(CONTRACT, pair.getKey(), refusing, permissive())
                                    .run(pair.getValue(), sling.resourceResolver(), context()),
                            pair.getKey() + " reported an engine that could not be asked as an"
                                    + " answer").category());
        }
    }

    @Test
    @DisplayName("an argument none of the six takes is refused before the engine is asked")
    void abadArgumentNeverReachesTheEngine() {
        final Engine engine = new Engine();
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("workflow_name", new DocumentValue.Text("review"));
        for (final WorkflowHandler.Kind kind : List.of(WorkflowHandler.Kind.MODELS,
                WorkflowHandler.Kind.INSTANCES, WorkflowHandler.Kind.INSPECTION,
                WorkflowHandler.Kind.START, WorkflowHandler.Kind.TERMINATION,
                WorkflowHandler.Kind.SUSPENSION)) {
            assertInstanceOf(CommandHandler.Failed.class,
                    new WorkflowHandler(CONTRACT, kind, engine, permissive())
                            .run(new DocumentValue.Mapping(unknown), sling.resourceResolver(),
                                    context()),
                    kind + " accepted a member nobody declared");
        }
        assertEquals(List.of(), engine.calls(),
                "the engine was asked to act on an argument this build had already refused");
    }

    @Test
    @DisplayName("a listing past the caller's own budget is refused rather than shortened")
    void alistingPastTheBudgetIsRefused() {
        final CallerContext narrow = contextWith(new Budget(Budget.Kind.DISCOVERY, 1));
        for (final var pair : List.of(
                Map.entry(WorkflowHandler.Kind.MODELS,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(WorkflowHandler.Kind.INSTANCES, search(List.of("running"))))) {
            assertEquals(WorkflowHandlers.DISCOVERY_BUDGET_EXCEEDED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new WorkflowHandler(CONTRACT, pair.getKey(), new Engine(), permissive())
                                    .run(pair.getValue(), sling.resourceResolver(), narrow),
                            pair.getKey() + " answered a shortened list past the caller's budget,"
                                    + " which reads as the complete answer").category());
        }
        assertEquals(1, WorkflowHandler.pageOf(List.of("a", "b"),
                new rs.slingshot.agent.command.ResultWindow.Initial(1, 1)).size());
        assertEquals(List.of("a", "b"), WorkflowHandler.pageOf(List.of("a", "b"),
                new rs.slingshot.agent.command.ResultWindow.Continuation("token")));
    }

    @Test
    @DisplayName("a termination reports the state the instance ended up in")
    void aterminationReportsTheState() {
        final DocumentValue.Mapping ended = assertInstanceOf(CommandHandler.Produced.class,
                run(WorkflowHandler.Kind.TERMINATION, identifier(INSTANCE)),
                "the termination was refused").result();
        assertEquals(new DocumentValue.Text("aborted"),
                ended.member(WorkflowResults.OBSERVED_STATE).orElseThrow());
        assertEquals(new DocumentValue.Text(INSTANCE),
                ended.member(WorkflowResults.INSTANCE_IDENTIFIER).orElseThrow());
    }

    /** An engine that will not answer anything, so every command's refusal path is reachable. */
    private static final class Refusing implements WorkflowService {

        private static final Outcome REFUSAL =
                new Refused(WorkflowHandlers.INVENTORY_FAILED, "the engine could not be asked");

        @Override
        public Outcome models(String titlePrefix, ResourceResolver session) {
            return REFUSAL;
        }

        @Override
        public Outcome start(String modelIdentifier, String payloadPath,
                             SequencedMap<String, String> metadata, ResourceResolver session) {
            return REFUSAL;
        }

        @Override
        public Outcome instances(InstanceQuery query, ResourceResolver session) {
            return REFUSAL;
        }

        @Override
        public Outcome inspect(String instanceIdentifier, ResourceResolver session) {
            return REFUSAL;
        }

        @Override
        public Outcome terminate(String instanceIdentifier, ResourceResolver session) {
            return REFUSAL;
        }

        @Override
        public Outcome suspend(String instanceIdentifier, SuspensionState requested,
                               ResourceResolver session) {
            return REFUSAL;
        }
    }

    @Test
    @DisplayName("all six rows are the client's own and every handler declares exactly them")
    void allsixRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(ListWorkflowModelsCommand.WIRE_NAME,
                        WorkflowHandlers.listingCategories()),
                Map.entry(FindWorkflowInstancesCommand.WIRE_NAME,
                        WorkflowHandlers.listingCategories()),
                Map.entry(StartWorkflowCommand.WIRE_NAME, WorkflowHandlers.startCategories()),
                Map.entry(WorkflowInstanceCommand.INSPECT_WIRE_NAME,
                        WorkflowHandlers.inspectionCategories()),
                Map.entry(WorkflowInstanceCommand.TERMINATE_WIRE_NAME,
                        WorkflowHandlers.controlCategories(
                                WorkflowHandlers.INSTANCE_NOT_TERMINABLE)),
                Map.entry(WorkflowInstanceCommand.SUSPEND_WIRE_NAME,
                        WorkflowHandlers.controlCategories(
                                WorkflowHandlers.INSTANCE_NOT_SUSPENDABLE)))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
        assertTrue(WorkflowHandlers.startCategories().containsAll(
                        java.util.Arrays.stream(StartWorkflowCommand.Refusal.values())
                                .map(WorkflowHandler::categoryFor).toList()),
                "a start refusal reaches a category this command's own row does not declare");
        assertEquals(RegistryRow.OperationKey.REQUIRED,
                row(StartWorkflowCommand.WIRE_NAME).operationKey());
        assertEquals(RegistryRow.OperationKey.REFUSED,
                row(ListWorkflowModelsCommand.WIRE_NAME).operationKey());
    }

    /** An engine that remembers what it was asked and answers from a fixed instance. */
    private static final class Engine implements WorkflowService {

        private final List<String> asked = new ArrayList<>();

        List<String> calls() {
            return List.copyOf(asked);
        }

        @Override
        public Outcome models(String titlePrefix, ResourceResolver session) {
            asked.add("models");
            return new Models(List.of(new Model(MODEL, "Request for Activation", "1.0"),
                    new Model(MODEL + "-legacy", "Request for Activation (legacy)", UNVERSIONED)));
        }

        @Override
        public Outcome start(String modelIdentifier, String payloadPath,
                             SequencedMap<String, String> metadata, ResourceResolver session) {
            asked.add("start");
            return new Started(new Instance(INSTANCE, modelIdentifier, payloadPath,
                    WorkflowInstanceState.RUNNING, "2026-09-02T09:00:00Z"));
        }

        @Override
        public Outcome instances(InstanceQuery query, ResourceResolver session) {
            asked.add("instances");
            return new Instances(List.of(
                    new Instance(INSTANCE, MODEL, PAGE, WorkflowInstanceState.RUNNING,
                            "2026-09-02T09:00:00Z"),
                    new Instance(INSTANCE + "_2", MODEL, PAGE, WorkflowInstanceState.SUSPENDED,
                            NOT_RECORDED)));
        }

        @Override
        public Outcome inspect(String instanceIdentifier, ResourceResolver session) {
            asked.add("inspect");
            return new Inspected(new Detail(
                    new Instance(INSTANCE, MODEL, PAGE, WorkflowInstanceState.RUNNING,
                            "2026-09-02T09:00:00Z"),
                    List.of(new WorkItem(INSTANCE + "/workItems/node0", "Approve", "reviewer"))));
        }

        @Override
        public Outcome terminate(String instanceIdentifier, ResourceResolver session) {
            asked.add("terminate");
            return new Moved(WorkflowInstanceState.ABORTED);
        }

        @Override
        public Outcome suspend(String instanceIdentifier, SuspensionState requested,
                               ResourceResolver session) {
            asked.add("suspend");
            return new Moved(WorkflowInstanceState.COMPLETED);
        }
    }

    private CommandHandler.Answer run(WorkflowHandler.Kind kind, DocumentValue.Mapping arguments) {
        return new WorkflowHandler(CONTRACT, kind, new Engine(), permissive())
                .run(arguments, sling.resourceResolver(), context());
    }

    private static PlatformControl permissive() {
        return PlatformControl.of("aem-6-5-lts", Set.of(ControlCapability.values()));
    }

    private void page() {
        sling.create().resource(PAGE, Map.of("kind", "article"));
    }

    private static DocumentValue.Mapping start(String model, String payload) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(StartWorkflowCommand.MODEL_IDENTIFIER, new DocumentValue.Text(model));
        members.put(StartWorkflowCommand.PAYLOAD_PATH, new DocumentValue.Text(payload));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping search(List<String> states) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FindWorkflowInstancesCommand.STATES, new DocumentValue.Sequence(states.stream()
                .map(state -> (DocumentValue) new DocumentValue.Text(state)).toList()));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping identifier(String instanceIdentifier) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(WorkflowInstanceCommand.INSTANCE_IDENTIFIER,
                new DocumentValue.Text(instanceIdentifier));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping suspension(String instanceIdentifier, String state) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(WorkflowInstanceCommand.INSTANCE_IDENTIFIER,
                new DocumentValue.Text(instanceIdentifier));
        members.put(WorkflowInstanceCommand.REQUESTED_STATE, new DocumentValue.Text(state));
        return new DocumentValue.Mapping(members);
    }

    private static CallerContext context() {
        return contextWith(Budget.discovery(CONTRACT));
    }

    private static CallerContext contextWith(Budget discovery) {
        return new CallerContext(operation(), discovery, Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row(String wire) {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry().row(wire).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
