// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.UUID;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority.Lease;
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

    /** The unique identity of this acquisition, preserved by renewal. */
    public static final String EPOCH = "rotation_epoch";

    private RotationLease() {
    }

    /** Why a node does not hold the lease. */
    public enum Refusal {
        /** Somebody else holds it, and it has not expired. */
        HELD_BY_ANOTHER,
        /** The record changed while this node was taking it. */
        CONTENDED,
        /** The supplied acquisition no longer matches the persisted lease. */
        NOT_HELD
    }

    /** The result of taking one: the lease, or the one reason there is none. */
    public sealed interface Outcome permits Taken, Refused {
    }

    /**
     * A lease this node holds until it expires.
     *
     * @param holder who holds it
     * @param heldUntilUnixMilliseconds when it stops being held
     * @param epoch the unique persisted acquisition identity
     */
    public record Taken(String holder, long heldUntilUnixMilliseconds, String epoch) implements Outcome {

        /**
         * The authority to present with a key write.
         *
         * @return the exact persisted lease
         */
        public Lease lease() {
            return new Lease(holder, heldUntilUnixMilliseconds, epoch);
        }
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
     * @throws IllegalArgumentException if the holder is empty
     * @throws ArithmeticException if the expiry cannot be represented
     */
    public static Outcome take(Session session, StatePath path, String holder,
                               long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        if (holder.isEmpty()) {
            throw new IllegalArgumentException("a rotation lease requires a holder name");
        }
        session.refresh(false);
        try {
            final Node node = session.getNode(path.path());
            CompareAndSet.stamp(node);
            final long heldUntil = CompareAndSet.held(node, HELD_UNTIL);
            if (nowUnixMilliseconds < heldUntil) {
                return new Refused(Refusal.HELD_BY_ANOTHER, held(node) + " holds the lease until "
                        + heldUntil + ", and it is " + nowUnixMilliseconds);
            }
            final long until = Math.addExact(nowUnixMilliseconds,
                    contract.value(ContractLimit.CONTINUATION_KEY_ROTATION_LEASE_MILLISECONDS));
            final Taken next = new Taken(holder, until, UUID.randomUUID().toString());
            write(node, next);
            session.save();
            return next;
        } catch (final InvalidItemStateException contended) {
            return new Refused(Refusal.CONTENDED, "another transition committed during acquisition");
        } finally {
            session.refresh(false);
        }
    }

    /**
     * Renews the exact live acquisition without changing its epoch.
     *
     * @param session the session to write under
     * @param path the node the lease sits on
     * @param expected the lease the caller holds
     * @param nowUnixMilliseconds the instant requiring ownership
     * @param contract the authenticated lease duration
     * @return the renewed lease or a refusal
     * @throws RepositoryException if the repository fails
     * @throws ArithmeticException if the expiry cannot be represented
     */
    public static Outcome renew(Session session, StatePath path, Lease expected,
                                 long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        session.refresh(false);
        try {
            final Node node = session.getNode(path.path());
            CompareAndSet.stamp(node);
            if (!matches(node, expected, nowUnixMilliseconds)) {
                return new Refused(Refusal.NOT_HELD,
                        "the persisted lease no longer matches this acquisition");
            }
            final long until = Math.max(expected.expiresAtUnixMilliseconds(),
                    Math.addExact(nowUnixMilliseconds,
                            contract.value(ContractLimit.CONTINUATION_KEY_ROTATION_LEASE_MILLISECONDS)));
            final Taken next = new Taken(expected.holder(), until, expected.epoch());
            write(node, next);
            session.save();
            return next;
        } catch (final InvalidItemStateException contended) {
            return new Refused(Refusal.CONTENDED, "another transition committed during renewal");
        } finally {
            session.refresh(false);
        }
    }

    /**
     * Whether the exact acquisition is still held.
     *
     * @param session the session to read under
     * @param path the node the lease sits on
     * @param lease the acquisition asking
     * @param nowUnixMilliseconds the instant requiring ownership
     * @return whether the complete live lease matches
     * @throws RepositoryException if the repository fails
     */
    public static boolean holds(Session session, StatePath path, Lease lease,
                                long nowUnixMilliseconds) throws RepositoryException {
        session.refresh(false);
        return session.nodeExists(path.path())
                && matches(session.getNode(path.path()), lease, nowUnixMilliseconds);
    }

    /**
     * Checks ownership inside an already stamped transaction without refreshing or saving.
     *
     * @param node the stamped node containing the lease and key ring
     * @param lease the acquisition presented
     * @param nowUnixMilliseconds the instant requiring ownership
     * @return whether the complete live lease matches
     * @throws RepositoryException if persisted authority cannot be read
     */
    static boolean matches(Node node, Lease lease, long nowUnixMilliseconds) throws RepositoryException {
        if (!node.hasProperty(HOLDER) || !node.hasProperty(HELD_UNTIL) || !node.hasProperty(EPOCH)) {
            return false;
        }
        return !lease.holder().isEmpty() && !lease.epoch().isEmpty()
                && nowUnixMilliseconds < lease.expiresAtUnixMilliseconds()
                && lease.epoch().equals(node.getProperty(EPOCH).getString())
                && lease.holder().equals(node.getProperty(HOLDER).getString())
                && lease.expiresAtUnixMilliseconds() == node.getProperty(HELD_UNTIL).getLong();
    }

    private static void write(Node node, Taken lease) throws RepositoryException {
        node.setProperty(HOLDER, lease.holder());
        node.setProperty(HELD_UNTIL, lease.heldUntilUnixMilliseconds());
        node.setProperty(EPOCH, lease.epoch());
    }

    private static String held(Node node) throws RepositoryException {
        return node.hasProperty(HOLDER) ? node.getProperty(HOLDER).getString() : "nobody";
    }
}
