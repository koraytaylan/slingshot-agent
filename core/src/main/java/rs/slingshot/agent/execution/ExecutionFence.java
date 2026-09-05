// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Optional;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.store.ClaimByCreation;
import rs.slingshot.agent.store.CompareAndSet;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/**
 * At most one worker executing one operation at any instant, and "at most one" that survives a node
 * disappearing mid-execution.
 *
 * <p>A worker that loses the fence stops without finishing, because finishing would be the second
 * effect this whole design exists to prevent. Nothing here waits for a worker to say it has gone: a
 * worker that stopped cannot say anything, so the hold runs out on its own and another worker takes
 * it by comparing against the exact expired record it read.</p>
 *
 * <p>The renewal interval leaves room for missed renewals before a takeover, so an ordinary pause —
 * a long commit, a garbage collection, a slow platform call — is not read as a handover.</p>
 */
public final class ExecutionFence {

    /** The node an operation's fence sits on. */
    public static final String NODE = "lease";

    /** The property the holding worker is written in. */
    public static final String WORKER = "worker";

    /** The property the instant the hold runs out is written in. */
    public static final String HELD_UNTIL = "held_until_unix_milliseconds";

    /** The unique acquisition epoch; renewal preserves it and takeover replaces it. */
    public static final String EPOCH = "acquisition_epoch";

    private ExecutionFence() {
    }

