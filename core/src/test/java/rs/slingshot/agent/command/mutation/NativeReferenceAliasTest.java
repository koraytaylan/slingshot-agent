// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

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
import java.util.stream.Stream;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
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
import rs.slingshot.agent.command.asset.AssetMutationHandler;
import rs.slingshot.agent.command.page.MovePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.json.DocumentValue;

/** Native property identities survive provider aliases during discovery, admission and assignment. */
@ExtendWith(SlingContextExtension.class)
final class NativeReferenceAliasTest {

    private static final String SOURCE = "/content/synthetic-alias-source/item";
    private static final String DESTINATION = "/content/synthetic-alias-destination/item";
    private static final String REFERENCE = "/content/synthetic-alias-reference";
    private static final String OTHER = "/content/synthetic-alias-unrelated";
    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private enum Target {
        PAGE, ASSET
    }

    private enum Mode {
        ALIAS_ONLY, WITH_ANCHOR
    }

    private enum Cardinality {
        SCALAR, MULTIPLE
    }

    private enum Kind {
        STRING(1), PATH(8), URI(11);

        private final int code;

        Kind(int code) {
            this.code = code;
        }
    }

    private enum Alias {
        ISO_SPACE("link label", "link_x0020_label"),
        ISO_UNDERSCORE("link_label", "link_x005f_label"),
        PERCENT_SPACE("link label", "link%20label"),
        PERCENT_PERCENT("link%label", "link%25label"),
        ORDINARY_CONTROL("firstLink", "secondLink");

        private final String decoded;
        private final String encoded;

        Alias(String decoded, String encoded) {
            this.decoded = decoded;
            this.encoded = encoded;
        }
    }

    private record Case(Target target, Mode mode, Kind kind, Cardinality cardinality, Alias alias) {
    }

