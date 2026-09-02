// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.function.Consumer;
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
 * <p><strong>What a claim means on a cluster.</strong> On a document store two nodes can each find
 * a path free, each create it, and each be told they claimed it — the store resolves the collision
 * afterwards, on its own background read, and both nodes then agree about what is there. That is
 * proved on two nodes against one shared repository rather than assumed. So {@code CLAIMED} means
 * "this node created this record", not "no other node did", and it is safe because what a claim
 * writes is derived from the identifier it is claiming: two claimants write the same record. Which
 * worker may <em>execute</em> is a different question, decided by a fenced lease rather than by
 * this primitive, precisely because this one cannot decide it.</p>
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
     * @return whether this writer claimed it or somebody else already had
     * @throws RepositoryException if the repository fails for a reason that is not the path being
     *     held, because a repository that cannot answer is a different thing from an answer of no
     */
    public static WriteOutcome claim(Session session, StatePath path, String primaryType,
                                     Consumer<Node> fill) throws RepositoryException {
        if (session.nodeExists(path.path())) {
            return WriteOutcome.ALREADY_HELD;
        }
        try {
            final Node claimed = create(session, path, primaryType);
            fill.accept(claimed);
            session.save();
            return WriteOutcome.CLAIMED;
        } catch (final ItemExistsException | ConstraintViolationException held) {
            // Somebody else committed between the check and this one. That is the race this
            // primitive exists to resolve, and the answer is the same as finding it there.
            session.refresh(false);
            return WriteOutcome.ALREADY_HELD;
        }
    }

    private static Node create(Session session, StatePath path, String primaryType)
            throws RepositoryException {
        final String parentPath = path.path().substring(0, path.path().lastIndexOf('/'));
        final String name = path.path().substring(path.path().lastIndexOf('/') + 1);
        return session.getNode(parentPath).addNode(name, primaryType);
    }
}
