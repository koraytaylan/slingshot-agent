// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
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
import rs.slingshot.agent.command.content.FindPagesUsingComponentsHandler;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.ComponentPlacement;
import rs.slingshot.agent.command.mutation.DeletedResourceResult;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The four commands that make up what an author does inside a page.
 *
 * <p>Proved together because ordering is the thing they share and the thing that goes wrong: adding
 * must not move what is already there, and reordering must put a component exactly where the caller
 * said relative to a neighbour they named.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ComponentMutationTest {

    private static final AgentContract CONTRACT = contract();

    private static final Path REPOSITORY = repositoryRoot();

    private static final String CONTENT = "/content/site/article/jcr:content";

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    /**
     * A repository that really keeps its children in order.
     *
     * <p>The resource-resolver mock refuses to order at all, and a plain repository has no page
     * type — so the commands that need a page are proved on the first and the ones that need an
     * order on the second. Reordering needs no page type: it is about a node and its siblings.</p>
     */
    private final SlingContext ordering = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a component is added last, and what was already there does not move")
    void anadditionGoesLastAndMovesNothing() {
        page();
        component("first");
        component("second");
        final DocumentValue.Mapping made = assertInstanceOf(CommandHandler.Produced.class,
                add("third", ComponentParent.CONTENT_ROOT), "the component was refused").result();
        assertEquals(new DocumentValue.Text(CONTENT + "/third"),
                made.member(AddComponentResult.TARGET_PATH).orElseThrow(),
                "the answer does not say where the component went");
        assertEquals(List.of("first", "second", "third"), namesIn(sling, CONTENT),
                "adding a component moved the ones that were already there, so a caller's page"
                        + " reads differently for a reason nobody asked for");
        assertEquals("site/components/teaser",
                stored(CONTENT + "/third",
                        FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY),
                "the component does not record what it is, and one with no type renders as"
                        + " nothing");
    }

    @Test
    @DisplayName("a parent that keeps no order is refused as itself rather than generically")
    void anunorderableParentIsItsOwnRefusal() {
        page();
        sling.create().resource(CONTENT + "/unordered", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "nt:folder"));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                add("teaser", "unordered"),
                "a component was added to a parent that keeps its children in no order");
        assertEquals(AddComponentHandler.PARENT_NOT_ORDERABLE, refused.category(),
                "a parent that cannot hold an order was reported as absent, and an author who"
                        + " could have fixed it goes looking for a path instead");
        assertTrue(sling.resourceResolver().getResource(CONTENT + "/unordered/teaser") == null,
                "a component was left behind by a request that was refused");
    }

    @Test
    @DisplayName("a reorder puts a component before the neighbour it named, and says what is in front")
    void areorderPlacesItWhereItWasAsked() {
        ordered("first", "second", "third");
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                reorderIn(ordering, "third", before("second")),
                "the reorder was refused").result();
        assertEquals(List.of("first", "third", "second"), namesIn(ordering, CONTENT),
                "the component did not end up before the neighbour the caller named");
        assertEquals(new DocumentValue.Text("first"),
                moved.member(ReorderComponentResult.PRECEDING_SIBLING_NAME).orElseThrow(),
                "the answer does not say what the component now sits behind, which is how a caller"
                        + " checks the page reads the way they meant");
    }

    @Test
    @DisplayName("a component moved to the front reports nothing in front of it")
    void amoveToTheFrontReportsNothing() {
        ordered("first", "second");
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                reorderIn(ordering, "second", before("first")),
                "the reorder was refused").result();
        assertEquals(List.of("second", "first"), namesIn(ordering, CONTENT));
        assertTrue(moved.member(ReorderComponentResult.PRECEDING_SIBLING_NAME).isEmpty(),
                "a component that is now first reported a neighbour in front of it, and an empty"
                        + " name would read as a neighbour called nothing");
    }

    @Test
    @DisplayName("a placement asking for the end puts the component last, naming every neighbour")
    void aplacementForTheEndPutsItLast() {
        ordered("first", "second", "third");
        final SequencedMap<String, DocumentValue> last = new LinkedHashMap<>();
        last.put(ComponentPlacement.MODE, new DocumentValue.Text(ComponentPlacement.LAST_MODE));
        final DocumentValue.Mapping moved = assertInstanceOf(CommandHandler.Produced.class,
                reorderIn(ordering, "first", new DocumentValue.Mapping(last)),
                "the reorder was refused").result();
        assertEquals(List.of("second", "third", "first"), namesIn(ordering, CONTENT),
                "the component did not end up last; the end is asked for by moving everything that"
                        + " follows in front of it, one named neighbour at a time");
        assertEquals(new DocumentValue.Text("third"),
                moved.member(ReorderComponentResult.PRECEDING_SIBLING_NAME).orElseThrow(),
                "the answer does not say what the component now sits behind");
    }

    @Test
    @DisplayName("a neighbour that is not among the siblings is refused, and nothing moves")
    void anabsentNeighbourIsRefused() {
        page();
        component("first");
        component("second");
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                reorder("second", before("nowhere")),
                "a component was moved before a neighbour that is not there");
        assertEquals(ComponentPathHandler.SIBLING_NOT_FOUND, refused.category());
        assertEquals(List.of("first", "second"), namesIn(sling, CONTENT),
                "the components moved anyway, so a refusal changed the page");
    }

    @Test
    @DisplayName("an update writes and removes on a component, and a delete says how much went")
    void anupdateAndADeleteActOnOneComponent() {
        page();
        sling.create().resource(CONTENT + "/teaser", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AddComponentHandler.ORDERED_TYPE,
                "text", "Hello", "subtitle", "A subtitle"));
        final SequencedMap<String, DocumentValue> written = new LinkedHashMap<>();
        written.put("text", single("Goodbye"));
        assertInstanceOf(CommandHandler.Produced.class,
                update(CONTENT + "/teaser", written, List.of("subtitle")),
                "the update was refused");
        assertEquals("Goodbye", stored(CONTENT + "/teaser", "text"));
        assertTrue(stored(CONTENT + "/teaser", "subtitle") == null,
                "a property named for removal is still there");
        final DocumentValue.Mapping removed = assertInstanceOf(CommandHandler.Produced.class,
                delete(CONTENT + "/teaser"), "the delete was refused").result();
        assertEquals(new DocumentValue.Whole(1),
                removed.member(DeletedResourceResult.REMOVED_NODE_COUNT).orElseThrow(),
                "the one node that went was not counted");
        assertTrue(sling.resourceResolver().getResource(CONTENT + "/teaser") == null,
                "the component is still there after a delete that reported it gone");
    }

    @Test
    @DisplayName("each of the four refuses a component that is not there, and says so as itself")
    void eachrefusesWhatIsNotThere() {
        page();
        assertEquals(ComponentPathHandler.COMPONENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        delete(CONTENT + "/nothing"), "a component that is not there was removed")
                        .category());
        assertEquals(ComponentPathHandler.COMPONENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        update(CONTENT + "/nothing", new LinkedHashMap<>(), List.of()),
                        "a component that is not there was updated").category());
        assertEquals(ComponentPathHandler.COMPONENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        reorder("nothing", before("first")),
                        "a component that is not there was reordered").category());
        assertEquals(AddComponentHandler.PAGE_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class,
                        run(new AddComponentHandler(CONTRACT),
                                addArgument("/content/site/nothing", "teaser",
                                        ComponentParent.CONTENT_ROOT)),
                        "a component was added to a page that is not there").category());
    }

    @Test
    @DisplayName("a commit the repository refuses leaves the page as it was")
    void arefusedCommitChangesNothing() {
        page();
        component("first");
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new ComponentPathHandler(CONTRACT, ComponentPathCommand.Shape.DELETE)
                        .run(pathArgument(CONTENT + "/first", new LinkedHashMap<>(), List.of()),
                                ReadOnlyResolver.around(sling.resourceResolver()), context()),
                "a component was reported removed through a session that refuses commits");
        assertEquals(ComponentPathHandler.COMMIT_FAILED, refused.category());
    }

    @Test
    @DisplayName("adding refuses a page that is not one, a parent that is not there, and a name taken")
    void addingRefusesEachWayItCannotProceed() {
        sling.create().resource("/content/site/folder", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, "sling:Folder"));
        assertEquals(AddComponentHandler.PAGE_INVALID,
                assertInstanceOf(CommandHandler.Failed.class,
                        run(new AddComponentHandler(CONTRACT),
                                addArgument("/content/site/folder", "teaser",
                                        ComponentParent.CONTENT_ROOT)),
                        "a component was added to something that is not a page").category(),
                "a folder was reported as absent rather than as not a page");
        page();
        assertEquals(AddComponentHandler.PARENT_NOT_FOUND,
                assertInstanceOf(CommandHandler.Failed.class, add("teaser", "nowhere/at/all"),
                        "a component was added under a parent that is not there").category());
        component("teaser");
        assertEquals(AddComponentHandler.TARGET_ALREADY_EXISTS,
                assertInstanceOf(CommandHandler.Failed.class,
                        add("teaser", ComponentParent.CONTENT_ROOT),
                        "a component was added over one already there").category(),
                "an addition replaced something, and this command replaces nothing");
    }

    @Test
    @DisplayName("a commit the repository refuses leaves no component behind")
    void anadditionThatCannotCommitLeavesNothing() {
        page();
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new AddComponentHandler(CONTRACT).run(
                        addArgument("/content/site/article", "teaser",
                                ComponentParent.CONTENT_ROOT),
                        ReadOnlyResolver.around(sling.resourceResolver()), context()),
                "a component was reported added through a session that refuses commits");
        assertEquals(AddComponentHandler.COMMIT_FAILED, refused.category());
    }

    @Test
    @DisplayName("all four rows are the client's own and every argument refusal lands on one")
    void everyrefusalLandsOnADeclaredCategory() {
        assertEquals(row(AddComponentCommand.WIRE_NAME).failureCategories().stream().sorted()
                        .toList(),
                AddComponentHandler.declaredCategories().stream().sorted().toList());
        for (final var shape : ComponentPathCommand.Shape.values()) {
            final String wire = switch (shape) {
                case UPDATE -> "update_component";
                case DELETE -> "delete_component";
                case REORDER -> "reorder_component";
            };
            assertEquals(row(wire).failureCategories().stream().sorted().toList(),
                    ComponentPathHandler.declaredCategories(shape).stream().sorted().toList(),
                    wire + " and its handler disagree about what it can fail with");
            assertTrue(ComponentPathHandler.declaredCategories(shape).containsAll(
                            java.util.Arrays.stream(ComponentPathCommand.Refusal.values())
                                    .map(refusal ->
                                            ComponentPathHandler.categoryFor(shape, refusal))
                                    .toList()),
                    wire + " has an argument refusal that reaches an undeclared category");
        }
        assertTrue(AddComponentHandler.declaredCategories().containsAll(
                        java.util.Arrays.stream(AddComponentCommand.Refusal.values())
                                .map(AddComponentHandler::categoryFor).toList()),
                "adding a component has an argument refusal that reaches an undeclared category");
    }

    private CommandHandler.Answer add(String name, String parent) {
        return run(new AddComponentHandler(CONTRACT),
                addArgument("/content/site/article", name, parent));
    }

    private CommandHandler.Answer update(String component,
                                         SequencedMap<String, DocumentValue> written,
                                         List<String> removed) {
        return run(new ComponentPathHandler(CONTRACT, ComponentPathCommand.Shape.UPDATE),
                pathArgument(component, written, removed));
    }

    private CommandHandler.Answer delete(String component) {
        return run(new ComponentPathHandler(CONTRACT, ComponentPathCommand.Shape.DELETE),
                pathArgument(component, new LinkedHashMap<>(), List.of()));
    }

    private CommandHandler.Answer reorder(String name, DocumentValue placement) {
        return reorderIn(sling, name, placement);
    }

    private static CommandHandler.Answer reorderIn(SlingContext context, String name,
                                                   DocumentValue placement) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ComponentPathCommand.COMPONENT_PATH,
                new DocumentValue.Text(CONTENT + "/" + name));
        members.put(ComponentPlacement.ARGUMENT_MEMBER, placement);
        return new ComponentPathHandler(CONTRACT, ComponentPathCommand.Shape.REORDER).run(
                new DocumentValue.Mapping(members), context.resourceResolver(),
                ComponentMutationTest.context());
    }

    /**
     * A parent that really keeps an order, with these children in it.
     *
     * @param names the children, in the order they go in
     */
    private void ordered(String... names) {
        ordering.create().resource(CONTENT, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AddComponentHandler.ORDERED_TYPE));
        for (final String name : names) {
            ordering.create().resource(CONTENT + "/" + name, Map.of(
                    ListChildPagesHandler.TYPE_PROPERTY, AddComponentHandler.ORDERED_TYPE));
        }
    }

    private CommandHandler.Answer run(CommandHandler handler, DocumentValue.Mapping arguments) {
        return handler.run(arguments, sling.resourceResolver(), context());
    }

    private static DocumentValue before(String sibling) {
        final SequencedMap<String, DocumentValue> placement = new LinkedHashMap<>();
        placement.put(ComponentPlacement.MODE,
                new DocumentValue.Text(ComponentPlacement.BEFORE_MODE));
        placement.put(ComponentPlacement.SIBLING_NAME, new DocumentValue.Text(sibling));
        return new DocumentValue.Mapping(placement);
    }

    private static DocumentValue.Mapping addArgument(String page, String name, String parent) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(AddComponentCommand.PAGE_PATH, new DocumentValue.Text(page));
        members.put(ComponentParent.ARGUMENT_MEMBER, new DocumentValue.Text(parent));
        members.put(AddComponentCommand.COMPONENT_NAME, new DocumentValue.Text(name));
        members.put(AddComponentCommand.RESOURCE_TYPE,
                new DocumentValue.Text("site/components/teaser"));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping pathArgument(String component,
                                                      SequencedMap<String, DocumentValue> written,
                                                      List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ComponentPathCommand.COMPONENT_PATH, new DocumentValue.Text(component));
        if (!written.isEmpty()) {
            members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(written));
        }
        if (!removed.isEmpty()) {
            members.put(PropertyChange.REMOVED_PROPERTY_NAMES,
                    new DocumentValue.Sequence(removed.stream()
                            .map(name -> (DocumentValue) new DocumentValue.Text(name))
                            .toList()));
        }
        return new DocumentValue.Mapping(members);
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

    private void page() {
        sling.create().resource("/content/site/article", Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, ListChildPagesHandler.PAGE_TYPE));
        sling.create().resource(CONTENT, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AddComponentHandler.ORDERED_TYPE));
    }

    private void component(String name) {
        sling.create().resource(CONTENT + "/" + name, Map.of(
                ListChildPagesHandler.TYPE_PROPERTY, AddComponentHandler.ORDERED_TYPE,
                FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY,
                "site/components/teaser"));
    }

    private static List<String> namesIn(SlingContext context, String parent) {
        final Resource held = context.resourceResolver().getResource(parent);
        assertTrue(held != null, parent + " is not there");
        final List<String> names = new ArrayList<>();
        final Iterator<Resource> children = held.listChildren();
        while (children.hasNext()) {
            names.add(children.next().getName());
        }
        return List.copyOf(names);
    }

    private String stored(String path, String property) {
        final Resource held = sling.resourceResolver().getResource(path);
        return held == null ? null : held.getValueMap().get(property, String.class);
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
