// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.NodeTypeTemplate;
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
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Wrong-kind component changes on a real JCR provider, including a page-content subtype. */
@ExtendWith(SlingContextExtension.class)
final class ComponentKindTest {

    private static final AgentContract CONTRACT = contract();

    private static final String PATH = "/content/synthetic-component-kind";

    private static final String PAGE_CONTENT_TYPE = "cq:PageContent";

    private static final String ORDINARY = "aaa_synthetic_remove";

    private static final String ADDED = "synthetic_invalid_component_probe";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("page content is refused before a component property can be committed")
    void pageContentCannotBeUpdatedAsAComponent() throws RepositoryException {
        pageContent();
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                update(List.of()));
        assertEquals(ComponentPathHandler.COMPONENT_INVALID, refused.category());
        assertUnchanged();
    }

    @Test
    @DisplayName("wrong-kind refusal precedes property removals and their provider exceptions")
    void wrongKindCannotStageAnEarlierOrdinaryRemoval() throws RepositoryException {
        pageContent();
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                update(List.of(ORDINARY, "jcr:primaryType")));
        assertEquals(ComponentPathHandler.COMPONENT_INVALID, refused.category());
        assertUnchanged();
    }

    @Test
    @DisplayName("a primary component node retains the existing successful update")
    void anOrdinaryComponentStillCommitsItsProperties() throws RepositoryException {
        sling.create().resource(PATH, Map.of(ListChildPagesHandler.TYPE_PROPERTY,
                AddComponentHandler.ORDERED_TYPE, ORDINARY, "synthetic-original"));
        session().save();
        assertInstanceOf(CommandHandler.Produced.class, update(List.of()));
        assertFalse(session().hasPendingChanges());
        assertEquals("synthetic-added", session().getNode(PATH).getProperty(ADDED).getString());
        assertEquals("synthetic-original", session().getNode(PATH).getProperty(ORDINARY).getString());
    }

    @Test
    @DisplayName("page-content deletion retains the existing wrong-kind refusal")
    void pageContentCannotBeDeletedAsAComponent() throws RepositoryException {
        pageContent();
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ComponentPathCommand.COMPONENT_PATH, new DocumentValue.Text(PATH));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                new ComponentPathHandler(CONTRACT, ComponentPathCommand.Shape.DELETE).run(
                        new DocumentValue.Mapping(members), sling.resourceResolver(), context()));
        assertEquals(ComponentPathHandler.COMPONENT_INVALID, refused.category());
        assertUnchanged();
    }

    private void pageContent() throws RepositoryException {
        final Session held = session();
        if (!List.of(held.getWorkspace().getNamespaceRegistry().getPrefixes()).contains("cq")) {
            held.getWorkspace().getNamespaceRegistry().registerNamespace("cq",
                    "http://www.day.com/jcr/cq/1.0");
        }
        final NodeTypeManager manager = held.getWorkspace().getNodeTypeManager();
        if (!manager.hasNodeType(PAGE_CONTENT_TYPE)) {
            final NodeTypeTemplate type = manager.createNodeTypeTemplate();
            type.setName(PAGE_CONTENT_TYPE);
            type.setDeclaredSuperTypeNames(new String[] {AddComponentHandler.ORDERED_TYPE});
            type.setOrderableChildNodes(true);
            manager.registerNodeType(type, false);
        }
        sling.create().resource(PATH, Map.of(ListChildPagesHandler.TYPE_PROPERTY, PAGE_CONTENT_TYPE,
                "sling:resourceType", "synthetic/page", ORDINARY, "synthetic-original"));
        held.save();
        assertTrue(held.getNode(PATH).isNodeType(AddComponentHandler.ORDERED_TYPE));
        assertEquals(PAGE_CONTENT_TYPE, held.getNode(PATH).getPrimaryNodeType().getName());
    }

    private void assertUnchanged() throws RepositoryException {
        assertFalse(session().hasPendingChanges());
        final Resource target = java.util.Objects.requireNonNull(
                sling.resourceResolver().getResource(PATH));
        assertEquals(PAGE_CONTENT_TYPE, target.getValueMap().get(ListChildPagesHandler.TYPE_PROPERTY));
        assertEquals("synthetic-original", target.getValueMap().get(ORDINARY));
        assertFalse(session().getNode(PATH).hasProperty(ADDED));
    }

    private CommandHandler.Answer update(List<String> removed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ComponentPathCommand.COMPONENT_PATH, new DocumentValue.Text(PATH));
        members.put(PropertyChange.PROPERTIES, new DocumentValue.Mapping(
                new LinkedHashMap<>(Map.of(ADDED, single("synthetic-added")))));
        if (!removed.isEmpty()) {
            members.put(PropertyChange.REMOVED_PROPERTY_NAMES, new DocumentValue.Sequence(
                    removed.stream().map(name -> (DocumentValue) new DocumentValue.Text(name)).toList()));
        }
        return new ComponentPathHandler(CONTRACT, ComponentPathCommand.Shape.UPDATE).run(
                new DocumentValue.Mapping(members), sling.resourceResolver(), context());
    }

    private Session session() {
        return java.util.Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
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
        final AgentOperationIdentifier operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT)).identifier();
        return new CallerContext(operation,
                new Budget(Budget.Kind.DISCOVERY,
                        CONTRACT.value(ContractLimit.MAINTENANCE_SWEEP_WORK_BOUND_ROWS)),
                Budget.time(CONTRACT), new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load()).contract();
    }
}
