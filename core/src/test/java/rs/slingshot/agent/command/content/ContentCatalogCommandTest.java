// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.fragment.FragmentHandlers;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What each of the four catalogues admits.
 */
@ExtendWith(SlingContextExtension.class)
final class ContentCatalogCommandTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load(), "the contract did not authenticate").contract();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DiscoveryRegistry discovery = new DiscoveryRegistry(CONTRACT);

    @AfterEach
    void releaseDiscovery() {
        discovery.close();
    }

    @Test
    void resultWriterRefusesContradictoryCompletion() {
        assertThrows(IllegalArgumentException.class,
                () -> IncrementalDiscoveryResult.documentOf(
                        List.of(), DocumentValue.Truth.TRUE, 0, "cursor"));
        assertThrows(IllegalArgumentException.class,
                () -> IncrementalDiscoveryResult.documentOf(List.of(), DocumentValue.Truth.FALSE, 0, ""));
    }

    @Test
    @DisplayName("a missing root does not expand the listing to an ancestor")
    void aMissingRootDoesNotExpandTheListingToAnAncestor() {
        sling.create().resource("/apps/acme/components/text", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        sling.create().resource("/apps/other/components/title", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        sling.create().resource("/libs/core/components/image", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        final var answer = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, discovery)
                .run(arguments("/apps/acme-rde"), requestResolver(), context());
        assertEquals("root_not_found", assertInstanceOf(CommandHandler.Failed.class, answer).category());
    }

    @Test
    @DisplayName("a named project folder lists that project's definitions")
    void aNamedProjectFolderListsThatProjectsDefinitions() {
        sling.create().resource("/apps/acme/components/text", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        sling.create().resource("/apps/other/components/title", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, "/apps/acme");
        assertEquals(List.of("/apps/acme/components/text"), paths(answered));
    }

    @Test
    @DisplayName("a definition is the component node, and its type is the path under apps")
    void aDefinitionIsTheComponentNode() {
        sling.create().resource("/apps/example/components/text", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ContentCatalogHandler.COMPONENT_DEFINITION_TYPE,
                ListChildPagesHandler.TITLE_PROPERTY, "Text"));
        sling.create().resource("/apps/example/components/text/cq:dialog", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "nt:unstructured"));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, "/apps");
        assertEquals(List.of("/apps/example/components/text"), paths(answered));
        assertEquals("example/components/text", text(answered, IncrementalDiscoveryResult.RESOURCE_TYPE));
    }

    @Test
    @DisplayName("an instance is a node that declares a resource type")
    void anInstanceDeclaresAResourceType() {
        sling.create().resource("/content/site/page/jcr:content/text", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "example/components/text"));
        sling.create().resource("/content/site/page/jcr:content/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        final DocumentValue.Mapping answered = listed(ContentCatalogHandler.Kind.COMPONENTS,
                "/content/site/page");
        assertEquals(List.of("/content/site/page/jcr:content/text"), paths(answered));
        assertEquals("example/components/text", text(answered, IncrementalDiscoveryResult.RESOURCE_TYPE));
    }

    @Test
    @DisplayName("a content fragment is an asset carrying the fragment flag")
    void aContentFragmentCarriesTheFlag() {
        sling.create().resource("/content/dam/site/article", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/article/jcr:content", Map.of(
                FragmentHandlers.CONTENT_FRAGMENT_FLAG, true,
                ListChildPagesHandler.TITLE_PROPERTY, "Article"));
        sling.create().resource("/content/dam/site/photo", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/photo/jcr:content", Map.of(
                FragmentHandlers.CONTENT_FRAGMENT_FLAG, false));
        sling.create().resource("/content/dam/site/article/jcr:content/renditions/original");
        sling.create().resource("/content/dam/site/later", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.CONTENT_FRAGMENT_TYPE));
        sling.create().resource("/content/dam/site/later/jcr:content", Map.of(
                FragmentHandlers.CONTENT_FRAGMENT_FLAG, true));
        final DocumentValue.Mapping answered = listed(ContentCatalogHandler.Kind.CONTENT_FRAGMENTS,
                "/content/dam");
        assertEquals(List.of("/content/dam/site/article", "/content/dam/site/later"),
                paths(answered));
    }

    @Test
    @DisplayName("fragment discovery checks current readability without looking up children of nonassets")
    void fragmentDiscoveryDoesNotLookUpNonassetContentChildren()
            throws org.apache.sling.api.resource.LoginException {
        final String root = "/content/synthetic-fragment-cost";
        final int count = 128;
        IntStream.range(0, count).forEach(index -> sling.create().resource(root + "/folder-" + index,
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        final AtomicLong currentReads = new AtomicLong();
        final AtomicLong contentReads = new AtomicLong();
        try (ResourceResolver current = new ResourceResolverWrapper(requestResolver().clone(Map.of())) {
            @Override
            public Resource getResource(String path) {
                final Resource resource = super.getResource(path);
                if (!path.startsWith(root + "/folder-")) {
                    return resource;
                }
                currentReads.incrementAndGet();
                return new ResourceWrapper(resource) {
                    @Override
                    public Resource getChild(String relativePath) {
                        contentReads.incrementAndGet();
                        return super.getChild(relativePath);
                    }
                };
            }
        }) {
            final var answered = produced(new ContentCatalogHandler(CONTRACT,
                    ContentCatalogHandler.Kind.CONTENT_FRAGMENTS, discovery)
                    .run(arguments(root), current, context()));
            assertTrue(paths(answered).isEmpty());
            assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                    answered.member(IncrementalDiscoveryResult.COMPLETE).orElseThrow());
            assertEquals(new DocumentValue.Whole(count + 1L),
                    answered.member(IncrementalDiscoveryResult.EXAMINED_NODES).orElseThrow());
            assertEquals(count, currentReads.get(),
                    "every visited resource must still check current authority");
            assertEquals(0, contentReads.get(), "a nonasset primary type cannot identify a content fragment");
        }
    }

    @Test
    @DisplayName("an experience fragment is the page whose content is the fragment page")
    void anExperienceFragmentIsNotItsVariation() {
        final String fragment = "/content/experience-fragments/site/promo";
        sling.create().resource(fragment, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
        sling.create().resource(fragment + "/jcr:content", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY,
                FragmentHandlers.EXPERIENCE_FRAGMENT_RESOURCE_TYPE,
                ListChildPagesHandler.TITLE_PROPERTY, "Promo"));
        sling.create().resource(fragment + "/web", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE));
        sling.create().resource(fragment + "/web/jcr:content", Map.of(
                "cq:template", "/conf/site/settings/wcm/templates/xf"));
        final DocumentValue.Mapping answered = listed(
                ContentCatalogHandler.Kind.EXPERIENCE_FRAGMENTS, "/content/experience-fragments");
        assertEquals(List.of(fragment), paths(answered));
    }

    @Test
    @DisplayName("an argument this catalogue does not take is refused before any node is read")
    void anArgumentThisCatalogueDoesNotTakeIsRefused() {
        for (final ContentCatalogHandler.Kind kind : ContentCatalogHandler.Kind.values()) {
            final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                    new ContentCatalogHandler(CONTRACT, kind, discovery).run(stray(),
                        requestResolver(), context()));
            assertEquals(ContentCatalogHandler.ARGUMENT_REJECTED, failed.category());
        }
        final List<String> expected = List.of("NOT_A_DOCUMENT", "MEMBER_UNKNOWN", "MEMBER_ABSENT",
                "NOT_AN_ABSOLUTE_PATH", "NOT_AN_ABSOLUTE_PATH", "WINDOW_REFUSED");
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ListComponentDefinitionsCommand.Refused.class,
                        ListComponentDefinitionsCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ComponentInstancesCommand.Refused.class,
                        ComponentInstancesCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ListContentFragmentsCommand.Refused.class,
                        ListContentFragmentsCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, refusedArguments().stream()
                .map(arguments -> assertInstanceOf(ListExperienceFragmentsCommand.Refused.class,
                        ListExperienceFragmentsCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
    }

    @Test
    @DisplayName("an anchor nothing is at is refused rather than answered empty")
    void anAnchorNothingIsAtIsRefused() {
        final CommandHandler.Answer answer = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery)
                .run(arguments("/content/missing"), requestResolver(), context());
        assertInstanceOf(CommandHandler.Failed.class, answer,
                "a missing anchor was answered, which reads as there being no components");
    }

    @Test
    @DisplayName("a bounded walk returns explicit partial progress and a continuation")
    void aBoundedWalkReturnsExplicitPartialProgress() {
        sling.create().resource("/content/site/first", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/components/text"));
        sling.create().resource("/content/site/second", Map.of(
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/components/title"));
        final ContentCatalogHandler handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final int everyNode = 3;
        assertInstanceOf(CommandHandler.Produced.class,
                handler.run(arguments("/content/site"), requestResolver(), budgeted(everyNode)));
        final var partial = assertInstanceOf(CommandHandler.Produced.class,
                handler.run(arguments("/content/site"), requestResolver(), budgeted(everyNode - 1))).result();
        assertEquals(new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                partial.member("complete").orElseThrow());
        assertEquals(new DocumentValue.Whole(everyNode - 1), partial.member("examined_nodes").orElseThrow());
        assertInstanceOf(DocumentValue.Text.class, partial.member("next_continuation_token").orElseThrow());
    }

    private static CallerContext budgeted(long nodes) {
        return new CallerContext(operation(), new Budget(Budget.Kind.DISCOVERY, nodes),
                Budget.time(CONTRACT), new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT),
                new CallerContext.Available(authority(), target(), generation(), 1_000L));
    }

    @Test
    void invalidContinuationIsRefusedBeforeAnyRepositoryAccess()
            throws org.apache.sling.api.resource.LoginException {
        try (ResourceResolver forbidden =
                new ResourceResolverWrapper(sling.resourceResolver().clone(Map.of())) {
                    @Override
                    public Resource getResource(String path) {
                        throw new AssertionError("invalid continuation reached the repository");
                    }
                }) {
            final SequencedMap<String, DocumentValue> request = new LinkedHashMap<>();
            request.put(RootedWindow.ROOT_PATH, new DocumentValue.Text("/apps"));
            final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
            window.put(ResultWindow.MODE, new DocumentValue.Text("continuation"));
            window.put("continuation_token", new DocumentValue.Text("bad-token"));
            request.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(window));
            final CommandHandler.Failed failure = assertInstanceOf(CommandHandler.Failed.class,
                    new ContentCatalogHandler(CONTRACT,
                        ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, discovery)
                            .run(new DocumentValue.Mapping(request), forbidden, context()));
            assertEquals("continuation_token_malformed", failure.category());
        }
    }

    @Test
    void aLargeCatalogueKeepsItsInitialLimitAndCanReplayOnePage() {
        final String root = "/content/large";
        final int count = 450;
        final int limit = 4;
        final int work = 17;
        IntStream.range(0, count).forEach(index -> sling.create().resource(root + "/node-" + index,
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/component"));
        final var handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final List<String> found = new ArrayList<>();
        String token = "";
        long examined = 0;
        int pages = 0;
        do {
            final var request = windowed(root, limit, token);
            final var page = produced(handler.run(request, requestResolver(), budgeted(work)));
            if (!token.isEmpty()) {
                assertEquals(page, produced(handler.run(request, requestResolver(), budgeted(work))),
                        "a repeated token must replay its page without advancing");
            }
            assertTrue(paths(page).size() <= limit);
            final long consumed = ((DocumentValue.Whole) page.member("examined_nodes").orElseThrow()).value();
            assertTrue(consumed <= work);
            examined += consumed;
            found.addAll(paths(page));
            token = next(page);
            assertEquals(new DocumentValue.Flag(token.isEmpty()
                            ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE),
                    page.member("complete").orElseThrow());
            pages++;
            assertTrue(pages <= count);
        } while (!token.isEmpty());
        assertEquals(count + 1, examined, "one retained traversal must examine each node only once");
        assertEquals(count, found.size());
        assertEquals(count, new HashSet<>(found).size());
    }

    @Test
    void initialOffsetsSpendWorkWithoutRescanningThePrefix() {
        final String root = "/content/offset";
        IntStream.range(0, 6).forEach(index -> sling.create().resource(root + "/node-" + index,
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/component"));
        final var handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final var initial = windowed(root, 2, "");
        final var initialWindow = (DocumentValue.Mapping) initial.member("result_window").orElseThrow();
        final var members = new LinkedHashMap<>(initialWindow.members());
        members.put("offset", new DocumentValue.Whole(4));
        final var arguments = new LinkedHashMap<>(initial.members());
        arguments.put("result_window", new DocumentValue.Mapping(members));
        final var first = produced(handler.run(new DocumentValue.Mapping(arguments),
                requestResolver(), budgeted(3)));
        assertEquals(List.of(), paths(first));
        assertFalse(next(first).isEmpty());
        final List<String> found = new ArrayList<>();
        String token = next(first);
        int pages = 0;
        while (!token.isEmpty()) {
            final var page = produced(handler.run(windowed(root, 2, token), requestResolver(), budgeted(3)));
            found.addAll(paths(page));
            token = next(page);
            pages++;
            assertTrue(pages < 8);
        }
        assertEquals(List.of(root + "/node-4", root + "/node-5"), found);
    }

    @Test
    void changedRowsInvalidateAReplayInsteadOfReturningStaleData() {
        final String root = "/content/replay";
        IntStream.range(0, 4).forEach(index -> sling.create().resource(root + "/node-" + index,
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/component"));
        final var handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final var first = produced(handler.run(windowed(root, 1, ""), requestResolver(), context()));
        final var resumed = windowed(root, 1, next(first));
        final var second = produced(handler.run(resumed, requestResolver(), context()));
        final Resource changed = sling.resourceResolver().getResource(paths(second).getFirst());
        final var values = java.util.Objects.requireNonNull(changed)
                .adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
        java.util.Objects.requireNonNull(values).put("jcr:title", "changed");
        assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                handler.run(resumed, requestResolver(), context())).category());
    }

    @Test
    void idleExpiryReleasesCapacityAndCannotRestartAnOldToken() {
        final String root = "/content/capacity";
        IntStream.range(0, 2).forEach(index -> sling.create().resource(root + "/node-" + index,
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/component"));
        final AtomicLong clock = new AtomicLong();
        try (var registry = new DiscoveryRegistry(CONTRACT, clock::get)) {
            final var handler = new ContentCatalogHandler(CONTRACT,
                    ContentCatalogHandler.Kind.COMPONENTS, registry);
            final long capacity = CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_CURSORS);
            final List<DocumentValue.Mapping> pages = IntStream.range(0, Math.toIntExact(capacity))
                    .mapToObj(index -> produced(handler.run(windowed(root, 1, ""),
                        requestResolver(), context()))).toList();
            assertEquals("discovery_budget_exceeded", assertInstanceOf(CommandHandler.Failed.class,
                    handler.run(windowed(root, 1, ""), requestResolver(), context())).category());
            clock.set(CONTRACT.value(ContractLimit.CONTINUATION_TOKEN_LIFETIME_MILLISECONDS));
            registry.collect();
            assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                    handler.run(windowed(root, 1, next(pages.getFirst())), requestResolver(),
                        context())).category());
            assertInstanceOf(CommandHandler.Produced.class,
                    handler.run(windowed(root, 1, ""), requestResolver(), context()));
        }
    }

    @Test
    void completeInitialListingsDoNotOccupyCursorCapacity() {
        final String root = "/content/complete";
        sling.create().resource(root, ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/component");
        final var handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final long beyondCapacity = CONTRACT.value(ContractLimit.MAXIMUM_DISCOVERY_CURSORS) + 1;
        IntStream.range(0, Math.toIntExact(beyondCapacity)).forEach(index -> {
            final var page = produced(handler.run(windowed(root, 1, ""), requestResolver(), context()));
            assertEquals("", next(page));
            assertEquals(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                    page.member("complete").orElseThrow());
        });
    }

    @Test
    void aTokenFromAnotherRuntimeCannotAliasANewCursor() {
        final String root = "/content/restart";
        sling.create().resource(root + "/first", ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/first");
        sling.create().resource(root + "/second",
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/second");
        final var original = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final var first = produced(original.run(windowed(root, 1, ""), requestResolver(), context()));
        try (var restarted = new DiscoveryRegistry(CONTRACT)) {
            final var handler = new ContentCatalogHandler(CONTRACT,
                    ContentCatalogHandler.Kind.COMPONENTS, restarted);
            assertInstanceOf(CommandHandler.Produced.class,
                    handler.run(windowed(root, 1, ""), requestResolver(), context()));
            assertEquals("continuation_token_wrong_query", assertInstanceOf(CommandHandler.Failed.class,
                    handler.run(windowed(root, 1, next(first)), requestResolver(), context())).category());
        }
    }

    @Test
    void closingTheRegistryRefusesBothContinuationAndNewTraversal() {
        final String root = "/content/closed";
        sling.create().resource(root + "/first", ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/first");
        sling.create().resource(root + "/second",
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/second");
        final var handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final var first = produced(handler.run(windowed(root, 1, ""), requestResolver(), context()));
        discovery.close();
        assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                handler.run(windowed(root, 1, next(first)), requestResolver(), context())).category());
        assertEquals("continuation_token_expired", assertInstanceOf(CommandHandler.Failed.class,
                handler.run(windowed(root, 1, ""), requestResolver(), context())).category());
    }

    @Test
    void aTokenForAnotherRootIsRefusedBeforeRepositoryAccess()
            throws org.apache.sling.api.resource.LoginException {
        final String root = "/content/scope";
        sling.create().resource(root + "/first", ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/first");
        sling.create().resource(root + "/second",
                ContentCatalogHandler.RESOURCE_TYPE_PROPERTY, "site/second");
        final var handler = new ContentCatalogHandler(CONTRACT,
                ContentCatalogHandler.Kind.COMPONENTS, discovery);
        final var first = produced(handler.run(windowed(root, 1, ""), requestResolver(), context()));
        try (ResourceResolver forbidden =
                new ResourceResolverWrapper(sling.resourceResolver().clone(Map.of())) {
                    @Override
                    public Resource getResource(String path) {
                        throw new AssertionError("wrong-query continuation reached the repository");
                    }
                }) {
            assertEquals("continuation_token_wrong_query", assertInstanceOf(CommandHandler.Failed.class,
                    handler.run(windowed("/content/other", 1, next(first)),
                            forbidden, context())).category());
        }
        assertInstanceOf(CommandHandler.Produced.class,
                handler.run(windowed(root, 1, next(first)), requestResolver(), context()));
    }

    private static DocumentValue.Mapping produced(CommandHandler.Answer answer) {
        return assertInstanceOf(CommandHandler.Produced.class, answer).result();
    }

    private static String next(DocumentValue.Mapping page) {
        return page.member("next_continuation_token").map(DocumentValue.Text.class::cast)
                .map(DocumentValue.Text::value).orElse("");
    }

    private static DocumentValue.Mapping windowed(String root, long limit, String token) {
        final var window = new LinkedHashMap<String, DocumentValue>();
        window.put("mode", new DocumentValue.Text(token.isEmpty() ? "initial" : "continuation"));
        if (token.isEmpty()) {
            window.put("offset", new DocumentValue.Whole(0));
            window.put("limit", new DocumentValue.Whole(limit));
        } else {
            window.put("continuation_token", new DocumentValue.Text(token));
        }
        final var request = new LinkedHashMap<>(arguments(root).members());
        request.put("result_window", new DocumentValue.Mapping(window));
        return new DocumentValue.Mapping(request);
    }

    private DocumentValue.Mapping listed(ContentCatalogHandler.Kind kind, String root) {
        final CommandHandler.Answer answer = new ContentCatalogHandler(CONTRACT, kind, discovery)
                .run(arguments(root), requestResolver(), context());
        assertInstanceOf(CommandHandler.Produced.class, answer, answer.toString());
        return ((CommandHandler.Produced) answer).result();
    }

    private static List<String> paths(DocumentValue.Mapping answered) {
        return ((DocumentValue.Sequence) answered.member(PageListingResult.MATCHES).orElseThrow())
                .items().stream()
                .map(item -> ((DocumentValue.Text) ((DocumentValue.Mapping) item)
                        .member(PageListingResult.REPOSITORY_PATH).orElseThrow()).value())
                .toList();
    }

    private static String text(DocumentValue.Mapping answered, String member) {
        final DocumentValue.Mapping row = (DocumentValue.Mapping)
                ((DocumentValue.Sequence) answered.member(PageListingResult.MATCHES).orElseThrow())
                        .items().getFirst();
        return ((DocumentValue.Text) row.member(member).orElseThrow()).value();
    }

    private static DocumentValue.Mapping stray() {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put("other", new DocumentValue.Text("x"));
        return new DocumentValue.Mapping(members);
    }

    private static List<DocumentValue> refusedArguments() {
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("other", new DocumentValue.Text("x"));
        final SequencedMap<String, DocumentValue> untyped = new LinkedHashMap<>();
        untyped.put(RootedWindow.ROOT_PATH, new DocumentValue.Whole(1));
        final SequencedMap<String, DocumentValue> relative = new LinkedHashMap<>();
        relative.put(RootedWindow.ROOT_PATH, new DocumentValue.Text("content"));
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(RootedWindow.ROOT_PATH, new DocumentValue.Text("/content"));
        final SequencedMap<String, DocumentValue> mode = new LinkedHashMap<>();
        mode.put(ResultWindow.MODE, new DocumentValue.Text("sideways"));
        window.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(mode));
        return List.of(new DocumentValue.Text("no"), new DocumentValue.Mapping(unknown),
                new DocumentValue.Mapping(new LinkedHashMap<>()),
                new DocumentValue.Mapping(untyped), new DocumentValue.Mapping(relative),
                new DocumentValue.Mapping(window));
    }

    private static DocumentValue.Mapping arguments(String root) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(RootedWindow.ROOT_PATH, new DocumentValue.Text(root));
        return new DocumentValue.Mapping(members);
    }

    private ResourceResolver requestResolver() {
        try {
            sling.resourceResolver().commit();
            return sling.resourceResolver();
        } catch (final org.apache.sling.api.resource.PersistenceException failure) {
            throw new AssertionError(failure);
        }
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT),
                new CallerContext.Available(authority(), target(), generation(), 1_000L));
    }

    private static ContinuationKeyAuthority authority() {
        return new ContinuationKeyAuthority() {
            @Override
            public ReadOutcome read() {
                return new Read(KeyRing.initial("paging-test-key"));
            }

            @Override
            public WriteOutcome compareAndSet(KeyRing expected, KeyRing next, Lease lease,
                                              long nowUnixMilliseconds) {
                return new NotWritten(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT, "test"));
            }
        };
    }

    private static DigestValue target() {
        return assertInstanceOf(DigestValue.Held.class,
                DigestValue.of("b".repeat(DigestValue.RENDERED_LENGTH))).digest();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST)).generation();
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }
}
