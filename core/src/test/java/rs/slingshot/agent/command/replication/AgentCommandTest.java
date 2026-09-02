// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

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
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The five commands about the replication agents.
 *
 * <p>These are what somebody reaches for when a page has been published and is not there. The rule
 * under test throughout is that no transport address appears anywhere: an agent's transport is a
 * URL, and a URL to a publish instance very frequently carries the credential it authenticates
 * with — a listing of agents would otherwise be one of the easiest places on an author instance to
 * collect passwords.</p>
 */
final class AgentCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String AGENT = "publish";

    private static final String ENTRY = "2026/9/2/entry_1";

    private static final String SECRET_TRANSPORT = "https://admin:hunter2@publish:4503/bin/receive";

    @Test
    @DisplayName("no answer carries a transport address, in any of the three that read")
    void noanswerCarriesATransportAddress() {
        for (final var pair : List.of(
                Map.entry(AgentHandler.Kind.LISTING,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(AgentHandler.Kind.AGENT, identifier(AGENT)),
                Map.entry(AgentHandler.Kind.QUEUE, identifier(AGENT)))) {
            final DocumentValue.Mapping answered = assertInstanceOf(CommandHandler.Produced.class,
                    run(pair.getKey(), pair.getValue()), pair.getKey() + " was refused").result();
            assertTrue(!String.valueOf(answered).contains("hunter2")
                            && !String.valueOf(answered).contains("publish:4503"),
                    pair.getKey() + " carried a transport address, and a URL to a publish instance"
                            + " very frequently carries the credential it authenticates with: "
                            + answered);
        }
        assertTrue(!AgentResults.LISTING_MEMBERS.contains("transport_uri")
                        && !AgentResults.AGENT_MEMBERS.contains("transport_uri"),
                "a result member exists that a transport address could travel in");
    }

    @Test
    @DisplayName("an agent says whether it is off and whether its queue is stuck, separately")
    void thetwoReasonsNothingIsReplicatedAreSeparate() {
        final DocumentValue.Mapping read = assertInstanceOf(CommandHandler.Produced.class,
                run(AgentHandler.Kind.AGENT, identifier(AGENT)),
                "the inspection was refused").result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                read.member(AgentResults.ENABLED).orElseThrow());
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                read.member(AgentResults.QUEUE_BLOCKED).orElseThrow(),
                "an agent that is on with a stuck queue was reported as one thing, and those are"
                        + " the two different reasons nothing is being replicated — with"
                        + " different fixes");
        assertEquals(new DocumentValue.Text("publish"),
                read.member(AgentResults.TRANSPORT_KIND).orElseThrow());
        assertTrue(read.member(AgentResults.REPOSITORY_PATH).isPresent(),
                "an agent does not say where its configuration is held, so an operator cannot go"
                        + " and read it under the repository's own access control");
        assertEquals(new DocumentValue.Whole(60000),
                read.member(AgentResults.RETRY_DELAY_MILLISECONDS).orElseThrow());
    }

    @Test
    @DisplayName("a queue entry says what it would do and why it last failed")
    void aqueueEntrySaysWhatItWouldDo() {
        final DocumentValue.Mapping read = assertInstanceOf(CommandHandler.Produced.class,
                run(AgentHandler.Kind.QUEUE, identifier(AGENT)),
                "the queue inspection was refused").result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                read.member(AgentResults.BLOCKED).orElseThrow());
        final List<DocumentValue> entries = assertInstanceOf(DocumentValue.Sequence.class,
                read.member(AgentResults.ENTRIES).orElseThrow()).items();
        final DocumentValue.Mapping first = assertInstanceOf(DocumentValue.Mapping.class,
                entries.getFirst());
        assertEquals(new DocumentValue.Text("activate"),
                first.member(AgentResults.ACTION).orElseThrow());
        assertEquals(new DocumentValue.Text("/content/site/article"),
                first.member(AgentResults.CONTENT_PATH).orElseThrow());
        assertTrue(first.member(AgentResults.LAST_FAILURE_CATEGORY).isPresent(),
                "an entry that has failed does not say why, which is the thing an operator came"
                        + " to read");
        assertTrue(assertInstanceOf(DocumentValue.Mapping.class, entries.get(1))
                        .member(AgentResults.LAST_FAILURE_CATEGORY).isEmpty(),
                "an entry that has never failed was given a failure category");
    }

    @Test
    @DisplayName("a flush carries the count the caller saw, and a mismatch is refused")
    void aflushCarriesTheExpectedCount() {
        final AgentCommands.Flush held = assertInstanceOf(AgentCommands.Flush.class,
                AgentCommands.flush(flush(AGENT, 41), CONTRACT), "a flush was refused");
        assertEquals(41, held.expectation());
        assertEquals(ReplicationInventory.ANY_COUNT,
                assertInstanceOf(AgentCommands.Flush.class,
                        AgentCommands.flush(identifier(AGENT), CONTRACT),
                        "a flush naming no count was refused").expectation(),
                "a flush naming no count was given one, and emptying whatever is there is a"
                        + " defensible thing to ask for");
        final Inventory mismatched = new Inventory();
        mismatched.refuse(AgentCommands.QUEUE_EXPECTATION_MISMATCH, "it now holds forty-nine");
        assertEquals(AgentCommands.QUEUE_EXPECTATION_MISMATCH,
                assertInstanceOf(CommandHandler.Failed.class,
                        new AgentHandler(CONTRACT, AgentHandler.Kind.FLUSH, mismatched,
                                permissive()).run(flush(AGENT, 41), null, context()),
                        "a queue that filled up between the read and the flush was emptied"
                                + " anyway, losing work the caller never saw").category());
        assertEquals(AgentCommands.QUEUE_EXPECTATION_MISMATCH,
                AgentHandler.categoryFor(AgentCommands.Refusal.EXPECTATION_REJECTED));
    }

    @Test
    @DisplayName("a deployment without replication control refuses the two actions, not the reads")
    void thereadsSurviveADeploymentThatPermitsNoAction() {
        final Inventory inventory = new Inventory();
        final PlatformControl refusing = PlatformControl.of("aem-cloud-service", Set.of());
        for (final var pair : List.of(
                Map.entry(AgentHandler.Kind.FLUSH, flush(AGENT, 41)),
                Map.entry(AgentHandler.Kind.RETRY, retry(AGENT, ENTRY)))) {
            assertEquals(PlatformControl.NOT_PERMITTED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new AgentHandler(CONTRACT, pair.getKey(), inventory, refusing)
                                    .run(pair.getValue(), null, context()),
                            pair.getKey() + " was carried out on a deployment that does not permit"
                                    + " it").category());
        }
        assertEquals(List.of(), inventory.calls(),
                "the platform was asked to act on a queue on a deployment that does not permit it");
        assertInstanceOf(CommandHandler.Produced.class,
                new AgentHandler(CONTRACT, AgentHandler.Kind.QUEUE, inventory, refusing)
                        .run(identifier(AGENT), null, context()),
                "reading a stuck queue was refused, and the deployment that will not let a queue"
                        + " be flushed is exactly the one where an operator most needs to see why"
                        + " it is stuck");
    }

    @Test
    @DisplayName("a missing entry is told apart from a missing agent, because the fixes differ")
    void amissingEntryIsItsOwnRefusal() {
        assertEquals(AgentCommands.Refusal.ENTRY_ABSENT,
                assertInstanceOf(AgentCommands.RetryRefused.class,
                        AgentCommands.retry(identifier(AGENT), CONTRACT),
                        "a retry naming no entry was accepted").refusal().refusal());
        assertEquals(AgentCommands.ENTRY_NOT_FOUND,
                AgentHandler.retryCategoryFor(AgentCommands.Refusal.ENTRY_ABSENT),
                "a missing entry and a missing agent were reported the same way, and one of them"
                        + " typed the wrong agent while the other is looking at a queue whose"
                        + " entry has already gone");
        assertEquals(AgentCommands.AGENT_NOT_FOUND,
                AgentHandler.retryCategoryFor(AgentCommands.Refusal.IDENTIFIER_REJECTED));
        assertEquals(AgentCommands.Refusal.MEMBER_ABSENT,
                assertInstanceOf(AgentCommands.RetryRefused.class,
                        AgentCommands.retry(new DocumentValue.Mapping(new LinkedHashMap<>()),
                                CONTRACT), "a retry naming no agent was accepted")
                        .refusal().refusal());
    }

    @Test
    @DisplayName("a retry says whether the platform took the entry back")
    void aretrySaysWhetherItWasTaken() {
        final DocumentValue.Mapping offered = assertInstanceOf(CommandHandler.Produced.class,
                run(AgentHandler.Kind.RETRY, retry(AGENT, ENTRY)),
                "the retry was refused").result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                offered.member(AgentResults.RESUBMITTED).orElseThrow(),
                "the answer does not say whether the platform took the entry, so a caller cannot"
                        + " tell an offer that landed from one that was quietly dropped");
        assertEquals(new DocumentValue.Text(ENTRY),
                offered.member(AgentResults.ENTRY_IDENTIFIER).orElseThrow());
    }

    @Test
    @DisplayName("a platform that could not be asked is reported as it saying so, in all five")
    void aplatformFailureReachesEveryCommand() {
        final Inventory refusing = new Inventory();
        refusing.refuse(AgentCommands.AGENT_ACCESS_DENIED, "this caller may not reach it");
        for (final var pair : attempts()) {
            assertEquals(AgentCommands.AGENT_ACCESS_DENIED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new AgentHandler(CONTRACT, pair.getKey(), refusing, permissive())
                                    .run(pair.getValue(), null, context()),
                            pair.getKey() + " reported a platform refusal as an answer").category());
        }
    }

    @Test
    @DisplayName("an argument none of the five takes is refused before the platform is asked")
    void abadArgumentNeverReachesThePlatform() {
        final Inventory inventory = new Inventory();
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("transport_uri", new DocumentValue.Text(SECRET_TRANSPORT));
        for (final var pair : attempts()) {
            assertInstanceOf(CommandHandler.Failed.class,
                    new AgentHandler(CONTRACT, pair.getKey(), inventory, permissive())
                            .run(new DocumentValue.Mapping(unknown), null, context()),
                    pair.getKey() + " accepted a member nobody declared");
        }
        assertEquals(List.of(), inventory.calls(),
                "the platform was asked to act on an argument this build had already refused");
    }

    @Test
    @DisplayName("a listing past the caller's own budget is refused rather than shortened")
    void alistingPastTheBudgetIsRefused() {
        final CallerContext narrow = contextWith(new Budget(Budget.Kind.DISCOVERY, 1));
        for (final var pair : List.of(
                Map.entry(AgentHandler.Kind.LISTING,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(AgentHandler.Kind.QUEUE, identifier(AGENT)))) {
            assertEquals(AgentCommands.DISCOVERY_BUDGET_EXCEEDED,
                    assertInstanceOf(CommandHandler.Failed.class,
                            new AgentHandler(CONTRACT, pair.getKey(), new Inventory(),
                                    permissive()).run(pair.getValue(), null, narrow),
                            pair.getKey() + " answered a shortened list past the caller's budget,"
                                    + " which reads as the complete answer").category());
        }
        assertEquals(1, AgentHandler.pageOf(List.of("a", "b"),
                new ResultWindow.Initial(1, 1)).size());
        assertEquals(List.of("a", "b"), AgentHandler.pageOf(List.of("a", "b"),
                new ResultWindow.Continuation("token")));
    }

    @Test
    @DisplayName("every transport kind and every action has a spelling, and each names one thing")
    void thevocabularyRoundTrips() {
        assertEquals(ReplicationInventory.TransportKind.values().length,
                ReplicationInventory.TransportKind.spellings().size());
        ReplicationInventory.TransportKind.spellings().forEach(spelled ->
                assertTrue(ReplicationInventory.TransportKind.named(spelled).isPresent(),
                        spelled + " is a spelling nothing names"));
        assertEquals(java.util.Optional.empty(),
                ReplicationInventory.TransportKind.named("dispatcher"));
        assertEquals(ReplicationInventory.Action.values().length,
                ReplicationInventory.Action.spellings().size());
        ReplicationInventory.Action.spellings().forEach(spelled ->
                assertTrue(ReplicationInventory.Action.named(spelled).isPresent(),
                        spelled + " is a spelling nothing names"));
        assertEquals(java.util.Optional.empty(), ReplicationInventory.Action.named("publish"));
        assertTrue(ReplicationInventory.Action.spellings().contains("test"),
                "the action that checks a transport works without carrying content is gone, and"
                        + " that is the one an operator runs first");
    }

    @Test
    @DisplayName("all five rows are the client's own and every handler declares exactly them")
    void allfiveRowsAreTheClientsOwn() {
        for (final var pair : List.of(
                Map.entry(AgentCommands.LIST_WIRE_NAME, AgentCommands.listingCategories()),
                Map.entry(AgentCommands.INSPECT_AGENT_WIRE_NAME, AgentCommands.agentCategories()),
                Map.entry(AgentCommands.INSPECT_QUEUE_WIRE_NAME, AgentCommands.queueCategories()),
                Map.entry(AgentCommands.FLUSH_WIRE_NAME, AgentCommands.flushCategories()),
                Map.entry(AgentCommands.RETRY_WIRE_NAME, AgentCommands.retryCategories()))) {
            assertEquals(row(pair.getKey()).failureCategories().stream().sorted().toList(),
                    pair.getValue().stream().sorted().toList(),
                    pair.getKey() + " and its handler disagree about what it can fail with");
        }
        assertEquals(RegistryRow.OperationKey.REQUIRED,
                row(AgentCommands.FLUSH_WIRE_NAME).operationKey(),
                "emptying a queue discards work somebody expected to happen, and this row no"
                        + " longer requires a key");
        assertEquals(RegistryRow.OperationKey.REFUSED,
                row(AgentCommands.LIST_WIRE_NAME).operationKey());
        assertTrue(AgentCommands.flushCategories().containsAll(
                        java.util.Arrays.stream(AgentCommands.Refusal.values())
                                .map(AgentHandler::categoryFor).toList()),
                "a flush refusal reaches a category this command's own row does not declare");
    }

    private static List<Map.Entry<AgentHandler.Kind, DocumentValue.Mapping>> attempts() {
        return List.of(
                Map.entry(AgentHandler.Kind.LISTING,
                        new DocumentValue.Mapping(new LinkedHashMap<>())),
                Map.entry(AgentHandler.Kind.AGENT, identifier(AGENT)),
                Map.entry(AgentHandler.Kind.QUEUE, identifier(AGENT)),
                Map.entry(AgentHandler.Kind.FLUSH, flush(AGENT, 41)),
                Map.entry(AgentHandler.Kind.RETRY, retry(AGENT, ENTRY)));
    }

    /** An inventory that remembers what it was asked and answers from a fixed instance. */
    private static final class Inventory implements ReplicationInventory {

        private final List<String> asked = new ArrayList<>();
        private final List<Refused> refusal = new ArrayList<>();

        void refuse(String category, String detail) {
            refusal.add(new Refused(category, detail));
        }

        List<String> calls() {
            return List.copyOf(asked);
        }

        private Outcome held(String call, Outcome answer) {
            asked.add(call);
            return refusal.isEmpty() ? answer : refusal.getFirst();
        }

        private static Agent agent() {
            return new Agent(AGENT, "Default publish agent",
                    "/etc/replication/agents.author/publish", TransportKind.PUBLISH,
                    Switch.ENABLED, Flow.BLOCKED, 41);
        }

        @Override
        public Outcome agents() {
            return held("agents", new Agents(List.of(agent(),
                    new Agent("flush", "Dispatcher flush", "/etc/replication/agents.author/flush",
                            TransportKind.FLUSH, Switch.DISABLED, Flow.MOVING, 0))));
        }

        @Override
        public Outcome inspect(String agentIdentifier) {
            return held("inspect", new Inspected(agent(), 60000));
        }

        @Override
        public Outcome queue(String agentIdentifier) {
            return held("queue", new Queue(Flow.BLOCKED, List.of(
                    new Entry(ENTRY, Action.ACTIVATE, "/content/site/article", 9,
                            "transport_failed"),
                    new Entry(ENTRY + "_2", Action.DELETE, "/content/site/old", 0,
                            NEVER_FAILED))));
        }

        @Override
        public Outcome flush(String agentIdentifier, long expectation) {
            return held("flush", new Flushed(41));
        }

        @Override
        public Outcome retry(String agentIdentifier, String entryIdentifier) {
            return held("retry", new Resubmitted(Resubmission.TAKEN));
        }
    }

    private static CommandHandler.Answer run(AgentHandler.Kind kind,
                                             DocumentValue.Mapping arguments) {
        return new AgentHandler(CONTRACT, kind, new Inventory(), permissive())
                .run(arguments, null, context());
    }

    private static PlatformControl permissive() {
        return PlatformControl.of("aem-6-5-lts", Set.of(ControlCapability.values()));
    }

    private static DocumentValue.Mapping identifier(String agentIdentifier) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(AgentCommands.AGENT_IDENTIFIER, new DocumentValue.Text(agentIdentifier));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping flush(String agentIdentifier, long expected) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(AgentCommands.AGENT_IDENTIFIER, new DocumentValue.Text(agentIdentifier));
        members.put(AgentCommands.EXPECTED_ENTRY_COUNT, new DocumentValue.Whole(expected));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping retry(String agentIdentifier, String entryIdentifier) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(AgentCommands.AGENT_IDENTIFIER, new DocumentValue.Text(agentIdentifier));
        members.put(AgentCommands.ENTRY_IDENTIFIER, new DocumentValue.Text(entryIdentifier));
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
