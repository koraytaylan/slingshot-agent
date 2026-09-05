// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.function.Consumer;
import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.ConstraintViolationException;

/**
 * The first of the two primitives: a path is claimed by creating it.
 *
 * <p>There is no lock held across a request. A lock held by a process that stopped is a lock nobody
 * can take, and a stale lock in a cluster is an agent that answers nothing until somebody restarts
 * it. Creating a node is atomic in the repository, so the writer whose commit lands first owns the
 * path and every other writer is told so.</p>
 *
 * <p>The repository's own "that already exists" is this primitive's second outcome rather than an
 * error, because it is the answer a caller asked for.</p>
 *
 * <p>Each claim carries a unique revision in the same commit as its initial values. Identical
 * submissions therefore conflict even when Oak would otherwise merge their identical bytes.
 * Only the successful commit returns {@code CLAIMED}; a losing contender refreshes before reading
 * the winner. This establishes admission ownership, while starting execution is a separate
 * conflict-protected operation transition. The parent revision is staged before rechecking
 * absence, so a refresh while filling a new node cannot adopt somebody else's claim. Sibling
 * claims can therefore contend on their bucket, and the caller must handle that refusal.</p>
 */
public final class ClaimByCreation {

    private ClaimByCreation() {
    }

    /**
     * Claims a path by creating a node at it.
     *
     * @param session the session to claim under
     * @param path the path to claim
     * @param primaryType the node type to create it as
     * @param fill what to write into the node before the claim is committed, which is committed
     *     with it or not at all
     * @return whether this writer claimed it, somebody else already had it, or contention left
     *     no visible winner
     * @throws RepositoryException if the repository fails for a reason that is not the path being
     *     held, because a repository that cannot answer is a different thing from an answer of no
     */
    public static WriteOutcome claim(Session session, StatePath path, String primaryType,
                                     Consumer<Node> fill) throws RepositoryException {
        if (session.nodeExists(path.path())) {
            return WriteOutcome.ALREADY_HELD;
        }
        try {
            final Node parent = parentOf(session, path);
            CompareAndSet.stamp(parent);
            if (session.nodeExists(path.path())) {
                session.refresh(false);
                return WriteOutcome.ALREADY_HELD;
            }
            final String name = path.path().substring(path.path().lastIndexOf('/') + 1);
            final Node claimed = parent.addNode(name, primaryType);
            fill.accept(claimed);
            CompareAndSet.stamp(claimed);
            session.save();
            return WriteOutcome.CLAIMED;
        } catch (final ItemExistsException | ConstraintViolationException held) {
            // Somebody else committed between the check and this one. That is the race this
            // primitive exists to resolve, and the answer is the same as finding it there.
            session.refresh(false);
            return WriteOutcome.ALREADY_HELD;
        } catch (final InvalidItemStateException contended) {
            session.refresh(false);
            return session.nodeExists(path.path())
                    ? WriteOutcome.ALREADY_HELD : WriteOutcome.CONTENDED;
        }
    }

    private static Node parentOf(Session session, StatePath path)
            throws RepositoryException {
        final String parentPath = path.path().substring(0, path.path().lastIndexOf('/'));
        return session.getNode(parentPath);
    }
}
