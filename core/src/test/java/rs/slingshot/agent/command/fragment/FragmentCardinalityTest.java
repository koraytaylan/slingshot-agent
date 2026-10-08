// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.NodeTypeTemplate;
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
import rs.slingshot.agent.command.content.ReadContentFragmentHandler;
import rs.slingshot.agent.command.content.ReadContentFragmentResult;
import rs.slingshot.agent.command.mutation.CountingResolver;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Fragment text and list cardinality through actual JCR assignments and commits. */
@ExtendWith(SlingContextExtension.class)
final class FragmentCardinalityTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    private static final String PARENT = "/content/dam/synthetic-cardinality";

    private static final String FRAGMENT = PARENT + "/synthetic-fragment";

    private static final String MASTER = FRAGMENT + "/jcr:content/data/master";

    private static final String MODEL = "/conf/synthetic-cardinality/model";

    private static final String FIELD = "synthetic_values";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a three-value fragment element stays multiple when updated to a one-item list")
    void multipleToOnePreservesTheDeclaredList() throws RepositoryException {
        existing(new String[] {"synthetic-first", "synthetic-second", "synthetic-first"});
        assertInstanceOf(CommandHandler.Produced.class, update(sequence("synthetic-one")));
        multiple("synthetic-one");
    }

    @Test
    @DisplayName("a one-item list is stored as multiple on initial fragment creation")
    void creationKeepsAOneItemList() throws RepositoryException {
        prerequisites();
        assertInstanceOf(CommandHandler.Produced.class, create(sequence("synthetic-one")));
        multiple("synthetic-one");
    }

    @Test
    @DisplayName("ordinary fragment text retains the original scalar creation behavior")
    void creationKeepsTextScalar() throws RepositoryException {
        prerequisites();
        assertInstanceOf(CommandHandler.Produced.class, create(text("synthetic-one")));
        scalar("synthetic-one");
    }

    @Test
    @DisplayName("an existing scalar element can be changed to an ordered multiple element")
    void scalarToMultipleDoesNotRaiseAProviderException() throws RepositoryException {
        existing("synthetic-original");
        assertInstanceOf(CommandHandler.Produced.class,
                update(sequence("synthetic-third", "synthetic-second", "synthetic-third")));
        multiple("synthetic-third", "synthetic-second", "synthetic-third");
    }

    @Test
    @DisplayName("an existing multiple element can be explicitly changed to scalar text")
    void multipleToTextDoesNotRaiseAProviderException() throws RepositoryException {
        existing(new String[] {"synthetic-first", "synthetic-second"});
        assertInstanceOf(CommandHandler.Produced.class, update(text("synthetic-one")));
        scalar("synthetic-one");
    }

    @Test
    @DisplayName("multiple-to-multiple updates preserve order and duplicate values")
    void existingMultipleKeepsOrderAndDuplicates() throws RepositoryException {
        existing(new String[] {"synthetic-original", "synthetic-second"});
        assertInstanceOf(CommandHandler.Produced.class,
                update(sequence("synthetic-third", "synthetic-second", "synthetic-third")));
        multiple("synthetic-third", "synthetic-second", "synthetic-third");
    }

    @Test
    @DisplayName("a refused protected element assignment commits none of the preceding changes")
    void protectedAssignmentReturnsADeclaredRefusalAndRollsBack() throws RepositoryException {
        existing("synthetic-original");
        sling.create().resource(MODEL + "/" + FragmentHandlers.MODEL_ELEMENTS + "/synthetic-protected",
                Map.of("jcr:primaryType", "nt:unstructured", "name", "jcr:primaryType"));
        session().save();
        final SequencedMap<String, DocumentValue> elements = new LinkedHashMap<>();
        elements.put(FIELD, text("synthetic-changed"));
        elements.put("jcr:primaryType", sequence("synthetic-invalid-type"));
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                run(FragmentMutationHandler.Kind.CONTENT_UPDATE, updateArguments(elements)));
        assertEquals(FragmentHandlers.ELEMENT_VALUE_REJECTED, refused.category());
        scalar("synthetic-original");
        assertEquals("nt:unstructured", session().getNode(MASTER).getPrimaryNodeType().getName());
    }

    @Test
    @DisplayName("an empty element list is refused as required by the published command schema")
    void emptyListCannotBeAcceptedAsAnElementValue() throws RepositoryException {
        existing("synthetic-original");
        final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                update(sequence()));
        assertEquals(FragmentHandlers.ELEMENT_VALUE_REJECTED, refused.category());
        scalar("synthetic-original");
    }

    @Test
    @DisplayName("a stored one-item array remains a list in the native fragment result document")
    void readKeepsTheRepositoryArrayOfOne() throws RepositoryException {
        existing(new String[] {"synthetic-one"});
        assertEquals(sequence("synthetic-one"), readElement());
    }

    @Test
    @DisplayName("a stored scalar remains text in the native fragment result document")
    void readKeepsRepositoryTextScalar() throws RepositoryException {
        existing("synthetic-one");
        assertEquals(text("synthetic-one"), readElement());
    }

    private void prerequisites() throws RepositoryException {
        final Session held = session();
        for (final String prefix : List.of("cq", "dam")) {
            if (!List.of(held.getWorkspace().getNamespaceRegistry().getPrefixes()).contains(prefix)) {
                held.getWorkspace().getNamespaceRegistry().registerNamespace(prefix,
                        "https://synthetic.invalid/" + prefix);
            }
        }
        final NodeTypeManager manager = held.getWorkspace().getNodeTypeManager();
        for (final String name : List.of("dam:Asset", "dam:AssetContent")) {
            if (!manager.hasNodeType(name)) {
                final NodeTypeTemplate type = manager.createNodeTypeTemplate();
                type.setName(name);
                type.setDeclaredSuperTypeNames(new String[] {"nt:unstructured"});
                manager.registerNodeType(type, false);
            }
        }
        sling.create().resource(PARENT, Map.of("jcr:primaryType", "nt:unstructured"));
        sling.create().resource(MODEL + "/" + FragmentHandlers.MODEL_ELEMENTS + "/synthetic-storage",
                Map.of("jcr:primaryType", "nt:unstructured", "name", FIELD));
        held.save();
    }

    private void existing(Object value) throws RepositoryException {
        prerequisites();
        sling.create().resource(FRAGMENT, Map.of("jcr:primaryType", "dam:Asset"));
        sling.create().resource(FRAGMENT + "/jcr:content",
                Map.of("jcr:primaryType", "dam:AssetContent", "contentFragment", true));
        sling.create().resource(FRAGMENT + "/jcr:content/data",
                Map.of("jcr:primaryType", "nt:unstructured", "cq:model", MODEL));
        sling.create().resource(MASTER, Map.of("jcr:primaryType", "nt:unstructured", FIELD, value));
        session().save();
    }

    private CommandHandler.Answer create(DocumentValue value) {
        final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>();
        arguments.put("parent_path", text(PARENT));
        arguments.put("name", text("synthetic-fragment"));
        arguments.put("model_path", text(MODEL));
        arguments.put("elements", new DocumentValue.Mapping(new LinkedHashMap<>(Map.of(FIELD, value))));
        return run(FragmentMutationHandler.Kind.CONTENT_CREATION, new DocumentValue.Mapping(arguments));
    }

    private CommandHandler.Answer update(DocumentValue value) {
        return run(FragmentMutationHandler.Kind.CONTENT_UPDATE,
                updateArguments(new LinkedHashMap<>(Map.of(FIELD, value))));
    }

    private static DocumentValue.Mapping updateArguments(
            SequencedMap<String, DocumentValue> elements) {
        final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>();
        arguments.put("fragment_path", text(FRAGMENT));
        arguments.put("elements", new DocumentValue.Mapping(elements));
        return new DocumentValue.Mapping(arguments);
    }

    private CommandHandler.Answer run(FragmentMutationHandler.Kind kind, DocumentValue.Mapping arguments) {
        try (CountingResolver counted = CountingResolver.around(assertDoesNotThrow(() ->
                sling.resourceResolver().clone(Map.of())))) {
            final CommandHandler.Answer answer = assertDoesNotThrow(() ->
                    new FragmentMutationHandler(CONTRACT, kind).run(arguments, counted, context()));
            if (answer instanceof CommandHandler.Failed) {
                assertEquals(0, counted.commits());
                counted.revert();
            } else {
                assertEquals(1, counted.commits());
            }
            sling.resourceResolver().refresh();
            return answer;
        }
    }

    private void multiple(String... expected) throws RepositoryException {
        assertFalse(session().hasPendingChanges());
        final Property property = session().getNode(MASTER).getProperty(FIELD);
        assertTrue(property.isMultiple());
        assertArrayEquals(expected, Arrays.stream(property.getValues()).map(value ->
                assertDoesNotThrow(value::getString)).toArray(String[]::new));
        assertEquals(sequence(expected), readElement());
    }

    private void scalar(String expected) throws RepositoryException {
        assertFalse(session().hasPendingChanges());
        final Property property = session().getNode(MASTER).getProperty(FIELD);
        assertFalse(property.isMultiple());
        assertEquals(expected, property.getString());
        assertEquals(text(expected), readElement());
    }

    private DocumentValue readElement() {
        final CommandHandler.Produced answer = assertInstanceOf(CommandHandler.Produced.class,
                new ReadContentFragmentHandler(CONTRACT).run(new DocumentValue.Mapping(
                        new LinkedHashMap<>(Map.of("fragment_path", text(FRAGMENT)))),
                        sling.resourceResolver(), context()));
        final DocumentValue.Mapping elements = assertInstanceOf(DocumentValue.Mapping.class,
                answer.result().member(ReadContentFragmentResult.ELEMENTS).orElseThrow());
        return elements.member(FIELD).orElseThrow();
    }

    private Session session() {
        return java.util.Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private static DocumentValue text(String value) {
        return new DocumentValue.Text(value);
    }

    private static DocumentValue sequence(String... values) {
        return new DocumentValue.Sequence(Arrays.stream(values).map(FragmentCardinalityTest::text).toList());
    }

    private static CallerContext context() {
        final AgentOperationIdentifier operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT)).identifier();
        return new CallerContext(operation, Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
    }
}
