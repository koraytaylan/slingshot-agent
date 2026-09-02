// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.ReplayCursor;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * The exact bytes a subscriber reads, or the bound that ended the stream instead.
 *
 * <p>Nothing partial is the property under test, and it is proved the only way it can be: by
 * looking at what the encoder produced at every refusal and finding nothing at all. An encoder that
 * emitted a shortened event would pass a test that only checked the refusal.</p>
 */
final class EventEncoderTest {

    private static final AgentContract CONTRACT = contract();

    @Test
    @DisplayName("an event is encoded as the fields a decoder reads, in the order it reads them")
    void aneventIsEncodedAsThefieldsAdecoderReads() {
        final EventEncoder.Encoded encoded = assertInstanceOf(EventEncoder.Encoded.class,
                EventEncoder.encode(event(JobEventKind.STARTED, 1), cursor(1, 1), document(1),
                        EventEncoder.Buffered.NOTHING, CONTRACT),
                "an event this build produced was refused");
        assertEquals("id:1:1" + EventEncoder.LINE_END
                        + "event:started" + EventEncoder.LINE_END
                        + "data:" + document(1) + EventEncoder.LINE_END + EventEncoder.LINE_END,
                encoded.wire(), "the wire form is not the one a decoder reads");
        assertTrue(encoded.bytes() > 0);
        assertFalse(EventEncoder.isAheartbeat(encoded.wire()),
                "an event was encoded as the absence of news");
    }

    @Test
    @DisplayName("the identifier carries the incarnation and the sequence together")
    void theidentifierCarriesBoth() {
        final EventEncoder.Encoded encoded = assertInstanceOf(EventEncoder.Encoded.class,
                EventEncoder.encode(event(JobEventKind.ACCEPTED, 0), cursor(2, 7), document(0),
                        EventEncoder.Buffered.NOTHING, CONTRACT));
        assertTrue(encoded.wire().contains("id:2:7"),
                "the cursor does not name both the incarnation and the sequence: "
                        + encoded.wire());
        assertEquals("2:7", cursor(2, 7).rendered(),
                "a cursor carrying only a sequence would be ambiguous the moment a store rotated");
    }

    @Test
    @DisplayName("a line, an event, and a buffer past their bounds each end the stream, named")
    void thethreeBoundsEachEndTheStream() {
        final long lineBound = CONTRACT.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_LINE_BYTES);
        final String atTheLine = "x".repeat((int) lineBound - "data:".length());
        assertInstanceOf(EventEncoder.Encoded.class,
                EventEncoder.encode(event(JobEventKind.PROGRESS, 2), cursor(1, 2), atTheLine,
                        EventEncoder.Buffered.NOTHING, CONTRACT),
                "a line at exactly the bound ended the stream");
        final EventEncoder.Refused line = EventEncoder.refusalIn(
                EventEncoder.encode(event(JobEventKind.PROGRESS, 2), cursor(1, 2),
                        atTheLine + "x", EventEncoder.Buffered.NOTHING, CONTRACT)).orElseThrow();
        assertEquals(StreamRefusal.LINE_TOO_LONG, line.refusal());
        assertTrue(line.detail().contains(String.valueOf(lineBound)), line.detail());

