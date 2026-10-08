// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
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
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
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

/** Native values that change after admission still obey the original adjustment ceiling. */
@ExtendWith(SlingContextExtension.class)
final class ReferenceAdjustmentRaceTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private static final int BOUND = Math.toIntExact(
            CONTRACT.value(ContractLimit.MAXIMUM_ADJUSTED_REFERENCES));
    private static final String SOURCE = "/content/synthetic-race-source/item";
    private static final String DESTINATION = "/content/synthetic-race-destination/item";
    private static final String REFERENCE = "/content/synthetic-race-reference";
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private enum Target {
        PAGE, ASSET
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void anArrayAppearingAfterAdmissionCannotCommitMoreThanTheOriginalBound(Target target)
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        plant(target);
        assertEquals(100000, BOUND);
        try (var writerOwner = sling.resourceResolver().clone(Map.of())) {
            final Session writer = nativeSession(writerOwner);
            assertAll(() -> {
                try (var caller = new RaceCaller(sling.resourceResolver().clone(Map.of()),
                        writer, BOUND + 1)) {
                    final var failure = assertInstanceOf(CommandHandler.Failed.class, move(target, caller));
                    assertEquals(MovePageHandler.ADJUSTMENT_BUDGET_EXCEEDED, failure.category());
                    assertEquals(1, caller.moves.get(), "the interleaving occurs at the native move");
                    assertEquals(1, caller.foreignCommits.get());
                    assertEquals(0, caller.commits.get(), "the changed reference count was committed");
                }
            }, () -> persisted(writer, false, BOUND + 1));
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void anArrayAppearingAfterAdmissionMayStillCommitExactlyTheOriginalBound(Target target)
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        plant(target);
        try (var writerOwner = sling.resourceResolver().clone(Map.of())) {
            final Session writer = nativeSession(writerOwner);
            try (var caller = new RaceCaller(sling.resourceResolver().clone(Map.of()), writer, BOUND - 1)) {
                final var answer = assertInstanceOf(CommandHandler.Produced.class, move(target, caller));
                assertEquals(new DocumentValue.Whole(BOUND), answer.result()
                        .member("adjusted_reference_count").orElseThrow());
                assertEquals(1, caller.moves.get());
                assertEquals(1, caller.foreignCommits.get());
                assertEquals(1, caller.commits.get());
                assertFalse(caller.nativeSession.hasPendingChanges());
            }
            persisted(writer, true, BOUND - 1);
        }
    }

    private static void persisted(Session writer, boolean moved, int arraySize) throws RepositoryException {
        writer.refresh(false);
        assertEquals(!moved, writer.nodeExists(SOURCE));
        assertEquals(moved, writer.nodeExists(DESTINATION));
        final Node reference = writer.getNode(REFERENCE);
        final String expected = moved ? DESTINATION : SOURCE;
        assertEquals(expected, reference.getProperty("link").getString());
        final var values = reference.getProperty("later").getValues();
        assertEquals(arraySize, values.length);
        assertTrue(Stream.of(values).allMatch(value -> {
            try {
                return value.getString().equals(expected);
            } catch (final RepositoryException failed) {
                throw new IllegalStateException(failed);
            }
        }), "persisted array values do not describe the one accepted outcome");
    }

    private void plant(Target target) throws RepositoryException {
        final Session session = nativeSession(sling.resourceResolver());
        final String prefix = target == Target.PAGE ? "cq" : "dam";
        final String name = target == Target.PAGE ? "cq:Page" : "dam:Asset";
        final var namespaces = session.getWorkspace().getNamespaceRegistry();
        if (!List.of(namespaces.getPrefixes()).contains(prefix)) {
            namespaces.registerNamespace(prefix, "https://synthetic.invalid/" + prefix);
        }
        final var manager = session.getWorkspace().getNodeTypeManager();
        if (!manager.hasNodeType(name)) {
            final NodeTypeTemplate type = manager.createNodeTypeTemplate();
            type.setName(name);
            type.setDeclaredSuperTypeNames(new String[]{"nt:unstructured"});
            manager.registerNodeType(type, false);
        }
        sling.create().resource(SOURCE, Map.of("jcr:primaryType", name));
        sling.create().resource("/content/synthetic-race-destination");
        sling.create().resource(REFERENCE, Map.of("link", SOURCE));
        session.save();
    }

    private static Session nativeSession(ResourceResolver resolver) {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class));
        assertTrue(session.getClass().getName().startsWith("org.apache.jackrabbit.oak."));
        assertTrue(session.isLive());
        return session;
    }

    private static CommandHandler.Answer move(Target target, ResourceResolver caller) {
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(MoveRequest.SOURCE_PATH, new DocumentValue.Text(SOURCE));
        members.put(MoveRequest.DESTINATION_PATH, new DocumentValue.Text(DESTINATION));
        members.put(MoveRequest.ADJUST_REFERENCES, new DocumentValue.Flag(DocumentValue.Truth.TRUE));
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

    private static final class RaceCaller extends ResourceResolverWrapper {
        private final Session nativeSession;
        private final Session countedSession;
        private final AtomicInteger moves = new AtomicInteger();
        private final AtomicInteger foreignCommits = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

        private RaceCaller(ResourceResolver resolver, Session writer, int arraySize) {
            super(resolver);
            nativeSession = nativeSession(resolver);
            assertNotSame(writer, nativeSession);
            countedSession = Session.class.cast(Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[]{Session.class},
                    (held, method, arguments) -> {
                        if ("move".equals(method.getName())) {
                            writer.refresh(false);
                            final String[] references = new String[arraySize];
                            Arrays.fill(references, SOURCE);
                            writer.getNode(REFERENCE).setProperty("later", references);
                            writer.save();
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
