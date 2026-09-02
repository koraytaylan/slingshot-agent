// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.store.ReplayCursor;
import rs.slingshot.agent.wire.JobEvent;

/**
 * One event as the client's own decoder reads it, or nothing at all.
 *
 * <p>Nothing partial is ever produced. Every bound is checked against the whole event before a byte
 * of it exists, because an event cut at a bound is not a smaller event: it is one the decoder
 * cannot frame, and a subscriber that received one would have no way to tell a short event from a
 * lost connection.</p>
 *
 * <p>The identifier carries the incarnation and the sequence together. A cursor that carried only a
 * sequence would be ambiguous the moment the store rotated — the same number in two incarnations
 * naming two different positions — and a reconnection would resume somewhere nobody meant.</p>
 */
public final class EventEncoder {

    /** The field an event's document arrives in. */
    public static final String DATA_FIELD = "data";

    /** The field an event's own name arrives in. */
    public static final String EVENT_FIELD = "event";

    /** The field the cursor arrives in. */
    public static final String IDENTIFIER_FIELD = "id";

    /** What separates a field from its value, and what begins a comment. */
    public static final String FIELD_SEPARATOR = ":";

    /** What one line ends with. */
    public static final String LINE_END = "\n";

    /** What this side sends to say only that the connection is alive. */
    public static final String HEARTBEAT = FIELD_SEPARATOR + " alive" + LINE_END + LINE_END;

    /** The media type one stream is, and the only one a client accepts. */
    public static final String MEDIA_TYPE = "text/event-stream";

    private EventEncoder() {
    }

    /** The result of encoding: the wire form, or the bound that ended the stream. */
    public sealed interface Outcome permits Encoded, Refused {
    }

    /**
     * One event as the bytes a decoder reads.
     *
     * @param wire the whole event, including the blank line that ends it
     */
    public record Encoded(String wire) implements Outcome {

        /**
         * How many bytes this event occupies.
         *
         * @return the bytes
         */
        public long bytes() {
            return wire.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * No event, and the bound that ended the stream instead.
     *
     * @param refusal which bound
     * @param detail what was observed, naming the bound and what was reached
     */
    public record Refused(StreamRefusal refusal, String detail) implements Outcome {
    }

    /**
     * What one stream is already holding for a reader that is not reading.
     *
     * @param buffered how many bytes are held
     */
    public record Buffered(long buffered) {

        /** Nothing held at all, which is where every stream starts. */
        public static final Buffered NOTHING = new Buffered(0);
    }

    /**
     * Encodes one event, or says which bound ended the stream.
     *
     * @param event the event
     * @param cursor where this event sits in the subscription's own order
     * @param document the event's canonical bytes, which are what a subscriber reads
     * @param held what this stream is already holding
     * @param contract the authenticated contract, which declares all four bounds
     * @return the wire form, or the bound that ended the stream
     */
    public static Outcome encode(JobEvent event, ReplayCursor cursor, String document,
                                 Buffered held, AgentContract contract) {
        final String identifier = cursor.rendered();
        final long identifierBound =
                contract.value(ContractLimit.MAXIMUM_AGENT_OPERATION_IDENTIFIER_BYTES);
        if (bytes(identifier) > identifierBound) {
            return new Refused(StreamRefusal.IDENTIFIER_TOO_LONG, "a cursor holds at most "
                    + identifierBound + " bytes and this holds " + bytes(identifier));
        }
        final List<String> lines = new ArrayList<>();
        lines.add(IDENTIFIER_FIELD + FIELD_SEPARATOR + identifier);
        lines.add(EVENT_FIELD + FIELD_SEPARATOR + event.kind().spelling());
        for (final String part : document.split("\n", -1)) {
            lines.add(DATA_FIELD + FIELD_SEPARATOR + part);
        }
        return bounded(lines, held, contract);
    }

    private static Outcome bounded(List<String> lines, Buffered held, AgentContract contract) {
        final long lineBound = contract.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_LINE_BYTES);
        for (final String line : lines) {
            if (bytes(line) > lineBound) {
                return new Refused(StreamRefusal.LINE_TOO_LONG, "one line holds at most "
                        + lineBound + " bytes and this reached " + bytes(line));
            }
        }
        final String wire = String.join(LINE_END, lines) + LINE_END + LINE_END;
        final long eventBound = contract.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_BYTES);
        if (bytes(wire) > eventBound) {
            return new Refused(StreamRefusal.EVENT_TOO_LARGE, "one event holds at most "
                    + eventBound + " bytes and this reached " + bytes(wire));
        }
        final long bufferBound =
                contract.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_BUFFER_BYTES);
        if (held.buffered() + bytes(wire) > bufferBound) {
            return new Refused(StreamRefusal.BUFFER_FULL, "this stream holds at most "
                    + bufferBound + " bytes for a reader that is not reading, and this event would"
                    + " take it to " + (held.buffered() + bytes(wire)));
        }
        return new Encoded(wire);
    }

    /**
     * What this side sends to say only that the connection is alive.
     *
     * <p>A comment rather than an event, because the client's decoder reads a comment as a
     * heartbeat and never as news — which is what stops a heartbeat from advancing a cursor.</p>
     *
     * @return the wire form
     */
    public static String heartbeat() {
        return HEARTBEAT;
    }

    /**
     * Whether one piece of a stream is a heartbeat rather than an event.
     *
     * @param wire the bytes
     * @return whether a decoder reads it as the absence of news
     */
    public static boolean isAheartbeat(String wire) {
        return wire.startsWith(FIELD_SEPARATOR);
    }

    private static long bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The bound that ended the stream, where one did.
     *
     * @param outcome what encoding produced
     * @return the refusal, or nothing where there is an event
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
