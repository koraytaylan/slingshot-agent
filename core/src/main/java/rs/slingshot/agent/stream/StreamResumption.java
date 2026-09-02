// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.List;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.store.EventReplay;
import rs.slingshot.agent.store.ReplayCursor;
import rs.slingshot.agent.store.ReplayOutcome;
import rs.slingshot.agent.store.SnapshotStore;

/**
 * Where a reconnecting subscriber picks up, and what it is told when it cannot.
 *
 * <p>The resumption identifier is read as an incarnation and a sequence together, so a cursor from
 * an earlier incarnation is recognised as one rather than misread as an early position — which is
 * the mistake that would silently serve somebody the beginning of a store they have never seen.</p>
 *
 * <p>Everything served is strictly after the cursor. The cursor's own event has been delivered
 * once; delivering it again would make a subscriber's own idempotency the thing that saves it, and
 * the whole point of a cursor is that it does not have to be.</p>
 */
public final class StreamResumption {

    /** The header a client resumes with, which is the identifier this side last issued. */
    public static final String RESUMPTION_HEADER = "Last-Event-ID";

    private StreamResumption() {
    }

    /** What a resumption produced. */
    public sealed interface Outcome permits Serving, Resetting, Refused {
    }

    /**
     * The events this subscriber has not been shown, oldest first.
     *
     * @param events the events, as the bytes they were written as
     * @param current what is currently true, for a subscriber that wants only that
     */
    public record Serving(List<String> events, SnapshotStore.Materialised current)
            implements Outcome {

        /** Holds a list nothing can change afterwards. */
        public Serving {
            events = List.copyOf(events);
        }
    }

    /**
     * That the cursor cannot be honoured, with what to resynchronise from.
     *
     * @param current what is currently true
     * @param detail what was observed
     */
    public record Resetting(SnapshotStore.Materialised current, String detail)
            implements Outcome {
    }

    /**
     * That there is nothing to serve at all.
     *
     * @param refusal what the store said
     * @param detail what was observed
     */
    public record Refused(ReplayOutcome.Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Where one subscriber picks up.
     *
     * @param store the caller's own session
     * @param session whose stream it is
     * @param resumption the identifier the client resumed with, empty where it sent none
     * @param contract the authenticated contract, which bounds one read
     * @return what to serve, or that the cursor cannot be honoured
     * @throws RepositoryException if the repository fails
     */
    public static Outcome from(Session store, StreamSession session, String resumption,
                               AgentContract contract) throws RepositoryException {
        final ReplayOutcome replayed = cursorIn(resumption)
                .map(cursor -> replay(store, session, cursor, contract))
                .orElseGet(() -> current(store, session, contract));
        if (replayed instanceof final ReplayOutcome.Served served) {
            return new Serving(served.events(), served.current());
        }
        if (replayed instanceof final ReplayOutcome.Reset reset) {
            return new Resetting(reset.current(), reset.detail());
        }
        final ReplayOutcome.Refused refused = (ReplayOutcome.Refused) replayed;
        return refused.refusal() == ReplayOutcome.Refusal.FOREIGN_GENERATION
                ? new Resetting(SnapshotStore.read(store, session.operation()), refused.detail())
                : new Refused(refused.refusal(), refused.detail());
    }

    private static ReplayOutcome replay(Session store, StreamSession session, ReplayCursor cursor,
                                        AgentContract contract) {
        try {
            return EventReplay.from(store, session.operation(), cursor, session.generation(),
                    contract);
        } catch (final RepositoryException unreadable) {
            throw new IllegalStateException("the store could not be read for a resumption",
                    unreadable);
        }
    }

    private static ReplayOutcome current(Session store, StreamSession session,
                                         AgentContract contract) {
        try {
            return EventReplay.current(store, session.operation(), contract);
        } catch (final RepositoryException unreadable) {
            throw new IllegalStateException("the store could not be read for a reconnection",
                    unreadable);
        }
    }

    /**
     * The cursor one resumption identifier names, where it names one this build reads.
     *
     * @param resumption the identifier the client sent
     * @return the cursor, or nothing where the client sent none this build reads
     */
    public static Optional<ReplayCursor> cursorIn(String resumption) {
        if (resumption == null || resumption.isBlank()) {
            return Optional.empty();
        }
        final ReplayCursor.Outcome read = ReplayCursor.read(resumption);
        return read instanceof final ReplayCursor.Held held
                ? Optional.of(held.cursor())
                : Optional.empty();
    }
}
