// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.UUID;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * The second of the two primitives: a value is written only if it is still what the caller read.
 *
 * <p>The commit fails rather than merges. A repository that merged two writers' changes would
 * produce a value neither of them asked for, and every decision taken on it afterwards would be
 * taken on a number nobody chose. Contention is retried a bounded number of times and then reported
 * as itself, distinctly from the value having changed — a caller that read a stale value has a
 * different thing to do from one that is simply losing races.</p>
 */
public final class CompareAndSet {

    /** How many times a writer retries a commit somebody else won before it reports contention. */
    public static final int ATTEMPTS = 5;

    /** The per-node commit identity, changed even when competing business values are identical. */
    public static final String REVISION = "transition_revision";

    private CompareAndSet() {
    }

    /**
     * Writes a property only if it still holds what the caller expected.
     *
     * @param session the session to write under
     * @param path the node the property is on
     * @param property the property's own name
     * @param expected what the caller read
     * @param next what the caller wants written instead
     * @return whether it was written, the value had changed, or the writer gave up
     * @throws RepositoryException if the repository fails for a reason that is not contention
     */
    public static WriteOutcome set(Session session, StatePath path, String property, long expected,
                                   long next) throws RepositoryException {
        int attempt = 0;
        while (attempt < ATTEMPTS) {
            final WriteOutcome outcome = attempt(session, path, property, expected, next);
            if (outcome != WriteOutcome.CONTENDED) {
                return outcome;
            }
            attempt = attempt + 1;
        }
        return WriteOutcome.CONTENDED;
    }

    private static WriteOutcome attempt(Session session, StatePath path, String property,
                                        long expected, long next) throws RepositoryException {
        session.refresh(false);
        final Node node = session.getNode(path.path());
        stamp(node);
        if (held(node, property) != expected) {
            session.refresh(false);
            return WriteOutcome.VALUE_CHANGED;
        }
        node.setProperty(property, next);
        try {
            session.save();
            return WriteOutcome.WRITTEN;
        } catch (final InvalidItemStateException contended) {
            // Somebody else committed while this one was being prepared. Nothing was written, and
            // what the caller expected may still be true, so the read is taken again.
            session.refresh(false);
            return WriteOutcome.CONTENDED;
        }
    }

    /**
     * Gives a pending node transition a unique commit identity.
     *
     * <p>Oak may merge identical property updates and identical node creations. Distinct revision
     * values force concurrent writers of the same base to conflict instead. Stage this stamp
     * before reading the expected business state, so an automatic refresh cannot adopt another
     * writer between the predicate and the first pending change. Commit the stamp with the
     * transition, or discard both when the predicate fails. No save or refresh happens here.</p>
     *
     * @param node the node whose pending transition must have one winner
     * @throws RepositoryException if the revision cannot be staged
     */
    public static void stamp(Node node) throws RepositoryException {
        node.setProperty(REVISION, UUID.randomUUID().toString());
    }

    /**
     * What a property currently holds, counting an absent property as zero.
     *
     * @param node the node the property is on
     * @param property the property's own name
     * @return the value
     * @throws RepositoryException if the repository fails
     */
    public static long held(Node node, String property) throws RepositoryException {
        return node.hasProperty(property) ? node.getProperty(property).getLong() : 0;
    }
}
