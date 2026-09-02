// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
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
 * Navigating one level of a site, without a query and without re-ordering it.
 *
 * <p>Two claims here are structural rather than incidental. This command issues no query at all,
 * which is checked against the declared-query inventory rather than by reading the handler. And the
 * order it answers in is the repository's own, checked against a direct child iteration of the same
 * parent rather than against a list this suite wrote down — a sorted answer would look perfectly
 * reasonable and would not be the site.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ListChildPagesCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /**
     * A resolver whose resources are plain values rather than typed repository nodes.
     *
     * <p>This command never touches a session: it reads a resource's own value map, its children,
     * and its path, all of which the resource layer answers. It is tested through that layer
     * because a page's type is {@code cq:Page}, which Adobe registers and a plain Oak repository
     * does not — so a Oak-backed fixture could not express the very thing this command tells
     * apart. The suite that proves a read cannot write runs against Oak, where that claim lives.
     * </p>
     */
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("this command issues no query, asserted against the declared-query inventory")
    void thiscommandIssuesNoQuery() {
        final String declared = read(REPOSITORY.resolve("policy/query-index-coverage.toml"));
        assertTrue(!declared.contains("issued_by = \"" + ListChildPagesCommand.WIRE_NAME + "\""),
                "this command declares a query, which means it searches where it should be"
                        + " reading — and then it needs an index on somebody's deployment to stay"
                        + " fast, which is the whole thing this command avoids");
    }

    @Test
    @DisplayName("repository order survives paging, checked against a direct child iteration")
    void repositoryOrderSurvivesPaging() {
        final Resource parent = parentWithChildren(CHILDREN);
        final List<String> iterated = new ArrayList<>();
        final Iterator<Resource> children = parent.listChildren();
        while (children.hasNext()) {
            iterated.add(children.next().getPath());
        }
        assertEquals(iterated, pathsFrom(listed(parent.getPath(), 0, CHILDREN * 2)),
                "the listing the handler answers is not in the order the repository holds the"
                        + " children");
        final List<String> paged = new ArrayList<>();
        for (long offset = 0; offset < iterated.size(); offset = offset + PAGE) {
            paged.addAll(pathsFrom(listed(parent.getPath(), offset, PAGE)));
        }
        assertEquals(iterated, paged,
                "reading every page did not produce the repository's own order, so a caller"
                        + " navigating the site sees something that is not the site");
    }

    /** How many children the parent has, which is more than one window. */
    private static final int CHILDREN = 13;

    /** How many children one page carries while this suite walks them. */
    private static final int PAGE = 4;

    @Test
    @DisplayName("a parent that is not a page and one that is not there are told apart")
    void thetwoAbsencesAreToldApart() {
        nodeAt("/content/not-a-page");
        final CommandHandler.Failed folder = assertInstanceOf(CommandHandler.Failed.class,
                run("/content/not-a-page"), "a parent that is not a page was listed");
        final CommandHandler.Failed absent = assertInstanceOf(CommandHandler.Failed.class,
                run("/content/nothing-is-here"), "a parent that is not there was listed");
        assertEquals(ListChildPagesHandler.ROOT_NOT_FOUND, folder.category());
        assertEquals(ListChildPagesHandler.ROOT_NOT_FOUND, absent.category());
        assertTrue(!folder.detail().equals(absent.detail()),
                "a caller who pointed at a folder and one who mistyped a path are told the same"
                        + " thing, and their next actions are different");
        assertTrue(folder.detail().contains("not a page"), folder.detail());
    }

    @Test
    @DisplayName("a page's title is carried, and a page without one is carried without it")
    void atitleIsCarriedWhereThereIsOne() {
        final Resource parent = parentWithChildren(2);
        final DocumentValue.Mapping result = listed(parent.getPath(), 0, TWO);
        assertEquals(TWO, ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES)
                .orElseThrow()).items().size(),
                "the listing did not carry both children");
        assertTrue(titlesFrom(result).stream().anyMatch(title -> !title.isEmpty()),
                "no child carried a title, and a listing without titles is not usable by a person");
        assertTrue(result.member(PageListingResult.NEXT_CONTINUATION_TOKEN).isEmpty(),
                "a page at the end carried a token member");
    }

    /** How many children the title fixture holds. */
    private static final int TWO = 2;

    @Test
    @DisplayName("a parent holding more children than the caller may examine is refused")
    void abudgetRefusalReachesTheCaller() {
        final Resource parent = parentWithChildren(CHILDREN);
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new ListChildPagesHandler(CONTRACT).run(
                        argument(parent.getPath(), window(CHILDREN)), readOnly(), narrowContext()),
                "a parent holding more children than the budget allows was listed anyway");
        assertEquals(ListChildPagesHandler.DISCOVERY_BUDGET_EXCEEDED, failed.category());
    }

    private static CallerContext narrowContext() {
        return new CallerContext(operation(), new Budget(Budget.Kind.DISCOVERY, CHILDREN / 2),
                Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private DocumentValue.Mapping listed(String parent, long offset, long limit) {
        return assertInstanceOf(CommandHandler.Produced.class,
                new ListChildPagesHandler(CONTRACT).run(
                        argument(parent, windowAt(offset, limit)), readOnly(), context()),
                "the listing was refused").result();
    }

    private static List<String> pathsFrom(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items().stream()
                .map(page -> ((DocumentValue.Mapping) page).member(PageListingResult.REPOSITORY_PATH)
                        .orElseThrow())
                .map(path -> ((DocumentValue.Text) path).value())
                .toList();
    }

    private static List<String> titlesFrom(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items().stream()
                // A page carrying no title carries no member at all rather than an empty one, so
                // an absent title reads back here as the empty string the fixture meant by it.
                .map(page -> ((DocumentValue.Mapping) page).member(PageListingResult.TITLE)
                        .orElse(new DocumentValue.Text("")))
                .map(title -> ((DocumentValue.Text) title).value())
                .toList();
    }

    private static DocumentValue windowAt(long offset, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        members.put(ResultWindow.OFFSET, new DocumentValue.Whole(offset));
        members.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        return new DocumentValue.Mapping(members);
    }

    @Test
    @DisplayName("the parent is required and never defaulted; an omitted window is the first page")
    void theparentIsRequiredAndTheWindowIsNot() {
        assertEquals(ListChildPagesCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument(null, window(25))).refusal());
        // The client's own schema requires the parent and not the window, so an argument naming a
        // parent alone is a complete question rather than half of one. Answering it with the first
        // page is what the other half already expects; refusing it would refuse a request the
        // client is entitled to send.
        final ListChildPagesCommand defaulted = assertInstanceOf(ListChildPagesCommand.Held.class,
                ListChildPagesCommand.of(argument("/content", null), CONTRACT),
                "a caller who named a parent and no window was refused").command();
        assertEquals(new ResultWindow.Initial(ResultWindow.BEGINNING,
                        CONTRACT.value(ContractLimit.DEFAULT_RESULT_LIMIT)),
                defaulted.window(),
                "an omitted window resolved to something other than the contract's own first page");
        assertEquals(ListChildPagesCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content", window(25))).refusal());
        assertEquals(ListChildPagesCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument("/content", window(0))).refusal());
        assertEquals(ListChildPagesCommand.Refusal.MEMBER_UNKNOWN,
                refusalOf(withExtra()).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and declares the client's own bound")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new ListChildPagesHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
    }

    private CommandHandler.Answer run(String parent) {
        return new ListChildPagesHandler(CONTRACT).run(argument(parent, window(25)), readOnly(),
                context());
    }

    private static ListChildPagesCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(ListChildPagesCommand.Refused.class,
                ListChildPagesCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(String parent, DocumentValue window) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (parent != null) {
            members.put(ListChildPagesCommand.ROOT_PATH, new DocumentValue.Text(parent));
        }
        if (window != null) {
            members.put(ResultWindow.ARGUMENT_MEMBER, window);
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping withExtra() {
        final SequencedMap<String, DocumentValue> members =
                new LinkedHashMap<>(argument("/content", window(25)).members());
        members.put("depth", new DocumentValue.Whole(2));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue window(long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        members.put(ResultWindow.OFFSET, new DocumentValue.Whole(0));
        members.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        return new DocumentValue.Mapping(members);
    }

    private Resource parentWithChildren(int count) {
        pageAt("/content/site");
        for (int child = 0; child < count; child = child + 1) {
            final String path = "/content/site/child-" + String.format("%02d", child);
            pageAt(path);
            sling.create().resource(path + "/" + ListChildPagesHandler.PAGE_CONTENT,
                    java.util.Map.of(ListChildPagesHandler.TITLE_PROPERTY, "Child " + child));
        }
        return Objects.requireNonNull(sling.resourceResolver().getResource("/content/site"));
    }

    private Resource pageAt(String path) {
        return sling.create().resource(path, java.util.Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
    }

    private Resource nodeAt(String path) {
        return sling.create().resource(path, java.util.Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "nt:folder"));
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
                .row(ListChildPagesCommand.WIRE_NAME).orElseThrow();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
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
