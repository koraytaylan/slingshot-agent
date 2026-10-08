// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.IntStream;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/** Page searches must make explicit bounded progress without losing or duplicating matches. */
@ExtendWith(SlingContextExtension.class)
final class PageSearchProgressTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String ROOT = "/content/synthetic-search";
    private static final String PHRASE = "synthetic-needle";
    private static final String TEXT = "synthetic/components/text";
    private static final String IMAGE = "synthetic/components/image";
    private static final long NODE_BUDGET = 4;
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);
    private final DiscoveryRegistry discovery = new DiscoveryRegistry(CONTRACT);

    @AfterEach
    void releaseDiscovery() {
        discovery.close();
    }

    private enum Search {
        PHRASE, ABSENT_PHRASE, ANY_COMPONENT, ALL_COMPONENTS
    }

    @ParameterizedTest
    @EnumSource(Search.class)
    void sparseSearchResumesWithinEachBudgetAndEnumeratesExactUniquePages(Search search)
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        assertEquals(expected(search), enumerate(search, ROOT),
                "the complete search lost a page, included a nonmatch, or changed ANY/ALL semantics");
    }

    @ParameterizedTest
    @CsvSource({
        "synthetic-needle, synthetic-needle, true",
        "synthetic-needle, synthetic-NEEDLE, false",
        "synthetic-NEEDLE, synthetic-needle, false",
        "synthetic-needle, synthetic-prefix synthetic-needle synthetic-suffix, true",
        "synthetic needle, synthetic separate needle, false",
        "caf\u00e9, cafe\u0301, false",
        "Stra\u00dfe, Strasse, false",
        "i, I, false"
    })
    void phraseMatchingPreservesTheClientsExactUnicodeContract(String phrase, String stored, boolean expected)
            throws org.apache.sling.api.resource.PersistenceException {
        sling.create().resource(ROOT, "jcr:primaryType", "cq:Page");
        sling.create().resource(ROOT + "/jcr:content", Map.of("jcr:primaryType", "cq:PageContent",
                "jcr:title", "", "jcr:description", stored));
        sling.resourceResolver().commit();
        final SequencedMap<String, DocumentValue> values = new LinkedHashMap<>(
                arguments(Search.PHRASE, ROOT, "").members());
        values.put("phrase", new DocumentValue.Text(phrase));
        final var result = produced(handler(Search.PHRASE).run(new DocumentValue.Mapping(values),
                cursorResolver(), context()));
        assertEquals(expected ? 1 : 0, matches(result).size(),
                "the agent changed the exact contiguous phrase declared by the client");
    }

    @ParameterizedTest
    @EnumSource(value = Search.class, names = {"PHRASE", "ANY_COMPONENT"})
    void aPageTitleExactlyAtTheUtf8ByteBoundIsReturnedIntact(Search search)
            throws org.apache.sling.api.resource.PersistenceException {
        final String title = boundedTitle();
        titlePage(title);
        final var result = produced(handler(search).run(arguments(search, ROOT, ""),
                cursorResolver(), context(20)));
        final var row = assertInstanceOf(DocumentValue.Mapping.class, matches(result).getFirst());
        assertEquals(title, assertInstanceOf(DocumentValue.Text.class,
                row.member("title").orElseThrow()).value());
    }

    @ParameterizedTest
    @EnumSource(value = Search.class, names = {"PHRASE", "ANY_COMPONENT"})
    void aPageTitleOneUtf8ByteBeyondTheBoundRefusesWithoutTruncation(Search search)
            throws org.apache.sling.api.resource.PersistenceException {
        titlePage(boundedTitle() + "x");
        final var refused = assertInstanceOf(CommandHandler.Failed.class,
                handler(search).run(arguments(search, ROOT, ""), cursorResolver(), context(20)),
                "a producer must not send a page title that the native contract rejects");
        assertEquals("discovery_budget_exceeded", refused.category());
    }

    private static String boundedTitle() {
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES);
        final String title = "\u00e9".repeat(Math.toIntExact(bound / 2));
        assertEquals(bound, title.getBytes(StandardCharsets.UTF_8).length);
        return title;
    }

    private void titlePage(String title) throws org.apache.sling.api.resource.PersistenceException {
        sling.create().resource(ROOT, "jcr:primaryType", "cq:Page");
        sling.create().resource(ROOT + "/jcr:content", Map.of("jcr:primaryType", "cq:PageContent",
                "jcr:title", title, "jcr:description", PHRASE));
        sling.create().resource(ROOT + "/jcr:content/text", "sling:resourceType", TEXT);
        sling.resourceResolver().commit();
    }

    @Test
    void phraseSearchKeepsNestedPagesAndPrunesFoldersInsidePages()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        matchingPage(ROOT + "/page-1/nested");
        sling.create().resource(ROOT + "/page-1/hidden", "jcr:primaryType", "sling:Folder");
        matchingPage(ROOT + "/page-1/hidden/excluded");
        sling.resourceResolver().commit();
        assertEquals(Set.of(ROOT + "/page-1", ROOT + "/page-2", ROOT + "/page-3",
                ROOT + "/page-1/nested"), enumerate(Search.PHRASE, ROOT));
    }

    @Test
    void globalPhraseSearchPrunesNonSitesAndUnsearchedBodyText()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        matchingPage("/content/dam/synthetic-excluded");
        matchingPage("/apps/synthetic-excluded");
        sling.create().resource(ROOT + "/body-only", "jcr:primaryType", "cq:Page");
        sling.create().resource(ROOT + "/body-only/jcr:content", Map.of(
                "jcr:primaryType", "cq:PageContent", "body", PHRASE));
        sling.resourceResolver().commit();
        assertEquals(expected(Search.PHRASE), enumerate(Search.PHRASE, "/"));
    }

    @Test
    void explicitNonSiteAnchorStillSearchesItsOwnPages()
            throws org.apache.sling.api.resource.PersistenceException {
        matchingPage("/content/dam/synthetic-included");
        sling.resourceResolver().commit();
        assertEquals(Set.of("/content/dam/synthetic-included"),
                enumerate(Search.PHRASE, "/content/dam"));
    }

    @Test
    void componentAnchorInsidePageReturnsItsContainingPage()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        assertEquals(Set.of(ROOT + "/page-1"),
                enumerate(Search.ALL_COMPONENTS, ROOT + "/page-1/jcr:content"));
    }

    @Test
    void componentAllDoesNotCombineTypesFromParentAndNestedPages()
            throws org.apache.sling.api.resource.PersistenceException {
        matchingPage(ROOT);
        matchingPage(ROOT + "/nested");
        sling.create().resource(ROOT + "/jcr:content/text", "sling:resourceType", TEXT);
        sling.create().resource(ROOT + "/nested/jcr:content/image", "sling:resourceType", IMAGE);
        sling.resourceResolver().commit();
        assertEquals(Set.of(), enumerate(Search.ALL_COMPONENTS, ROOT));
        assertEquals(Set.of(ROOT, ROOT + "/nested"), enumerate(Search.ANY_COMPONENT, ROOT));
    }

    @Test
    void removedWitnessCannotAuthorizeAnAggregatedResult()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        final var handler = handler(Search.ALL_COMPONENTS);
        final var first = produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, ""),
                cursorResolver(), context()));
        final var second = produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, next(first)),
                cursorResolver(), context()));
        sling.resourceResolver().delete(Optional.ofNullable(sling.resourceResolver().getResource(
                ROOT + "/page-1/jcr:content/node-0")).orElseThrow());
        sling.resourceResolver().commit();
        final var refused = finishUntilRefusal(handler, Search.ALL_COMPONENTS, next(second), NODE_BUDGET);
        assertEquals("continuation_token_expired", refused.category());
        assertEquals(Set.of(ROOT + "/page-3"), enumerate(Search.ALL_COMPONENTS, ROOT));
    }

    @Test
    void componentReplayRechecksTheOriginalWitnesses()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        final var handler = handler(Search.ALL_COMPONENTS);
        String requested = "";
        for (int page = 0; page < 200; page++) {
            final var result = produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, requested),
                    cursorResolver(), context()));
            if (!matches(result).isEmpty()) {
                assertEquals(result, produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, requested),
                        cursorResolver(), context())), "the most recent response changed on replay");
                sling.resourceResolver().delete(Optional.ofNullable(sling.resourceResolver().getResource(
                        ROOT + "/page-1/jcr:content/node-0")).orElseThrow());
                sling.resourceResolver().commit();
                assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                        handler.run(arguments(Search.ALL_COMPONENTS, ROOT, requested),
                                cursorResolver(), context())).category());
                return;
            }
            requested = next(result);
        }
        throw new AssertionError("no component page was returned within the finite fixture bound");
    }

    @Test
    void impossibleProofBudgetRefusesWithoutAnEndlessEmptyCursor()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        final var handler = handler(Search.ALL_COMPONENTS);
        assertEquals("discovery_budget_exceeded",
                finishUntilRefusal(handler, Search.ALL_COMPONENTS, "", 2).category());
    }

    @Test
    void readablePageDoesNotPermitReplayWhenItsComponentWitnessIsHidden()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        final var handler = handler(Search.ALL_COMPONENTS);
        String requested = "";
        for (int page = 0; page < 200; page++) {
            final var result = produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, requested),
                    cursorResolver(), context()));
            if (!matches(result).isEmpty()) {
                final Set<String> denied = Set.of(ROOT + "/page-1/jcr:content/node-0");
                assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                        handler.run(arguments(Search.ALL_COMPONENTS, ROOT, requested),
                                cursorResolver(denied), context())).category());
                return;
            }
            requested = next(result);
        }
        throw new AssertionError("no component page was returned within the finite fixture bound");
    }

    @Test
    void changedNearestPageCannotReuseOldComponentWitnesses()
            throws org.apache.sling.api.resource.PersistenceException {
        corpus();
        sling.resourceResolver().commit();
        final var handler = handler(Search.ALL_COMPONENTS);
        final var first = produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, ""),
                cursorResolver(), context()));
        final var second = produced(handler.run(arguments(Search.ALL_COMPONENTS, ROOT, next(first)),
                cursorResolver(), context()));
        final var changed = Optional.ofNullable(
                sling.resourceResolver().getResource(ROOT + "/page-1/jcr:content")).orElseThrow();
        Optional.ofNullable(changed.adaptTo(ModifiableValueMap.class)).orElseThrow()
                .put("jcr:primaryType", "cq:Page");
        sling.resourceResolver().commit();
        assertEquals("continuation_token_expired",
                finishUntilRefusal(handler, Search.ALL_COMPONENTS, next(second), NODE_BUDGET).category());
    }

    private CommandHandler.Failed finishUntilRefusal(CommandHandler handler, Search search,
                                                     String initial, long nodes) {
        String token = initial;
        for (int page = 0; page < 200; page++) {
            final var answer = handler.run(arguments(search, ROOT, token), cursorResolver(), context(nodes));
            if (answer instanceof final CommandHandler.Failed refused) {
                return refused;
            }
            final var result = produced(answer);
            assertTrue(matches(result).isEmpty(), "unverified component witnesses produced a page");
            token = next(result);
            assertFalse(token.isEmpty(), "an unverified search claimed completion");
        }
        throw new AssertionError("the cursor never reported its terminal proof refusal");
    }

    private static DocumentValue.Mapping produced(CommandHandler.Answer answer) {
        return assertInstanceOf(CommandHandler.Produced.class, answer).result();
    }

    private static List<DocumentValue> matches(DocumentValue.Mapping result) {
        return assertInstanceOf(DocumentValue.Sequence.class, result.member("matches").orElseThrow()).items();
    }

    private static String next(DocumentValue.Mapping result) {
        return result.member("next_continuation_token").map(DocumentValue.Text.class::cast)
                .map(DocumentValue.Text::value).orElse("");
    }

    private Set<String> enumerate(Search search, String root) {
        final CommandHandler handler = handler(search);
        final Set<String> seen = new HashSet<>();
        String token = "";
        boolean complete = false;
        for (int page = 0; page < 200 && !complete; page++) {
            final var answer = handler.run(arguments(search, root, token),
                    cursorResolver(), context());
            if (answer instanceof final CommandHandler.Failed refused) {
                assertEquals("discovery_budget_exceeded", refused.category(),
                        "a different refusal cannot demonstrate whole-tree budget exhaustion");
            }
            final var result = assertInstanceOf(CommandHandler.Produced.class, answer,
                    "a small discovery budget must yield explicit progress, not a whole-tree refusal")
                    .result();
            final long examined = assertInstanceOf(DocumentValue.Whole.class,
                    result.member("examined_nodes").orElseThrow()).value();
            assertTrue(examined >= 0 && examined <= NODE_BUDGET,
                    "the reported work exceeds this request's node allowance");
            final var matches = assertInstanceOf(DocumentValue.Sequence.class,
                    result.member("matches").orElseThrow()).items();
            assertTrue(matches.size() <= 1, "a successor widened the initial one-row limit");
            matches.forEach(row -> assertTrue(seen.add(path(row)), "a page was emitted twice"));
            complete = assertInstanceOf(DocumentValue.Flag.class,
                    result.member("complete").orElseThrow()).value() == DocumentValue.Truth.TRUE;
            token = result.member("next_continuation_token").map(DocumentValue.Text.class::cast)
                    .map(DocumentValue.Text::value).orElse("");
            assertEquals(complete, token.isEmpty(), "completion and continuation contradict each other");
            if (page == 0) {
                assertFalse(complete, "the sparse tree was examined beyond the first page's budget");
            }
            assertFalse(result.toString().contains("synthetic-body-secret"),
                    "a search result disclosed unrequested content");
        }
        assertTrue(complete, "the cursor failed to finish within the fixture's finite work bound");
        return seen;
    }

    private void corpus() {
        sling.create().resource(ROOT, "jcr:primaryType", "sling:Folder");
        IntStream.rangeClosed(1, 3).forEach(this::page);
    }

    private void matchingPage(String path) {
        sling.create().resource(path, "jcr:primaryType", "cq:Page");
        sling.create().resource(path + "/jcr:content", Map.of(
                "jcr:primaryType", "cq:PageContent", "jcr:title", PHRASE));
    }

    private ResourceResolver cursorResolver() {
        return cursorResolver(Set.of());
    }

    private ResourceResolver cursorResolver(Set<String> denied) {
        return new ResourceResolverWrapper(ReadOnlyResolver.around(sling.resourceResolver())) {
            @Override
            public Resource getResource(String path) {
                return denied.contains(path) ? null : super.getResource(path);
            }

            @Override
            public ResourceResolver clone(Map<String, Object> authentication) throws LoginException {
                return ReadOnlyResolver.around(sling.resourceResolver().clone(authentication));
            }
        };
    }

    private void page(int index) {
        final String path = ROOT + "/page-" + index;
        sling.create().resource(path, "jcr:primaryType", "cq:Page");
        final String content = path + "/jcr:content";
        sling.create().resource(content, Map.of("jcr:primaryType", "cq:PageContent",
                "jcr:title", PHRASE, "body", "synthetic-body-secret"));
        IntStream.range(0, 18).forEach(child -> sling.create().resource(content + "/node-" + child,
                Map.of("jcr:primaryType", "nt:unstructured", "sling:resourceType",
                        child == 0 || index == 2 ? TEXT : child == 17 ? IMAGE : "synthetic/unused")));
    }

    private CommandHandler handler(Search search) {
        return search == Search.PHRASE || search == Search.ABSENT_PHRASE
                ? new FindPagesContainingPhraseHandler(CONTRACT, discovery)
                : new FindPagesUsingComponentsHandler(CONTRACT, discovery);
    }

    private static Set<String> expected(Search search) {
        return switch (search) {
            case ABSENT_PHRASE -> Set.of();
            case ALL_COMPONENTS -> Set.of(ROOT + "/page-1", ROOT + "/page-3");
            default -> Set.of(ROOT + "/page-1", ROOT + "/page-2", ROOT + "/page-3");
        };
    }

    private static String path(DocumentValue value) {
        final var row = assertInstanceOf(DocumentValue.Mapping.class, value);
        return assertInstanceOf(DocumentValue.Text.class,
                row.member("repository_path").orElseThrow()).value();
    }

    private static DocumentValue.Mapping arguments(Search search, String root, String token) {
        final SequencedMap<String, DocumentValue> values = new LinkedHashMap<>();
        values.put("root_path", new DocumentValue.Text(root));
        if (search == Search.PHRASE || search == Search.ABSENT_PHRASE) {
            values.put("phrase", new DocumentValue.Text(search == Search.PHRASE
                    ? PHRASE : "synthetic-absent-needle"));
        } else {
            values.put("resource_types", new DocumentValue.Sequence(
                    List.of(new DocumentValue.Text(TEXT), new DocumentValue.Text(IMAGE))));
            values.put("match_mode", new DocumentValue.Text(search == Search.ALL_COMPONENTS ? "all" : "any"));
        }
        values.put("result_window", new DocumentValue.Mapping(new LinkedHashMap<>(token.isEmpty()
                ? Map.of("mode", new DocumentValue.Text("initial"), "offset", new DocumentValue.Whole(0),
                        "limit", new DocumentValue.Whole(1))
                : Map.of("mode", new DocumentValue.Text("continuation"),
                        "continuation_token", new DocumentValue.Text(token)))));
        return new DocumentValue.Mapping(values);
    }

    private static CallerContext context() {
        return context(NODE_BUDGET);
    }

    private static CallerContext context(long nodes) {
        final var operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of("1".repeat(64), CONTRACT)).identifier();
        final var target = assertInstanceOf(DigestValue.Held.class,
                DigestValue.of("b".repeat(DigestValue.RENDERED_LENGTH))).digest();
        final var generation = assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST)).generation();
        return new CallerContext(operation, new Budget(Budget.Kind.DISCOVERY, nodes),
                Budget.time(CONTRACT), new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT),
                new CallerContext.Available(authority(), target, generation, 1_000L));
    }

    private static ContinuationKeyAuthority authority() {
        return new ContinuationKeyAuthority() {
            @Override
            public ReadOutcome read() {
                return new Read(KeyRing.initial("synthetic-page-search-key"));
            }

            @Override
            public WriteOutcome compareAndSet(KeyRing expected, KeyRing next, Lease lease,
                                              long nowUnixMilliseconds) {
                return new NotWritten(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT, "test"));
            }
        };
    }
}
