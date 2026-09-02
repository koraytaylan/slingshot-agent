// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.store.ReplayCursor;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.wire.JobEvent;

/**
 * One stream, from the first thing written to the last, and the one path every ending goes through.
 *
 * <p>Four things end a stream: it completes, the client goes away, the session reaches the bound
 * this side publishes, or something faults. All four leave through the same ending, because an
 * ending that skipped it would leak the room the stream was admitted into and nobody would notice
 * until an instance had run out of rooms.</p>
 *
 * <p>News is looked for on the heartbeat's own interval. A stream that polled faster would be a
 * stream charging a repository for the privilege of having nothing to say, and the interval is the
 * one number the contract already declares about how often this side speaks.</p>
 *
 * <p>What it holds is whose stream it is and what it is bounded by, and nothing else. The bytes and
 * the repository session are somebody else's to open and close, handed in for the length of one
 * stream: a writer that kept them would be a writer that outlived the request it belongs to.</p>
 *
 * @param session whose stream this is
 * @param contract the authenticated contract, which declares every bound
 */
public record StreamWriter(StreamSession session, AgentContract contract) {

    /** How one stream ended, of which there are four ways and no fifth. */
    public enum Ending {
        /** It reached the bound this side publishes and closed cleanly. */
        REACHED_THE_SESSION_BOUND,
        /** There was nothing left to serve at all, which is an ending rather than a silence. */
        NOTHING_LEFT_TO_SERVE,
        /** The client stopped reading, which is an ending like any other. */
        THE_CLIENT_WENT_AWAY,
        /** The store stopped answering, so the stream ends rather than stalling. */
        THE_STORE_STOPPED_ANSWERING
    }

    /** Whether the bytes stopped by being closed or by the client already being gone. */
    private enum Closing {
        /** The stream was closed. */
        CLOSED,
        /** There was nobody left to close it to. */
        THE_CLIENT_WAS_ALREADY_GONE
    }

    /** Whether a round of looking had anything to say. */
    private enum Said {
        /** It wrote something, so a heartbeat is not due yet. */
        SOMETHING,
        /** It wrote nothing at all. */
        NOTHING
    }

    /** Whether the stream goes on after a round. */
    private enum Standing {
        /** It goes on. */
        GOING,
        /** It ends, cleanly, after the final heartbeat. */
        ENDED
    }

    /**
     * What one round of looking left behind.
     *
     * @param cursor where this stream has now delivered up to
     * @param said whether it wrote anything
     * @param standing whether the stream goes on
     */
    private record Round(String cursor, Said said, Standing standing) {
    }

    /**
     * Writes one stream until it ends, and releases everything it held however it ends.
     *
     * @param writer where the bytes go
     * @param store the caller's own session
     * @param ticker what time it is, and how this stream waits
     * @param resumption the identifier the client resumed with, empty where it sent none
     * @return how it ended
     */
    public Ending serve(Writer writer, Session store, StreamTicker ticker, String resumption) {
        try {
            return written(writer, store, ticker, resumption);
        } finally {
            // One path for every ending there is: the bytes stop, and then the room goes back. An
            // ending that skipped it would leak a room nobody would miss until an instance had run
            // out of them.
            release(store, closed(writer));
        }
    }

    private Ending written(Writer writer, Session store, StreamTicker ticker, String resumption) {
        try {
            return held(writer, store, ticker, resumption);
        } catch (final IOException gone) {
            // The client stopped reading. That is an ending like any other rather than a fault:
            // nothing is owed to somebody who is not there.
            return Ending.THE_CLIENT_WENT_AWAY;
        } catch (final RepositoryException unreadable) {
            // A store that stopped answering ends the stream rather than stalling it, because a
            // subscriber waiting on a stream that will never speak again waits forever.
            return Ending.THE_STORE_STOPPED_ANSWERING;
        }
    }

    private Ending held(Writer writer, Session store, StreamTicker ticker, String resumption)
            throws IOException, RepositoryException {
        final long openedAt = ticker.elapsedMilliseconds();
        String cursor = resumption;
        long lastSpokeAt = openedAt;
        Standing standing = Standing.GOING;
        while (standing == Standing.GOING
                && !SessionBound.isReached(openedAt, ticker.elapsedMilliseconds(), contract)) {
            final Round round = round(writer, store, cursor);
            cursor = round.cursor();
            standing = round.standing();
            lastSpokeAt = round.said() == Said.SOMETHING
                    ? ticker.elapsedMilliseconds() : lastSpokeAt;
            lastSpokeAt = beat(writer, ticker, lastSpokeAt);
            ticker.pause(Heartbeat.intervalMilliseconds(contract));
        }
        // The last thing on the wire is a heartbeat and then a close, so the client's decoder reads
        // an ordinary ending rather than a severed connection it has to guess about.
        write(writer, Heartbeat.bytes());
        return standing == Standing.GOING
                ? Ending.REACHED_THE_SESSION_BOUND
                : Ending.NOTHING_LEFT_TO_SERVE;
    }

