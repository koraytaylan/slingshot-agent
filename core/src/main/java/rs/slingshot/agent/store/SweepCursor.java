// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * Where the last sweep stopped, so the next one carries on rather than starting again.
 *
 * <p>A sweep that runs to completion or not at all is a sweep that never finishes on a full store:
 * the pass gets longer as the store gets bigger, and the store gets bigger precisely because the
 * pass never finishes. So a pass is bounded, and where it stopped is durable.</p>
 *
 * <p>The position is a bucket number rather than a path, because the store is bucketed by the first
 * characters of an identifier and the buckets are therefore a fixed, ordered, complete set. That
 * makes the position a whole number, which makes advancing it a compare-and-set — which is what
 * stops two sweeps from working the same region and from both believing they finished.</p>
 */
public final class SweepCursor {

    /** The deployment-level node the position is kept at. */
    public static final String NODE = "sweep";

    /** The property the next bucket to examine is written in. */
    public static final String POSITION = "next_bucket";

    /** The property the instant the position last moved is written in. */
    public static final String ADVANCED_AT = "advanced_at_unix_milliseconds";

    /** The first bucket, which is where a pass that has never run starts. */
    public static final long FIRST = 0;

    /** How many bits one hexadecimal character carries. */
    private static final int BITS_PER_CHARACTER = 4;

    /** What one level of bucketing shifts by, which is that level's own characters. */
    private static final int LEVEL_SHIFT = StatePath.BUCKET_CHARACTERS * BITS_PER_CHARACTER;

    /** The mask one level of bucketing keeps. */
    private static final long LEVEL_MASK = (1L << LEVEL_SHIFT) - 1;

    /**
     * How many buckets there are, which is every combination the layout's own bucketing can name.
     *
     * <p>Derived from the layout rather than written down, so a store that bucketed one character
     * deeper would sweep the buckets it actually has rather than the ones this file remembered.</p>
     */
    public static final long BUCKETS = 1L << (LEVEL_SHIFT * StatePath.BUCKET_DEPTH);

    private final long bucket;

    private final long advancedAtUnixMilliseconds;

    private SweepCursor(long bucket, long advancedAtUnixMilliseconds) {
        this.bucket = bucket;
        this.advancedAtUnixMilliseconds = advancedAtUnixMilliseconds;
    }

    /**
     * Where the next sweep starts, which is the first bucket where none has ever run.
     *
     * @param session the session to read under
     * @return the position
     * @throws RepositoryException if the repository fails
     */
    public static SweepCursor read(Session session) throws RepositoryException {
        final StatePath path = StatePath.deployment(NODE);
        if (!session.nodeExists(path.path())) {
            return new SweepCursor(FIRST, 0);
        }
        final javax.jcr.Node held = session.getNode(path.path());
        return new SweepCursor(CompareAndSet.held(held, POSITION),
                CompareAndSet.held(held, ADVANCED_AT));
    }

    /**
     * Moves the position, only if it is still where the sweep read it.
     *
     * @param session the session to write under
     * @param read the position as the sweep read it
     * @param bucket where the sweep got to
     * @param nowUnixMilliseconds what this side's clock says
     * @return whether it moved, the position had changed, or the store was busy
     * @throws RepositoryException if the repository fails
     */
    public static WriteOutcome advance(Session session, SweepCursor read, long bucket,
                                       long nowUnixMilliseconds) throws RepositoryException {
        final StatePath path = StatePath.deployment(NODE);
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
        final WriteOutcome moved =
                CompareAndSet.set(session, path, POSITION, read.bucket(), wrapped(bucket));
        if (moved == WriteOutcome.WRITTEN) {
            session.getNode(path.path()).setProperty(ADVANCED_AT, nowUnixMilliseconds);
            session.save();
        }
        return moved;
    }

    private static long wrapped(long bucket) {
        return bucket >= BUCKETS ? FIRST : bucket;
    }

    /**
     * Which bucket the next sweep examines first.
     *
     * @return the bucket
     */
    public long bucket() {
        return bucket;
    }

    /**
     * When the position last moved.
     *
     * @return the instant
     */
    public long advancedAtUnixMilliseconds() {
        return advancedAtUnixMilliseconds;
    }

    /**
     * The two path segments one bucket number names, in the order they appear in a path.
     *
     * @param bucket the bucket number
     * @return the segments
     */
    public static java.util.List<String> segments(long bucket) {
        return java.util.List.of(hexadecimal(bucket >> LEVEL_SHIFT & LEVEL_MASK),
                hexadecimal(bucket & LEVEL_MASK));
    }

    private static String hexadecimal(long value) {
        final StringBuilder written = new StringBuilder(Long.toHexString(value));
        while (written.length() < StatePath.BUCKET_CHARACTERS) {
            written.insert(0, '0');
        }
        return written.toString();
    }

    /**
     * Whether a bucket number is one this store has.
     *
     * @param bucket the bucket number
     * @return whether it is one of the store's own buckets
     */
    public static boolean holds(long bucket) {
        return bucket >= FIRST && bucket < BUCKETS;
    }

    /**
     * A position at a bucket, for a sweep that is deciding where to resume from.
     *
     * @param bucket the bucket number
     * @param advancedAtUnixMilliseconds when it moved there
     * @return the position, or nothing where that is not a bucket this store has
     */
    public static Optional<SweepCursor> at(long bucket, long advancedAtUnixMilliseconds) {
        return holds(bucket)
                ? Optional.of(new SweepCursor(bucket, advancedAtUnixMilliseconds))
                : Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final SweepCursor held && held.bucket == bucket
                && held.advancedAtUnixMilliseconds == advancedAtUnixMilliseconds;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(bucket, advancedAtUnixMilliseconds);
    }

    @Override
    public String toString() {
        return "bucket " + bucket + " of " + BUCKETS;
    }
}
