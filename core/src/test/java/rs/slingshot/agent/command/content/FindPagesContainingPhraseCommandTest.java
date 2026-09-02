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
 * Full-text search, where the budget is the whole safety story.
 *
 * <p>The assertion that matters most is that the budget <em>refuses</em>. A shortened list of
 * matches is the one answer worse than no answer: the caller asked which pages contain a phrase and
 * received a list of pages that contain it, so every page the search never reached looks like a page
 * that does not match. Nothing in a trimmed answer says otherwise, which is why this refuses.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class FindPagesContainingPhraseCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /**
     * Text in a property this command does not search, which must never reach a caller.
     *
     * <p>It sits beside the searched properties on the same content node, so a handler that read
     * the whole value map — which is what the index cannot answer — would both match on it and
     * carry it back.</p>
     */
    private static final String BODY = "the-body-text-no-index-covers";

    /** Text in a property the page index does cover, which is what a search may match on. */
    private static final String DESCRIPTION = "a-quarterly-report-about-revenue";

    /** The phrase this suite searches for, which appears only in the covered property. */
    private static final String PHRASE = "quarterly-report";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("the budget refuses rather than trims, one node past its limit")
    void thebudgetRefusesRatherThanTrims() {
        corpus(PAGES);
        final CommandHandler.Answer within = new FindPagesContainingPhraseHandler(CONTRACT)
                .run(argument("/content/site", PHRASE, 100), readOnly(), budgeted(PAGES * 4));
        assertInstanceOf(CommandHandler.Produced.class, within,
                "a search inside its budget was refused");
        final CommandHandler.Failed past = assertInstanceOf(CommandHandler.Failed.class,
                new FindPagesContainingPhraseHandler(CONTRACT)
                        .run(argument("/content/site", PHRASE, 100), readOnly(), budgeted(2)),
                "a search past its budget answered with a list, and a caller reading it would"
                        + " conclude the pages it never reached do not contain the phrase");
        assertEquals(FindPagesContainingPhraseHandler.DISCOVERY_BUDGET_EXCEEDED, past.category());
        assertTrue(past.detail().contains("refused rather than shortened"), past.detail());
    }

    /** How many pages the corpus holds. */
    private static final int PAGES = 6;

    @Test
    @DisplayName("a phrase in a property no index covers matches nothing, because it is not searched")
    void anuncoveredPropertyIsNotSearched() {
        corpus(PAGES);
        final DocumentValue.Mapping result = assertInstanceOf(CommandHandler.Produced.class,
                new FindPagesContainingPhraseHandler(CONTRACT)
                        .run(argument("/content/site", "body-text-no-index", 100), readOnly(),
                                budgeted(PAGES * 4)),
                "the search was refused").result();
        assertEquals(0, matchesIn(result).size(),
                "a phrase was matched in a property Adobe's page index does not cover, so this"
                        + " search is wider than the index that is supposed to answer it and would"
                        + " walk somebody's repository instead");
    }

    @Test
    @DisplayName("a match carries an address and a title, and no excerpt of what matched")
    void amatchCarriesNoExcerpt() {
        corpus(PAGES);
        final DocumentValue.Mapping result = assertInstanceOf(CommandHandler.Produced.class,
                new FindPagesContainingPhraseHandler(CONTRACT)
                        .run(argument("/content/site", PHRASE, 100), readOnly(),
                                budgeted(PAGES * 4)),
                "the search was refused").result();
        assertTrue(!matchesIn(result).isEmpty(),
                "the search found nothing in a corpus every page of which contains the phrase");
        assertTrue(!String.valueOf(result).contains(BODY),
                "the body text of a matched page reached the caller. An excerpt is content, and"
                        + " answering with content performs a read nobody checked this caller"
                        + " could make.");
        assertTrue(!String.valueOf(result).contains(DESCRIPTION),
                "the very text that matched reached the caller, which is the excerpt this command"
                        + " does not answer with");
        assertEquals(List.of(), everyMember(result).stream()
                        .filter(member -> !List.of(
                                PageListingResult.MATCHES, PageListingResult.REPOSITORY_PATH,
                                PageListingResult.TITLE,
                                PageListingResult.NEXT_CONTINUATION_TOKEN).contains(member))
                        .toList(),
                "the result carries a member this command does not declare");
    }

    @Test
    @DisplayName("an empty phrase and one past the deployment's bound are refused distinctly")
    void thephraseIsBounded() {
        assertEquals(FindPagesContainingPhraseCommand.Refusal.PHRASE_EMPTY,
                refusalOf(argument("/content", "  ", 25)).refusal(),
                "an empty phrase asks for everything rather than for something");
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_SEARCH_PHRASE_BYTES);
        assertEquals(FindPagesContainingPhraseCommand.Refusal.PHRASE_TOO_LONG,
                refusalOf(argument("/content", "p".repeat((int) bound + 1), 25)).refusal());
        assertInstanceOf(FindPagesContainingPhraseCommand.Held.class,
                FindPagesContainingPhraseCommand.of(
                        argument("/content", "p".repeat((int) bound), 25), CONTRACT),
                "a phrase of exactly the bound was refused");
    }

    @Test
    @DisplayName("a refusal never repeats the caller's own phrase back into a message")
    void arefusalDoesNotEchoThePhrase() {
        final String secret = "a-phrase-somebody-typed-that-should-not-be-logged";
        final FindPagesContainingPhraseCommand.Refused refused =
                refusalOf(argument("content", secret, 25));
        assertTrue(!refused.detail().contains(secret),
                "the caller's own phrase was repeated back into a message that goes to a log: "
                        + refused.detail());
    }

    @Test
    @DisplayName("an argument missing any member is refused, and none of the three is defaulted")
    void nomemberIsDefaulted() {
        assertEquals(FindPagesContainingPhraseCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument(null, PHRASE, 25)).refusal());
        assertEquals(FindPagesContainingPhraseCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument("/content", null, 25)).refusal());
        assertEquals(FindPagesContainingPhraseCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content", PHRASE, 25)).refusal());
        assertEquals(FindPagesContainingPhraseCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument("/content", PHRASE, 0)).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and matches what the handler can fail with")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new FindPagesContainingPhraseHandler(CONTRACT).categories().stream().sorted()
                        .toList(),
                "the handler and its row disagree about what this command can fail with");
    }

    @Test
    @DisplayName("the query this command issues is one the coverage policy declares")
    void thequeryIsDeclared() {
        assertTrue(read(REPOSITORY.resolve("policy/query-index-coverage.toml"))
                        .contains("issued_by = \"" + FindPagesContainingPhraseCommand.WIRE_NAME
                                + "\""),
                "this command issues a query nobody declared, so nothing checks it against the"
                        + " indexes a deployment provides");
    }

    private static List<String> everyMember(DocumentValue value) {
        return switch (value) {
            case DocumentValue.Mapping mapping -> mapping.members().entrySet().stream()
                    .flatMap(member -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(member.getKey()),
                            everyMember(member.getValue()).stream()))
                    .toList();
            case DocumentValue.Sequence sequence -> sequence.items().stream()
                    .flatMap(item -> everyMember(item).stream())
                    .toList();
            default -> List.of();
        };
    }

    private void corpus(int pages) {
        sling.create().resource("/content/site", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        for (int page = 0; page < pages; page = page + 1) {
            final String path = "/content/site/page-" + page;
            sling.create().resource(path, Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
            sling.create().resource(path + "/" + ListChildPagesHandler.PAGE_CONTENT, Map.of(
                    ListChildPagesHandler.TITLE_PROPERTY, "Page " + page,
                    "jcr:description", DESCRIPTION,
                    "text", BODY));
        }
    }

    private static FindPagesContainingPhraseCommand.Refused refusalOf(
            DocumentValue.Mapping arguments) {
        return assertInstanceOf(FindPagesContainingPhraseCommand.Refused.class,
                FindPagesContainingPhraseCommand.of(arguments, CONTRACT),
                "the argument was accepted");
    }

    private static List<DocumentValue> matchesIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items();
    }

    private static DocumentValue.Mapping argument(String root, String phrase, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (root != null) {
            members.put(FindPagesContainingPhraseCommand.ROOT_PATH, new DocumentValue.Text(root));
        }
        if (phrase != null) {
            members.put(FindPagesContainingPhraseCommand.PHRASE, new DocumentValue.Text(phrase));
        }
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

    private static CallerContext budgeted(long nodes) {
        return new CallerContext(operation(), new Budget(Budget.Kind.DISCOVERY, nodes),
                Budget.time(CONTRACT),
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
                .row(FindPagesContainingPhraseCommand.WIRE_NAME).orElseThrow();
    }

    private static String read(Path file) {
        try {
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
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
