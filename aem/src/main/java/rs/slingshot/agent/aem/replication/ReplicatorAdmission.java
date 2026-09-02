// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.replication;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.Replicator;
import java.util.List;
import java.util.Optional;
import javax.jcr.Session;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.platform.ContentAdmission;

/**
 * Hands addresses to Adobe's replication service, one at a time, under the caller's own session.
 *
 * <p>One at a time because that is the shape of the service's own method, and because a failure
 * part-way through has to be reportable: what comes back is the count that was admitted before it
 * stopped, and the caller learns that some of their subtree is queued rather than being told the
 * whole thing failed. A queue is one of the few places where partial progress is genuinely
 * recoverable — the same offer can be made again for what did not go — so reporting it honestly is
 * better than pretending the operation was atomic when the service does not make it so.</p>
 *
 * <p>A session that cannot be obtained is a rejection rather than an unknown. Nothing was offered,
 * and nothing is in doubt.</p>
 */
public final class ReplicatorAdmission implements ContentAdmission {

    private final Replicator replicator;

    /**
     * Holds one admission bound to the platform's replication service.
     *
     * @param replicator the service that queues content for publication
     */
    public ReplicatorAdmission(Replicator replicator) {
        this.replicator = replicator;
    }

    @Override
    public Outcome offer(List<String> paths, ResourceResolver session) {
        final Optional<Session> repository = sessionOf(session);
        return repository.isEmpty()
                ? new Rejected("this caller has no repository session to replicate through, so"
                        + " nothing was offered")
                : offered(paths, repository.orElseThrow());
    }

    /**
     * The caller's own repository session, which this borrows and never closes.
     *
     * <p>Borrowed rather than obtained: it belongs to the resolver that was handed in, and closing
     * it here would close a session the caller is still using.</p>
     *
     * @param session the caller's own resolver
     * @return their repository session, or nothing where the resolver has none
     */
    private static Optional<Session> sessionOf(ResourceResolver session) {
        return Optional.ofNullable(session.adaptTo(Session.class));
    }

    private Outcome offered(List<String> paths, Session repository) {
        long admitted = 0;
        for (final String path : paths) {
            try {
                replicator.replicate(repository, ReplicationActionType.ACTIVATE, path);
                admitted = admitted + 1;
            } catch (final ReplicationException refused) {
                return partial(admitted, paths.size(), refused.getMessage());
            }
        }
        return new Admitted(admitted);
    }

    /**
     * What to answer when the service stopped part-way through.
     *
     * <p>Admitted where anything was taken, because those items really are queued and a caller told
     * the whole offer failed would believe otherwise. Rejected only where nothing was.</p>
     *
     * @param admitted how many it took before it stopped
     * @param offered how many were offered
     * @param detail what the service said
     * @return the answer
     */
    private static Outcome partial(long admitted, int offered, String detail) {
        return admitted > 0
                ? new Admitted(admitted)
                : new Rejected("the replication service refused all " + offered + " of them: "
                        + detail);
    }
}
