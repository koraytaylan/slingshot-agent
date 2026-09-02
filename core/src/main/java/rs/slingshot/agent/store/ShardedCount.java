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
 * <p>Sharding is what keeps a single hot property from serialising every writer in the cluster;
 * compare-and-set is what makes each shard exact. Two nodes advancing different shards from one
 * read can each admit, so a total may be understated by at most one advance per shard while
 * advances are in flight — which is why an admission compares against the declared bound less that
 * margin. A decision may be conservative and may never be wrong.</p>
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
        final Node node = session.getNode(path.path());
        long total = 0;
        int shard = 0;
        while (shard < shards) {
            total = total + CompareAndSet.held(node, SHARD_PREFIX + shard);
            shard = shard + 1;
        }
        return total;
    }

    /**
     * The margin a total spread over so many shards may be understated by.
     *
     * <p>One advance per <em>other</em> shard: a writer commits its own advance before it reads, so
     * what it cannot see is at most one uncommitted advance on each shard that is not its own. A
     * count on a single shard is therefore exact, which is why a small bound is not sharded at all
     * — sharding a count of eight would mean refusing at eight less a margin of sixteen.</p>
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
