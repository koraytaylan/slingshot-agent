// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.ArrayList;
import java.util.List;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Which incarnation of the event store this deployment is serving, and every one it has served.
 *
 * <p>A client holding rows from a generation this agent no longer serves has to be told, rather
 * than silently answered from a store that was rebuilt underneath it. So the record keeps every
 * generation it has ever been at: a repeat is then something the store can detect rather than
 * something that is merely unlikely.</p>
 *
 * <p>An absent record on a tree that otherwise exists is a refusal rather than an implicit
 * creation. A store that was never prepared and a store that lost its generation need different
 * answers, and creating one here would give them the same one.</p>
 */
public final class GenerationStore {

    /** The node the generation record sits on, under the agent's own tree. */
    public static final String NODE = "generation";

    /** The property the currently served generation is written in. */
    public static final String SERVING = "serving";

    /** The property every generation ever served is written in. */
    public static final String SERVED = "served";

    private GenerationStore() {
    }

    /** Why a generation could not be read, established, or moved to. */
    public enum Refusal {
        /** There is no record, and one is not created by reading for it. */
        NO_RECORD,
        /** The tree itself is not there, which is a deployment that was never prepared. */
        NO_TREE,
        /** The generation asked for has been served before, so it would not be a new incarnation. */
        ALREADY_SERVED,
        /** The generation asked for is before the one being served. */
        BEFORE_THE_ONE_SERVED,
        /** Somebody else was writing the record often enough that this writer gave up. */
        CONTENDED
    }

    /** The result of asking about it: the generation, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * The generation this store is serving.
     *
     * @param generation the generation
     */
    public record Held(EventStoreGeneration generation) implements Outcome {
    }

    /**
     * No generation, for a reason that is never "so one was created".
     *
     * @param refusal why there is none
     * @param detail what was observed, naming both numbers where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** Whether a generation an identity names is one this store answers about. */
    public enum Membership {
        /** It is the one being served, and an operation naming it is an operation of this store. */
        SERVING,
        /** It was served before: the store has been rebuilt, and those rows are not answered from. */
        RETAINED,
        /** It has never been served here at all, which is a client talking to another deployment. */
        UNKNOWN
    }

    /**
     * Establishes the first generation, if nothing has established one yet.
     *
     * <p>By claim, so two instances starting together establish it once: the one whose commit lands
     * first writes the record and the other is told it is already there, which is the answer it
     * wanted.</p>
     *
     * @param session the session to write under
     * @return the generation now being served, or the one reason there is none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome establish(Session session) throws RepositoryException {
        if (!session.nodeExists(StatePath.ROOT)) {
            return new Refused(Refusal.NO_TREE, StatePath.ROOT + " is not there, so this"
                    + " deployment was never prepared");
        }
        ClaimByCreation.claim(session, record(), "nt:unstructured", node -> {
            write(node, SERVING, EventStoreGeneration.FIRST);
            write(node, SERVED, List.of(EventStoreGeneration.FIRST));
        });
        return serving(session);
    }

    /**
     * The generation this store is serving.
     *
     * @param session the session to read under
     * @return the generation, or the one reason there is none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome serving(Session session) throws RepositoryException {
        if (!session.nodeExists(StatePath.ROOT)) {
            return new Refused(Refusal.NO_TREE, StatePath.ROOT + " is not there, so this"
                    + " deployment was never prepared");
        }
        if (!session.nodeExists(record().path())) {
            return new Refused(Refusal.NO_RECORD, record().path() + " is not there on a tree that"
                    + " is, which is a store that lost its generation rather than one nobody"
                    + " prepared");
        }
        final long serving = session.getNode(record().path()).getProperty(SERVING).getLong();
        final EventStoreGeneration.Outcome held = EventStoreGeneration.of(serving);
        if (held instanceof final EventStoreGeneration.Refused refused) {
            return new Refused(Refusal.NO_RECORD, refused.detail());
        }
        return new Held(((EventStoreGeneration.Held) held).generation());
    }

    /**
     * Moves this store to another incarnation.
     *
     * @param session the session to write under
     * @param next the generation to serve from now on
     * @return the generation now being served, or the one reason it is not that one
     * @throws RepositoryException if the repository fails
     */
    public static Outcome rotate(Session session, EventStoreGeneration next)
            throws RepositoryException {
        final Outcome current = serving(session);
        if (current instanceof Refused) {
            return current;
        }
        final EventStoreGeneration serving = ((Held) current).generation();
        if (served(session).contains(next.number())) {
            return new Refused(Refusal.ALREADY_SERVED, next + " has been served before, and a"
                    + " generation that repeats is one a client cannot tell from the first");
        }
        if (next.compareTo(serving) <= 0) {
            return new Refused(Refusal.BEFORE_THE_ONE_SERVED,
                    next + " is not after " + serving + ", which this store is serving");
        }
        return written(session, next);
    }

    private static Outcome written(Session session, EventStoreGeneration next)
            throws RepositoryException {
        final long serving = session.getNode(record().path()).getProperty(SERVING).getLong();
        final WriteOutcome outcome =
                CompareAndSet.set(session, record(), SERVING, serving, next.number());
        if (outcome != WriteOutcome.WRITTEN) {
            return new Refused(Refusal.CONTENDED, "the record changed while it was being moved: "
                    + outcome);
        }
        final List<Long> served = new ArrayList<>(served(session));
        served.add(next.number());
        write(session.getNode(record().path()), SERVED, served);
        session.save();
        return new Held(next);
    }

    /**
     * Whether a generation an identity names is one this store answers about.
     *
     * @param session the session to read under
     * @param named the generation the identity names
     * @return which of the three it is
     * @throws RepositoryException if the repository fails
     */
    public static Membership membership(Session session, EventStoreGeneration named)
            throws RepositoryException {
        final Outcome current = serving(session);
        if (current instanceof Held && ((Held) current).generation().equals(named)) {
            return Membership.SERVING;
        }
        return served(session).contains(named.number()) ? Membership.RETAINED : Membership.UNKNOWN;
    }

    /**
     * Every generation this store has ever served.
     *
     * @param session the session to read under
     * @return the numbers, in the order they were served
     * @throws RepositoryException if the repository fails
     */
    public static List<Long> served(Session session) throws RepositoryException {
        if (!session.nodeExists(record().path())) {
            return List.of();
        }
        final Node node = session.getNode(record().path());
        if (!node.hasProperty(SERVED)) {
            return List.of();
        }
        final List<Long> served = new ArrayList<>();
        for (final javax.jcr.Value value : node.getProperty(SERVED).getValues()) {
            served.add(value.getLong());
        }
        return served;
    }

    /**
     * Where the generation record sits.
     *
     * @return the path
     */
    public static StatePath record() {
        return StatePath.deployment(NODE);
    }

    private static void write(Node node, String property, long value) {
        try {
            node.setProperty(property, value);
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the record could not be written", unwritable);
        }
    }

    private static void write(Node node, String property, List<Long> values) {
        try {
            final javax.jcr.Value[] written = new javax.jcr.Value[values.size()];
            int index = 0;
            while (index < values.size()) {
                written[index] = node.getSession().getValueFactory()
                        .createValue(values.get(index));
                index = index + 1;
            }
            node.setProperty(property, written);
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the record could not be written", unwritable);
        }
    }
}