    private long beat(Writer writer, StreamTicker ticker, long lastSpokeAt) throws IOException {
        if (!Heartbeat.isDue(lastSpokeAt, ticker.elapsedMilliseconds(), contract)) {
            return lastSpokeAt;
        }
        write(writer, Heartbeat.bytes());
        return ticker.elapsedMilliseconds();
    }

    private Round round(Writer writer, Session store, String cursor)
            throws IOException, RepositoryException {
        // Another session's commit is invisible to this one until it is refreshed, so a stream that
        // never refreshed would be a stream that reported the moment it opened, forever.
        store.refresh(false);
        final StreamResumption.Outcome resumed =
                StreamResumption.from(store, session, cursor, contract);
        if (resumed instanceof final StreamResumption.Serving serving) {
            return served(writer, serving, cursor);
        }
        if (resumed instanceof final StreamResumption.Resetting resetting) {
            return reset(writer, resetting);
        }
        // Nothing left to serve at all: an operation this store no longer holds is an ending rather
        // than a silence, and a subscriber told nothing would wait for news that cannot come.
        return new Round(cursor, Said.NOTHING, Standing.ENDED);
    }

    private Round reset(Writer writer, StreamResumption.Resetting resetting) throws IOException {
        final Optional<String> notice = ResetNotice.bytes(session, resetting.current());
        if (notice.isEmpty()) {
            return new Round(at(resetting.current()), Said.NOTHING, Standing.ENDED);
        }
        write(writer, notice.get());
        return new Round(at(resetting.current()), Said.SOMETHING, Standing.GOING);
    }

    private Round served(Writer writer, StreamResumption.Serving serving, String cursor)
            throws IOException {
        String at = opening(writer, serving, cursor);
        long buffered = 0;
        for (final String document : serving.events()) {
            final Optional<JobEvent> event = eventIn(document);
            if (event.isEmpty()) {
                continue;
            }
            final ReplayCursor position =
                    new ReplayCursor(session.generation(), event.get().sequence());
            final EventEncoder.Outcome encoded = EventEncoder.encode(event.get(), position,
                    document, new EventEncoder.Buffered(buffered), contract);
            if (!(encoded instanceof final EventEncoder.Encoded wire)) {
                // A bound ends the stream rather than shortening an event: a truncated event is not
                // a smaller event but an unparseable one, and the subscriber cannot tell which.
                return new Round(at, Said.SOMETHING, Standing.ENDED);
            }
            write(writer, wire.wire());
            buffered = buffered + wire.bytes();
            at = position.rendered();
        }
        return new Round(at, serving.events().isEmpty() && at.equals(cursor)
                ? Said.NOTHING
                : Said.SOMETHING, Standing.GOING);
    }

    /**
     * What a subscriber with no position at all is told before it is told anything else.
     *
     * <p>Where it is currently, which is the snapshot: a subscriber served only the events after a
     * snapshot it was never shown would be a subscriber quietly missing everything that happened
     * before it connected, and believing it had seen it all. Where it already has a position,
     * nothing is written, because everything at or below it has been shown once.</p>
     *
     * @param writer where the bytes go
     * @param serving what the store served
     * @param cursor where the client said it was, empty where it said nothing
     * @return where this stream has now delivered up to
     * @throws IOException if the client is gone
     */
    private String opening(Writer writer, StreamResumption.Serving serving, String cursor)
            throws IOException {
        if (!cursor.isEmpty()) {
            return cursor;
        }
        final Optional<String> notice = ResetNotice.bytes(session, serving.current());
        if (notice.isEmpty()) {
            return cursor;
        }
        write(writer, notice.get());
        return at(serving.current());
    }

    private String at(SnapshotStore.Materialised current) {
        return current instanceof final SnapshotStore.Known known
                ? new ReplayCursor(session.generation(), known.snapshot().sequence()).rendered()
                : "";
    }

    private Optional<JobEvent> eventIn(String document) {
        final BoundedDocumentReader.Outcome read = BoundedDocumentReader.read(
                document.getBytes(StandardCharsets.UTF_8),
                BoundedDocumentReader.Bounds.from(contract));
        if (!(read instanceof final BoundedDocumentReader.Read value)) {
            return Optional.empty();
        }
        final JobEvent.Outcome held =
                JobEvent.read(value.value(), session.generation(), contract);
        return held instanceof final JobEvent.Held event
                ? Optional.of(event.event())
                : Optional.empty();
    }

    private static void write(Writer writer, String wire) throws IOException {
        writer.write(wire);
        // Flushed as it is written, because a buffered stream is not a slow stream: it is a stream
        // that delivers nothing at all until it ends.
        writer.flush();
    }

    private static Closing closed(Writer writer) {
        try {
            writer.close();
            return Closing.CLOSED;
        } catch (final IOException gone) {
            // A client that is already gone cannot be told the stream ended.
            return Closing.THE_CLIENT_WAS_ALREADY_GONE;
        }
    }

    private void release(Session store, Closing closing) {
        try {
            StreamAdmission.close(store, session.caller(), contract);
        } catch (final RepositoryException unreadable) {
            // A room that could not be given back is one this instance will hold until the sweep
            // reclaims it, and an instance quietly losing rooms is what runs out of them.
            throw new IllegalStateException("a stream that ended " + closing
                    + " could not give its room back", unreadable);
        }
    }
}
