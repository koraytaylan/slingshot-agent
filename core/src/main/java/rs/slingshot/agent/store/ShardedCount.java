// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * Counting, over compare-and-set rather than over an atomic counter.
 *
 * <p>Oak's atomic counter mixin increments without a read-modify-write, which is exactly what
 * accounting seems to want — but on a clustered document store its increments are consolidated by a
 * background task, so the value a node reads back is eventually consistent rather than current. An
 * admission decision taken on it would be correct on one instance and quietly wrong on a cluster,
 * which is the worst shape a defect can have: it passes on the tier and fails on the customer's
 * author. So counting is compare-and-set like everything else, and the mixin is refused everywhere
 * in this repository.</p>
 *
 * <p>The shard properties preserve the stored counter layout. A unique node revision serialises
 * competing changes, including changes to different shards. Capacity admission fences the entire
 * total and caller count before checking the bounds; shard headroom alone cannot protect a
 * multi-unit admission from concurrent writers.</p>
 */
public final class ShardedCount {

    /** The most shards one count is ever spread over. */
    public static final int SHARDS = 16;

    /** How a shard's own property is named, before its number. */
    public static final String SHARD_PREFIX = "shard_";

    private ShardedCount() {
    }

    /**
     * Advances one count by one, on the shard a writer's own name lands on.
     *
     * <p>This conditional delta may return {@link WriteOutcome#VALUE_CHANGED} if its initial read
     * becomes stale. A caller must handle that outcome and retry from fresh state as appropriate;
     * capacity accounting uses reservation transactions instead of this standalone helper.</p>
     *
     * @param session the session to write under
     * @param path the node the count sits on
     * @param writer the writer advancing it, which decides the shard so two writers rarely meet
     * @param by how much to advance it
     * @param shards how many shards this count is spread over
     * @return whether it was advanced, the shard had changed, or the writer gave up
     * @throws RepositoryException if the repository fails for a reason that is not contention
     */
    public static WriteOutcome advance(Session session, StatePath path, String writer, long by,
                                       int shards) throws RepositoryException {
        final String shard = shardOf(writer, shards);
        final long held = CompareAndSet.held(session.getNode(path.path()), shard);
        return CompareAndSet.set(session, path, shard, held, held + by);
    }

    /**
     * What a count currently holds, read across every shard together.
     *
     * <p>Read together rather than one at a time, so the total is a value the repository held at
     * one moment rather than a sum of values it held at sixteen.</p>
     *
     * @param session the session to read under
     * @param path the node the count sits on
     * @param shards how many shards this count is spread over
     * @return the total
     * @throws RepositoryException if the repository fails
     */
    public static long total(Session session, StatePath path, int shards)
            throws RepositoryException {
        session.refresh(false);
        return total(session.getNode(path.path()), shards);
    }

    /**
     * Reads every shard without refreshing or discarding an enclosing pending transaction.
     *
     * @param node the counter node from the transaction's session
     * @param shards how many shards this count is spread over
     * @return the total in the current session view
     * @throws RepositoryException if the repository fails
     */
    public static long total(Node node, int shards) throws RepositoryException {
        long total = 0;
        int shard = 0;
        while (shard < shards) {
            total = total + CompareAndSet.held(node, SHARD_PREFIX + shard);
            shard = shard + 1;
        }
        return total;
    }

    /**
     * The conservative headroom retained by the existing admission contract.
     *
     * <p>This is not a concurrency guarantee: amounts can exceed one, and more than one writer
     * can target a shard. The admission transaction supplies exclusivity. Keeping this headroom
     * preserves the currently exposed admission thresholds.</p>
     *
     * @param shards how many shards the count is spread over
     * @return the margin
     */
    public static long inFlightMargin(int shards) {
        return shards - 1L;
    }

    /**
     * How many shards a count of a given size is spread over.
     *
     * <p>Sharding is what keeps a single hot property from serialising every writer in the cluster,
     * and it costs a margin. So a count is spread only as far as it can afford: at most one shard
     * per sixteen of what it may hold, and never more than the ceiling. A count small enough that
     * writers meeting on it is rare is exact instead.</p>
     *
     * @param bound what the count may hold
     * @return how many shards to spread it over
     */
    public static int shardsFor(long bound) {
        return (int) Math.max(1, Math.min(SHARDS, bound / SHARDS));
    }

    /**
     * Which shard one writer advances.
     *
     * @param writer the writer's own name
     * @param shards how many shards the count is spread over
     * @return the shard's property name
     */
    public static String shardOf(String writer, int shards) {
        return SHARD_PREFIX + Math.floorMod(writer.hashCode(), shards);
    }
}
