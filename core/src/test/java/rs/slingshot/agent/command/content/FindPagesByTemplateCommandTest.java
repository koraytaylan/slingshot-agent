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
 * Which pages use one template, and the difference between none and nowhere.
 *
 * <p>The assertion that matters here is that a mistyped root is refused while a correct root with
 * no matches answers an empty list. Somebody asks this question because they are about to change
 * every page in the answer; an empty answer to a wrong root reads as "this change affects nothing",
 * they proceed, and the pages that actually use the template are the ones nobody looked at.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class FindPagesByTemplateCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** The template the corpus's pages use. */
    private static final String TEMPLATE = "/conf/site/settings/wcm/templates/article";

    /** Another template some corpus pages use, which this command must not confuse with the first. */
    private static final String OTHER = "/conf/site/settings/wcm/templates/landing";

    /** A template nothing in the corpus uses at all, so a search for it finds none. */
    private static final String UNUSED = "/conf/site/settings/wcm/templates/nobody-uses-this";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a mistyped root is refused where a correct root with no matches answers empty")
    void amistypedRootIsRefusedAndAnEmptyResultIsNot() {
        corpus();
        final CommandHandler.Failed mistyped = assertInstanceOf(CommandHandler.Failed.class,
                run("/content/sight", TEMPLATE),
                "a root nobody can read was answered with a list, and an empty answer to a"
                        + " mistyped root reads as a migration with nothing to do");
        assertEquals(FindPagesByTemplateHandler.ROOT_NOT_FOUND, mistyped.category());
        final DocumentValue.Mapping none = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/site", UNUSED),
                "a correct root with no matches was refused, and a caller cannot tell that from a"
                        + " root that is not there").result();
        assertEquals(0, matchesIn(none).size(),
                "a root with no matching page did not answer an empty list");
    }

    @Test
    @DisplayName("every page using the template is found, each with what it is called")
    void everymatchCarriesWhatItIsCalled() {
        corpus();
        final DocumentValue.Mapping found = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/site", TEMPLATE), "the search was refused").result();
        assertEquals(USING, matchesIn(found).size(),
                "the search did not find every page using the template");
        final List<String> titles = matchesIn(found).stream()
                .map(page -> ((DocumentValue.Mapping) page).member(PageListingResult.TITLE)
                        .orElse(new DocumentValue.Text("")))
                .map(value -> ((DocumentValue.Text) value).value())
                .toList();
        assertEquals(USING, titles.size());
        assertTrue(titles.stream().noneMatch(String::isBlank),
                "a match carried no title, and a list of addresses alone is not something a person"
                        + " can plan a migration from");
    }

    /** How many pages in the corpus use the template. */
    private static final int USING = 3;

    /** How many pages in the corpus use another one. */
    private static final int OTHERS = 2;

    @Test
    @DisplayName("a page using another template is not a match")
    void anotherTemplateIsNotAMatch() {
        corpus();
        final DocumentValue.Mapping found = assertInstanceOf(CommandHandler.Produced.class,
                run("/content/site", TEMPLATE), "the search was refused").result();
        assertEquals(USING, matchesIn(found).size(),
                "a page using another template was counted as a match, and the migration would"
                        + " touch " + OTHERS + " pages nobody asked about");
    }

    @Test
    @DisplayName("a template that is not an absolute path is refused as its own thing")
    void thetemplateIsAnAbsolutePath() {
        assertEquals(FindPagesByTemplateCommand.Refusal.TEMPLATE_NOT_A_PATH,
                refusalOf(argument("/content", "article", 25)).refusal());
        assertEquals(FindPagesByTemplateCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content", TEMPLATE, 25)).refusal());
        assertEquals(FindPagesByTemplateCommand.Refusal.MEMBER_ABSENT,
                refusalOf(argument("/content", null, 25)).refusal());
        assertEquals(FindPagesByTemplateCommand.Refusal.WINDOW_REFUSED,
                refusalOf(argument("/content", TEMPLATE, 0)).refusal());
    }

    @Test
    @DisplayName("this command's row refuses an operation key and matches what the handler can fail with")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new FindPagesByTemplateHandler(CONTRACT).categories().stream().sorted().toList(),
                "the handler and its row disagree about what this command can fail with");
    }

    @Test
    @DisplayName("the query is declared, covered on every deployment, and shipped by nobody")
    void thequeryIsCoveredAndNotShipped() {
        final String coverage = read(REPOSITORY.resolve("policy/query-index-coverage.toml"));
        assertTrue(coverage.contains("issued_by = \"" + FindPagesByTemplateCommand.WIRE_NAME
                        + "\""),
                "this command issues a query nobody declared");
        assertTrue(coverage.contains("jcr:content/cq:template"),
                "the template property is not among what the declared query filters on");
        assertTrue(coverage.contains("deployment = \"aem-cloud-service\"")
                        && coverage.contains("deployment = \"aem-6-5-lts\""),
                "the index this query relies on is not recorded on both supported deployments");
    }

    private CommandHandler.Answer run(String root, String template) {
        return new FindPagesByTemplateHandler(CONTRACT).run(argument(root, template, 100),
                readOnly(), context());
    }

    private void corpus() {
        sling.create().resource("/content/site", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        for (int page = 0; page < USING; page = page + 1) {
            page("/content/site/article-" + page, TEMPLATE, "Article " + page);
        }
        for (int page = 0; page < OTHERS; page = page + 1) {
            page("/content/site/landing-" + page, OTHER, "Landing " + page);
        }
    }

    private void page(String path, String template, String title) {
        sling.create().resource(path, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        sling.create().resource(path + "/" + ListChildPagesHandler.PAGE_CONTENT, Map.of(
                FindPagesByTemplateHandler.TEMPLATE_PROPERTY, template,
                ListChildPagesHandler.TITLE_PROPERTY, title));
    }

    private static List<DocumentValue> matchesIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(PageListingResult.MATCHES).orElseThrow())
                .items();
    }

    private static FindPagesByTemplateCommand.Refused refusalOf(DocumentValue.Mapping arguments) {
        return assertInstanceOf(FindPagesByTemplateCommand.Refused.class,
                FindPagesByTemplateCommand.of(arguments, CONTRACT), "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(String root, String template, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (root != null) {
            members.put(FindPagesByTemplateCommand.ROOT_PATH, new DocumentValue.Text(root));
        }
        if (template != null) {
            members.put(FindPagesByTemplateCommand.TEMPLATE_PATH, new DocumentValue.Text(template));
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
                .row(FindPagesByTemplateCommand.WIRE_NAME).orElseThrow();
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
