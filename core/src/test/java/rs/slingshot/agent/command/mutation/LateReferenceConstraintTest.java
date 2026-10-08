// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import org.apache.sling.api.resource.PersistenceException;
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
import rs.slingshot.agent.command.asset.AssetMutationHandler;
import rs.slingshot.agent.command.page.MovePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Late native constraints admit the actual replacement before assignment and commit. */
@ExtendWith(SlingContextExtension.class)
final class LateReferenceConstraintTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String SOURCE = "/content/synthetic-late-constraint-source/item";
    private static final String DESTINATION = "/content/synthetic-late-constraint-destination/item";
    private static final String REFERENCE = "/content/synthetic-late-constraint-reference";
    private static final String UNRELATED = "/content/synthetic-late-constraint-unrelated";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private enum Target {
        PAGE, ASSET
    }

    private enum Kind {
        STRING(javax.jcr.PropertyType.STRING), PATH(javax.jcr.PropertyType.PATH),
        URI(javax.jcr.PropertyType.URI);

        private final int code;

        Kind(int code) {
            this.code = code;
        }
    }

    private record Case(Target target, boolean mixin, boolean multiple, Kind kind) {
    }

    private static Stream<Case> cases() {
        return Stream.of(Target.values()).flatMap(target -> Stream.of(false, true)
                .flatMap(mixin -> Stream.of(false, true)
                        .flatMap(multiple -> Stream.of(Kind.values())
                                .map(kind -> new Case(target, mixin, multiple, kind)))));
    }


    @ParameterizedTest
    @MethodSource("cases")
    void newlyVisibleDeniedReplacementRefusesBeforeAssignmentAndCommit(Case fixture)
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant(fixture.target());
        prepareDefinition(fixture, false);
        try (ResourceResolver writerOwner = sling.resourceResolver().clone(Map.of())) {
            final Session writer = nativeSession(writerOwner);
            try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()),
                    writer, fixture)) {
                assertNotSame(writer, caller.nativeSession);
                assertFalse(caller.nativeSession.hasPendingChanges());
                final CommandHandler.Failed refused = assertInstanceOf(CommandHandler.Failed.class,
                        move(fixture.target(), caller, true));
                assertEquals(MovePageHandler.COMMIT_FAILED, refused.category());
                assertEquals(1, caller.moves.get());
                assertEquals(1, caller.foreignCommits.get());
                assertEquals(0, caller.commits.get(), "late native admission reached commit");
                assertTrue(caller.nativeSession.hasPendingChanges());
            }
            persisted(fixture, writer, false);
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void newlyVisibleAllowedReplacementPreservesOtherValuesAndNativeKind(Case fixture)
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant(fixture.target());
        prepareDefinition(fixture, true);
        try (ResourceResolver writerOwner = sling.resourceResolver().clone(Map.of())) {
            final Session writer = nativeSession(writerOwner);
            try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()),
                    writer, fixture)) {
                assertNotSame(writer, caller.nativeSession);
                final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                        move(fixture.target(), caller, true));
                assertEquals(new DocumentValue.Whole(fixture.multiple() ? 3 : 2),
                        produced.result().member("adjusted_reference_count").orElseThrow());
                assertEquals(1, caller.moves.get());
                assertEquals(1, caller.foreignCommits.get());
                assertEquals(1, caller.commits.get());
                assertFalse(caller.nativeSession.hasPendingChanges());
            }
            persisted(fixture, writer, true);
        }
    }

    private void prepareDefinition(Case fixture, boolean allowDestination)
            throws RepositoryException, ReflectiveOperationException {
        final var manager = session().getWorkspace().getNodeTypeManager();
        final NodeTypeTemplate type = manager.createNodeTypeTemplate();
        type.setName("syntheticLateReference");
        type.setMixin(fixture.mixin());
        if (!fixture.mixin()) {
            type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
        }
        final var definition = manager.createPropertyDefinitionTemplate();
        definition.setName("later");
        definition.setRequiredType(fixture.kind().code);
        definition.setMultiple(fixture.multiple());
        definition.setValueConstraints(allowDestination ? new String[]{SOURCE, UNRELATED, DESTINATION}
                : new String[]{SOURCE, UNRELATED});
        List.class.getMethod("add", Object.class).invoke(type.getPropertyDefinitionTemplates(), definition);
        manager.registerNodeType(type, false);
        final Node reference = session().getNode(REFERENCE);
        if (fixture.mixin()) {
            reference.addMixin(type.getName());
        } else {
            reference.setPrimaryType(type.getName());
        }
        session().save();
        assertFalse(reference.hasProperty("later"));
    }

    private static void foreignCommit(Session writer, Case fixture) throws RepositoryException {
        writer.refresh(false);
        final Node reference = writer.getNode(REFERENCE);
        if (fixture.multiple()) {
            reference.setProperty("later", new String[]{SOURCE, UNRELATED, SOURCE}, fixture.kind().code);
        } else {
            reference.setProperty("later", SOURCE, fixture.kind().code);
        }
        writer.save();
        assertFalse(writer.hasPendingChanges());
    }

    private static void persisted(Case fixture, Session writer, boolean moved) throws RepositoryException {
        writer.refresh(false);
        assertEquals(!moved, writer.nodeExists(SOURCE));
        assertEquals(moved, writer.nodeExists(DESTINATION));
        final Node reference = writer.getNode(REFERENCE);
        final String expected = moved ? DESTINATION : SOURCE;
        assertEquals(expected, reference.getProperty("link").getString());
        final var property = reference.getProperty("later");
        assertEquals("syntheticLateReference", property.getDefinition().getDeclaringNodeType().getName());
        assertEquals(fixture.kind().code, property.getType());
        assertEquals(fixture.multiple(), property.isMultiple());
        if (fixture.multiple()) {
            assertArrayEquals(new String[]{expected, UNRELATED, expected},
                    Stream.of(property.getValues()).map(value -> {
                        try {
                            return value.getString();
                        } catch (final RepositoryException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }).toArray(String[]::new));
        } else {
            assertEquals(expected, property.getString());
        }
    }

    private static Session nativeSession(ResourceResolver resolver) {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class));
        assertTrue(session.isLive());
        assertTrue(session.getClass().getName().startsWith("org.apache.jackrabbit.oak."));
        return session;
    }

    private void plant(Target target) throws RepositoryException {
        final String prefix = target == Target.PAGE ? "cq" : "dam";
        final String name = target == Target.PAGE ? "cq:Page" : "dam:Asset";
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
        sling.create().resource("/content/synthetic-late-constraint-destination");
        sling.create().resource(REFERENCE, Map.of("link", SOURCE));
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private static CommandHandler.Answer move(Target target, ResourceResolver caller, boolean adjust) {
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(MoveRequest.SOURCE_PATH, new DocumentValue.Text(SOURCE));
        members.put(MoveRequest.DESTINATION_PATH, new DocumentValue.Text(DESTINATION));
        members.put(MoveRequest.ADJUST_REFERENCES, new DocumentValue.Flag(adjust
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        final CommandHandler handler = target == Target.PAGE ? new MovePageHandler(CONTRACT)
                : new AssetMutationHandler(CONTRACT, AssetMutationHandler.Kind.MOVE);
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
        private final AtomicInteger foreignCommits = new AtomicInteger();

        private FixtureCaller(ResourceResolver delegate, Session writer, Case fixture) {
            super(delegate);
            nativeSession = nativeSession(delegate);
            countedSession = Session.class.cast(Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[]{Session.class},
                    (held, method, arguments) -> {
                        if ("move".equals(method.getName())) {
                            assertEquals(0, moves.get());
                            foreignCommit(writer, fixture);
                            foreignCommits.incrementAndGet();
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
