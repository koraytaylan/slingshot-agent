// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.nio.charset.StandardCharsets;
import java.util.List;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Where one thing this agent knows about lives, derived from what it is called and nothing else.
 *
 * <p>A path is a generation, two levels of bucket taken from the leading characters of an
 * identifier, and the identifier. No date, no counter, and nothing ambient: a caller holding only an
 * identifier — which is all a recovering client has — finds the record, and finds the same one every
 * time.</p>
 *
 * <p>A path cannot be built from a string. Every derivation takes a constructed identity, and a
 * caller's own name is constrained into one before it is used, because an unconstrained name is a
 * path separator waiting to be written by whoever sends it. A name that only carries characters
 * this tree cannot safely hold as its own segment - an address, say, rather than a login name - is
 * not refused for it: it is counted under a digest of itself instead, because the platform decides
 * what a caller is called and this store still has to have somewhere for them.</p>
 */
public final class StatePath {

    /** The tree this agent writes, which is the only one it is granted. */
    public static final String ROOT = "/var/slingshot-agent";

    /** Where every logical operation sits. */
    public static final String OPERATIONS = "operations";

    /** Where the counters admission is decided against sit. */
    public static final String CAPACITY = "capacity";

    /** Where one submitting caller's own counters sit. */
    public static final String CALLERS = "callers";

    /** How many levels of bucket a derivation has. */
    public static final int BUCKET_DEPTH = 2;

    /** How many characters each level of bucket takes. */
    public static final int BUCKET_CHARACTERS = 2;

    private final String path;

    private StatePath(String path) {
        this.path = path;
    }

    /** Why a name cannot be part of a path. */
    public enum Refusal {
        /** It carries a path separator, which would put the record somewhere else entirely. */
        CARRIES_A_SEPARATOR,
        /** It carries a parent reference, which would climb out of the tree. */
        CLIMBS_OUT_OF_THE_TREE,
        /** It begins with a separator, which would make it an address rather than a name. */
        BEGINS_WITH_A_SEPARATOR,
        /** It is empty, or shorter than one bucket needs. */
        TOO_SHORT
    }

    /** The result of constraining a name: the caller, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A name that can be part of a path.
     *
     * @param caller the constrained name
     */
    public record Held(Caller caller) implements Outcome {
    }

    /**
     * A name that cannot.
     *
     * @param refusal why it cannot
     * @param detail what was observed, without echoing the whole name back
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * One submitting caller, as a name a path may be derived from.
     *
     * <p>It exists so that a caller's own name is constrained once, where it arrives, rather than
     * trusted at every place it is used. A record's path is data a request supplied until a type has
     * said what it may be.</p>
     */
    public static final class Caller {

        private final String name;

        private Caller(String name) {
            this.name = name;
        }

        /**
         * The caller's own name.
         *
         * @return the name
         */
        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof final Caller caller && name.equals(caller.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Constrains a name into one a path may be derived from.
     *
     * <p>A name carrying a separator, a parent reference, or shorter than one bucket needs is
     * refused outright: each of those is a shape a legitimate login name has no reason to take, and
     * admitting one would put the record somewhere this tree does not mean it to be. A name that is
     * merely spelled with characters outside letters, digits, hyphens and underscores - the shape
     * an IMS technical account's address takes - is not refused; see {@link #digested(String)}.</p>
     *
     * @param name the name as it arrived
     * @return the caller, or the one reason there is none
     */
    public static Outcome caller(String name) {
        if (name.startsWith("/")) {
            return new Refused(Refusal.BEGINS_WITH_A_SEPARATOR,
                    "a name that begins with a separator is an address rather than a name");
        }
        if (name.contains("..")) {
            return new Refused(Refusal.CLIMBS_OUT_OF_THE_TREE,
                    "a name carrying a parent reference would climb out of the agent's own tree");
        }
        if (name.contains("/")) {
            return new Refused(Refusal.CARRIES_A_SEPARATOR,
                    "a name carrying a separator would put the record somewhere else entirely");
        }
        if (name.length() < BUCKET_DEPTH * BUCKET_CHARACTERS) {
            return new Refused(Refusal.TOO_SHORT, "a name shorter than "
                    + BUCKET_DEPTH * BUCKET_CHARACTERS + " characters cannot be bucketed");
        }
        return named(name);
    }

    private static Outcome named(String name) {
        final boolean outside = name.chars().anyMatch(scalar -> !isNameCharacter(scalar));
        return outside ? new Held(new Caller(digested(name))) : new Held(new Caller(name));
    }

    /**
     * The name a platform-established identity is counted under where its own spelling is not one
     * a path may hold.
     *
     * <p>An IMS technical account authenticates as an address such as
     * {@code 00000000-...@techacct.example.com}: this repository decides nothing about what an
     * authorizable is called, so a name shaped like that is not a defect to refuse but a caller
     * this store still has to count. A digest is always letters and digits and nothing else, so it
     * always fits; two different names cannot land on the same one; and one name always digests to
     * the same one, so a caller found this way today is the same caller found this way tomorrow.
     * </p>
     *
     * @param name the name as it arrived
     * @return the digest a path may be built from instead
     */
    private static String digested(String name) {
        return Digest.of(name.getBytes(StandardCharsets.UTF_8)).rendered();
    }

    private static boolean isNameCharacter(int scalar) {
        return scalar >= 'a' && scalar <= 'z' || scalar >= 'A' && scalar <= 'Z'
                || scalar >= '0' && scalar <= '9' || scalar == '-' || scalar == '_';
    }

    /**
     * Where one logical operation lives.
     *
     * @param generation the incarnation of the store it belongs to
     * @param identifier the operation's own name
     * @return the path
     */
    public static StatePath operation(EventStoreGeneration generation,
                                      AgentOperationIdentifier identifier) {
        return new StatePath(ROOT + "/" + OPERATIONS + "/" + generationSegment(generation)
                + bucketed(identifier.rendered()));
    }

    /**
     * Where one submitting caller's counters live.
     *
     * @param caller the caller
     * @return the path
     */
    public static StatePath caller(Caller caller) {
        return new StatePath(ROOT + "/" + CAPACITY + "/" + CALLERS + bucketed(caller.name()));
    }

    /**
     * Where something the whole deployment holds lives.
     *
     * @param name the node's own name, which the layout declares
     * @return the path
     */
    public static StatePath deployment(String name) {
        return new StatePath(ROOT + "/" + name);
    }

    private static String generationSegment(EventStoreGeneration generation) {
        return "g" + generation.number();
    }

    private static String bucketed(String identifier) {
        final StringBuilder buckets = new StringBuilder();
        int level = 0;
        while (level < BUCKET_DEPTH) {
            final int from = level * BUCKET_CHARACTERS;
            buckets.append('/').append(identifier, from, from + BUCKET_CHARACTERS);
            level = level + 1;
        }
        return buckets + "/" + identifier;
    }

    /**
     * A child of this path, by the name the layout declares for it.
     *
     * @param name the child's own name
     * @return the path
     */
    public StatePath child(String name) {
        return new StatePath(path + "/" + name);
    }

    /**
     * Every child of an operation the layout declares.
     *
     * @return the names, in the layout's own order
     */
    public static List<String> operationChildren() {
        return List.of("outbox", "lease", "events", "snapshot", "artifacts", "intake",
                EventLedger.TERMINAL_BUDGET, MaintenanceSweep.COMPLETION);
    }

    /**
     * The path itself.
     *
     * @return the path, which is always inside the agent's own tree
     */
    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final StatePath derived && path.equals(derived.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }
}
