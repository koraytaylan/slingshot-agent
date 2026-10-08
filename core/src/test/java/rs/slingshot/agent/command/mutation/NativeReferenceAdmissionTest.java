// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

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
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeTypeTemplate;
import javax.jcr.security.Privilege;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** Native metadata and caller permissions must be checked before moving caller content. */
@ExtendWith(SlingContextExtension.class)
final class NativeReferenceAdmissionTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final String SOURCE = "/content/synthetic-native-source/item";
    private static final String DESTINATION = "/content/synthetic-native-destination/item";
    private static final String EARLIER = "/content/synthetic-native-earlier";
    private static final String REFERENCE = "/content/synthetic-native-reference";
    private static final String USER = "synthetic-reference-caller";
    private static final String PASSWORD = "synthetic-reference-password";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private enum Target {
        PAGE, ASSET
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void aProtectedReferenceRefusesBeforeMovementAndEarlierAssignments(Target target)
            throws RepositoryException, LoginException, ReflectiveOperationException {
        plant(target);
        final var manager = session().getWorkspace().getNodeTypeManager();
        final NodeTypeTemplate type = manager.createNodeTypeTemplate();
        type.setName("syntheticProtectedReference");
        type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
        final var property = manager.createPropertyDefinitionTemplate();
        property.setName("link");
        property.setRequiredType(javax.jcr.PropertyType.STRING);
        property.setAutoCreated(true);
        property.setProtected(true);
        property.setDefaultValues(new Value[]{session().getValueFactory().createValue(SOURCE)});
        // JCR exposes this template list without a generic element type. Its public add method
        // avoids unchecked casts or disabling a compiler warning in this native fixture.
        final List<?> definitions = type.getPropertyDefinitionTemplates();
        List.class.getMethod("add", Object.class).invoke(definitions, property);
        manager.registerNodeType(type, false);
        session().getNode("/content").addNode("synthetic-native-reference", type.getName());
        session().save();
        assertTrue(session().getNode(REFERENCE).getProperty("link").getDefinition().isProtected());

        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            assertNotNull(required(caller, REFERENCE).adaptTo(ModifiableValueMap.class));
            refuse(target, caller);
        } finally {
            persistedUntouched();
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void aCallerDeniedReferenceRefusesBeforeMovementAndEarlierAssignments(Target target)
            throws RepositoryException, LoginException {
        plant(target);
        sling.create().resource(REFERENCE, Map.of("link", SOURCE, "links", new String[]{SOURCE, SOURCE}));
        session().save();
        final Session limited = limited();

        try (FixtureCaller caller = new FixtureCaller(callerResolver(limited))) {
            assertTrue(limited.nodeExists(REFERENCE));
            assertFalse(limited.hasPermission(REFERENCE + "/link", Session.ACTION_SET_PROPERTY));
            assertTrue(limited.hasPermission(SOURCE, Session.ACTION_REMOVE));
            assertTrue(limited.hasPermission("/content/synthetic-native-destination",
                    Session.ACTION_ADD_NODE));
            assertNotNull(required(caller, REFERENCE).adaptTo(ModifiableValueMap.class));
            refuse(target, caller);
        } finally {
            limited.logout();
            persistedUntouched();
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void writableNativeReferencesStillMoveAndCommitOnce(Target target)
            throws RepositoryException, LoginException {
        plant(target);
        session().save();

        try (FixtureCaller caller = new FixtureCaller(sling.resourceResolver().clone(Map.of()))) {
            accept(target, caller, true, 3);
            assertEquals(DESTINATION, required(caller, EARLIER).getValueMap().get("link"));
            assertArrayEquals(new String[]{DESTINATION, DESTINATION}, required(caller, EARLIER)
                    .getValueMap().get("links", String[].class));
        }
        session().refresh(false);
        assertFalse(session().nodeExists(SOURCE));
        assertTrue(session().nodeExists(DESTINATION));
        assertEquals(DESTINATION, session().getNode(EARLIER).getProperty("link").getString());
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void explicitlyLeavingReferencesPreservesTheNativeCallerDenialOutsideTheMove(Target target)
            throws RepositoryException, LoginException {
        plant(target);
        sling.create().resource(REFERENCE, Map.of("link", SOURCE));
        session().save();
        final Session limited = limited();

        try (FixtureCaller caller = new FixtureCaller(callerResolver(limited))) {
            assertFalse(limited.hasPermission(REFERENCE + "/link", Session.ACTION_SET_PROPERTY));
            accept(target, caller, false, 0);
        } finally {
            limited.logout();
        }
        session().refresh(false);
        assertFalse(session().nodeExists(SOURCE));
        assertTrue(session().nodeExists(DESTINATION));
        assertEquals(SOURCE, session().getNode(REFERENCE).getProperty("link").getString());
        assertEquals(SOURCE, session().getNode(EARLIER).getProperty("link").getString());
    }

    private void refuse(Target target, FixtureCaller caller) throws RepositoryException {
        final CommandHandler.Answer answer = assertDoesNotThrow(() -> move(target, caller, true),
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

    private static void accept(Target target, FixtureCaller caller, boolean adjust, long count) {
        final CommandHandler.Answer answer = move(target, caller, adjust);
        final CommandHandler.Produced result = assertInstanceOf(CommandHandler.Produced.class,
                answer, "the native control answered " + answer);
        assertEquals(new DocumentValue.Whole(count), result.result()
                .member("adjusted_reference_count").orElseThrow());
        assertEquals(1, caller.moves.get());
        assertEquals(1, caller.commits.get());
    }

    private void persistedUntouched() throws RepositoryException {
        session().refresh(false);
        assertTrue(session().nodeExists(SOURCE));
        assertFalse(session().nodeExists(DESTINATION));
        assertEquals(SOURCE, session().getNode(EARLIER).getProperty("link").getString());
        assertEquals(SOURCE, session().getNode(REFERENCE).getProperty("link").getString());
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
        sling.create().resource("/content/synthetic-native-destination");
        sling.create().resource(EARLIER, Map.of("link", SOURCE, "links", new String[]{SOURCE, SOURCE}));
    }

    private Session limited() throws RepositoryException {
        final var user = ((JackrabbitSession) session()).getUserManager().createUser(USER, PASSWORD);
        final var access = session().getAccessControlManager();
        final var root = (JackrabbitAccessControlList)
                access.getApplicablePolicies("/content").nextAccessControlPolicy();
        root.addEntry(user.getPrincipal(), new Privilege[]{access.privilegeFromName(Privilege.JCR_READ),
                access.privilegeFromName(Privilege.JCR_WRITE),
                access.privilegeFromName(Privilege.JCR_NODE_TYPE_MANAGEMENT)}, true);
        access.setPolicy("/content", root);
        final var reference = (JackrabbitAccessControlList)
                access.getApplicablePolicies(REFERENCE).nextAccessControlPolicy();
        reference.addEntry(user.getPrincipal(), new Privilege[]{
                access.privilegeFromName(Privilege.JCR_MODIFY_PROPERTIES)}, false,
                Map.of("rep:glob", session().getValueFactory().createValue("/link")));
        access.setPolicy(REFERENCE, reference);
        session().save();
        return session().getRepository().login(new javax.jcr.SimpleCredentials(USER, PASSWORD.toCharArray()));
    }

    private ResourceResolver callerResolver(Session limited)
            throws org.apache.sling.api.resource.LoginException {
        return Objects.requireNonNull(sling.getService(ResourceResolverFactory.class))
                .getResourceResolver(Map.of("user.jcr.session", limited));
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private static Resource required(ResourceResolver caller, String path) {
        return Objects.requireNonNull(caller.getResource(path), "the native synthetic fixture resource");
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
