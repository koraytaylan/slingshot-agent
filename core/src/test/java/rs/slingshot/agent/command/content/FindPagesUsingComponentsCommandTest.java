// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which pages use a component, answered once per page.
 *
 * <p>The assertion that matters is the deduplication. A page holding many nodes of one type is one
 * entry with that type listed once: a caller planning a migration is deciding scope, and an answer
 * whose length was the node count rather than the page count would be useless for exactly that.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class FindPagesUsingComponentsCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** A component type the corpus uses many times on one page. */
    private static final String TEASER = "site/components/teaser";

    /** A second component type, so a page can carry two distinct ones. */
    private static final String CAROUSEL = "site/components/carousel";

    /** A component type nothing in the corpus uses. */
    private static final String UNUSED = "site/components/nobody-uses-this";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a page holding many matching nodes appears once")
    void apageAppearsOnceHoweverManyNodesMatch() {
        corpus();
        final DocumentValue.Mapping found = listed(List.of(TEASER, CAROUSEL));
        assertEquals(1, matchesIn(found).size(),
                "a page holding " + TEASERS + " matching nodes was answered more than once, so the"
                        + " answer's length is the node count rather than the page count and"
                        + " nobody can decide scope from it");
        assertEquals(List.of("/content/site/article"), pathsFrom(found));
    }

    @Test
    @DisplayName("matching all of the types answers only the pages using every one of them")
    void allmeansEveryTypeRatherThanAnyOfThem() {
        corpus();
        assertEquals(List.of("/content/site/article"),
                pathsFrom(listed(List.of(TEASER, CAROUSEL), MatchMode.ALL)),
                "the page using both components was not the answer to a search for both");
        assertEquals(List.of(),
                pathsFrom(listed(List.of(TEASER, UNUSED), MatchMode.ALL)),
                "a page using one of two named components answered a search for both, which is"
                        + " the search for either wearing the other one's name");
        assertEquals(List.of("/content/site/article"),
                pathsFrom(listed(List.of(TEASER, UNUSED), MatchMode.ANY)),
                "the same page stopped matching a search for either of the two");
    }

    /** How many teaser nodes the one matching page holds. */
    private static final int TEASERS = 5;

    @Test
    @DisplayName("a component type nothing uses yields an empty page rather than a refusal")
    void anunusedTypeYieldsAnEmptyPage() {
        corpus();
        final DocumentValue.Mapping none = listed(List.of(UNUSED));
        assertEquals(0, matchesIn(none).size(),
                "a component nothing uses was not answered with an empty list");
    }

    @Test
    @DisplayName("an empty component list is refused at construction rather than matching everything")
    void anemptyListIsRefused() {
        assertEquals(FindPagesUsingComponentsCommand.Refusal.NO_COMPONENT_TYPES,
                refusalOf(argument("/content", List.of(), 25)).refusal(),
                "an empty component list was accepted, and a search for no component in particular"
                        + " is a walk of every node under the root");
        final long bound =
                CONTRACT.value(ContractLimit.MAXIMUM_REQUESTED_COMPONENT_RESOURCE_TYPES);
        assertEquals(FindPagesUsingComponentsCommand.Refusal.TOO_MANY_COMPONENT_TYPES,
                refusalOf(argument("/content",
                        java.util.stream.IntStream.rangeClosed(0, (int) bound)
                                .mapToObj(type -> "site/components/type-" + type)
                                .toList(), 25)).refusal());
    }

    @Test
    @DisplayName("a match inside a nested page belongs to the nearest page above it")
    void amatchBelongsToTheNearestPage() {
        corpus();
        page("/content/site/article/child");
        component("/content/site/article/child/jcr:content/teaser", TEASER);
        final DocumentValue.Mapping found = listed(List.of(TEASER));
        assertEquals(List.of("/content/site/article", "/content/site/article/child"),
                pathsFrom(found).stream().sorted().toList(),
                "a match inside a nested page was folded up to the outer page, so a migration"
                        + " would be told the wrong page holds the component");
    }

    @Test
    @DisplayName("an argument missing any member is refused, and none of the three is defaulted")
    void nomemberIsDefaulted() {
        assertEquals(FindPagesUsingComponentsCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument(null, List.of(TEASER), 25)).refusal());
        assertEquals(FindPagesUsingComponentsCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content", List.of(TEASER), 25)).refusal());
        assertEquals(FindPagesUsingComponentsCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument("/content", List.of(TEASER), 0)).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and matches what the handler can fail with")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new FindPagesUsingComponentsHandler(CONTRACT).categories().stream().sorted()
                        .toList(),
                "the handler and its row disagree about what this command can fail with");
    }

    private DocumentValue.Mapping listed(List<String> types) {
        return listed(types, MatchMode.ANY);
    }

    private DocumentValue.Mapping listed(List<String> types, MatchMode mode) {
        return assertInstanceOf(CommandHandler.Produced.class,
                new FindPagesUsingComponentsHandler(CONTRACT)
                        .run(argument("/content/site", types, 100, mode), readOnly(), context()),
                "the search was refused").result();
    }

    private static List<String> pathsFrom(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES)
                .orElseThrow()).items().stream()
                .map(page -> ((DocumentValue.Mapping) page)
                        .member(PageListingResult.REPOSITORY_PATH).orElseThrow())
                .map(path -> ((DocumentValue.Text) path).value())
                .toList();
    }

    private static List<DocumentValue> matchesIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items();
    }

    private void corpus() {
        page("/content/site");
        page("/content/site/article");
        for (int teaser = 0; teaser < TEASERS; teaser = teaser + 1) {
            component("/content/site/article/jcr:content/teaser-" + teaser, TEASER);
        }
        component("/content/site/article/jcr:content/carousel", CAROUSEL);
        page("/content/site/plain");
        component("/content/site/plain/jcr:content/text", "site/components/text");
    }

    private void page(String path) {
        sling.create().resource(path, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
    }

    private void component(String path, String type) {
        sling.create().resource(path, Map.of(
                FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY, type));
    }

    private static FindPagesUsingComponentsCommand.Refused refusalOf(
            DocumentValue.Mapping arguments) {
        return assertInstanceOf(FindPagesUsingComponentsCommand.Refused.class,
                FindPagesUsingComponentsCommand.of(arguments, CONTRACT),
                "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(String root, List<String> types, long limit) {
        return argument(root, types, limit, MatchMode.ANY);
    }

    private static DocumentValue.Mapping argument(String root, List<String> types, long limit,
                                                  MatchMode mode) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FindPagesUsingComponentsCommand.MATCH_MODE,
                new DocumentValue.Text(mode.spelling()));
        if (root != null) {
            members.put(FindPagesUsingComponentsCommand.ROOT_PATH, new DocumentValue.Text(root));
        }
        members.put(FindPagesUsingComponentsCommand.RESOURCE_TYPES,
                new DocumentValue.Sequence(types.stream()
                        .map(type -> (DocumentValue) new DocumentValue.Text(type))
                        .toList()));
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        window.put(ResultWindow.OFFSET, new DocumentValue.Whole(0));
        window.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        members.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(window));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row() {
        return assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(REPOSITORY.resolve("policy/commands")),
                "the committed registry was refused").registry()
                .row(FindPagesUsingComponentsCommand.WIRE_NAME).orElseThrow();
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
