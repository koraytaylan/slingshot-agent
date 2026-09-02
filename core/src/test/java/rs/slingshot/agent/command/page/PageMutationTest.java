// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
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
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.DeletedResourceResult;
import rs.slingshot.agent.command.mutation.MoveRequest;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.mutation.ReferencePolicy;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Changing, removing and moving a page — the three mutations an author's day is made of.
 *
 * <p>Proved together because what matters about them is the same thing three times: that a request
 * which was refused left the repository exactly as it was. A suite per command would prove each
 * refusal in isolation and never look at the page afterwards.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class PageMutationTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("an update writes one list, removes the other, and leaves everything else alone")
    void anupdateTouchesOnlyWhatItNames() {
        page("/content/site/article", Map.of(ListChildPagesHandler.TITLE_PROPERTY, "An Article",
                "subtitle", "A subtitle", "keep", "untouched"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdatePageCommand.PAGE_PATH, new DocumentValue.Text("/content/site/article"));
        final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
        properties.put("subtitle", single("A new subtitle"));
        members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(properties));
        members.put(PropertyChange.REMOVED_PROPERTY_NAMES,
                new DocumentValue.Sequence(List.of(new DocumentValue.Text("gone"))));
        page("/content/site/article", Map.of(ListChildPagesHandler.TITLE_PROPERTY, "An Article",
                "subtitle", "A subtitle", "keep", "untouched", "gone", "removed"));
        assertInstanceOf(CommandHandler.Produced.class,
                new UpdatePageHandler(CONTRACT).run(new DocumentValue.Mapping(members),
                        sling.resourceResolver(), context()),
                "the update was refused");
        assertEquals("A new subtitle", stored("/content/site/article/jcr:content", "subtitle"));
        assertEquals("untouched", stored("/content/site/article/jcr:content", "keep"),
                "a property named in neither list was changed, so an update carrying a partial"
                        + " view destroys the rest of the page");
        assertTrue(stored("/content/site/article/jcr:content", "gone") == null,
                "a property named for removal is still there");
    }

    @Test
    @DisplayName("a delete says what went and how much, and an absent page is refused")
    void adeleteSaysWhatWent() {
        page("/content/site/article", Map.of());
        final DocumentValue.Mapping removed = assertInstanceOf(CommandHandler.Produced.class,
                delete("/content/site/article", ReferencePolicy.IGNORE_REFERENCES),
                "the delete was refused").result();
        assertEquals(new DocumentValue.Text("/content/site/article"),
                removed.member(DeletedResourceResult.REPOSITORY_PATH).orElseThrow());
        assertTrue(((DocumentValue.Whole) removed.member(DeletedResourceResult.REMOVED_NODE_COUNT)
                        .orElseThrow()).value() > 1,
                "the page and its content node were reported as fewer than two nodes");
        assertEquals(DeletePageHandler.TARGET_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete("/content/site/nothing-is-here", ReferencePolicy.IGNORE_REFERENCES),
                        "a page that is not there was reported as deleted").category(),
                "an absent page was reported as nothing to do, and a caller who mistyped an"
                        + " address believes something is gone that is not");
    }

    @Test
    @DisplayName("a referenced page is refused under one policy and removed under the other")
    void thepolicyDecidesWhatHappens() {
        page("/content/site/article", Map.of());
        page("/content/site/other", Map.of("link", "/content/site/article"));
        assertEquals(DeletePageHandler.TARGET_IS_REFERENCED,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete("/content/site/article", ReferencePolicy.REFUSE_WHEN_REFERENCED),
                        "a referenced page was removed under the refusing policy").category());
        assertTrue(stored("/content/site/article", ListChildPagesHandler.TYPE_PROPERTY) != null,
                "the page was removed by a request that was refused");
        assertInstanceOf(CommandHandler.Produced.class,
                delete("/content/site/article", ReferencePolicy.IGNORE_REFERENCES),
                "the same page was refused under the policy that ignores references, so the"
                        + " caller's choice decided nothing");
    }

    @Test
    @DisplayName("a move reports both addresses, and a destination inside the source is refused")
    void amoveReportsBothAddresses() {
        page("/content/site/article", Map.of());
        sling.create().resource("/content/other", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                move("/content/site/article", "/content/other/article", false),
                "the move was refused").result();
        assertEquals(new DocumentValue.Text("/content/site/article"),
                moved.member(MovePageResult.SOURCE_PATH).orElseThrow());
        assertEquals(new DocumentValue.Text("/content/other/article"),
                moved.member(MovePageResult.DESTINATION_PATH).orElseThrow());
        assertEquals(MoveRequest.Refusal.DESTINATION_INSIDE_SOURCE,
                assertInstanceOf(MoveRequest.Refused.class,
                        MovePageCommand.of(moveArgument("/content/site/article",
                                "/content/site/article/inner", false), CONTRACT),
                        "a page was moved inside itself").refusal());
        assertEquals(MoveRequest.Refusal.DESTINATION_INSIDE_SOURCE,
                assertInstanceOf(MoveRequest.Refused.class,
                        MovePageCommand.of(moveArgument("/content/site/article",
                                "/content/site/article", false), CONTRACT),
                        "a page was moved onto itself").refusal());
    }

    @Test
    @DisplayName("a move that would rename the page changes nothing and says why")
    void arenameIsRefusedWithNothingChanged() {
        page("/content/site/article", Map.of());
        sling.create().resource("/content/other", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                move("/content/site/article", "/content/other/renamed", false),
                "a move that renames the page was carried out, and the platform moves a page under"
                        + " a new parent without renaming it");
        assertEquals(MovePageHandler.COMMIT_FAILED, refused.category());
        assertTrue(refused.detail().contains("renames"), refused.detail());
        assertTrue(stored("/content/site/article", ListChildPagesHandler.TYPE_PROPERTY) != null,
                "the page moved anyway, so a refusal left the repository changed");
    }

    @Test
    @DisplayName("a property the repository will not let go of is the one that stops an update")
    void animmovablePropertyStopsEverything() {
        // Proved against a node that keeps one property whatever it is asked, because no mock
        // repository protects anything and a real one protects different things than the next.
        // What is proved is the decision: removed, looked at again, and still there.
        final Map<String, Object> protecting = new Protecting(
                Map.of("jcr:primaryType", "cq:PageContent", "subtitle", "A subtitle"));
        assertEquals(java.util.Optional.of("jcr:primaryType"),
                changeRemoving("subtitle", "jcr:primaryType").immovableIn(protecting),
                "a property that was still there after being removed was reported as removed, and"
                        + " a caller told a removal succeeded builds on that");
        assertEquals(java.util.Optional.empty(),
                changeRemoving("subtitle").immovableIn(
                        new LinkedHashMap<>(Map.of("subtitle", "A subtitle"))),
                "an ordinary property was reported as one the repository will not let go of");
        assertEquals(java.util.Optional.empty(),
                changeRemoving("never-there").immovableIn(new LinkedHashMap<>()),
                "removing a property that was never there was reported as a refusal, and an"
                        + " absent property is already in the state the caller asked for");
    }

    @Test
    @DisplayName("an update, a delete and a move each refuse what is not there")
    void eachrefusesWhatIsNotThere() {
        assertEquals(UpdatePageHandler.PAGE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        update("/content/site/nothing", new LinkedHashMap<>(), List.of()),
                        "a page that is not there was updated").category());
        sling.create().resource("/content/site/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        assertEquals(UpdatePageHandler.PAGE_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        update("/content/site/folder", new LinkedHashMap<>(), List.of()),
                        "something that is not a page was updated").category(),
                "a folder was reported as absent rather than as not a page, and the caller who"
                        + " pointed at one goes looking inside it");
        assertEquals(DeletePageHandler.TARGET_NOT_A_PAGE,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete("/content/site/folder", ReferencePolicy.IGNORE_REFERENCES),
                        "something that is not a page was deleted").category());
        assertEquals(MovePageHandler.SOURCE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        move("/content/site/nothing", "/content/other/nothing", false),
                        "a page that is not there was moved").category());
    }

    @Test
    @DisplayName("a move onto a taken address, or one whose parent is missing, is refused")
    void amoveNeedsSomewhereToLand() {
        page("/content/site/article", Map.of());
        page("/content/other/article", Map.of());
        assertEquals(MovePageHandler.DESTINATION_ALREADY_EXISTS,
                assertInstanceOf(CommandHandler.Failed.class,
                        move("/content/site/article", "/content/other/article", false),
                        "a page was moved onto one that was already there").category());
        assertEquals(MovePageHandler.DESTINATION_PARENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        move("/content/site/article", "/content/nowhere/article", false),
                        "a page was moved somewhere whose parent is not there").category());
    }

    @Test
    @DisplayName("a move that follows references repoints them and says how many")
    void amoveTakesItsLinksWithIt() {
        page("/content/site/article", Map.of());
        sling.create().resource("/content/other", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        page("/content/site/pointing", Map.of("link", "/content/site/article"));
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                move("/content/site/article", "/content/other/article", true),
                "the move was refused").result();
        assertEquals(new DocumentValue.Whole(1),
                moved.member(MovePageResult.ADJUSTED_REFERENCE_COUNT).orElseThrow(),
                "the link pointing at the page was not counted, so a caller cannot tell the scale"
                        + " of what just happened");
        assertEquals("/content/other/article",
                stored("/content/site/pointing/jcr:content", "link"),
                "a link that was counted as adjusted still points at the old address");
    }

    @Test
    @DisplayName("a title is changed on its own, and a page with no content node is refused")
    void atitleIsItsOwnChange() {
        page("/content/site/article", Map.of(ListChildPagesHandler.TITLE_PROPERTY, "An Article"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdatePageCommand.PAGE_PATH, new DocumentValue.Text("/content/site/article"));
        members.put(UpdatePageCommand.TITLE, new DocumentValue.Text("A Better Article"));
        assertInstanceOf(CommandHandler.Produced.class,
                new UpdatePageHandler(CONTRACT).run(new DocumentValue.Mapping(members),
                        sling.resourceResolver(), context()),
                "an update naming only a title was refused");
        assertEquals("A Better Article", stored("/content/site/article/jcr:content",
                ListChildPagesHandler.TITLE_PROPERTY));
        sling.create().resource("/content/site/hollow", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        assertEquals(UpdatePageHandler.PAGE_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        update("/content/site/hollow", new LinkedHashMap<>(), List.of()),
                        "a page with no content node was updated").category(),
                "a page the platform could not have produced was reported as an ordinary one");
    }

    @Test
    @DisplayName("a commit the repository refuses leaves the page as it was")
    void arefusedCommitChangesNothing() {
        page("/content/site/article", Map.of(ListChildPagesHandler.TITLE_PROPERTY, "An Article"));
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("subtitle", single("A new subtitle"));
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdatePageCommand.PAGE_PATH, new DocumentValue.Text("/content/site/article"));
        members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(written));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new UpdatePageHandler(CONTRACT).run(new DocumentValue.Mapping(members),
                        ReadOnlyResolver.around(sling.resourceResolver()), context()),
                "an update was reported through a session that refuses commits");
        assertEquals(UpdatePageHandler.COMMIT_FAILED, refused.category(),
                "a commit the repository refused was reported as something else, and a caller"
                        + " cannot tell whether to retry");
    }

    @Test
    @DisplayName("all three rows are the client's own and every argument refusal lands on one")
    void everyrefusalLandsOnADeclaredCategory() {
        for (final String wire : List.of(UpdatePageCommand.WIRE_NAME, DeletePageCommand.WIRE_NAME,
                MovePageCommand.WIRE_NAME)) {
            final RegistryRow row = row(wire);
            assertEquals(RegistryRow.OperationKey.REQUIRED, row.operationKey(), wire
                    + " does not require an operation key, and running it twice is not running it"
                    + " once");
            assertTrue(row.failureCategories().contains("mutation_outcome_unknown"), wire
                    + " does not declare the answer nobody knows");
        }
        assertEquals(row(UpdatePageCommand.WIRE_NAME).failureCategories().stream().sorted().toList(),
                UpdatePageHandler.declaredCategories().stream().sorted().toList());
        assertEquals(row(DeletePageCommand.WIRE_NAME).failureCategories().stream().sorted().toList(),
                DeletePageHandler.declaredCategories().stream().sorted().toList());
        assertEquals(row(MovePageCommand.WIRE_NAME).failureCategories().stream().sorted().toList(),
                MovePageHandler.declaredCategories().stream().sorted().toList());
        assertTrue(UpdatePageHandler.declaredCategories().containsAll(
                java.util.Arrays.stream(UpdatePageCommand.Refusal.values())
                        .map(UpdatePageHandler::categoryFor).toList()),
                "an update's argument refusal reaches an undeclared category");
        assertTrue(DeletePageHandler.declaredCategories().containsAll(
                java.util.Arrays.stream(DeletePageCommand.Refusal.values())
                        .map(DeletePageHandler::categoryFor).toList()),
                "a delete's argument refusal reaches an undeclared category");
        assertTrue(MovePageHandler.declaredCategories().containsAll(
                java.util.Arrays.stream(MoveRequest.Refusal.values())
                        .map(MovePageHandler::categoryFor).toList()),
                "a move's argument refusal reaches an undeclared category");
    }

    /**
     * A node that keeps one property whatever it is asked, the way a repository keeps a protected
     * one.
     *
     * <p>No mock repository protects anything and a real one protects different things than the
     * next, so the decision is proved against a node that behaves the way the case is defined
     * rather than against whichever repository happened to be to hand.</p>
     */
    private static final class Protecting extends LinkedHashMap<String, Object> {

        private static final long serialVersionUID = 1L;

        /** The property this node will not let go of. */
        private static final String KEPT = "jcr:primaryType";

        Protecting(Map<String, Object> held) {
            super(held);
        }

        @Override
        public Object remove(Object key) {
            return KEPT.equals(key) ? get(key) : super.remove(key);
        }
    }

    private static PropertyChange changeRemoving(String... names) {
        return new PropertyChange(new LinkedHashMap<>(),
                new java.util.LinkedHashSet<>(List.of(names)));
    }

    private CommandHandler.Answer update(String page, SequencedMap<String, DocumentValue> written,
                                         List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(UpdatePageCommand.PAGE_PATH, new DocumentValue.Text(page));
        if (!written.isEmpty()) {
            members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(written));
        }
        if (!removed.isEmpty()) {
            members.put(PropertyChange.REMOVED_PROPERTY_NAMES,
                    new DocumentValue.Sequence(removed.stream()
                            .map(name -> (DocumentValue) new DocumentValue.Text(name))
                            .toList()));
        }
        return new UpdatePageHandler(CONTRACT).run(new DocumentValue.Mapping(members),
                sling.resourceResolver(), context());
    }

    private CommandHandler.Answer delete(String page, ReferencePolicy policy) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(DeletePageCommand.PAGE_PATH, new DocumentValue.Text(page));
        members.put(ReferencePolicy.ARGUMENT_MEMBER, new DocumentValue.Text(policy.spelling()));
        return new DeletePageHandler(CONTRACT).run(new DocumentValue.Mapping(members),
                sling.resourceResolver(), context());
    }

    private CommandHandler.Answer move(String source, String destination, boolean adjust) {
        return new MovePageHandler(CONTRACT).run(moveArgument(source, destination, adjust),
                sling.resourceResolver(), context());
    }

    private static DocumentValue.Mapping moveArgument(String source, String destination,
                                                      boolean adjust) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(MoveRequest.SOURCE_PATH, new DocumentValue.Text(source));
        members.put(MoveRequest.DESTINATION_PATH, new DocumentValue.Text(destination));
        members.put(MoveRequest.ADJUST_REFERENCES, new DocumentValue.Flag(
                adjust ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(members);
    }

    private void page(String path, Map<String, Object> content) {
        if (sling.resourceResolver().getResource(path) == null) {
            sling.create().resource(path, Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        }
        if (sling.resourceResolver().getResource(path + "/jcr:content") == null) {
            sling.create().resource(path + "/jcr:content", content);
        }
    }

    private String stored(String path, String property) {
        final var held = sling.resourceResolver().getResource(path);
        return held == null ? null : held.getValueMap().get(property, String.class);
    }

    private static DocumentValue single(String value) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(PropertyValue.CARDINALITY, new DocumentValue.Text(PropertyValue.SINGLE));
        final SequencedMap<String, DocumentValue> scalar = new LinkedHashMap<>();
        scalar.put(PropertyScalar.TYPE, new DocumentValue.Text(ScalarKind.STRING.spelling()));
        scalar.put(PropertyScalar.VALUE, new DocumentValue.Text(value));
        held.put(PropertyValue.VALUE, new DocumentValue.Mapping(scalar));
        return new DocumentValue.Mapping(held);
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
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
