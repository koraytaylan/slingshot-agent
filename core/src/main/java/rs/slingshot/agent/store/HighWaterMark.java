// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.wire.EventSequence;

/**
 * How far a subscriber has been shown, moved forwards by compare-and-set and never back.
 *
 * <p>A decrease is refused rather than clamped. Clamping looks kinder and is worse: a mark that
 * quietly refuses to move backwards leaves the caller believing it moved, and the caller's next
 * read starts where it thought it asked for. Told no, a caller can find out why it is behind; told
 * yes, it shows somebody an event they have already acted on.</p>
 *
 * <p>The mark is stored as how many events have been shown rather than as the newest sequence,
 * because the first sequence is zero and a stored zero would then be indistinguishable from a
 * subscriber that has been shown nothing.</p>
 */
public final class HighWaterMark {

    private HighWaterMark() {
    }

    /** Why a mark did not move. */
    public enum Refusal {
        /** There is no subscription record to move, so there is no promise to keep. */
        NO_RECORD,
        /** The mark would go backwards, and a subscriber does not unsee an event. */
        WOULD_GO_BACKWARDS,
        /** The store was busy for as long as this writer is willing to wait. */
        CONTENDED
    }

    /** What advancing did: the mark moved, or the one reason it did not. */
    public sealed interface Outcome permits Advanced, Refused {
    }

    /**
     * A mark that now stands further along than it did.
     *
     * @param to where it now stands
     * @param atUnixMilliseconds when it was moved
     */
    public record Advanced(EventSequence to, long atUnixMilliseconds) implements Outcome {
    }

    /**
     * A mark that stands exactly where it did.
     *
     * @param refusal why it did not move
     * @param detail what was observed, naming both values where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Moves one subscription's mark forwards, or leaves it exactly where it was.
     *
     * @param session the session to write under
     * @param identifier whose subscription
     * @param to the newest sequence the subscriber has now been shown
     * @param nowUnixMilliseconds what this side's clock says
     * @return what advancing it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome advance(Session session, SubscriptionRecord.Identifier identifier,
                                  EventSequence to, long nowUnixMilliseconds)
            throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(identifier);
        if (!session.nodeExists(path.path())) {
            return new Refused(Refusal.NO_RECORD, "there is no subscription at " + path.path()
                    + ", so there is nothing that promised anything");
        }
        final long shown = CompareAndSet.held(session.getNode(path.path()),
                SubscriptionRecord.EVENTS_SHOWN);
        final long asked = to.number() + 1;
        if (asked <= shown) {
            return new Refused(Refusal.WOULD_GO_BACKWARDS, "the mark stands at "
                    + SubscriptionRecord.cursorFor(shown) + " and " + to + " is not past it");
        }
        return written(session, path, shown, asked, to, nowUnixMilliseconds);
    }

    private static Outcome written(Session session, StatePath path, long shown, long asked,
                                   EventSequence to, long nowUnixMilliseconds)
            throws RepositoryException {
        final WriteOutcome moved = CompareAndSet.set(session, path,
                SubscriptionRecord.EVENTS_SHOWN, shown, asked);
        if (moved == WriteOutcome.VALUE_CHANGED) {
            return new Refused(Refusal.WOULD_GO_BACKWARDS, "the mark moved to something other than "
                    + shown + " while this move to " + to + " was being made");
        }
        if (moved != WriteOutcome.WRITTEN) {
            return new Refused(Refusal.CONTENDED,
                    "the store was busy for as long as this writer waits");
        }
        stamped(session, path, nowUnixMilliseconds);
        return new Advanced(to, nowUnixMilliseconds);
    }

    private static void stamped(Session session, StatePath path, long nowUnixMilliseconds)
            throws RepositoryException {
        final Node record = session.getNode(path.path());
        record.setProperty(SubscriptionRecord.LAST_ADVANCED_AT, nowUnixMilliseconds);
        session.save();
    }

    /**
     * Where one subscription's mark currently stands.
     *
     * @param session the session to read under
     * @param identifier whose subscription
     * @return where it stands, which is nothing shown yet where there is no record at all
     * @throws RepositoryException if the repository fails
     */
    public static SubscriptionRecord.Cursor read(Session session,
                                                 SubscriptionRecord.Identifier identifier)
            throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(identifier);
        return session.nodeExists(path.path())
                ? SubscriptionRecord.cursorFor(CompareAndSet.held(session.getNode(path.path()),
                        SubscriptionRecord.EVENTS_SHOWN))
                : SubscriptionRecord.Unread.NOTHING_SHOWN_YET;
    }

    /**
     * The one reason a mark did not move, where it did not.
     *
     * @param outcome what advancing it did
     * @return the refusal, or nothing where it moved
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
