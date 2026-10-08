// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeTypeTemplate;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.command.page.MovePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Native value constraints admit the complete replacement before a page moves. */
@ExtendWith(SlingContextExtension.class)
final class NativeReferenceConstraintTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String SOURCE = "/content/synthetic-constraint-source/item";
    private static final String DESTINATION = "/content/synthetic-constraint-destination/item";
    private static final String EARLIER = "/content/synthetic-constraint-earlier";
    private static final String REFERENCE = "/content/synthetic-constraint-reference/item";
    private static final String UNRELATED = "/content/synthetic-constraint-unrelated";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private enum Kind {
        STRING(javax.jcr.PropertyType.STRING), PATH(javax.jcr.PropertyType.PATH),
        URI(javax.jcr.PropertyType.URI);

        private final int code;

        Kind(int code) {
            this.code = code;
        }
    }

    private record Case(boolean mixin, boolean multiple, Kind kind) {
    }

    private static Stream<Case> cases() {
        return Stream.of(false, true)
                .flatMap(mixin -> Stream.of(false, true)
                        .flatMap(multiple -> Stream.of(Kind.values())
                                .map(kind -> new Case(mixin, multiple, kind))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void rejectedReplacementRefusesBeforeMovementAndEarlierAssignments(Case fixture)
            throws RepositoryException, ReflectiveOperationException {
        plant();
        plantConstraint(fixture, false, true);
        assertAll(() -> {
            try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
                final Node node = nativeReference(caller, fixture);
                assertFalse(allowsReplacement(node, fixture));
                refuse(caller);
            }
        }, this::persistedUntouched);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void permittedReplacementMovesAndCommitsOncePreservingOtherArrayValues(Case fixture)
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant();
        plantConstraint(fixture, true, true);
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            assertTrue(allowsReplacement(nativeReference(caller, fixture), fixture));
            accept(caller, true, fixture.multiple() ? 5 : 4);
        }
        persistedMoved(fixture, true, true);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void explicitlyLeavingReferencesDoesNotApplyValueConstraintsToTheMove(Case fixture)
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant();
        plantConstraint(fixture, false, true);
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            assertFalse(allowsReplacement(nativeReference(caller, fixture), fixture));
            accept(caller, false, 0);
        }
        persistedMoved(fixture, false, true);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void nonmatchingConstrainedPropertyRemainsUntouchedWhileOtherReferencesMove(Case fixture)
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant();
        plantConstraint(fixture, false, false);
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            assertFalse(allowsReplacement(nativeReference(caller, fixture), fixture));
            accept(caller, true, 3);
        }
        persistedMoved(fixture, true, false);
    }

    private void plantConstraint(Case fixture, boolean allowDestination, boolean mentions)
            throws RepositoryException, ReflectiveOperationException {
        final var manager = session().getWorkspace().getNodeTypeManager();
        final NodeTypeTemplate type = manager.createNodeTypeTemplate();
        type.setName("syntheticConstrainedReference");
        type.setMixin(fixture.mixin());
        if (!fixture.mixin()) {
            type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
        }
        final var definition = manager.createPropertyDefinitionTemplate();
        definition.setName("link");
        definition.setRequiredType(fixture.kind().code);
        definition.setMultiple(fixture.multiple());
        definition.setValueConstraints(allowDestination ? new String[]{SOURCE, UNRELATED, DESTINATION}
                : mentions ? new String[]{SOURCE, UNRELATED} : new String[]{UNRELATED});
        List.class.getMethod("add", Object.class).invoke(type.getPropertyDefinitionTemplates(), definition);
        manager.registerNodeType(type, false);
        sling.create().resource("/content/synthetic-constraint-reference");
        final Node node = session().getNode("/content/synthetic-constraint-reference")
                .addNode("item", fixture.mixin() ? "nt:unstructured" : type.getName());
        if (fixture.mixin()) {
            node.addMixin(type.getName());
        }
        final String value = mentions ? SOURCE : UNRELATED;
        if (fixture.multiple()) {
            node.setProperty("link", new String[]{value, UNRELATED, value}, fixture.kind().code);
        } else {
            node.setProperty("link", value, fixture.kind().code);
        }
        session().save();
    }

    private static Node nativeReference(FixtureCaller caller, Case fixture) throws RepositoryException {
        final Resource resource = required(caller, REFERENCE);
        assertNotNull(resource.adaptTo(ModifiableValueMap.class));
        final Node node = Objects.requireNonNull(resource.adaptTo(Node.class));
        assertTrue(node.getSession().hasPermission(REFERENCE + "/link", Session.ACTION_SET_PROPERTY));
        assertTrue(node.isCheckedOut());
        assertFalse(node.isLocked());
        assertEquals("syntheticConstrainedReference",
                node.getProperty("link").getDefinition().getDeclaringNodeType().getName());
        assertEquals(fixture.multiple(), node.getProperty("link").isMultiple());
        assertEquals(fixture.kind().code, node.getProperty("link").getType());
        assertFalse(node.getProperty("link").getDefinition().isProtected());
        return node;
    }

    private static boolean allowsReplacement(Node node, Case fixture) throws RepositoryException {
        final var type = node.getProperty("link").getDefinition().getDeclaringNodeType();
        final var factory = node.getSession().getValueFactory();
        return fixture.multiple() ? type.canSetProperty("link", new javax.jcr.Value[]{
            factory.createValue(DESTINATION), factory.createValue(UNRELATED),
            factory.createValue(DESTINATION)})
                : type.canSetProperty("link", factory.createValue(DESTINATION));
    }

    private void persistedMoved(Case fixture, boolean adjusted, boolean mentioned)
            throws RepositoryException {
        session().refresh(false);
        assertFalse(session().nodeExists(SOURCE));
        assertTrue(session().nodeExists(DESTINATION));
        final String earlier = adjusted ? DESTINATION : SOURCE;
        assertEquals(earlier, session().getNode(EARLIER).getProperty("link").getString());
        final String reference = mentioned ? earlier : UNRELATED;
        final var property = session().getNode(REFERENCE).getProperty("link");
        assertEquals(fixture.kind().code, property.getType());
        if (fixture.multiple()) {
            assertArrayEquals(new String[]{reference, UNRELATED, reference},
                    Stream.of(property.getValues()).map(value -> {
                        try {
                            return value.getString();
                        } catch (final RepositoryException failed) {
                            throw new IllegalStateException(failed);
                        }
                    }).toArray(String[]::new));
        } else {
            assertEquals(reference, property.getString());
        }
    }

    private void refuse(FixtureCaller caller) throws RepositoryException {
        final CommandHandler.Answer answer = assertDoesNotThrow(() -> move(caller, true),
                "a known native reference refusal escaped the handler");
        final CommandHandler.Failed failure = assertInstanceOf(CommandHandler.Failed.class, answer);
        assertEquals(MovePageHandler.COMMIT_FAILED, failure.category());
        assertEquals(0, caller.moves.get(), "reference admission happened after native movement");
        assertEquals(0, caller.commits.get(), "reference admission happened after a commit attempt");
        assertFalse(caller.nativeSession.hasPendingChanges(), "a refused move staged native effects");
        assertTrue(caller.nativeSession.nodeExists(SOURCE));
        assertFalse(caller.nativeSession.nodeExists(DESTINATION));
        assertEquals(SOURCE, caller.nativeSession.getNode(EARLIER).getProperty("link").getString());
    }

    private static void accept(FixtureCaller caller, boolean adjust, long count) {
        final CommandHandler.Answer answer = move(caller, adjust);
        final CommandHandler.Produced result = assertInstanceOf(CommandHandler.Produced.class,
                answer, "the native control answered " + answer);
        assertEquals(new DocumentValue.Whole(count), result.result()
                .member("adjusted_reference_count").orElseThrow());
        assertEquals(1, caller.moves.get());
        assertEquals(1, caller.commits.get());
    }

    private void persistedUntouched() throws RepositoryException {
        session().refresh(false);
        assertAll(() -> assertTrue(session().nodeExists(SOURCE)),
                () -> assertFalse(session().nodeExists(DESTINATION)),
                () -> assertEquals(SOURCE, session().getNode(EARLIER).getProperty("link").getString()),
                () -> {
                    final var property = session().getNode(REFERENCE).getProperty("link");
                    assertEquals(SOURCE, property.isMultiple() ? property.getValues()[0].getString()
                            : property.getString());
                });
    }

    private void plant() throws RepositoryException {
        final String prefix = "cq";
        final String name = "cq:Page";
        final var namespaces = session().getWorkspace().getNamespaceRegistry();
        if (!List.of(namespaces.getPrefixes()).contains(prefix)) {
            namespaces.registerNamespace(prefix, "https://synthetic.invalid/" + prefix);
        }
        final var manager = session().getWorkspace().getNodeTypeManager();
        if (!manager.hasNodeType(name)) {
            final NodeTypeTemplate type = manager.createNodeTypeTemplate();
            type.setName(name);
            type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
            manager.registerNodeType(type, false);
        }
        sling.create().resource(SOURCE, Map.of("jcr:primaryType", name));
        sling.create().resource("/content/synthetic-constraint-destination");
        sling.create().resource(EARLIER, Map.of("link", SOURCE, "links", new String[]{SOURCE, SOURCE}));
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private static Resource required(ResourceResolver caller, String path) {
        return Objects.requireNonNull(caller.getResource(path), "the native synthetic fixture resource");
    }

    private static CommandHandler.Answer move(ResourceResolver caller, boolean adjust) {
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(MoveRequest.SOURCE_PATH, new DocumentValue.Text(SOURCE));
        members.put(MoveRequest.DESTINATION_PATH, new DocumentValue.Text(DESTINATION));
        members.put(MoveRequest.ADJUST_REFERENCES, new DocumentValue.Flag(adjust
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        final CommandHandler handler = new MovePageHandler(CONTRACT);
        final var operation = assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT)).identifier();
        final var context = new CallerContext(operation, Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
        return handler.run(new DocumentValue.Mapping(members), caller, context);
    }

    private static final class FixtureCaller extends ResourceResolverWrapper {

        private final Session nativeSession;
        private final Session countedSession;
        private final AtomicInteger moves = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

        private FixtureCaller(ResourceResolver delegate) {
            super(delegate);
            nativeSession = Objects.requireNonNull(delegate.adaptTo(Session.class));
            countedSession = Session.class.cast(Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[]{Session.class},
                    (held, method, arguments) -> {
                        if ("move".equals(method.getName())) {
                            moves.incrementAndGet();
                        }
                        try {
                            return method.invoke(nativeSession, arguments);
                        } catch (final InvocationTargetException failed) {
                            throw failed.getCause();
                        }
                    }));
        }

        @Override
        public <Adapter> Adapter adaptTo(Class<Adapter> type) {
            return type == Session.class ? type.cast(countedSession) : super.adaptTo(type);
        }

        @Override
        public void commit() throws PersistenceException {
            commits.incrementAndGet();
            super.commit();
        }
    }
}
