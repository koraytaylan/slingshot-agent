// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Moving this store to a new incarnation, explicitly, and keeping the old ones while anybody needs
 * them.
 *
 * <p>Rotation is never implicit. A store that rebuilt itself quietly would answer a client's lookup
 * about a durable thing with a confident nothing — the worst possible answer, because it is
 * indistinguishable from "that never happened" and the client acts on it.</p>
 *
 * <p>So a prior generation is retained, with a retention of its own, and a rotation that would push
 * one out before that retention has ended is refused rather than performed. Which means a busy
 * store can be told it may not rotate yet — which is the point: rotating is a thing an operator
 * chooses, and being told why they cannot yet is better than losing what a client is still reading.
 * </p>
 */
public final class GenerationRotation {

    /** The child of the generation record retained generations are recorded under. */
    public static final String RETAINED = "retained";

    /** The property a retained generation's own retention is written in. */
    public static final String RETAINED_UNTIL = "retained_until_unix_milliseconds";

    /** How a retained generation's node is named, which is how a generation is named in a path. */
    private static final String PREFIX = "g";

    private GenerationRotation() {
    }

    /** Why a rotation did not happen. */
    public enum Refusal {
        /** Making room would drop a generation somebody may still be reading. */
        INSIDE_A_RETENTION,
        /** The store itself would not move, and it says why. */
        STORE_REFUSED
    }

    /** What rotating did. */
    public sealed interface Outcome permits Rotated, Refused {
    }

    /**
     * A store now serving a new incarnation.
     *
     * @param serving the incarnation now being served
     * @param retained every incarnation still answered about, oldest first
     */
    public record Rotated(EventStoreGeneration serving, List<RetainedGeneration> retained)
            implements Outcome {

        /** Holds a list nothing can change afterwards. */
        public Rotated {
            retained = List.copyOf(retained);
        }
    }

    /**
     * A store that did not move.
     *
     * @param refusal why not
     * @param detail what was observed, naming the generation and the instant where both matter
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** What may be done with one incarnation this store knows about. */
    public sealed interface Access permits Serving, Readable, Retired {
    }

    /**
     * The incarnation being served, which may be read and written.
     *
     * @param generation the incarnation
     */
    public record Serving(EventStoreGeneration generation) implements Access {
    }

    /**
     * An incarnation that may be read and never added to.
     *
     * @param generation the incarnation, with the instant it stops being answered about
     */
    public record Readable(RetainedGeneration generation) implements Access {
    }

    /**
     * An incarnation this store no longer answers about at all.
     *
     * @param named the incarnation that was asked about
     * @param serving the incarnation being served instead
     */
    public record Retired(EventStoreGeneration named, EventStoreGeneration serving)
            implements Access {

        /**
         * What a client asking about this is told, which names both.
         *
         * @return the message
         */
        public String rendered() {
            return "generation " + named.number() + " is no longer answered about and this store is"
                    + " serving generation " + serving.number();
        }
    }

    /**
     * Moves this store to the next incarnation, keeping the one it leaves.
     *
     * @param session the session to write under
     * @param next the incarnation to serve from now on
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares how many are kept and for how long
     * @return what rotating did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome rotate(Session session, EventStoreGeneration next,
                                 long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final GenerationStore.Outcome current = GenerationStore.serving(session);
        if (current instanceof final GenerationStore.Refused refused) {
            return new Refused(Refusal.STORE_REFUSED, refused.refusal() + ": " + refused.detail());
        }
        final EventStoreGeneration serving = ((GenerationStore.Held) current).generation();
        final List<RetainedGeneration> retained = retained(session);
        final long bound = contract.value(ContractLimit.MAXIMUM_PRIOR_GENERATIONS);
        final Optional<Refused> room = room(retained, bound, nowUnixMilliseconds);
        if (room.isPresent()) {
            return room.get();
        }
        final GenerationStore.Outcome moved = GenerationStore.rotate(session, next);
        if (moved instanceof final GenerationStore.Refused refused) {
            return new Refused(Refusal.STORE_REFUSED, refused.refusal() + ": " + refused.detail());
        }
        return written(session, serving, nowUnixMilliseconds, contract, bound);
    }

    private static Optional<Refused> room(List<RetainedGeneration> retained, long bound,
                                          long nowUnixMilliseconds) {
        if (retained.size() < bound) {
            return Optional.empty();
        }
        final RetainedGeneration oldest = retained.getFirst();
        return oldest.mayBeDropped(nowUnixMilliseconds)
                ? Optional.empty()
                : Optional.of(new Refused(Refusal.INSIDE_A_RETENTION, "keeping " + bound
                        + " prior generations means dropping generation "
                        + oldest.generation().number() + ", which is answered about until "
                        + oldest.retainedUntilUnixMilliseconds()));
    }

    private static Outcome written(Session session, EventStoreGeneration retiring,
                                   long nowUnixMilliseconds, AgentContract contract, long bound)
            throws RepositoryException {
        keep(session, retiring, nowUnixMilliseconds + longestRetention(contract));
        final List<RetainedGeneration> retained = new ArrayList<>(retained(session));
        while (retained.size() > bound) {
            drop(session, retained.removeFirst());
        }
        final GenerationStore.Outcome serving = GenerationStore.serving(session);
        return new Rotated(((GenerationStore.Held) serving).generation(), retained);
    }

    /**
     * How long a retired incarnation is answered about, which is the longest anything in it is kept.
     *
     * @param contract the authenticated contract
     * @return the milliseconds
     */
    public static long longestRetention(AgentContract contract) {
        long longest = 0;
        for (final RetentionPolicy.Kind kind : RetentionPolicy.Kind.values()) {
            longest = Math.max(longest, kind.minimum(contract));
        }
        return longest;
    }

