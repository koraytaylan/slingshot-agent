// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.wire.JobEvent;

/**
 * What a subscriber is sent when its cursor cannot be honoured.
 *
 * <p>Never an empty result and never a different position. A subscriber served from somewhere else
 * silently would believe it had seen everything in between, and everything it does afterwards would
 * be built on that belief; a subscriber served nothing would wait. So it is told, in an event its
 * own decoder reads, and the thing it is told carries what is currently true so it can start from
 * there.</p>
 */
public final class ResetNotice {

    /** What this side calls the event that says a cursor cannot be honoured. */
    public static final String EVENT_NAME = "reset";

    /** The member the incarnation now being served is carried in. */
    public static final String GENERATION = JobEvent.GENERATION;

    /** The member the operation is carried in. */
    public static final String IDENTIFIER = JobEvent.IDENTIFIER;

    /** The member what is currently true is carried in. */
    public static final String KIND = JobEvent.KIND;

    /** The member the sequence to resynchronise from is carried in. */
    public static final String SEQUENCE = JobEvent.SEQUENCE;

    private ResetNotice() {
    }

    /**
     * The bytes one reset is on the wire.
     *
     * @param session whose stream it is
     * @param current what is currently true about the operation
     * @return the wire form, or nothing where this build cannot write it
     */
    public static Optional<String> bytes(StreamSession session,
                                         SnapshotStore.Materialised current) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(GENERATION, new DocumentValue.Whole(session.generation().number()));
        members.put(IDENTIFIER, new DocumentValue.Text(identifierOf(session)));
        members.put(KIND, new DocumentValue.Text(
                current instanceof final SnapshotStore.Known known
                        ? known.snapshot().kind().spelling()
                        : ""));
        members.put(SEQUENCE, new DocumentValue.Whole(
                current instanceof final SnapshotStore.Known known
                        ? known.snapshot().sequence().number()
                        : 0));
        final CanonicalByteWriter.Outcome written =
                CanonicalByteWriter.write(new DocumentValue.Mapping(members));
        if (!(written instanceof final CanonicalByteWriter.Written bytes)) {
            return Optional.empty();
        }
        return Optional.of(EventEncoder.EVENT_FIELD + EventEncoder.FIELD_SEPARATOR + EVENT_NAME
                + EventEncoder.LINE_END
                + EventEncoder.DATA_FIELD + EventEncoder.FIELD_SEPARATOR + bytes.rendered()
                + EventEncoder.LINE_END + EventEncoder.LINE_END);
    }

    /**
     * Whether one piece of a stream is a reset.
     *
     * @param wire the bytes
     * @return whether a decoder reads it as a reset rather than as news
     */
    public static boolean isAreset(String wire) {
        return wire.startsWith(EventEncoder.EVENT_FIELD + EventEncoder.FIELD_SEPARATOR
                + EVENT_NAME);
    }

    private static String identifierOf(StreamSession session) {
        final String path = session.operation().path();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
