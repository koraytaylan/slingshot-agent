// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
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
 * The simplest paged command, and therefore where paging is proved.
 *
 * <p>Two things matter here beyond the ordinary. Paging must be gapless and non-overlapping — a
 * caller reading every page must see every address exactly once — which is proved by comparing the
 * concatenated pages against one unbounded read of the same corpus rather than against a list this
 * suite wrote down. And the result must carry nothing but addresses, proved over a corpus whose
 * nodes carry distinctive property values that must not appear anywhere in it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class QueryPathsCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REGISTRY = repositoryRoot().resolve("policy/commands");

    /** A value stored on every node, which no address is and no result may disclose. */
    private static final String A_SECRET = "a-value-no-address-contains";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every page together is exactly one unbounded read, in the same order")
    void pagingIsGaplessAndNonOverlapping() throws RepositoryException {
        final List<String> corpus = corpus(CORPUS);
        final List<String> whole = QueryPathsHandler.pageOf(corpus,
                new ResultWindow.Initial(0, CORPUS * 2), CONTRACT);
        final List<String> paged = new ArrayList<>();
        for (long offset = 0; offset < corpus.size(); offset = offset + PAGE) {
            paged.addAll(QueryPathsHandler.pageOf(corpus,
                    new ResultWindow.Initial(offset, PAGE), CONTRACT));
        }
        assertEquals(whole, paged,
                "reading every page did not produce what one unbounded read produces, so two pages"
                        + " overlap or skip and a caller would see an address twice or never");
        assertEquals(whole.size(), whole.stream().distinct().count(),
                "one unbounded read returned the same address twice");
        assertEquals(whole, whole.stream().sorted().toList(),
                "the order is not one two pages can agree on");
    }

    /** How many pages of nodes the corpus holds, which is more than one window. */
    private static final int CORPUS = 23;

    /** How many addresses one page carries while this suite walks the corpus. */
    private static final int PAGE = 5;

    @Test
    @DisplayName("the result carries addresses and nothing else, over content full of values")
    void theresultDisclosesNoPropertyValue() throws RepositoryException {
        final List<String> found = corpus(SMALL);
        final DocumentValue.Mapping result = QueryPathsResult.documentOf(found, "");
        assertEquals(List.of(), QueryPathsResult.disclosedBeyondAddresses(result, found),
                "something that is not an address reached the result");
        assertTrue(!rendered(result).contains(A_SECRET),
                "a property value every node in the corpus carries appeared in a result that is"
                        + " supposed to answer addresses and nothing else");
        assertEquals(found.size(), ((DocumentValue.Sequence) result
                        .member(QueryPathsResult.MATCHES).orElseThrow()).items().size(),
                "the answer does not carry every address the search found");
    }

    /** A corpus small enough to render whole into one assertion. */
    private static final int SMALL = 4;

    @Test
    @DisplayName("the disclosure check finds a value hidden at any depth, not just at the top")
    void thedisclosureCheckLooksAllTheWayDown() {
        final List<String> addresses = List.of("/content/a", "/content/b");
        assertEquals(List.of(), QueryPathsResult.disclosedBeyondAddresses(
                        QueryPathsResult.documentOf(addresses, "a-token"), addresses),
                "a result carrying only addresses and a token was reported as disclosing");
        final SequencedMap<String, DocumentValue> leaking = new LinkedHashMap<>();
        final SequencedMap<String, DocumentValue> buried = new LinkedHashMap<>();
        buried.put("title", new DocumentValue.Text(A_SECRET));
        buried.put("hidden", new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        buried.put("absent", new DocumentValue.Nothing());
        buried.put("size", new DocumentValue.Whole(12));
        leaking.put(QueryPathsResult.MATCHES, new DocumentValue.Sequence(List.of(
                new DocumentValue.Text("/content/a"), new DocumentValue.Mapping(buried))));
        assertEquals(List.of(A_SECRET),
                QueryPathsResult.disclosedBeyondAddresses(new DocumentValue.Mapping(leaking),
                        addresses),
                "a property value nested inside the address list was not found, so the check that"
                        + " makes this command's central claim would not notice a leak");
    }

    @Test
    @DisplayName("a page with nothing following it carries no token, so the end is definite")
    void theendCarriesNoToken() {
        final DocumentValue.Mapping ended = QueryPathsResult.documentOf(List.of("/content/a"), "");
        assertTrue(ended.member(QueryPathsResult.NEXT_CONTINUATION_TOKEN).isEmpty(),
                "a page at the end carried a token member, so an end is indistinguishable from a"
                        + " token that happens to be empty");
        final DocumentValue.Mapping more =
                QueryPathsResult.documentOf(List.of("/content/a"), "a-token");
        assertEquals(new DocumentValue.Text("a-token"),
                more.member(QueryPathsResult.NEXT_CONTINUATION_TOKEN).orElseThrow());
    }

    @Test
    @DisplayName("a window is read from its declared mode rather than from what members are present")
    void thewindowIsReadFromItsMode() {
        assertInstanceOf(ResultWindow.Held.class, ResultWindow.of(window("initial", 0, 10, ""),
                CONTRACT));
        assertInstanceOf(ResultWindow.Held.class,
                ResultWindow.of(continuationWindow("a-token"), CONTRACT));
        assertEquals(ResultWindow.Refusal.UNKNOWN_MODE, windowRefusal(window("sideways", 0, 10, "")));
        assertEquals(ResultWindow.Refusal.CONTINUATION_NOT_ALONE,
                windowRefusal(window("continuation", 0, 10, "a-token")),
                "a continuation restating a page size was honoured, and a member that is always"
                        + " ignored is one somebody eventually believes");
        assertEquals(ResultWindow.Refusal.CONTINUATION_INCOMPLETE,
                windowRefusal(continuationWindow("")));
    }

    @Test
    @DisplayName("only the root is required; a type and a window are the caller's to leave out")
    void ontherootAloneIsRequired() {
        assertEquals(QueryPathsCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument(null, "cq:Page", window("initial", 0, 10, ""))).refusal());
        // The client's own schema requires the root alone. A search with no type is every node
        // under the root, which the path index answers and a caller is entitled to ask for; a
        // search with no window is the first page of it.
        final QueryPathsCommand open = assertInstanceOf(QueryPathsCommand.Held.class,
                QueryPathsCommand.of(argument("/content", null, null), CONTRACT),
                "a caller who named a root and nothing else was refused").command();
        assertEquals(QueryPathsCommand.ANY_NODE_TYPE, open.primaryNodeType());
        assertEquals(List.of(), open.predicates(),
                "a search naming no predicate was given one");
        assertEquals(QueryPathsCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content", "cq:Page", window("initial", 0, 10, ""))).refusal());
        assertEquals(QueryPathsCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument("/content", "cq:Page",
                        window("initial", 0, 0, ""))).refusal(),
                "a page of no addresses answers no question anybody meant to ask");
    }

    @Test
    @DisplayName("this command's row refuses an operation key, which the client's own table says")
    void therowRefusesAnOperationKey() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey(),
                "asking the same subtree for the same type twice is asking once, so a key would"
                        + " decide nothing");
        assertEquals(1048576, row.resultBytes(), "the result bound is not the client's own");
    }

    @Test
    @DisplayName("every category this handler produces is one its own committed row declares")
    void everycategoryIsTheRowsOwn() {
        assertEquals(row().failureCategories().stream().sorted().toList(),
                new QueryPathsHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
        assertEquals(FIVE_CONTINUATION_CATEGORIES, new QueryPathsHandler(CONTRACT).categories()
                        .stream()
                        .filter(category -> category.startsWith("continuation_token_"))
                        .count(),
                "the continuation categories are not the ones the client's own discovery set"
                        + " declares; a seventh spelling of them is exactly what the conformance"
                        + " gate exists to refuse");
    }

    /**
     * How many continuation failures a caller is ever told about.
     *
     * <p>Five, not six. This side tells a stale generation apart from the other refusals
     * internally, and the client's own discovery set does not publish it as a category of its own:
     * an enumeration whose store was rebuilt is gone, which is what a caller acts on, and which of
     * this agent's internal reasons produced that is not their business.</p>
     */
    private static final int FIVE_CONTINUATION_CATEGORIES = 5;

    @Test
    @DisplayName("a root nothing is at, or nobody may see, is one answer rather than two")
    void anabsentRootIsOneAnswer() {
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                new QueryPathsHandler(CONTRACT).run(
                        argument("/content/nothing-is-here", "cq:Page", window("initial", 0, 10, "")),
                        readOnly(), context()),
                "a root with nothing at it was answered");
        assertEquals(QueryPathsHandler.ROOT_NOT_FOUND, failed.category());
    }

    @Test
    @DisplayName("a search finds every node of the asked-for type and no node of another")
    void thesearchFindsExactlyTheAskedForType() throws RepositoryException {
        corpus(SMALL);
        nodeAt("/content/corpus/not-a-page");
        session().save();
        final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                new QueryPathsHandler(CONTRACT).run(
                        argument("/content/corpus", "nt:unstructured",
                                window("initial", 0, 100, "")),
                        readOnly(), context()),
                "the search was refused");
        assertTrue(!((DocumentValue.Sequence) produced.result()
                        .member(QueryPathsResult.MATCHES).orElseThrow()).items().isEmpty(),
                "the search found nothing in a corpus it was pointed at");
    }

    private String rendered(DocumentValue.Mapping result) {
        return String.valueOf(result);
    }

    private static ResultWindow.Refusal windowRefusal(DocumentValue window) {
        return assertInstanceOf(ResultWindow.Refused.class, ResultWindow.of(window, CONTRACT),
                "the window was accepted").refusal();
    }

    private static QueryPathsCommand.Refused refusalOf(DocumentValue arguments) {
        return assertInstanceOf(QueryPathsCommand.Refused.class,
                QueryPathsCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(String root, String type, DocumentValue window) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (root != null) {
            members.put(QueryPathsCommand.ROOT_PATH, new DocumentValue.Text(root));
        }
        if (type != null) {
            members.put(QueryPathsCommand.PRIMARY_NODE_TYPE, new DocumentValue.Text(type));
        }
        if (window != null) {
            members.put(ResultWindow.ARGUMENT_MEMBER, window);
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue window(String mode, long offset, long limit, String token) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text(mode));
        if (!"continuation".equals(mode) || !token.isEmpty()) {
            members.put(ResultWindow.OFFSET, new DocumentValue.Whole(offset));
            members.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        }
        if (!token.isEmpty()) {
            members.put(ResultWindow.TOKEN, new DocumentValue.Text(token));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue continuationWindow(String token) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text("continuation"));
        if (!token.isEmpty()) {
            members.put(ResultWindow.TOKEN, new DocumentValue.Text(token));
        }
        return new DocumentValue.Mapping(members);
    }

    private List<String> corpus(int size) throws RepositoryException {
        final List<String> paths = new ArrayList<>();
        for (int page = 0; page < size; page = page + 1) {
            final Node node = nodeAt("/content/corpus/page-" + String.format("%03d", page));
            node.setProperty("jcr:title", A_SECRET);
            node.setProperty("description", A_SECRET);
            paths.add(node.getPath());
        }
        session().save();
        return paths.stream().sorted().toList();
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
        return assertInstanceOf(CommandRegistry.Loaded.class, CommandRegistry.read(REGISTRY),
                "the committed registry was refused").registry()
                .row(QueryPathsCommand.WIRE_NAME).orElseThrow();
    }

    private Node nodeAt(String path) throws RepositoryException {
        Node node = session().getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session().save();
        return node;
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
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
