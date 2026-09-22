// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.ReadOnlyResolver;
import rs.slingshot.agent.command.ResultWindow;
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
 * The three child listings, driven through the handlers the runtime dispatches.
 *
 * <p>One anchor holds a page, a folder, and a grandchild page, created out of path order. The
 * untyped listing reports every immediate child, the typed listing reports one primary type, and
 * the page listing is that typed listing with {@code cq:Page} projected onto the page document.
 * </p>
 */
@ExtendWith(SlingContextExtension.class)
final class ChildListingsCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String ANCHOR = "/content/anchor";

    private static final String FOLDER = ANCHOR + "/a-folder";

    private static final String UNTITLED_PAGE = ANCHOR + "/b-page";

    private static final String TITLED_PAGE = ANCHOR + "/c-page";

    private static final String GRANDCHILD = TITLED_PAGE + "/grand";

    private static final String NESTED = FOLDER + "/nested";

    private static final String FOLDER_TYPE = "nt:folder";

    private static final String TITLE = "Charlie";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("none of the three listings is declared as issuing a query")
    void thelistingsIssueNoQuery() {
        final String declared = read(REPOSITORY.resolve("policy/query-index-coverage.toml"));
        for (final String wire : List.of(ListChildNodesCommand.WIRE_NAME,
                ListChildNodesByTypeCommand.WIRE_NAME, ListChildPagesCommand.WIRE_NAME)) {
            assertFalse(declared.contains("issued_by = \"" + wire + "\""),
                    wire + " is declared as issuing a query");
        }
    }

    @Test
    @DisplayName("every immediate child is listed, in path order, and a grandchild is not")
    void everyimmediateChildIsListedInPathOrder() {
        final List<String> created = mixedAnchor();
        final DocumentValue window = window(0, 20);
        final DocumentValue.Mapping listed = produced(new ChildListingHandler(CONTRACT),
                nodes(ANCHOR, window), readOnly());
        assertEquals(List.of(FOLDER, UNTITLED_PAGE, TITLED_PAGE), paths(listed),
                "the immediate children were not all listed, or not in path order");
        assertFalse(created.equals(paths(listed)),
                "the fixture was created in the order the listing returned, so a missing sort"
                        + " would still pass");
        assertEquals(List.of(FOLDER_TYPE, ListChildPagesHandler.PAGE_TYPE,
                ListChildPagesHandler.PAGE_TYPE), types(listed));
        assertEquals(List.of("", "", TITLE), titles(listed));
        assertTrue(paths(listed).stream().noneMatch(path -> GRANDCHILD.equals(path)
                || NESTED.equals(path)), "a grandchild was listed as a child");
        final DocumentValue.Mapping folderOnly = produced(new ChildListingHandler(CONTRACT),
                byType(FOLDER_TYPE, ANCHOR, window), readOnly());
        assertEquals(List.of(FOLDER), paths(folderOnly),
                "a sibling of another type was included in the typed listing");
        assertEquals(List.of(FOLDER_TYPE), types(folderOnly));
    }

    @Test
    @DisplayName("a missing anchor and an examination overrun refuse with no matches")
    void amissingAnchorAndABudgetOverrunRefuseWithNoMatches() {
        mixedAnchor();
        final DocumentValue window = window(0, 20);
        final DocumentValue.Mapping absent = nodes("/content/nothing-is-here", window);
        refused(new ChildListingHandler(CONTRACT), absent, ListChildPagesHandler.ROOT_NOT_FOUND,
                context());
        refused(new ChildListingHandler(CONTRACT), byType(ListChildPagesHandler.PAGE_TYPE,
                "/content/nothing-is-here", window), ListChildPagesHandler.ROOT_NOT_FOUND,
                context());
        refused(new ListChildPagesHandler(CONTRACT), pages("/content/nothing-is-here", window),
                ListChildPagesHandler.ROOT_NOT_FOUND, context());
        final CallerContext narrow = new CallerContext(operation(),
                new Budget(Budget.Kind.DISCOVERY, 2), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
        assertEquals(paths(produced(new ChildListingHandler(CONTRACT), nodes(ANCHOR, window),
                readOnly())), paths(assertInstanceOf(CommandHandler.Produced.class,
                        new ChildListingHandler(CONTRACT).run(nodes(ANCHOR, window), readOnly(),
                                narrow), "a wide folder was refused").result()));
        assertEquals(paths(produced(new ChildListingHandler(CONTRACT),
                byType(ListChildPagesHandler.PAGE_TYPE, ANCHOR, window), readOnly())),
                paths(assertInstanceOf(CommandHandler.Produced.class,
                        new ChildListingHandler(CONTRACT).run(byType(
                                ListChildPagesHandler.PAGE_TYPE, ANCHOR, window), readOnly(),
                                narrow), "a wide folder was refused").result()));
        assertEquals(paths(produced(new ListChildPagesHandler(CONTRACT), pages(ANCHOR, window),
                readOnly())), paths(assertInstanceOf(CommandHandler.Produced.class,
                        new ListChildPagesHandler(CONTRACT).run(pages(ANCHOR, window), readOnly(),
                                narrow), "a wide folder was refused").result()));
    }

    @Test
    @DisplayName("the page listing is the typed cq:Page listing, projected, from one child walk")
    void thepageListingExecutesTheTypedListingOnce() throws IOException {
        mixedAnchor();
        final DocumentValue window = window(0, 20);
        final DocumentValue.Mapping typedArguments = byType(ListChildPagesHandler.PAGE_TYPE,
                ANCHOR, window);
        final DocumentValue.Mapping pageArguments = pages(ANCHOR, window);
        final Walks walks = new Walks();
        final DocumentValue.Mapping typed = produced(new ChildListingHandler(CONTRACT),
                typedArguments, walks.around(readOnly()));
        assertEquals(1, walks.walks(), "the typed listing did not walk the anchor once");
        final Walks pageWalks = new Walks();
        final DocumentValue.Mapping pages = produced(new ListChildPagesHandler(CONTRACT),
                pageArguments, pageWalks.around(readOnly()));
        assertEquals(1, pageWalks.walks(),
                "the page listing walked the anchor more than the one typed execution");
        assertEquals(paths(typed), paths(pages),
                "the page listing's paths are not the typed cq:Page listing's");
        assertEquals(titles(typed), titles(pages));
        assertTrue(paths(pages).stream().noneMatch(path -> FOLDER.equals(path)
                || GRANDCHILD.equals(path) || NESTED.equals(path)),
                "a non-page sibling or a grandchild was listed");
        for (final DocumentValue item : ((DocumentValue.Sequence) pages.member(
                PageListingResult.MATCHES).orElseThrow()).items()) {
            assertFalse(((DocumentValue.Mapping) item).members()
                    .containsKey(ChildNodeListingResult.PRIMARY_NODE_TYPE),
                    "a page match carried a primary type");
        }
        final DocumentValue narrow = window(1, 1);
        assertEquals(paths(produced(new ChildListingHandler(CONTRACT),
                byType(ListChildPagesHandler.PAGE_TYPE, ANCHOR, narrow), readOnly())),
                paths(produced(new ListChildPagesHandler(CONTRACT), pages(ANCHOR, narrow),
                        readOnly())),
                "the same window did not select the same page");
        final Walks delegated = new Walks();
        final Observed observed = new Observed(new ChildListingHandler(CONTRACT), delegated);
        final DocumentValue.Mapping projected = produced(
                new ListChildPagesHandler(CONTRACT, observed), pageArguments,
                delegated.around(readOnly()));
        assertEquals(1, observed.calls, "list_child_pages did not execute the typed listing once");
        assertEquals(0, observed.walksBefore,
                "the page listing walked children before executing the typed listing");
        assertEquals(1, observed.walksAfter,
                "the typed listing's one walk was not the page listing's only walk");
        assertEquals(1, delegated.walks());
        assertEquals(new DocumentValue.Text(ListChildPagesHandler.PAGE_TYPE),
                observed.arguments.member(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE)
                        .orElseThrow());
        assertEquals(pageArguments.member(ListChildPagesCommand.ROOT_PATH).orElseThrow(),
                observed.arguments.member(ListChildNodesCommand.ROOT_PATH).orElseThrow());
        assertEquals(pageArguments.member(ResultWindow.ARGUMENT_MEMBER).orElseThrow(),
                observed.arguments.member(ResultWindow.ARGUMENT_MEMBER).orElseThrow());
        assertEquals(paths(pages), paths(projected));
        assertEquals(titles(pages), titles(projected));
        try (InputStream bytecode = ListChildPagesHandler.class.getResourceAsStream(
                "ListChildPagesHandler.class")) {
            final String pool = new String(bytecode.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(pool.contains("primary_node_type"),
                    "the page listing does not name the typed request's type");
            assertTrue(pool.contains("ChildListingHandler"),
                    "the page listing does not construct the typed listing's handler");
            assertFalse(pool.contains("matching"),
                    "the page listing walks children beside the typed listing");
        }
    }

    @Test
    @DisplayName("a continuation token this command cannot read is refused before any child is listed")
    void agarbageContinuationIsRefused() {
        node(ANCHOR, FOLDER_TYPE);
        refused(new ListChildPagesHandler(CONTRACT), pages(ANCHOR, continuation("not-a-token")),
                "continuation_token_integrity_invalid", context());
    }

    @Test
    @DisplayName("an argument a child listing does not take is the refusal its own reading names")
    void anArgumentAChildListingDoesNotTakeIsNamed() {
        final List<String> expected = List.of("NOT_A_DOCUMENT", "MEMBER_UNKNOWN", "MEMBER_ABSENT",
                "NOT_AN_ABSOLUTE_PATH", "NOT_AN_ABSOLUTE_PATH", "WINDOW_REFUSED");
        assertEquals(expected, childArguments(false).stream()
                .map(arguments -> assertInstanceOf(ListChildNodesCommand.Refused.class,
                        ListChildNodesCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        assertEquals(expected, childArguments(true).stream()
                .map(arguments -> assertInstanceOf(ListChildNodesByTypeCommand.Refused.class,
                        ListChildNodesByTypeCommand.of(arguments, CONTRACT)).refusal().name())
                .toList());
        final ListChildNodesCommand.Held nodes = assertInstanceOf(ListChildNodesCommand.Held.class,
                ListChildNodesCommand.of(nodes(ANCHOR, window(0, 1)), CONTRACT));
        assertEquals(ANCHOR, nodes.command().rootPath());
        final ListChildNodesByTypeCommand.Held typed = assertInstanceOf(
                ListChildNodesByTypeCommand.Held.class,
                ListChildNodesByTypeCommand.of(byType(FOLDER_TYPE, ANCHOR, window(0, 1)),
                        CONTRACT));
        assertEquals(FOLDER_TYPE, typed.command().primaryNodeType());
        assertEquals(ChildListingArgument.Refusal.MEMBER_ABSENT,
                ChildListingArgument.typeRefusal(new DocumentValue.Mapping(new LinkedHashMap<>())));
        assertEquals(ChildListingArgument.Refusal.NOT_A_DOCUMENT,
                ChildListingArgument.typeRefusal(new DocumentValue.Text("no")));
        assertNull(ChildListingArgument.typeRefusal(byType(FOLDER_TYPE, ANCHOR, window(0, 1))));
    }

    private static List<DocumentValue> childArguments(boolean typed) {
        final SequencedMap<String, DocumentValue> unknown = new LinkedHashMap<>();
        unknown.put("other", new DocumentValue.Text("x"));
        final SequencedMap<String, DocumentValue> untyped = new LinkedHashMap<>();
        untyped.put(ListChildNodesCommand.ROOT_PATH, new DocumentValue.Whole(1));
        final SequencedMap<String, DocumentValue> relative = new LinkedHashMap<>();
        relative.put(ListChildNodesCommand.ROOT_PATH, new DocumentValue.Text("content"));
        final SequencedMap<String, DocumentValue> window = new LinkedHashMap<>();
        window.put(ListChildNodesCommand.ROOT_PATH, new DocumentValue.Text("/content"));
        final SequencedMap<String, DocumentValue> mode = new LinkedHashMap<>();
        mode.put(ResultWindow.MODE, new DocumentValue.Text("sideways"));
        window.put(ResultWindow.ARGUMENT_MEMBER, new DocumentValue.Mapping(mode));
        if (typed) {
            final DocumentValue.Text type = new DocumentValue.Text(FOLDER_TYPE);
            untyped.put(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE, type);
            relative.put(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE, type);
            window.put(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE, type);
        }
        return List.of(new DocumentValue.Text("no"), new DocumentValue.Mapping(unknown),
                new DocumentValue.Mapping(new LinkedHashMap<>()),
                new DocumentValue.Mapping(untyped), new DocumentValue.Mapping(relative),
                new DocumentValue.Mapping(window));
    }

    @Test
    @DisplayName("a resumed page listing stays on this command and matches the typed page")
    void aresumedPageListingMatchesTheTypedPage() {
        mixedAnchor();
        final DocumentValue.Mapping first = produced(new ListChildPagesHandler(CONTRACT),
                pages(ANCHOR, window(0, 1)), readOnly());
        assertEquals(List.of(UNTITLED_PAGE), paths(first));
        final String token = ((DocumentValue.Text) first.member(
                PageListingResult.NEXT_CONTINUATION_TOKEN).orElseThrow()).value();
        final DocumentValue.Mapping resumed = produced(new ListChildPagesHandler(CONTRACT),
                pages(ANCHOR, continuation(token)), readOnly());
        final long limit = CONTRACT.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        final DocumentValue.Mapping typed = produced(new ChildListingHandler(CONTRACT),
                byType(ListChildPagesHandler.PAGE_TYPE, ANCHOR, window(1, limit)), readOnly());
        assertEquals(paths(typed), paths(resumed));
        assertEquals(titles(typed), titles(resumed));
        assertFalse(resumed.member(PageListingResult.NEXT_CONTINUATION_TOKEN).isPresent(),
                "the last page carried a continuation");
    }

    private void refused(CommandHandler handler, DocumentValue.Mapping arguments, String category,
                         CallerContext caller) {
        final CommandHandler.Answer answer = handler.run(arguments, readOnly(), caller);
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class, answer,
                "the refusal carried matches");
        assertEquals(category, failed.category());
    }

    private List<String> mixedAnchor() {
        node(ANCHOR, FOLDER_TYPE);
        final List<String> created = new ArrayList<>();
        created.add(page(TITLED_PAGE, TITLE));
        page(GRANDCHILD, "Grand");
        created.add(node(FOLDER, FOLDER_TYPE));
        page(NESTED, "Nested");
        created.add(page(UNTITLED_PAGE, ""));
        return created;
    }

    private String page(String path, String title) {
        node(path, ListChildPagesHandler.PAGE_TYPE);
        if (!title.isEmpty()) {
            sling.create().resource(path + "/" + ListChildPagesHandler.PAGE_CONTENT,
                    java.util.Map.of(ListChildPagesHandler.TITLE_PROPERTY, title));
        }
        return path;
    }

    private String node(String path, String type) {
        sling.create().resource(path, java.util.Map.of(ListChildPagesHandler.TYPE_PROPERTY, type));
        return path;
    }

    private DocumentValue.Mapping produced(CommandHandler handler, DocumentValue.Mapping arguments,
                                           ResourceResolver resolver) {
        return assertInstanceOf(CommandHandler.Produced.class,
                handler.run(arguments, resolver, context()), "the listing was refused").result();
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private static List<String> paths(DocumentValue.Mapping result) {
        return texts(result, PageListingResult.REPOSITORY_PATH);
    }

    private static List<String> titles(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items().stream()
                .map(item -> ((DocumentValue.Mapping) item).member(PageListingResult.TITLE)
                        .orElse(new DocumentValue.Text("")))
                .map(title -> ((DocumentValue.Text) title).value())
                .toList();
    }

    private static List<String> types(DocumentValue.Mapping result) {
        return texts(result, ChildNodeListingResult.PRIMARY_NODE_TYPE);
    }

    private static List<String> texts(DocumentValue.Mapping result, String member) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items().stream()
                .map(item -> ((DocumentValue.Mapping) item).member(member).orElseThrow())
                .map(value -> ((DocumentValue.Text) value).value())
                .toList();
    }

    private static DocumentValue.Mapping nodes(String root, DocumentValue window) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ListChildNodesCommand.ROOT_PATH, new DocumentValue.Text(root));
        members.put(ResultWindow.ARGUMENT_MEMBER, window);
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping byType(String type, String root, DocumentValue window) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE, new DocumentValue.Text(type));
        members.put(ListChildNodesByTypeCommand.ROOT_PATH, new DocumentValue.Text(root));
        members.put(ResultWindow.ARGUMENT_MEMBER, window);
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping pages(String root, DocumentValue window) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ListChildPagesCommand.ROOT_PATH, new DocumentValue.Text(root));
        members.put(ResultWindow.ARGUMENT_MEMBER, window);
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue window(long offset, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        members.put(ResultWindow.OFFSET, new DocumentValue.Whole(offset));
        members.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue continuation(String token) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.CONTINUATION_MODE));
        members.put(ResultWindow.TOKEN, new DocumentValue.Text(token));
        return new DocumentValue.Mapping(members);
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT), new CallerContext.Available(authority(), target(),
                        generation(), 1_000L));
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
                        CONTRACT)).identifier();
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** How many times the anchor's own children were listed. */
    private static final class Walks {

        private int visits;

        private int walks() {
            return visits;
        }

        private ResourceResolver around(ResourceResolver resolver) {
            return new Resolver(resolver, this);
        }

        private static final class Resolver extends ResourceResolverWrapper {

            private final Walks counted;

            private Resolver(ResourceResolver wrapped, Walks counted) {
                super(wrapped);
                this.counted = counted;
            }

            @Override
            public Resource getResource(String path) {
                final Resource resource = super.getResource(path);
                if (resource == null) {
                    return null;
                }
                return new ResourceWrapper(resource) {
                    @Override
                    public Iterator<Resource> listChildren() {
                        counted.visits = counted.visits + 1;
                        return super.listChildren();
                    }
                };
            }
        }
    }

    /** The typed listing the page handler executed, and the walk it performed. */
    private static final class Observed implements CommandHandler {

        private final CommandHandler delegate;

        private final Walks walks;

        private int calls;

        private int walksBefore;

        private int walksAfter;

        private DocumentValue.Mapping arguments;

        private Observed(CommandHandler delegate, Walks walks) {
            this.delegate = delegate;
            this.walks = walks;
            this.arguments = new DocumentValue.Mapping(new LinkedHashMap<>());
        }

        @Override
        public Answer run(DocumentValue.Mapping submitted, ResourceResolver resolver,
                          CallerContext context) {
            calls = calls + 1;
            arguments = submitted;
            walksBefore = walks.walks();
            final Answer answer = delegate.run(submitted, resolver, context);
            walksAfter = walks.walks();
            return answer;
        }
    }
}
