// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.JobState;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.SuspensionState;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The four commands about the work the platform is holding for later.
 *
 * <p>The rule under test everywhere here is the one about values. A job's properties belong to
 * whatever created it and routinely carry content addresses and occasionally a credential; the
 * names are enough to say what kind of work a stuck job is. So the inspection carries the names and
 * nothing else, and every other command carries neither.</p>
 */
final class JobCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String JOB = "2026/9/2/replication_1";

    private static final String TOPIC = "com/day/cq/replication/job";

    @Test
    @DisplayName("an inspection carries the names of what a job holds and never their values")
    void aninspectionCarriesNamesAndNeverValues() {
        final DocumentValue.Mapping read = assertInstanceOf(CommandHandler.Produced.class,
                run(JobHandler.Kind.INSPECTION, identifier(JOB)),
                "the inspection was refused").result();
        final DocumentValue.Sequence keys = assertInstanceOf(DocumentValue.Sequence.class,
                read.member(JobResults.PROPERTY_KEYS).orElseThrow());
        assertEquals(List.of(new DocumentValue.Text("path"), new DocumentValue.Text("agentId")),
                keys.items(),
                "the names tell an operator what kind of work a stuck job is, and they are not"
                        + " here");
        assertTrue(!String.valueOf(read).contains("/content/site")
                        && !String.valueOf(read).contains("hunter2"),
                "the answer carries a job property value, which belongs to whatever created the"
                        + " job and routinely holds content addresses and occasionally a"
                        + " credential: " + read);
        assertEquals(new DocumentValue.Whole(3),
                read.member(JobResults.RETRY_COUNT).orElseThrow());
        assertEquals(new DocumentValue.Whole(10),
                read.member(JobResults.MAXIMUM_RETRY_COUNT).orElseThrow(),
                "the answer does not say how many attempts the platform will make, so an operator"
                        + " cannot tell a job that will retry from one that has given up");
    }

    @Test
    @DisplayName("no listing carries a job property, by name or by value")
    void nolistingCarriesAJobProperty() {
        for (final var pair : List.of(
                Map.entry(JobHandler.Kind.QUEUES,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(JobHandler.Kind.JOBS, search(List.of("error"))))) {
            final DocumentValue.Mapping answered = assertInstanceOf(CommandHandler.Produced.class,
                    run(pair.getKey(), pair.getValue()), pair.getKey() + " was refused").result();
            assertTrue(!String.valueOf(answered).contains("path")
                            && !String.valueOf(answered).contains("agentId"),
                    pair.getKey() + " carried a job property, and a listing across a whole"
                            + " instance is the answer that ends up pasted somewhere: " + answered);
        }
    }

    @Test
    @DisplayName("the three states that mean work did not happen are told apart")
    void thethreeFailureStatesAreDistinct() {
        assertEquals(Optional.of(JobState.ERROR), JobState.named("error"));
        assertEquals(Optional.of(JobState.CANCELLED), JobState.named("cancelled"));
        assertEquals(Optional.of(JobState.DROPPED), JobState.named("dropped"));
        assertEquals(6, JobState.values().length,
                "the set of job states changed, and the three that mean work did not happen have"
                        + " three different answers: one waits, one needs somebody, and one means"
                        + " a queue was configured to throw work away");
        assertEquals(Optional.empty(), JobState.named("failed"));
    }

    @Test
    @DisplayName("a search must name its states, because the successes would answer first")
    void asearchMustNameItsStates() {
        assertEquals(JobCommands.Refusal.MEMBER_ABSENT,
                assertInstanceOf(JobCommands.SearchRefused.class,
                        JobCommands.search(new DocumentValue.Mapping(new LinkedHashMap<>()),
                                CONTRACT),
                        "a search naming no state was accepted, and a busy instance holds a great"
                                + " many jobs that succeeded").refusal());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(JobCommands.STATES, new DocumentValue.Sequence(
                List.of(new DocumentValue.Text("failed"))));
        assertEquals(JobCommands.Refusal.STATE_REJECTED,
                assertInstanceOf(JobCommands.SearchRefused.class,
                        JobCommands.search(new DocumentValue.Mapping(unknown), CONTRACT),
                        "a state nobody publishes was accepted").refusal());
        final JobCommands.Search held = assertInstanceOf(JobCommands.Search.class,
                JobCommands.search(search(List.of("error")), CONTRACT),
                "a search naming a state was refused");
        assertEquals(List.of(JobState.ERROR), held.states());
        assertEquals(JobCommands.EVERY_TOPIC, held.topic());
    }

    @Test
    @DisplayName("a queue listing takes no filter, because the queue that matters is the unasked one")
    void aqueueListingTakesNoFilter() {
        final SequencedMap<String, DocumentValue> filtered = new LinkedHashMap<>();
        filtered.put(JobResults.QUEUE_NAME, new DocumentValue.Text("replication"));
        assertEquals(JobCommands.Refusal.MEMBER_UNKNOWN,
                assertInstanceOf(JobCommands.QueueRefused.class,
                        JobCommands.queues(new DocumentValue.Mapping(filtered), CONTRACT),
                        "a filter was accepted on a listing whose whole value is showing every"
                                + " queue at once").refusal());
        assertInstanceOf(JobCommands.QueueWindow.class,
                JobCommands.queues(new DocumentValue.Mapping(new LinkedHashMap<>()), CONTRACT),
                "a listing naming nothing was refused");
        assertEquals(JobCommands.Refusal.NOT_A_DOCUMENT,
                assertInstanceOf(JobCommands.QueueRefused.class,
                        JobCommands.queues(new DocumentValue.Text("queues"), CONTRACT),
                        "text was accepted as an argument").refusal());
    }

    @Test
    @DisplayName("a queue says whether it is taking work, using the state a workflow instance uses")
    void aqueueSaysWhetherItIsTakingWork() {
        final DocumentValue.Mapping listed = assertInstanceOf(CommandHandler.Produced.class,
                run(JobHandler.Kind.QUEUES, new DocumentValue.Mapping(new LinkedHashMap<>())),
                "the listing was refused").result();
        final DocumentValue.Mapping first = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Sequence.class,
                        listed.member(JobResults.MATCHES).orElseThrow()).items().getFirst());
        assertEquals(new DocumentValue.Text(SuspensionState.SUSPENDED.spelling()),
                first.member(JobResults.STATE).orElseThrow(),
                "a suspended queue was not reported as suspended, which is the single most common"
                        + " reason work is not happening on an author instance");
        assertEquals(new DocumentValue.Whole(41),
                first.member(JobResults.QUEUED_JOB_COUNT).orElseThrow(),
                "the listing does not say how much work is waiting, which is what tells an"
                        + " operator whether a suspended queue matters");
    }

    @Test
    @DisplayName("a deployment that does not permit job control refuses the cancellation only")
    void adeploymentWithoutJobControlRefusesTheCancellation() {
        final Inventory inventory = new Inventory();
        assertEquals(PlatformControl.NOT_PERMITTED,
                assertInstanceOf(CommandHandler.Failed.class,
                        new JobHandler(CONTRACT, JobHandler.Kind.CANCELLATION, inventory,
                                PlatformControl.of("aem-cloud-service", Set.of()))
                                .run(identifier(JOB), null, context()),
                        "a job was cancelled on a deployment that does not permit it").category());
        assertEquals(List.of(), inventory.calls(),
                "the platform was asked to cancel a job on a deployment that does not permit it");
        assertInstanceOf(CommandHandler.Produced.class,
                new JobHandler(CONTRACT, JobHandler.Kind.QUEUES, inventory,
                        PlatformControl.of("aem-cloud-service", Set.of()))
                        .run(new DocumentValue.Mapping(new LinkedHashMap<>()), null, context()),
                "listing queues was refused on a deployment that will not let a job be cancelled,"
                        + " and what-is-not-happening is the question an operator has precisely"
                        + " where they cannot change anything");
    }

    @Test
    @DisplayName("a cancellation reports where the job ended up rather than that it was cancelled")
    void acancellationReportsWhereItEndedUp() {
        final DocumentValue.Mapping gone = assertInstanceOf(CommandHandler.Produced.class,
                run(JobHandler.Kind.CANCELLATION, identifier(JOB)),
                "the cancellation was refused").result();
        assertEquals(new DocumentValue.Text("succeeded"),
                gone.member(JobResults.OBSERVED_STATE).orElseThrow(),
                "a job that finished a moment before the request was reported as cancelled, which"
                        + " is a state it is not in");
        assertEquals(new DocumentValue.Text(JOB),
                gone.member(JobResults.JOB_IDENTIFIER).orElseThrow());
    }

    @Test
    @DisplayName("a platform that could not be asked is reported as it saying so, in all four")
    void aplatformFailureReachesEveryCommand() {
        final Refusing refusing = new Refusing();
        for (final var pair : List.of(
                Map.entry(JobHandler.Kind.QUEUES,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(JobHandler.Kind.JOBS, search(List.of("error"))),
                Map.entry(JobHandler.Kind.INSPECTION, identifier(JOB)),
                Map.entry(JobHandler.Kind.CANCELLATION, identifier(JOB)))) {
            assertEquals(JobCommands.INVENTORY_FAILED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new JobHandler(CONTRACT, pair.getKey(), refusing, permissive())
                                    .run(pair.getValue(), null, context()),
                            pair.getKey() + " reported a job system that could not be asked as an"
                                    + " answer").category());
        }
    }

    @Test
    @DisplayName("an argument none of the four takes is refused before the platform is asked")
    void abadArgumentNeverReachesThePlatform() {
        final Inventory inventory = new Inventory();
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("job_name", new DocumentValue.Text("replication"));
        for (final JobHandler.Kind kind : List.of(JobHandler.Kind.QUEUES, JobHandler.Kind.JOBS,
                JobHandler.Kind.INSPECTION, JobHandler.Kind.CANCELLATION)) {
            assertInstanceOf(CommandHandler.Failed.class,
                    new JobHandler(CONTRACT, kind, inventory, permissive())
                            .run(new DocumentValue.Mapping(unknown), null, context()),
                    kind + " accepted a member nobody declared");
        }
        assertEquals(List.of(), inventory.calls(),
                "the platform was asked to act on an argument this build had already refused");
    }

    @Test
    @DisplayName("a listing past the caller's own budget is refused rather than shortened")
    void alistingPastTheBudgetIsRefused() {
        final CallerContext narrow = contextWith(new Budget(Budget.Kind.DISCOVERY, 1));
        for (final var pair : List.of(
                Map.entry(JobHandler.Kind.QUEUES,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(JobHandler.Kind.JOBS, search(List.of("error"))))) {
            assertEquals(JobCommands.DISCOVERY_BUDGET_EXCEEDED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new JobHandler(CONTRACT, pair.getKey(), new Inventory(), permissive())
                                    .run(pair.getValue(), null, narrow),
                            pair.getKey() + " answered a shortened list past the caller's budget,"
                                    + " which reads as the complete answer").category());
        }
        assertEquals(1, JobHandler.pageOf(List.of("a", "b"),
                new ResultWindow.Initial(1, 1)).size());
        assertEquals(List.of("a", "b"), JobHandler.pageOf(List.of("a", "b"),
                new ResultWindow.Continuation("token")));
    }

    @Test
    @DisplayName("all four rows are the client's own and every handler declares exactly them")
    void allfourRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(JobCommands.QUEUES_WIRE_NAME, JobCommands.listingCategories()),
                Map.entry(JobCommands.JOBS_WIRE_NAME, JobCommands.listingCategories()),
                Map.entry(JobCommands.INSPECT_WIRE_NAME, JobCommands.inspectionCategories()),
                Map.entry(JobCommands.CANCEL_WIRE_NAME, JobCommands.cancellationCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
        assertEquals(RegistryRow.OperationKey.REQUIRED,
                row(JobCommands.CANCEL_WIRE_NAME).operationKey(),
                "a cancelled job is work that will never happen, and this row no longer requires"
                        + " a key");
        assertEquals(RegistryRow.OperationKey.REFUSED,
                row(JobCommands.QUEUES_WIRE_NAME).operationKey());
    }

    /** An inventory that remembers what it was asked and answers from a fixed job system. */
    private static final class Inventory implements JobInventory {

        private final List<String> asked = new ArrayList<>();

        List<String> calls() {
            return List.copyOf(asked);
        }

        @Override
        public Outcome queues() {
            asked.add("queues");
            return new Queues(List.of(
                    new Queue("Adobe Replication Queue", SuspensionState.SUSPENDED, 0, 41),
                    new Queue("Granite Workflow Queue", SuspensionState.RUNNING, 2, 0)));
        }

        @Override
        public Outcome jobs(String topic, List<JobState> states) {
            asked.add("jobs");
            return new Jobs(List.of(
                    new Job(JOB, TOPIC, "Adobe Replication Queue", JobState.ERROR, 3),
                    new Job(JOB + "_2", TOPIC, NO_QUEUE, JobState.DROPPED, 0)));
        }

        @Override
        public Outcome inspect(String jobIdentifier) {
            asked.add("inspect");
            return new Inspected(new JobDetail(
                    new Job(JOB, TOPIC, "Adobe Replication Queue", JobState.ERROR, 3),
                    List.of("path", "agentId"), 10));
        }

        @Override
        public Outcome cancel(String jobIdentifier) {
            asked.add("cancel");
            return new Cancelled(JobState.SUCCEEDED);
        }
    }

    /** An inventory that will not answer anything, so every refusal path is reachable. */
    private static final class Refusing implements JobInventory {

        private static final Outcome REFUSAL =
                new Refused(JobCommands.INVENTORY_FAILED, "the job system could not be asked");

        @Override
        public Outcome queues() {
            return REFUSAL;
        }

        @Override
        public Outcome jobs(String topic, List<JobState> states) {
            return REFUSAL;
        }

        @Override
        public Outcome inspect(String jobIdentifier) {
            return REFUSAL;
        }

        @Override
        public Outcome cancel(String jobIdentifier) {
            return REFUSAL;
        }
    }

    private static CommandHandler.Answer run(JobHandler.Kind kind,
                                             DocumentValue.Mapping arguments) {
        return new JobHandler(CONTRACT, kind, new Inventory(), permissive())
                .run(arguments, null, context());
    }

    private static PlatformControl permissive() {
        return PlatformControl.of("aem-6-5-lts", Set.of(ControlCapability.values()));
    }

    private static DocumentValue.Mapping identifier(String jobIdentifier) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobCommands.JOB_IDENTIFIER, new DocumentValue.Text(jobIdentifier));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping search(List<String> states) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobCommands.STATES, new DocumentValue.Sequence(states.stream()
                .map(state -> (DocumentValue) new DocumentValue.Text(state)).toList()));
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
