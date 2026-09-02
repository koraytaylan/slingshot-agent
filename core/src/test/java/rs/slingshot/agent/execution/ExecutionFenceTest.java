// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.StatePath;

/**
 * At most one worker executing, and what the second one is told.
 *
 * <p>Time is a number this suite states rather than a clock it waits on. A fence whose expiry is
 * proved by sleeping is a fence proved on a machine's timing, and the property is about what the
 * store decides for two workers who each say what time it is.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ExecutionFenceTest {

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private static final long LEASE =
            CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("one worker takes the fence and a second is refused while the first holds it")
    void onlyOneWorkerHoldsAtATime() throws RepositoryException {
        final Session session = withRecord();
        final FenceHolder holder = held(session, "the-first-worker", NOW);
        assertEquals(NOW + LEASE, holder.heldUntilUnixMilliseconds());
        final FenceOutcome.Refused refused = assertInstanceOf(FenceOutcome.Refused.class,
                ExecutionFence.take(session, identity(), "the-second-worker", NOW, CONTRACT),
                "two workers held one fence at once");
        assertEquals("the-first-worker", refused.holder().worker());
        assertTrue(refused.holder().liveAt(NOW));
    }

    @Test
    @DisplayName("a fence nobody is holding any more is taken by the next worker to ask")
    void anexpiredFenceIsTaken() throws RepositoryException {
        final Session session = withRecord();
        final FenceHolder first = held(session, "the-first-worker", NOW);
        final FenceHolder second = held(session, "the-second-worker",
                first.heldUntilUnixMilliseconds());
        assertEquals("the-second-worker", second.worker());
        assertFalse(ExecutionFence.stillHeld(session, identity(), first,
                        first.heldUntilUnixMilliseconds()),
                "a worker whose hold ran out was still told it holds the fence");
    }

    @Test
    @DisplayName("a renewal by the holder keeps it, and one by anybody else is lost rather than"
            + " contended")
    void arenewalKeepsTheFenceOnlyForItsHolder() throws RepositoryException {
        final Session session = withRecord();
        final FenceHolder holder = held(session, "the-first-worker", NOW);
        final FenceHolder renewed = assertInstanceOf(FenceOutcome.Held.class,
                ExecutionFence.renew(session, identity(), holder, NOW + 1000, CONTRACT),
                "the holder could not keep its own fence").holder();
        assertEquals(NOW + 1000 + LEASE, renewed.heldUntilUnixMilliseconds());
        final FenceOutcome.Lost lost = assertInstanceOf(FenceOutcome.Lost.class,
                ExecutionFence.renew(session, identity(), holder, NOW + 2000, CONTRACT),
                "a worker renewed a fence it no longer holds");
        assertTrue(lost.detail().contains("not what the store holds")
                || lost.detail().contains("gone"), lost.detail());
    }

    @Test
    @DisplayName("a worker that lost the fence writes nothing further")
    void alostFenceStopsTheWorker() throws RepositoryException {
        final Session session = withRecord();
        final FenceHolder first = held(session, "the-first-worker", NOW);
        final FenceHolder second = held(session, "the-second-worker",
                first.heldUntilUnixMilliseconds());
        final String before = fenceAsWritten(session);
        assertFalse(ExecutionFence.stillHeld(session, identity(), first, NOW + LEASE + 1),
                "a worker whose fence was taken was told it still holds it");
        assertInstanceOf(FenceOutcome.Lost.class,
                ExecutionFence.renew(session, identity(), first, NOW + LEASE + 1, CONTRACT));
        assertEquals(before, fenceAsWritten(session),
                "a worker that had lost the fence wrote something anyway");
        assertEquals("the-second-worker", second.worker());
    }

    @Test
    @DisplayName("the renewal interval leaves room for missed renewals before a handover")
    void anordinaryPauseIsNotAHandover() {
        assertTrue(ExecutionFence.missedRenewalsBeforeTakeover(CONTRACT) >= 2,
                "a worker that missed one renewal would lose the fence, so an ordinary pause is a"
                        + " handover");
        assertEquals(LEASE / CONTRACT.value(
                        ContractLimit.WORKER_EXECUTION_LEASE_RENEWAL_MILLISECONDS),
                ExecutionFence.missedRenewalsBeforeTakeover(CONTRACT),
                "the margin is written here rather than read from the contract");
    }

    @Test
    @DisplayName("a store too busy to decide says so, rather than saying the fence was lost")
    void abusyStoreIsNotALostFence() throws RepositoryException {
        final Session session = withRecord();
        final FenceHolder holder = held(session, "the-first-worker", NOW);
        final FenceOutcome.Contended contended = assertInstanceOf(FenceOutcome.Contended.class,
                ExecutionFence.renew(losing(session), identity(), holder, NOW + 1000, CONTRACT),
                "a store that decided nothing was read as a handover");
        assertTrue(contended.detail().contains("CONTENDED"), contended.detail());
    }

    /** A session whose every commit is lost to somebody else, so nothing is ever decided. */
    private static Session losing(Session session) {
        final java.lang.reflect.InvocationHandler handler = (proxy, method, arguments) -> {
            if ("save".equals(method.getName())) {
                throw new javax.jcr.InvalidItemStateException("somebody else committed first");
            }
            try {
                return method.invoke(session, arguments);
            } catch (final java.lang.reflect.InvocationTargetException failed) {
                if (failed.getCause() instanceof final RepositoryException repository) {
                    repository.addSuppressed(failed);
                    throw repository;
                }
                throw new IllegalStateException("the session failed", failed);
            }
        };
        return (Session) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                handler);
    }

    @Test
    @DisplayName("two workers racing for an expired fence leave exactly one holder")
    void tworacingWorkersLeaveOneHolder() throws RepositoryException {
        final Session session = withRecord();
        final FenceHolder first = held(session, "the-first-worker", NOW);
        final long after = first.heldUntilUnixMilliseconds();
        final Session other = another();
        final FenceOutcome taken = ExecutionFence.take(session, identity(), "the-second-worker",
                after, CONTRACT);
        final FenceOutcome raced = ExecutionFence.take(other, identity(), "the-third-worker",
                after, CONTRACT);
        assertInstanceOf(FenceOutcome.Held.class, taken, "the first taker did not take it");
        assertFalse(raced instanceof FenceOutcome.Held,
                "two workers took one expired fence: " + raced);
        assertEquals("the-second-worker", assertInstanceOf(FenceOutcome.Held.class, taken)
                .holder().worker());
    }

    private String fenceAsWritten(Session session) throws RepositoryException {
        final Node fence = session.getNode(ExecutionFence.pathOf(identity()).path());
        return fence.getProperty(ExecutionFence.WORKER).getString() + " until "
                + fence.getProperty(ExecutionFence.HELD_UNTIL).getLong();
    }

    private FenceHolder held(Session session, String worker, long now) throws RepositoryException {
        return assertInstanceOf(FenceOutcome.Held.class,
                ExecutionFence.take(session, identity(), worker, now, CONTRACT),
                worker + " could not take the fence").holder();
    }

    private static OperationIdentity identity() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION,
                new DocumentValue.Whole(EventStoreGeneration.FIRST));
        members.put(OperationIdentity.IDENTIFIER,
                new DocumentValue.Text(digest("one operation").rendered()));
        members.put(OperationIdentity.TARGET_DIGEST,
                new DocumentValue.Text(digest("a target").rendered()));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new DocumentValue.Text("revision-2026-09-01"));
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(new DocumentValue.Mapping(members), CONTRACT),
                "the identity was refused").identity();
    }

    private static CommandContractIdentity commandContract() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(CommandContractIdentity.WIRE_NAME, new DocumentValue.Text("query_paths"));
        members.put(CommandContractIdentity.CONTRACT_VERSION, new DocumentValue.Text("1.0.0"));
        members.put(CommandContractIdentity.LIMITS_DIGEST,
                new DocumentValue.Text(digest("limits").rendered()));
        members.put(CommandContractIdentity.ARGUMENT_DIGEST,
                new DocumentValue.Text(digest("arguments").rendered()));
        members.put(CommandContractIdentity.RESULT_DIGEST,
                new DocumentValue.Text(digest("result").rendered()));
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(new DocumentValue.Mapping(members),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private Session withRecord() throws RepositoryException {
        final Session session = prepared();
        OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                LogicalOperation.accepted(identity(), digest("a submission"), commandContract(),
                        caller(), NOW, NOW, CONTRACT)).operation());
        return session;
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-submitting-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        if (!session.nodeExists(StatePath.ROOT)) {
            final Node variable = session.getRootNode().hasNode("var")
                    ? session.getRootNode().getNode("var")
                    : session.getRootNode().addNode("var", "nt:unstructured");
            variable.addNode("slingshot-agent", "nt:unstructured");
            session.save();
        }
        return session;
    }

    private Session another() {
        try {
            final org.apache.sling.api.resource.ResourceResolverFactory factory =
                    java.util.Objects.requireNonNull(sling.getService(
                            org.apache.sling.api.resource.ResourceResolverFactory.class),
                            "the context holds no resolver factory");
            return java.util.Objects.requireNonNull(
                    factory.getResourceResolver(java.util.Map.of()).adaptTo(Session.class),
                    "the second resolver has no session");
        } catch (final org.apache.sling.api.resource.LoginException refused) {
            throw new IllegalStateException("a second session could not be opened", refused);
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
