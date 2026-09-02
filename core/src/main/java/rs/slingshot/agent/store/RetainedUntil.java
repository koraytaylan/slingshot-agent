// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

/**
 * The instant one kind of stored thing stops being kept, derived from a record and nothing else.
 *
 * <p>There is no way to make one of these from a clock reading. That is the whole reason it is a
 * type: a retention computed from "now" is a retention that lengthens every time somebody asks
 * about it, and a client that budgeted against the window it was told about at request time would
 * then be budgeting against something else. Only {@link RetentionPolicy}, reading a record that
 * carries the instant its client's request began, can produce one.</p>
 */
public final class RetainedUntil {

    private final RetentionPolicy.Kind kind;

    private final long instantUnixMilliseconds;

    private RetainedUntil(RetentionPolicy.Kind kind, long instantUnixMilliseconds) {
        this.kind = kind;
        this.instantUnixMilliseconds = instantUnixMilliseconds;
    }

    /**
     * The retained-until instant for one kind, made only from a record's own request-start.
     *
     * @param kind which kind of stored thing
     * @param instantUnixMilliseconds when it stops being kept
     * @return the value
     */
    static RetainedUntil from(RetentionPolicy.Kind kind, long instantUnixMilliseconds) {
        return new RetainedUntil(kind, instantUnixMilliseconds);
    }

    /**
     * Which kind of stored thing this is about.
     *
     * @return the kind
     */
    public RetentionPolicy.Kind kind() {
        return kind;
    }

    /**
     * When it stops being kept.
     *
     * @return the instant
     */
    public long instantUnixMilliseconds() {
        return instantUnixMilliseconds;
    }

    /**
     * Whether this instant is behind the one a sweep is running at.
     *
     * <p>Comparing against a clock reading is not the same as deriving from one: what is compared
     * here was already decided by the record.</p>
     *
     * @param nowUnixMilliseconds what this side's clock says
     * @return whether what this covers may now be removed
     */
    public boolean hasPassed(long nowUnixMilliseconds) {
        return nowUnixMilliseconds >= instantUnixMilliseconds;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final RetainedUntil held && held.kind == kind
                && held.instantUnixMilliseconds == instantUnixMilliseconds;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(kind, instantUnixMilliseconds);
    }

    @Override
    public String toString() {
        return kind.spelling() + " until " + instantUnixMilliseconds;
    }
}
