// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Optional;
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
     */
    public static FenceOutcome take(Session session, OperationIdentity identity, String worker,
                                    long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final StatePath path = pathOf(identity);
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
        final Optional<FenceHolder> holder = holderIn(session, path);
        if (holder.filter(held -> held.liveAt(nowUnixMilliseconds)).isPresent()) {
            return new FenceOutcome.Refused(holder.orElseThrow());
        }
        final long until = nowUnixMilliseconds
                + contract.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        final long expected = holder.map(FenceHolder::heldUntilUnixMilliseconds).orElse(0L);
        return written(session, path, worker, expected, until);
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
     */
    public static FenceOutcome renew(Session session, OperationIdentity identity,
                                     FenceHolder holder, long nowUnixMilliseconds,
                                     AgentContract contract) throws RepositoryException {
        final StatePath path = pathOf(identity);
        final Optional<FenceHolder> current = holderIn(session, path);
        if (current.isEmpty() || !current.orElseThrow().worker().equals(holder.worker())) {
            return new FenceOutcome.Lost(current.map(FenceHolder::worker).orElse("nobody")
                    + " holds this fence now, and this worker's own hold is gone");
        }
        if (current.orElseThrow().heldUntilUnixMilliseconds()
                != holder.heldUntilUnixMilliseconds()) {
            return new FenceOutcome.Lost("this fence was taken and given back before this renewal,"
                    + " so what this worker holds is not what the store holds");
        }
        final long until = nowUnixMilliseconds
                + contract.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        return written(session, path, holder.worker(), holder.heldUntilUnixMilliseconds(), until);
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
        final Optional<FenceHolder> current = holderIn(session, pathOf(identity));
        return current.filter(held -> held.worker().equals(holder.worker()))
                .filter(held -> held.heldUntilUnixMilliseconds()
                        == holder.heldUntilUnixMilliseconds())
                .filter(held -> held.liveAt(nowUnixMilliseconds))
                .isPresent();
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

    private static FenceOutcome written(Session session, StatePath path, String worker,
                                        long expected, long until) throws RepositoryException {
        final WriteOutcome outcome = CompareAndSet.set(session, path, HELD_UNTIL, expected, until);
        if (outcome == WriteOutcome.VALUE_CHANGED) {
            return new FenceOutcome.Lost("somebody else took this fence while it was being taken");
        }
        if (outcome != WriteOutcome.WRITTEN) {
            return new FenceOutcome.Contended("the store was busy and nothing was decided: "
                    + outcome);
        }
        session.getNode(path.path()).setProperty(WORKER, worker);
        session.save();
        return new FenceOutcome.Held(new FenceHolder(worker, until));
    }

    private static Optional<FenceHolder> holderIn(Session session, StatePath path)
            throws RepositoryException {
        session.refresh(false);
        if (!session.nodeExists(path.path())) {
            return Optional.empty();
        }
        final Node node = session.getNode(path.path());
        if (!node.hasProperty(WORKER)) {
            return Optional.empty();
        }
        return Optional.of(new FenceHolder(node.getProperty(WORKER).getString(),
                CompareAndSet.held(node, HELD_UNTIL)));
    }
}
