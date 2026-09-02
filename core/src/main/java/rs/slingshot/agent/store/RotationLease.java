// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The claim one node holds while it rotates the continuation key ring.
 *
 * <p>Two nodes deciding to rotate at the same moment must produce one rotation and one refusal
 * rather than two keys: a second rotation inside one retention window strands every token issued
 * under the key that falls off the end. So rotating is something a node holds a lease for, and the
 * lease is taken by compare-and-set against what the holder read.</p>
 *
 * <p>A lease expires rather than being released, because a holder that stopped cannot release
 * anything and a rotation that could never happen again is worse than one that happens late.</p>
 */
public final class RotationLease {

    /** The property the current holder's own name is written in. */
    public static final String HOLDER = "rotation_holder";

    /** The property the instant the lease stops being held is written in. */
    public static final String HELD_UNTIL = "rotation_held_until";

    private RotationLease() {
    }

    /** Why a node does not hold the lease. */
    public enum Refusal {
        /** Somebody else holds it, and it has not expired. */
        HELD_BY_ANOTHER,
        /** The record changed while this node was taking it. */
        CONTENDED
    }

    /** The result of taking one: the lease, or the one reason there is none. */
    public sealed interface Outcome permits Taken, Refused {
    }

    /**
     * A lease this node holds until it expires.
     *
     * @param holder who holds it
     * @param heldUntilUnixMilliseconds when it stops being held
     */
    public record Taken(String holder, long heldUntilUnixMilliseconds) implements Outcome {
    }

    /**
     * A lease this node does not hold.
     *
     * @param refusal why it does not
     * @param detail what was observed, naming the holder and the instant
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Takes the lease, if nobody holds it.
     *
     * @param session the session to write under
     * @param path the node the lease sits on
     * @param holder this node's own name
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares how long a lease is held
     * @return the lease, or the one reason this node does not hold it
     * @throws RepositoryException if the repository fails
     */
    public static Outcome take(Session session, StatePath path, String holder,
                               long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        session.refresh(false);
        final Node node = session.getNode(path.path());
        final long heldUntil = CompareAndSet.held(node, HELD_UNTIL);
        if (nowUnixMilliseconds < heldUntil) {
            return new Refused(Refusal.HELD_BY_ANOTHER, held(node) + " holds the lease until "
                    + heldUntil + ", and it is " + nowUnixMilliseconds);
        }
        final long until = nowUnixMilliseconds
                + contract.value(ContractLimit.CONTINUATION_KEY_ROTATION_LEASE_MILLISECONDS);
        final WriteOutcome outcome = CompareAndSet.set(session, path, HELD_UNTIL, heldUntil, until);
        if (outcome != WriteOutcome.WRITTEN) {
            return new Refused(Refusal.CONTENDED,
                    "the lease was taken by somebody else while this node was taking it: "
                            + outcome);
        }
        session.getNode(path.path()).setProperty(HOLDER, holder);
        session.save();
        return new Taken(holder, until);
    }

    /**
     * Whether one node still holds the lease.
     *
     * @param session the session to read under
     * @param path the node the lease sits on
     * @param holder the node asking
     * @param nowUnixMilliseconds what this side's clock says
     * @return whether that node holds it
     * @throws RepositoryException if the repository fails
     */
    public static boolean holds(Session session, StatePath path, String holder,
                                long nowUnixMilliseconds) throws RepositoryException {
        session.refresh(false);
        final Node node = session.getNode(path.path());
        return holder.equals(held(node))
                && nowUnixMilliseconds < CompareAndSet.held(node, HELD_UNTIL);
    }

    private static String held(Node node) throws RepositoryException {
        return node.hasProperty(HOLDER) ? node.getProperty(HOLDER).getString() : "nobody";
    }
}
