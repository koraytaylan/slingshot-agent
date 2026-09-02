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
 * What a page references, and being honest about which kinds were looked at.
 *
 * <p>The claim worth proving is that each declared kind is actually found and an undeclared one is
 * actually ignored. A command that quietly searched more than it was asked to would make "this page
 * references nothing" mean something different from what the caller asked — and the next thing
 * somebody does with that sentence is delete assets.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class FindAssetsReferencedByPageCommandTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    /** The asset referenced by a plain property value. */
    private static final String BY_PROPERTY = "/content/dam/site/hero.png";

    /** The asset referenced from inside a fragment of markup. */
    private static final String BY_MARKUP = "/content/dam/site/inline.png";

    /** The asset referenced as one item of a multi-valued property. */
    private static final String BY_STRUCTURE = "/content/dam/site/gallery-one.png";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("every way a page can reference an asset is found, whichever way it was written")
    void everywayOfReferencingIsFound() {
        corpus();
        // A page references an asset through a property holding its path, through a path inside a
        // fragment of stored markup, and through one item of a multi-valued property. The client
        // asks about a page rather than about a way of referencing, so all three are one answer:
        // an asset missed because of how somebody's component stored it is an asset reported as
        // unreferenced, and deleted.
        assertEquals(List.of(BY_PROPERTY, BY_MARKUP, BY_STRUCTURE).stream().sorted().toList(),
                pathsFrom(referenced()).stream().sorted().toList(),
                "a way of referencing an asset was not looked for");
    }

    @Test
    @DisplayName("an asset referenced several times appears once, with every place listed")
    void anassetReferencedTwiceAppearsOnce() {
        corpus();
        page("/content/site/repeat");
        sling.create().resource("/content/site/repeat/jcr:content", Map.of(
                "fileReference", BY_PROPERTY, "secondReference", BY_PROPERTY));
        final DocumentValue.Mapping found = assertInstanceOf(CommandHandler.Produced.class,
                new FindAssetsReferencedByPageHandler(CONTRACT).run(
                        argument("/content/site/repeat", 100), readOnly(), context()),
                "the walk was refused").result();
        assertEquals(1, matchesIn(found).size(),
                "an asset referenced twice on one page was answered twice, and what a caller does"
                        + " with this answer is decide whether the asset can be removed");
        assertEquals(2, referencesOf(found).size(),
                "both places the asset was referenced from were not listed, and the caller who"
                        + " decides it cannot be removed has to go and look at them");
    }

    @Test
    @DisplayName("nothing there, unreadable, and not a page are three answers rather than one")
    void thethreePageFailuresAreDistinct() {
        corpus();
        sling.create().resource("/content/site/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        assertEquals(FindAssetsReferencedByPageHandler.PAGE_NOT_FOUND,
                failed("/content/site/nothing-is-here").category());
        assertEquals(FindAssetsReferencedByPageHandler.PAGE_INVALID,
                failed("/content/site/folder").category(),
                "something that is there and is not a page was reported as absent, and the caller"
                        + " who pointed at a folder needs to go looking inside it");
        assertTrue(new FindAssetsReferencedByPageHandler(CONTRACT).categories()
                        .contains(FindAssetsReferencedByPageHandler.PAGE_ACCESS_DENIED),
                "this command does not declare the permission failure its own client's"
                        + " classification gives it");
    }

    @Test
    @DisplayName("the page is required and named absolutely, and nothing else is taken")
    void thepageIsRequiredAndAbsolute() {
        assertEquals(FindAssetsReferencedByPageCommand.Refusal.MEMBER_ABSENT,
                refusalOf(new DocumentValue.Mapping(new LinkedHashMap<>())).refusal(),
                "an argument naming no page was accepted, and this command chooses none");
        assertEquals(FindAssetsReferencedByPageCommand.Refusal.NOT_AN_ABSOLUTE_PATH,
                refusalOf(argument("content/site/article", 25)).refusal());
        final SequencedMap<String, DocumentValue> extra =
                new LinkedHashMap<>(argument("/content/site/article", 25).members());
        extra.put("reference_kinds", new DocumentValue.Sequence(List.of()));
        assertEquals(FindAssetsReferencedByPageCommand.Refusal.MEMBER_UNKNOWN,
                refusalOf(new DocumentValue.Mapping(extra)).refusal(),
                "a member the client's own schema does not declare was accepted rather than"
                        + " refused, and a caller sending one believes it did something");
    }

    @Test
    @DisplayName("a markup fragment yields the asset path and not the markup around it")
    void markupYieldsThePathAlone() {
        assertEquals(List.of("/content/dam/site/one.png", "/content/dam/site/two.jpg"),
                FindAssetsReferencedByPageHandler.markupReferences(
                        "<p><img src=\"/content/dam/site/one.png\"/> and "
                                + "<img src='/content/dam/site/two.jpg'></p>"),
                "the paths inside a markup fragment were not found, or the markup around them came"
                        + " with them");
        assertEquals(List.of(), FindAssetsReferencedByPageHandler.markupReferences("<p>no assets</p>"));
    }

    @Test
    @DisplayName("this command's row refuses an operation key and declares its own page failures")
    void therowIsTheClientsOwn() {
        final RegistryRow row = row();
        assertEquals(RegistryRow.OperationKey.REFUSED, row.operationKey());
        assertEquals(1048576, row.resultBytes());
        assertEquals(row.failureCategories().stream().sorted().toList(),
                new FindAssetsReferencedByPageHandler(CONTRACT).categories().stream().sorted()
                        .toList(),
                "the handler and its row disagree about what this command can fail with");
        assertTrue(row.failureCategories().stream().noneMatch(category ->
                        category.startsWith("root_")),
                "this command declares the shared root-anchor failures, and it anchors on one page"
                        + " a caller named rather than on a subtree");
    }

    private CommandHandler.Failed failed(String page) {
        return assertInstanceOf(CommandHandler.Failed.class,
                new FindAssetsReferencedByPageHandler(CONTRACT).run(
                        argument(page, 100), readOnly(), context()),
                page + " was walked");
    }

    private DocumentValue.Mapping referenced() {
        return assertInstanceOf(CommandHandler.Produced.class,
                new FindAssetsReferencedByPageHandler(CONTRACT).run(
                        argument("/content/site/article", 100), readOnly(), context()),
                "the walk was refused").result();
    }

    private static List<DocumentValue> matchesIn(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) result.member(FindAssetsReferencedByPageResult.MATCHES)
                .orElseThrow()).items();
    }

    private static List<String> pathsFrom(DocumentValue.Mapping result) {
        return matchesIn(result).stream()
                .map(asset -> ((DocumentValue.Mapping) asset)
                        .member(FindAssetsReferencedByPageResult.REPOSITORY_PATH).orElseThrow())
                .map(path -> ((DocumentValue.Text) path).value())
                .toList();
    }

    private static List<DocumentValue> referencesOf(DocumentValue.Mapping result) {
        return ((DocumentValue.Sequence) ((DocumentValue.Mapping) matchesIn(result).getFirst())
                .member(FindAssetsReferencedByPageResult.REFERENCE_PATHS).orElseThrow()).items();
    }

    private void corpus() {
        page("/content/site");
        page("/content/site/article");
        sling.create().resource("/content/site/article/jcr:content", Map.of(
                "fileReference", BY_PROPERTY,
                "text", "<p><img src=\"" + BY_MARKUP + "\"/></p>",
                "gallery", new String[] {BY_STRUCTURE, "/content/other/not-an-asset"}));
    }

    private void page(String path) {
        sling.create().resource(path, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
    }

    private static FindAssetsReferencedByPageCommand.Refused refusalOf(
            DocumentValue.Mapping arguments) {
        return assertInstanceOf(FindAssetsReferencedByPageCommand.Refused.class,
                FindAssetsReferencedByPageCommand.of(arguments, CONTRACT),
                "the argument was accepted");
    }

    private static DocumentValue.Mapping argument(String page, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(FindAssetsReferencedByPageCommand.PAGE_PATH, new DocumentValue.Text(page));
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
                .row(FindAssetsReferencedByPageCommand.WIRE_NAME).orElseThrow();
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