    /**
     * Takes the fence for one worker, if nobody else is holding it.
     *
     * @param session the session to write under
     * @param identity which operation
     * @param worker the worker asking
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the lease
     * @return the fence, or the one reason this worker does not hold it
     * @throws RepositoryException if the repository fails
     * @throws IllegalArgumentException if the worker name is empty
     * @throws ArithmeticException if the lease expiry cannot be represented
     */
    public static FenceOutcome take(Session session, OperationIdentity identity, String worker,
                                    long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        if (worker.isEmpty()) {
            throw new IllegalArgumentException("an execution fence requires a worker name");
        }
        final StatePath path = pathOf(identity);
        if (prepare(session, path) == WriteOutcome.CONTENDED) {
            return new FenceOutcome.Contended("fence creation was CONTENDED");
        }
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            final FenceOutcome outcome = acquire(session, path, worker, nowUnixMilliseconds, contract);
            if (!(outcome instanceof FenceOutcome.Contended)) {
                return outcome;
            }
            attempt = attempt + 1;
        }
        return new FenceOutcome.Contended("fence acquisition remained CONTENDED");
    }

    private static WriteOutcome prepare(Session session, StatePath path) throws RepositoryException {
        session.refresh(false);
        try {
            return ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
        } finally {
            session.refresh(false);
        }
    }

    private static FenceOutcome acquire(Session session, StatePath path, String worker, long now,
                                         AgentContract contract) throws RepositoryException {
        session.refresh(false);
        try {
            final Node node = session.getNode(path.path());
            CompareAndSet.stamp(node);
            final Optional<FenceHolder> current = holderIn(node);
            if (current.filter(held -> held.liveAt(now)).isPresent()) {
                return new FenceOutcome.Refused(current.orElseThrow());
            }
            final FenceHolder next = new FenceHolder(worker,
                    Math.addExact(now, contract.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS)),
                    java.util.UUID.randomUUID().toString());
            write(node, next);
            session.save();
            return new FenceOutcome.Held(next);
        } catch (final InvalidItemStateException contended) {
            return new FenceOutcome.Contended("fence acquisition was CONTENDED");
        } finally {
            session.refresh(false);
        }
    }

    /**
     * Keeps the fence for the worker that holds it.
     *
     * @param session the session to write under
     * @param identity which operation
     * @param holder what this worker believes it holds
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the lease
     * @return the renewed fence, or the one reason this worker no longer holds it
     * @throws RepositoryException if the repository fails
     * @throws ArithmeticException if the renewed expiry cannot be represented
     */
    public static FenceOutcome renew(Session session, OperationIdentity identity,
                                     FenceHolder holder, long nowUnixMilliseconds,
                                     AgentContract contract) throws RepositoryException {
        int attempt = 0;
        while (attempt < CompareAndSet.ATTEMPTS) {
            final FenceOutcome outcome = renewing(session, pathOf(identity), holder, nowUnixMilliseconds,
                    contract);
            if (!(outcome instanceof FenceOutcome.Contended)) {
                return outcome;
            }
            attempt = attempt + 1;
        }
        return new FenceOutcome.Contended("fence renewal remained CONTENDED");
    }

    private static FenceOutcome renewing(Session session, StatePath path, FenceHolder expected, long now,
                                          AgentContract contract) throws RepositoryException {
        session.refresh(false);
        try {
            if (!session.nodeExists(path.path())) {
                return new FenceOutcome.Lost("the worker's fence is gone");
            }
            final Node node = session.getNode(path.path());
            CompareAndSet.stamp(node);
            if (expected.epoch().isEmpty() || !expected.liveAt(now)
                    || !holderIn(node).filter(expected::equals).isPresent()) {
                return new FenceOutcome.Lost("this epoch is not what the store holds, or its hold is gone");
            }
            final long until = Math.max(expected.heldUntilUnixMilliseconds(),
                    Math.addExact(now, contract.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS)));
            final FenceHolder next = new FenceHolder(expected.worker(), until, expected.epoch());
            write(node, next);
            session.save();
            return new FenceOutcome.Held(next);
        } catch (final InvalidItemStateException contended) {
            return new FenceOutcome.Contended("fence renewal was CONTENDED");
        } finally {
            session.refresh(false);
        }
    }

    /**
     * Whether one worker still holds the fence.
     *
     * @param session the session to read under
     * @param identity which operation
     * @param holder what this worker believes it holds
     * @param nowUnixMilliseconds what this side's clock says
     * @return whether this worker may still write anything at all
     * @throws RepositoryException if the repository fails
     */
    public static boolean stillHeld(Session session, OperationIdentity identity,
                                    FenceHolder holder, long nowUnixMilliseconds)
            throws RepositoryException {
        session.refresh(false);
        final StatePath path = pathOf(identity);
        return !holder.epoch().isEmpty() && session.nodeExists(path.path())
                && holderIn(session.getNode(path.path())).filter(holder::equals)
                        .filter(held -> held.liveAt(nowUnixMilliseconds)).isPresent();
    }

    /**
     * Fences a pending protected transition against the persisted acquisition epoch.
     *
     * <p>This neither saves nor refreshes. The caller must commit its protected writes with this
     * stamp, or discard the whole transaction when authority is absent or persistence fails.</p>
     *
     * @param session the enclosing write transaction
     * @param identity the protected operation
     * @param holder the authority presented by the worker
     * @param nowUnixMilliseconds the instant at which authority is required
     * @return whether the complete live holder matches durable authority
     * @throws RepositoryException if authority cannot be read or fenced
     */
    public static boolean stageHeld(Session session, OperationIdentity identity, FenceHolder holder,
                                     long nowUnixMilliseconds) throws RepositoryException {
        final StatePath path = pathOf(identity);
        if (holder.epoch().isEmpty() || !session.nodeExists(path.path())) {
            return false;
        }
        final Node node = session.getNode(path.path());
        CompareAndSet.stamp(node);
        return holderIn(node).filter(holder::equals)
                .filter(held -> held.liveAt(nowUnixMilliseconds)).isPresent();
    }

    /**
     * How long a worker may go without renewing before another worker may take the fence, counted
     * in renewals.
     *
     * <p>Read from the contract rather than written here: an ordinary pause is not a handover, and
     * how much of one is ordinary is a property of the deployment rather than of this class.</p>
     *
     * @param contract the authenticated contract
     * @return how many renewals a worker may miss before its hold runs out
     */
    public static long missedRenewalsBeforeTakeover(AgentContract contract) {
        return contract.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS)
                / contract.value(ContractLimit.WORKER_EXECUTION_LEASE_RENEWAL_MILLISECONDS);
    }

    /**
     * Where one operation's fence sits.
     *
     * @param identity which operation
     * @return the path
     */
    public static StatePath pathOf(OperationIdentity identity) {
        return OperationStore.pathOf(identity).child(NODE);
    }

    private static void write(Node node, FenceHolder holder) throws RepositoryException {
        node.setProperty(WORKER, holder.worker());
        node.setProperty(HELD_UNTIL, holder.heldUntilUnixMilliseconds());
        node.setProperty(EPOCH, holder.epoch());
    }

    private static Optional<FenceHolder> holderIn(Node node) throws RepositoryException {
        if (!node.hasProperty(WORKER) || !node.hasProperty(HELD_UNTIL)
                || node.getProperty(WORKER).getString().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FenceHolder(node.getProperty(WORKER).getString(),
                node.getProperty(HELD_UNTIL).getLong(),
                node.hasProperty(EPOCH) ? node.getProperty(EPOCH).getString() : ""));
    }
}
