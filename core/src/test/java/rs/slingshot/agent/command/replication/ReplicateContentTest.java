// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
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
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.ContentAdmission;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The command that offers content to replication and claims nothing about publication.
 *
 * <p>What is proved here is mostly what this command refuses to say. It commits nothing, because
 * what it changes is not the caller's repository; it answers a count of what was admitted and no
 * member that could be read as a claim that anything was published; and when the offer left this
 * process without an answer it says so rather than reporting a failure, because the platform may
 * well have taken it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ReplicateContentTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String SITE = "/content/site";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("one address alone is offered where the scope says so, and the subtree where it does not")
    void thescopeDecidesHowMuchTravels() {
        site();
        final Recorder recorder = new Recorder(new ContentAdmission.Admitted(1));
        assertInstanceOf(CommandHandler.Produced.class,
                run(recorder, argument(SITE + "/campaign", false)), "the offer was refused");
        assertEquals(List.of(SITE + "/campaign"), recorder.offered(),
                "an offer of one address carried more than that address");
        final Recorder subtree = new Recorder(new ContentAdmission.Admitted(3));
        assertInstanceOf(CommandHandler.Produced.class,
                run(subtree, argument(SITE + "/campaign", true)), "the offer was refused");
        assertEquals(List.of(SITE + "/campaign", SITE + "/campaign/spring",
                        SITE + "/campaign/summer"), subtree.offered(),
                "an offer of a subtree did not carry everything beneath the address");
    }

    @Test
    @DisplayName("the answer is a count of what was admitted and never a claim about publication")
    void theanswerClaimsNoPublication() {
        site();
        final DocumentValue.Mapping answered = assertInstanceOf(CommandHandler.Produced.class,
                run(new Recorder(new ContentAdmission.Admitted(3)),
                        argument(SITE + "/campaign", true)), "the offer was refused").result();
        assertEquals(List.of(ReplicateContentResult.ACCEPTED_ITEM_COUNT),
                List.copyOf(answered.members().keySet()),
                "the answer holds a member beyond the count of what was admitted");
        assertEquals(new DocumentValue.Whole(3),
                answered.member(ReplicateContentResult.ACCEPTED_ITEM_COUNT).orElseThrow());
        final String rendered = String.valueOf(answered);
        assertTrue(!rendered.contains("publish") && !rendered.contains("published"),
                "the answer says something about publication, which an author instance has no way"
                        + " to observe and this command therefore must not claim");
    }

    @Test
    @DisplayName("an offer nobody heard back about is unknown rather than failed")
    void anofferWithNoAnswerIsUnknown() {
        site();
        final CommandHandler.Failed unknown = assertInstanceOf(CommandHandler.Failed.class,
                run(new Recorder(new ContentAdmission.Unknown("the connection went away")),
                        argument(SITE + "/campaign", false)),
                "an offer nobody heard back about was reported as having succeeded");
        assertEquals(SingleCommit.ADMISSION_OUTCOME_UNKNOWN, unknown.category(),
                "an offer that may well have been taken was reported as a failure, and a caller"
                        + " told that believes their content is not queued when it may be");
        assertEquals(ReplicateContentHandler.ADMISSION_REJECTED,
                assertInstanceOf(CommandHandler.Failed.class,
                        run(new Recorder(new ContentAdmission.Rejected("no agent is configured")),
                                argument(SITE + "/campaign", false)),
                        "a refused offer was reported as having succeeded").category(),
                "a refusal and an unknown answer were reported the same way, and they are the two"
                        + " things a caller most needs told apart");
    }

    @Test
    @DisplayName("this command commits nothing, because what it changes is not the caller's repository")
    void thiscommandOwesNoCommit() {
        assertEquals(Optional.of(SingleCommit.Expectation.NO_COMMIT),
                SingleCommit.expectationOf(row(ReplicateContentCommand.WIRE_NAME)),
                "the row no longer says this command owes no commit, so the wrapper would demand a"
                        + " write nobody asked for");
        site();
        final Recorder recorder = new Recorder(new ContentAdmission.Admitted(1));
        assertInstanceOf(CommandHandler.Produced.class,
                run(recorder, argument(SITE + "/campaign", false)), "the offer was refused");
    }

    @Test
    @DisplayName("a source nothing is at is refused, and a caller offers only what they can read")
    void acallerOffersOnlyWhatTheyCanRead() {
        site();
        assertEquals(ReplicateContentHandler.SOURCE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        run(new Recorder(new ContentAdmission.Admitted(0)),
                                argument(SITE + "/nothing", false)),
                        "an address nothing is at was offered").category());
        final Recorder recorder = new Recorder(new ContentAdmission.Admitted(3));
        assertInstanceOf(CommandHandler.Produced.class,
                run(recorder, argument(SITE + "/campaign", true)), "the offer was refused");
        assertTrue(recorder.offered().stream().allMatch(
                        path -> sling.resourceResolver().getResource(path) != null),
                "an address this caller cannot read was offered, and a caller may offer exactly"
                        + " what they can read and no more");
    }

    @Test
    @DisplayName("the two budgets are told apart, and the smaller one is what the walk stops at")
    void thetwoBudgetsAreToldApart() {
        assertEquals(Optional.empty(), ReplicateContentHandler.budgetRefusal(2, 4, 8),
                "a candidate set within both bounds was refused");
        assertEquals(Optional.of(ReplicateContentHandler.CANDIDATE_LIMIT_EXCEEDED),
                ReplicateContentHandler.budgetRefusal(6, 4, 8),
                "a set larger than one offer carries and smaller than the walk was not reported as"
                        + " the offer's own bound");
        assertEquals(Optional.of(ReplicateContentHandler.TRAVERSAL_BUDGET_EXCEEDED),
                ReplicateContentHandler.budgetRefusal(9, 4, 8),
                "a subtree this side would not enumerate at all was reported as the offer's bound"
                        + " rather than the walk's, and the two have different answers: offer a"
                        + " smaller root, or offer this one in parts");
    }

    @Test
    @DisplayName("a subtree past the caller's own traversal budget is refused with nothing offered")
    void asubtreePastTheTraversalBudgetIsRefused() {
        site();
        final Recorder recorder = new Recorder(new ContentAdmission.Admitted(3));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new ReplicateContentHandler(CONTRACT, recorder).run(
                        argument(SITE + "/campaign", true), sling.resourceResolver(),
                        narrow()),
                "a subtree past this caller's own budget was offered");
        assertEquals(ReplicateContentHandler.TRAVERSAL_BUDGET_EXCEEDED, refused.category());
        assertEquals(List.of(), recorder.offered(),
                "a refused offer still handed addresses to the platform, and half a subtree in a"
                        + " publish queue is a site that renders half old and half new");
    }

    @Test
    @DisplayName("the scope is one of the two things a flag is, and neither member has a default")
    void thescopeAndTheAddressAreBothRequired() {
        final SequencedMap<String, DocumentValue> scopeless = new LinkedHashMap<>();
        scopeless.put(ReplicateContentCommand.PATH, new DocumentValue.Text(SITE));
        assertEquals(ReplicateContentCommand.Refusal.MEMBER_ABSENT,
                refused(new DocumentValue.Mapping(scopeless)).refusal());
        final SequencedMap<String, DocumentValue> spelled = new LinkedHashMap<>();
        spelled.put(ReplicateContentCommand.PATH, new DocumentValue.Text(SITE));
        spelled.put(SubtreeScope.ARGUMENT_MEMBER, new DocumentValue.Text("true"));
        assertEquals(ReplicateContentCommand.Refusal.SCOPE_REJECTED,
                refused(new DocumentValue.Mapping(spelled)).refusal(),
                "a scope spelled as text was accepted");
        assertEquals(ReplicateContentCommand.Refusal.NOT_A_DOCUMENT,
                refused(new DocumentValue.Text(SITE)).refusal());
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put(ReplicateContentCommand.PATH, new DocumentValue.Text(SITE));
        unknown.put(SubtreeScope.ARGUMENT_MEMBER, new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        unknown.put("agent_name", new DocumentValue.Text("publish"));
        assertEquals(ReplicateContentCommand.Refusal.MEMBER_UNKNOWN,
                refused(new DocumentValue.Mapping(unknown)).refusal());
        final SequencedMap<String, DocumentValue> relative = new LinkedHashMap<>();
        relative.put(ReplicateContentCommand.PATH, new DocumentValue.Text("content/site"));
        relative.put(SubtreeScope.ARGUMENT_MEMBER,
                new DocumentValue.Flag(DocumentValue.Truth.FALSE));
        assertEquals(ReplicateContentCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refused(new DocumentValue.Mapping(relative)).refusal());
    }

    @Test
    @DisplayName("the row is the client's own and the handler declares exactly what it declares")
    void therowIsTheClientsOwn() {
        assertEquals(row(ReplicateContentCommand.WIRE_NAME).failureCategories().stream()
                        .sorted().toList(),
                ReplicateContentHandler.declaredCategories().stream().sorted().toList(),
                "this command and its handler disagree about what it can fail with");
        assertEquals(RegistryRow.OperationKey.REQUIRED,
                row(ReplicateContentCommand.WIRE_NAME).operationKey(),
                "offering the same subtree twice queues it twice, and this row no longer requires"
                        + " a key");
        assertTrue(ReplicateContentHandler.declaredCategories().containsAll(
                        Arrays.stream(ReplicateContentCommand.Refusal.values())
                                .map(ReplicateContentHandler::categoryFor).toList()),
                "an argument refusal reaches a category this command's own row does not declare");
        assertTrue(CONTRACT.value(ContractLimit.MAXIMUM_REPLICATION_CANDIDATE_PATHS) > 0,
                "the contract states no bound on how many items one offer carries");
    }

    /** An admission that remembers what it was handed and answers whatever it was built with. */
    private static final class Recorder implements ContentAdmission {

        private final List<String> seen = new ArrayList<>();
        private final Outcome answer;

        Recorder(Outcome answer) {
            this.answer = answer;
        }

        @Override
        public Outcome offer(List<String> paths, ResourceResolver session) {
            seen.addAll(paths);
            return answer;
        }

        List<String> offered() {
            return List.copyOf(seen);
        }
    }

    private CommandHandler.Answer run(ContentAdmission admission,
                                      DocumentValue.Mapping arguments) {
        return new ReplicateContentHandler(CONTRACT, admission)
                .run(arguments, sling.resourceResolver(), context());
    }

    private static DocumentValue.Mapping argument(String path, boolean recursive) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ReplicateContentCommand.PATH, new DocumentValue.Text(path));
        members.put(SubtreeScope.ARGUMENT_MEMBER, new DocumentValue.Flag(
                recursive ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(members);
    }

    private static ReplicateContentCommand.Refused refused(DocumentValue argument) {
        return assertInstanceOf(ReplicateContentCommand.Refused.class,
                ReplicateContentCommand.of(argument, CONTRACT),
                "an argument this command does not take was accepted");
    }

    private void site() {
        for (final String path : List.of(SITE, SITE + "/campaign", SITE + "/campaign/spring",
                SITE + "/campaign/summer")) {
            sling.create().resource(path, Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        }
    }

    private static CallerContext context() {
        return contextWith(Budget.discovery(CONTRACT));
    }

    /** A caller whose own budget will not reach past a handful of nodes. */
    private static CallerContext narrow() {
        return contextWith(new Budget(Budget.Kind.DISCOVERY, 1));
    }

    private static CallerContext contextWith(Budget discovery) {
        return new CallerContext(operation(), discovery, Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
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
