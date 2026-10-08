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
import org.junit.jupiter.api.Test;
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

/** Native version and lock state must admit every matching reference before movement. */
@ExtendWith(SlingContextExtension.class)
final class NativeReferenceStateTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String SOURCE = "/content/synthetic-state-source/item";
    private static final String DESTINATION = "/content/synthetic-state-destination/item";
    private static final String EARLIER = "/content/synthetic-state-earlier";
    private static final String REFERENCE = "/content/synthetic-state-reference/item";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private enum State {
        CHECKED_IN, LOCKED
    }

    private record Case(State state, boolean inherited) {
    }

    private static Stream<Case> refusedCases() {
        return Stream.of(State.values())
                .flatMap(state -> Stream.of(false, true)
                        .map(inherited -> new Case(state, inherited)));
    }

    private static Stream<Case> directCases() {
        return Stream.of(State.values())
                .map(state -> new Case(state, false));
    }

    @ParameterizedTest
    @MethodSource("refusedCases")
    void unavailableNativeStateRefusesBeforeMovementAndEarlierAssignments(Case fixture)
            throws RepositoryException {
        plant();
        sling.create().resource(REFERENCE, Map.of("link", SOURCE));
        session().save();
        final Node referenceNode = session().getNode(REFERENCE);
        final Node owner = fixture.inherited() ? referenceNode.getParent() : referenceNode;
        constrain(owner, fixture.state());

        assertAll(() -> {
            try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
                assertNotNull(required(caller, REFERENCE).adaptTo(ModifiableValueMap.class));
                final Node reference = Objects.requireNonNull(required(caller, REFERENCE)
                        .adaptTo(Node.class));
                assertTrue(reference.getSession().hasPermission(REFERENCE + "/link",
                        Session.ACTION_SET_PROPERTY));
                if (fixture.state() == State.CHECKED_IN) {
                    assertFalse(reference.isCheckedOut());
                } else {
                    assertTrue(reference.isLocked());
                    assertFalse(reference.getSession().getWorkspace().getLockManager()
                            .getLock(REFERENCE).isLockOwningSession());
                }
                refuse(caller);
            }
        }, this::persistedUntouched);
    }

    @ParameterizedTest
    @MethodSource("directCases")
    void callerWritableNativeStateStillMovesAndCommitsOnce(Case fixture)
            throws RepositoryException, LoginException {
        plant();
        sling.create().resource(REFERENCE, Map.of("link", SOURCE));
        session().save();
        final Node node = session().getNode(REFERENCE);
        if (fixture.state() == State.CHECKED_IN) {
            constrain(node, fixture.state());
            session().getWorkspace().getVersionManager().checkout(REFERENCE);
        }
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            if (fixture.state() == State.LOCKED) {
                constrain(Objects.requireNonNull(required(caller, REFERENCE).adaptTo(Node.class)),
                        fixture.state());
                assertTrue(caller.nativeSession.getWorkspace().getLockManager()
                        .getLock(REFERENCE).isLockOwningSession());
            }
            accept(caller, true, 4);
            assertArrayEquals(new String[]{DESTINATION, DESTINATION}, required(caller, EARLIER)
                    .getValueMap().get("links", String[].class));
        }
        session().refresh(false);
        assertFalse(session().nodeExists(SOURCE));
        assertTrue(session().nodeExists(DESTINATION));
        assertEquals(DESTINATION, session().getNode(REFERENCE).getProperty("link").getString());
        assertEquals(DESTINATION, session().getNode(EARLIER).getProperty("link").getString());
    }

    @ParameterizedTest
    @MethodSource("directCases")
    void explicitlyLeavingReferencesKeepsUnavailableStateOutsideTheMove(Case fixture)
            throws RepositoryException, LoginException {
        plant();
        sling.create().resource(REFERENCE, Map.of("link", SOURCE));
        session().save();
        constrain(session().getNode(REFERENCE), fixture.state());
        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            accept(caller, false, 0);
        }
        session().refresh(false);
        assertFalse(session().nodeExists(SOURCE));
        assertTrue(session().nodeExists(DESTINATION));
        assertEquals(SOURCE, session().getNode(REFERENCE).getProperty("link").getString());
        assertEquals(SOURCE, session().getNode(EARLIER).getProperty("link").getString());
    }

    private static void constrain(Node node, State state) throws RepositoryException {
        if (state == State.CHECKED_IN) {
            node.addMixin("mix:versionable");
            node.getSession().save();
            node.getSession().getWorkspace().getVersionManager().checkin(node.getPath());
        } else {
            node.addMixin("mix:lockable");
            node.getSession().save();
            node.getSession().getWorkspace().getLockManager().lock(node.getPath(), true, true,
                    Long.MAX_VALUE, "synthetic-reference-owner");
        }
    }

    @Test
    void anIgnorePropertyOnCheckedInContentStillMovesAndCommitsOnce()
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant();
        final var manager = session().getWorkspace().getNodeTypeManager();
        final NodeTypeTemplate type = manager.createNodeTypeTemplate();
        type.setName("syntheticIgnoredReference");
        type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
        final var property = manager.createPropertyDefinitionTemplate();
        property.setName("link");
        property.setRequiredType(javax.jcr.PropertyType.STRING);
        property.setOnParentVersion(javax.jcr.version.OnParentVersionAction.IGNORE);
        final List<?> definitions = type.getPropertyDefinitionTemplates();
        List.class.getMethod("add", Object.class).invoke(definitions, property);
        manager.registerNodeType(type, false);
        sling.create().resource("/content/synthetic-state-reference");
        final Node node = session().getNode("/content/synthetic-state-reference")
                .addNode("item", type.getName());
        node.setProperty("link", SOURCE);
        session().save();
        constrain(node, State.CHECKED_IN);

        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            final Node reference = Objects.requireNonNull(required(caller, REFERENCE)
                    .adaptTo(Node.class));
            assertFalse(reference.isCheckedOut());
            assertEquals(javax.jcr.version.OnParentVersionAction.IGNORE,
                    reference.getProperty("link").getDefinition().getOnParentVersion());
            accept(caller, true, 4);
        }
        session().refresh(false);
        assertFalse(session().nodeExists(SOURCE));
        assertTrue(session().nodeExists(DESTINATION));
        assertEquals(DESTINATION, session().getNode(REFERENCE).getProperty("link").getString());
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
                () -> assertEquals(SOURCE, session().getNode(REFERENCE).getProperty("link").getString()));
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
        sling.create().resource("/content/synthetic-state-destination");
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
