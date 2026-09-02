// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The continuation key ring, kept in the repository where every node of a deployment can see it.
 *
 * <p>Plan 0002 said every deployment implements all of the authority and none may implement it more
 * cheaply. This is where that promise either holds or quietly stops holding, because a
 * repository-backed ring is exactly where somebody would be tempted to notice that there is only
 * one node today. Nothing here observes node count, clustering, or which deployment it is: every
 * write is a compare-and-set against what the caller read, and a rotation is held by a lease.</p>
 *
 * <p>Key material comes from the platform's own cryptographically secure source. If that source is
 * unavailable this refuses to start rather than falling back to a seeded or time-derived one — a
 * key somebody can predict is a token anybody can forge, and a fallback is how that happens without
 * anybody deciding it should.</p>
 */
public final class DefaultContinuationKeyAuthority implements ContinuationKeyAuthority {

    /** How many bytes of key material a key carries. */
    public static final int KEY_BYTES = 32;

    /** The property the current key is written in. */
    public static final String CURRENT = "current";

    /** The property the retained prior key is written in. */
    public static final String PRIOR = "prior";

    /** The property the instant the prior key stops being accepted is written in. */
    public static final String PRIOR_UNTIL = "prior_until";

    private final Session session;
    private final AgentContract contract;
    private final SecureRandom source;

    private DefaultContinuationKeyAuthority(Session session, AgentContract contract, SecureRandom source) {
        this.session = session;
        this.contract = contract;
        this.source = source;
    }

    /** The result of opening one: the authority, or the one reason there is none. */
    public sealed interface Opening permits Opened, NotOpened {
    }

    /**
     * An authority this deployment can use.
     *
     * @param authority the authority
     */
    public record Opened(DefaultContinuationKeyAuthority authority) implements Opening {
    }

    /**
     * No authority, because something it needs is not there.
     *
     * @param detail what is missing
     */
    public record NotOpened(String detail) implements Opening {
    }

    /**
     * Opens the authority against a session and the platform's secure source.
     *
     * @param session the session the ring is read and written under, which is the service user's
     * @param contract the authenticated contract, which declares every bound
     * @return the authority, or the one reason there is none
     */
    public static Opening open(Session session, AgentContract contract) {
        return open(session, contract, SecureRandom::getInstanceStrong);
    }

    /**
     * Where the secure source comes from.
     *
     * <p>Package-private and never widened: a source a caller could supply is a fallback, and a
     * fallback is how key material stops being unpredictable without anybody deciding that it
     * should. What it exists for is a suite proving that an unavailable source refuses to start.
     * </p>
     */
    @FunctionalInterface
    interface StrongSource {

        /**
         * The platform's own cryptographically secure source.
         *
         * @return the source
         * @throws NoSuchAlgorithmException if this runtime has none
         */
        SecureRandom get() throws NoSuchAlgorithmException;
    }

    /**
     * Opens the authority against a source a suite states, which nothing else may.
     *
     * @param session the session the ring is read and written under
     * @param contract the authenticated contract, which declares every bound
     * @param source where the secure source comes from
     * @return the authority, or the one reason there is none
     */
    static Opening open(Session session, AgentContract contract, StrongSource source) {
        try {
            return new Opened(new DefaultContinuationKeyAuthority(session, contract, source.get()));
        } catch (final NoSuchAlgorithmException absent) {
            return new NotOpened("this runtime has no strong secure source, and a key from a"
                    + " weaker one is a token anybody can forge: " + absent.getMessage());
        }
    }

    /**
     * Where the ring sits.
     *
     * @return the path
     */
    public static StatePath record() {
        return StatePath.deployment("key-ring");
    }

    @Override
    public ReadOutcome read() {
        try {
            if (!session.nodeExists(record().path())) {
                return new Unavailable(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT,
                        "this deployment holds no key ring at " + record().path()
                                + ", and one is not created implicitly"));
            }
            session.refresh(false);
            return new Read(ringIn(session.getNode(record().path())));
        } catch (final RepositoryException unreadable) {
            return new Unavailable(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT,
                    "the ring could not be read: " + unreadable.getMessage()));
        }
    }

    /**
     * Establishes the first ring, if nothing has established one yet.
     *
     * <p>By claim, so two nodes starting together establish it once: the one whose commit lands
     * first writes the ring and the other reads what is there.</p>
     *
     * @return the ring this deployment now holds, or the one reason there is none
     */
    public ReadOutcome establish() {
        try {
            ClaimByCreation.claim(session, record(), "nt:unstructured",
                    node -> write(node, KeyRing.initial(material())));
            return read();
        } catch (final RepositoryException unwritable) {
            return new Unavailable(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT,
                    "the ring could not be established: " + unwritable.getMessage()));
        }
    }

    @Override
    public ContinuationKeyAuthority.WriteOutcome compareAndSet(KeyRing expected,
            KeyRing next, Lease lease, long nowUnixMilliseconds) {
        if (nowUnixMilliseconds >= lease.expiresAtUnixMilliseconds()) {
            return new NotWritten(new KeyRingRefusal(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER,
                    lease.holder() + " no longer holds the lease"));
        }
        final Optional<KeyRingRefusal> unbounded = next.unbounded(contract);
        if (unbounded.isPresent()) {
            return new NotWritten(unbounded.get());
        }
        return written(expected, next);
    }

    private ContinuationKeyAuthority.WriteOutcome written(KeyRing expected,
                                                          KeyRing next) {
        try {
            session.refresh(false);
            final Node node = session.getNode(record().path());
            if (!ringIn(node).equals(expected)) {
                return new NotWritten(new KeyRingRefusal(
                        KeyRingRefusal.Failure.CHANGED_SINCE_IT_WAS_READ,
                        "the ring changed since it was read, so this write is not the one meant"));
            }
            write(node, next);
            session.save();
            return new Written(next);
        } catch (final RepositoryException unwritable) {
            return new NotWritten(new KeyRingRefusal(
                    KeyRingRefusal.Failure.CHANGED_SINCE_IT_WAS_READ,
                    "the ring could not be written: " + unwritable.getMessage()));
        }
    }

    /**
     * A key from the platform's own secure source.
     *
     * @return the key, rendered in lower-case hexadecimal
     */
    public String material() {
        final byte[] bytes = new byte[KEY_BYTES];
        source.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static KeyRing ringIn(Node node) throws RepositoryException {
        final String current = node.hasProperty(CURRENT)
                ? node.getProperty(CURRENT).getString()
                : "";
        if (!node.hasProperty(PRIOR)) {
            return new KeyRing(current, new KeyRing.NothingRetained());
        }
        return new KeyRing(current, new KeyRing.Retained(node.getProperty(PRIOR).getString(),
                CompareAndSet.held(node, PRIOR_UNTIL)));
    }

    private static void remove(Node node, String property) throws RepositoryException {
        if (node.hasProperty(property)) {
            node.getProperty(property).remove();
        }
    }

    private static void write(Node node, KeyRing ring) {
        try {
            node.setProperty(CURRENT, ring.current());
            if (ring.prior() instanceof final KeyRing.Retained retained) {
                node.setProperty(PRIOR, retained.key());
                node.setProperty(PRIOR_UNTIL, retained.expiresAtUnixMilliseconds());
                return;
            }
            remove(node, PRIOR);
            remove(node, PRIOR_UNTIL);
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the ring could not be written", unwritable);
        }
    }
}