        final long bufferBound =
                CONTRACT.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_BUFFER_BYTES);
        final EventEncoder.Refused buffer = EventEncoder.refusalIn(
                EventEncoder.encode(event(JobEventKind.PROGRESS, 2), cursor(1, 2), document(2),
                        new EventEncoder.Buffered(bufferBound), CONTRACT)).orElseThrow();
        assertEquals(StreamRefusal.BUFFER_FULL, buffer.refusal());
        assertTrue(buffer.detail().contains(String.valueOf(bufferBound)), buffer.detail());
        assertInstanceOf(EventEncoder.Encoded.class,
                EventEncoder.encode(event(JobEventKind.PROGRESS, 2), cursor(1, 2), document(2),
                        new EventEncoder.Buffered(0), CONTRACT),
                "a stream holding nothing refused an event anyway");
    }

    @Test
    @DisplayName("an event larger than an event may be ends the stream rather than being cut")
    void aneventLargerThanAneventMayBeEndsTheStream() {
        final long eventBound = CONTRACT.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_BYTES);
        final long lineBound = CONTRACT.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_LINE_BYTES);
        final int lines = (int) (eventBound / lineBound) + 2;
        final String many = String.join("\n",
                java.util.Collections.nCopies(lines, "y".repeat((int) lineBound - 16)));
        final EventEncoder.Refused refused = EventEncoder.refusalIn(
                EventEncoder.encode(event(JobEventKind.PROGRESS, 3), cursor(1, 3), many,
                        EventEncoder.Buffered.NOTHING, CONTRACT)).orElseThrow();
        assertEquals(StreamRefusal.EVENT_TOO_LARGE, refused.refusal());
        assertTrue(refused.detail().contains(String.valueOf(eventBound)), refused.detail());
    }

    @Test
    @DisplayName("a cursor longer than a cursor may be ends the stream rather than being cut")
    void acursorLongerThanAcursorMayBeEndsTheStream() {
        // A cursor is a generation and a sequence, so it cannot reach the shipped bound of
        // ninety-six bytes. The check is proved against a contract shrunk to where it can.
        final AgentContract narrow =
                contractWith("maximum_agent_operation_identifier_bytes", 3);
        assertInstanceOf(EventEncoder.Encoded.class,
                EventEncoder.encode(event(JobEventKind.STARTED, 1), cursor(1, 1), document(1),
                        EventEncoder.Buffered.NOTHING, narrow),
                "a cursor at exactly the bound ended the stream");
        final EventEncoder.Refused refused = EventEncoder.refusalIn(
                EventEncoder.encode(event(JobEventKind.STARTED, 10), cursor(1, 10), document(10),
                        EventEncoder.Buffered.NOTHING, narrow)).orElseThrow();
        assertEquals(StreamRefusal.IDENTIFIER_TOO_LONG, refused.refusal());
        assertEquals(4, StreamRefusal.values().length, "a stream refusal was added or lost");
        assertEquals(StreamRefusal.BUFFER_FULL, StreamRefusal.named("buffer_full").orElseThrow());
        assertTrue(StreamRefusal.named("something_else").isEmpty());
    }

    @Test
    @DisplayName("a payload carrying line breaks is encoded so a decoder recovers it exactly")
    void apayloadWithLineBreaksIsRecoveredExactly() {
        final String broken = "one\ntwo\nthree";
        final EventEncoder.Encoded encoded = assertInstanceOf(EventEncoder.Encoded.class,
                EventEncoder.encode(event(JobEventKind.PROGRESS, 4), cursor(1, 4), broken,
                        EventEncoder.Buffered.NOTHING, CONTRACT));
        assertEquals(List.of("data:one", "data:two", "data:three"),
                encoded.wire().lines().filter(line -> line.startsWith("data:")).toList(),
                "a payload with line breaks was not split the way a decoder joins it");
        assertEquals(broken, encoded.wire().lines()
                        .filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring("data:".length()))
                        .reduce((first, second) -> first + "\n" + second).orElseThrow(),
                "what a decoder would join is not what was sent");
    }

    @Test
    @DisplayName("a heartbeat is the absence of news and can never carry a cursor")
    void aheartbeatIsTheAbsenceOfNews() {
        assertTrue(EventEncoder.isAheartbeat(EventEncoder.heartbeat()),
                "a heartbeat is not encoded as one");
        assertFalse(EventEncoder.heartbeat().contains(EventEncoder.IDENTIFIER_FIELD + ":"),
                "a heartbeat carries a cursor, which would let it advance one");
        assertFalse(EventEncoder.heartbeat().contains(EventEncoder.DATA_FIELD + ":"),
                "a heartbeat carries data, which would make it news");
        assertTrue(EventEncoder.heartbeat().endsWith(EventEncoder.LINE_END + EventEncoder.LINE_END),
                "a heartbeat does not end the way a decoder expects");
    }

    private static JobEvent event(JobEventKind kind, long sequence) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobEvent.GENERATION, new DocumentValue.Whole(EventStoreGeneration.FIRST));
        members.put(JobEvent.IDENTIFIER, new DocumentValue.Text(
                Digest.of("an operation".getBytes(StandardCharsets.UTF_8)).rendered()));
        members.put(JobEvent.KIND, new DocumentValue.Text(kind.spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(sequence));
        return assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(new DocumentValue.Mapping(members), generation(), CONTRACT),
                kind + " is not an event").event();
    }

    private static String document(long sequence) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobEvent.GENERATION, new DocumentValue.Whole(EventStoreGeneration.FIRST));
        members.put(JobEvent.IDENTIFIER, new DocumentValue.Text(
                Digest.of("an operation".getBytes(StandardCharsets.UTF_8)).rendered()));
        members.put(JobEvent.KIND, new DocumentValue.Text(JobEventKind.PROGRESS.spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(sequence));
        return assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(new DocumentValue.Mapping(members)),
                "the event has no canonical form").rendered();
    }

    private static ReplayCursor cursor(long generation, long sequence) {
        return assertInstanceOf(ReplayCursor.Held.class, ReplayCursor.of(generation, sequence),
                generation + ":" + sequence + " is not a cursor").cursor();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static AgentContract contractWith(String bound, long value) {
        final StringBuilder rewritten = new StringBuilder();
        readSupportContract().lines().forEach(line -> {
            final String name = line.contains("=") ? line.substring(0, line.indexOf('=')).strip()
                    : "";
            rewritten.append(bound.equals(name) ? bound + " = " + value : line).append('\n');
        });
        final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        return assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load(document, AgentContract.digestOf(document)),
                "the shrunken contract is not one this build reads").contract();
    }

    private static String readSupportContract() {
        try {
            java.nio.file.Path walked = java.nio.file.Path.of("").toAbsolutePath();
            while (walked != null && !java.nio.file.Files.exists(walked.resolve("policy"))) {
                walked = walked.getParent();
            }
            return java.nio.file.Files.readString(java.util.Objects.requireNonNull(walked,
                            "this suite is not inside the repository")
                    .resolve("support/agent-contract.toml"), StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