    @ParameterizedTest
    @MethodSource("cases")
    void exactNativeReferencesAreCountedAndRewrittenWithoutChangingTheirAlias(Case fixture)
            throws RepositoryException, PersistenceException, LoginException {
        final List<String> names = plant(fixture);
        final Session observer = nativeSession(sling.resourceResolver());
        final long matching = fixture.cardinality() == Cardinality.MULTIPLE ? 2 : 1;
        final long adjusted = matching + (fixture.mode() == Mode.WITH_ANCHOR ? 1 : 0);
        try (Caller caller = new Caller(sling.resourceResolver().clone(Map.of()))) {
            assertNotSame(observer, caller.nativeSession);
            final RepositoryReach.References found = RepositoryReach.references(caller, SOURCE, 100000);
            assertTrue(found.complete());
            assertEquals(1, found.found().size());
            assertTrue(RepositoryReach.possiblyReferenced(caller, SOURCE, 100000));
            final Resource reference = Objects.requireNonNull(caller.getResource(REFERENCE));
            assertTrue(RepositoryReach.adjustmentsWithin(List.of(reference), SOURCE, adjusted));
            assertFalse(RepositoryReach.adjustmentsWithin(List.of(reference), SOURCE, adjusted - 1));
            final CommandHandler.Produced produced = assertInstanceOf(CommandHandler.Produced.class,
                    move(fixture, caller));
            assertEquals(new DocumentValue.Whole(adjusted),
                    produced.result().member("adjusted_reference_count").orElseThrow());
            assertEquals(1, caller.moves);
            assertEquals(1, caller.commits);
            assertFalse(caller.nativeSession.hasPendingChanges());
        }
        observer.refresh(false);
        assertFalse(observer.nodeExists(SOURCE));
        assertTrue(observer.nodeExists(DESTINATION));
        final Node persisted = observer.getNode(REFERENCE);
        assertEquals(0, count(persisted, names.getLast(), SOURCE));
        assertEquals(matching, count(persisted, names.getLast(), DESTINATION));
        unchangedAlias(persisted, names, fixture);
        if (fixture.mode() == Mode.WITH_ANCHOR) {
            assertEquals(DESTINATION, persisted.getProperty("anchor").getString());
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void theMatchingNativeAliasDefinitionRefusesBeforeMovementOrCommit(Case fixture)
            throws RepositoryException, PersistenceException, LoginException, ReflectiveOperationException {
        final List<String> names = plant(fixture);
        final Session observer = nativeSession(sling.resourceResolver());
        denyReplacement(observer.getNode(REFERENCE), names.getLast(), fixture);
        sling.resourceResolver().commit();
        try (Caller caller = new Caller(sling.resourceResolver().clone(Map.of()))) {
            final CommandHandler.Failed failure = assertInstanceOf(CommandHandler.Failed.class,
                    move(fixture, caller));
            assertEquals(MovePageHandler.COMMIT_FAILED, failure.category());
            assertEquals(0, caller.moves);
            assertEquals(0, caller.commits);
            assertFalse(caller.nativeSession.hasPendingChanges());
        }
        observer.refresh(false);
        assertTrue(observer.nodeExists(SOURCE));
        assertFalse(observer.nodeExists(DESTINATION));
        final Node persisted = observer.getNode(REFERENCE);
        assertEquals(fixture.cardinality() == Cardinality.MULTIPLE ? 2 : 1,
                count(persisted, names.getLast(), SOURCE));
        assertEquals(0, count(persisted, names.getLast(), DESTINATION));
        unchangedAlias(persisted, names, fixture);
        if (fixture.mode() == Mode.WITH_ANCHOR) {
            assertEquals(SOURCE, persisted.getProperty("anchor").getString());
        }
    }

    private static void unchangedAlias(Node node, List<String> names, Case fixture)
            throws RepositoryException {
        assertEquals(1, count(node, names.getFirst(), OTHER));
        assertEquals(0, count(node, names.getFirst(), SOURCE));
        assertEquals(0, count(node, names.getFirst(), DESTINATION));
        final Property matching = node.getProperty(names.getLast());
        assertEquals(fixture.kind().code, matching.getType());
        assertEquals(fixture.cardinality() == Cardinality.MULTIPLE, matching.isMultiple());
        if (fixture.cardinality() == Cardinality.MULTIPLE) {
            assertEquals(1, count(node, names.getLast(), OTHER));
        }
    }

    private List<String> plant(Case fixture) throws RepositoryException, PersistenceException {
        final Session session = nativeSession(sling.resourceResolver());
        final String prefix = fixture.target() == Target.PAGE ? "cq" : "dam";
        final String name = fixture.target() == Target.PAGE ? "cq:Page" : "dam:Asset";
        final var namespaces = session.getWorkspace().getNamespaceRegistry();
        if (!List.of(namespaces.getPrefixes()).contains(prefix)) {
            namespaces.registerNamespace(prefix, "https://synthetic.invalid/" + prefix);
        }
        final var manager = session.getWorkspace().getNodeTypeManager();
        if (!manager.hasNodeType(name)) {
            final var type = manager.createNodeTypeTemplate();
            type.setName(name);
            type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
            manager.registerNodeType(type, false);
        }
        sling.create().resource(SOURCE, Map.of("jcr:primaryType", name));
        sling.create().resource("/content/synthetic-alias-destination");
        sling.create().resource(REFERENCE);
        final Node reference = session.getNode(REFERENCE);
        put(reference, fixture.alias().decoded, List.of(OTHER), fixture);
        put(reference, fixture.alias().encoded, List.of(OTHER), fixture);
        if (fixture.mode() == Mode.WITH_ANCHOR) {
            reference.setProperty("anchor", SOURCE);
        }
        sling.resourceResolver().commit();
        final List<String> names = Stream.iterate(reference.getProperties(),
                        PropertyIterator::hasNext, iterator -> iterator).map(PropertyIterator::nextProperty)
                .map(NativeReferenceAliasTest::propertyName)
                .filter(held -> List.of(fixture.alias().decoded, fixture.alias().encoded).contains(held))
                .toList();
        assertEquals(2, names.size());
        put(reference, names.getLast(), fixture.cardinality() == Cardinality.MULTIPLE
                ? List.of(SOURCE, OTHER, SOURCE) : List.of(SOURCE), fixture);
        sling.resourceResolver().commit();
        return names;
    }

    private static void put(Node node, String name, List<String> values, Case fixture)
            throws RepositoryException {
        if (fixture.cardinality() == Cardinality.MULTIPLE) {
            node.setProperty(name, values.toArray(String[]::new), fixture.kind().code);
            return;
        }
        node.setProperty(name, values.getFirst(), fixture.kind().code);
    }

    private static String propertyName(Property property) {
        try {
            return property.getName();
        } catch (final RepositoryException unreadable) {
            throw new AssertionError("synthetic property metadata refused", unreadable);
        }
    }

    private static void denyReplacement(Node node, String name, Case fixture)
            throws RepositoryException, ReflectiveOperationException {
        final var manager = node.getSession().getWorkspace().getNodeTypeManager();
        final var type = manager.createNodeTypeTemplate();
        type.setName("syntheticAliasReference");
        type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
        final var definition = manager.createPropertyDefinitionTemplate();
        definition.setName(name);
        definition.setRequiredType(fixture.kind().code);
        definition.setMultiple(fixture.cardinality() == Cardinality.MULTIPLE);
        definition.setValueConstraints(new String[]{SOURCE, OTHER});
        List.class.getMethod("add", Object.class).invoke(type.getPropertyDefinitionTemplates(), definition);
        manager.registerNodeType(type, false);
        node.setPrimaryType(type.getName());
        final Property property = node.getProperty(name);
        assertEquals(type.getName(), property.getDefinition().getDeclaringNodeType().getName());
        final var factory = node.getSession().getValueFactory();
        if (fixture.cardinality() == Cardinality.MULTIPLE) {
            assertTrue(property.getDefinition().getDeclaringNodeType()
                    .canSetProperty(name, property.getValues()));
            assertFalse(property.getDefinition().getDeclaringNodeType().canSetProperty(name,
                    new javax.jcr.Value[]{factory.createValue(DESTINATION, fixture.kind().code),
                            factory.createValue(OTHER, fixture.kind().code),
                            factory.createValue(DESTINATION, fixture.kind().code)}));
            return;
        }
        assertTrue(property.getDefinition().getDeclaringNodeType().canSetProperty(name, property.getValue()));
        assertFalse(property.getDefinition().getDeclaringNodeType()
                .canSetProperty(name, factory.createValue(DESTINATION, fixture.kind().code)));
    }

    private static long count(Node node, String name, String address) throws RepositoryException {
        final Property property = node.getProperty(name);
        if (!property.isMultiple()) {
            return property.getValue().getString().equals(address) ? 1 : 0;
        }
        long matches = 0;
        for (final var value : property.getValues()) {
            if (value.getString().equals(address)) {
                matches++;
            }
        }
        return matches;
    }

    private static Session nativeSession(ResourceResolver owner) {
        final Session session = Objects.requireNonNull(owner.adaptTo(Session.class));
        assertTrue(session.isLive());
        assertTrue(session.getClass().getName().startsWith("org.apache.jackrabbit.oak."));
        return session;
    }

    private static CommandHandler.Answer move(Case fixture, ResourceResolver caller) {
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(MoveRequest.SOURCE_PATH, new DocumentValue.Text(SOURCE));
        members.put(MoveRequest.DESTINATION_PATH, new DocumentValue.Text(DESTINATION));
        members.put(MoveRequest.ADJUST_REFERENCES, new DocumentValue.Flag(DocumentValue.Truth.TRUE));
        final CommandHandler handler = fixture.target() == Target.PAGE ? new MovePageHandler(CONTRACT)
                : new AssetMutationHandler(CONTRACT, AssetMutationHandler.Kind.MOVE);
        final var operation = ((AgentOperationIdentifier.Held) AgentOperationIdentifier.of(
                "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8", CONTRACT))
                .identifier();
        final var context = new CallerContext(operation, Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                new Budget(Budget.Kind.RESULT,
                        CONTRACT.value(ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES)),
                ProgressSink.under(CONTRACT));
        return handler.run(new DocumentValue.Mapping(members), caller, context);
    }

    private static Stream<Case> cases() {
        return Stream.of(Target.values()).flatMap(NativeReferenceAliasTest::cases);
    }

    private static Stream<Case> cases(Target target) {
        return Stream.of(Mode.values()).flatMap(mode -> cases(target, mode));
    }

    private static Stream<Case> cases(Target target, Mode mode) {
        return Stream.of(Kind.values()).flatMap(kind -> cases(target, mode, kind));
    }

    private static Stream<Case> cases(Target target, Mode mode, Kind kind) {
        return Stream.of(Cardinality.values()).flatMap(cardinality -> Stream.of(Alias.values())
                .map(alias -> new Case(target, mode, kind, cardinality, alias)));
    }

    private static final class Caller extends ResourceResolverWrapper {
        private final Session nativeSession;
        private final Session counted;
        private int moves;
        private int commits;

        Caller(ResourceResolver owner) {
            super(owner);
            nativeSession = nativeSession(owner);
            counted = Session.class.cast(Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{Session.class}, (proxy, method, arguments) -> {
                        if ("move".equals(method.getName())) {
                            moves++;
                        }
                        try {
                            return method.invoke(nativeSession, arguments);
                        } catch (final InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }));
        }

        @Override
        public <Adapter> Adapter adaptTo(Class<Adapter> type) {
            return type == Session.class ? type.cast(counted) : super.adaptTo(type);
        }

        @Override
        public void commit() throws PersistenceException {
            commits++;
            super.commit();
        }
    }
}