    private static void keep(Session session, EventStoreGeneration retiring, long retainedUntil)
            throws RepositoryException {
        final StatePath record = GenerationStore.record().child(RETAINED);
        ClaimByCreation.claim(session, record, "nt:unstructured", node -> { });
        final StatePath kept = record.child(PREFIX + retiring.number());
        ClaimByCreation.claim(session, kept, "nt:unstructured", node -> { });
        session.getNode(kept.path()).setProperty(RETAINED_UNTIL, retainedUntil);
        session.save();
    }

    private static void drop(Session session, RetainedGeneration dropped)
            throws RepositoryException {
        final StatePath kept = GenerationStore.record().child(RETAINED)
                .child(PREFIX + dropped.generation().number());
        if (session.nodeExists(kept.path())) {
            session.getNode(kept.path()).remove();
            session.save();
        }
    }

    /**
     * Every incarnation this store still answers about, oldest first.
     *
     * @param session the session to read under
     * @return the retained generations
     * @throws RepositoryException if the repository fails
     */
    public static List<RetainedGeneration> retained(Session session) throws RepositoryException {
        final StatePath record = GenerationStore.record().child(RETAINED);
        final List<RetainedGeneration> held = new ArrayList<>();
        if (!session.nodeExists(record.path())) {
            return held;
        }
        final javax.jcr.NodeIterator kept = session.getNode(record.path()).getNodes();
        while (kept.hasNext()) {
            final Node one = kept.nextNode();
            final EventStoreGeneration.Outcome named =
                    EventStoreGeneration.of(numbered(one.getName()));
            if (named instanceof final EventStoreGeneration.Held generation) {
                held.add(new RetainedGeneration(generation.generation(),
                        CompareAndSet.held(one, RETAINED_UNTIL)));
            }
        }
        held.sort(java.util.Comparator.comparingLong(one -> one.generation().number()));
        return held;
    }

    private static long numbered(String name) {
        final String written = name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
        return written.chars().allMatch(scalar -> scalar >= '0' && scalar <= '9')
                && !written.isEmpty()
                ? Long.parseLong(written)
                : 0;
    }

    /**
     * What may be done with one incarnation a client names.
     *
     * @param session the session to read under
     * @param named the incarnation the client named
     * @return whether it is served, only readable, or gone
     * @throws RepositoryException if the repository fails
     */
    public static Access accessTo(Session session, EventStoreGeneration named)
            throws RepositoryException {
        final GenerationStore.Outcome current = GenerationStore.serving(session);
        final EventStoreGeneration serving = current instanceof final GenerationStore.Held held
                ? held.generation()
                : named;
        if (named.equals(serving)) {
            return new Serving(serving);
        }
        return retained(session).stream()
                .filter(one -> one.generation().equals(named))
                .findFirst()
                .<Access>map(Readable::new)
                .orElseGet(() -> new Retired(named, serving));
    }

    /**
     * The one reason a rotation did not happen, where it did not.
     *
     * @param outcome what rotating did
     * @return the refusal, or nothing where it happened
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
