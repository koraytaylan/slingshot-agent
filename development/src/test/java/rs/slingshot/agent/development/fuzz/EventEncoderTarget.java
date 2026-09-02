// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.store.ReplayCursor;
import rs.slingshot.agent.stream.EventEncoder;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * Arbitrary text carried through the encoder every event leaves by.
 *
 * <p>Two properties. Every encoded event decodes back to the same event — which for a line-oriented
 * wire means the payload survives whatever newlines and separators it happened to contain, since a
 * document carrying a line end is the input that quietly turns one event into two. And no input
 * produces output past a bound: a stream that grew past one would be ended mid-flight by something
 * that cannot say why, which reads to a client as the connection failing.</p>
 */
public final class EventEncoderTarget implements FuzzTarget {

    /** How the fuzzing tool reaches this target. */
    private static final EventEncoderTarget TARGET = new EventEncoderTarget();

    /** The operation every encoded event here belongs to. */
    private static final String THE_OPERATION =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    private final AgentContract contract;

    /** Holds one target bound by the contract this build authenticated. */
    public EventEncoderTarget() {
        this.contract = ((AgentContract.Loaded) AgentContract.load()).contract();
    }

    /**
     * The entry point the fuzzing tool calls.
     *
     * @param input arbitrary bytes
     */
    public static void fuzzerTestOneInput(byte[] input) {
        final FuzzOutcome outcome = TARGET.of(input);
        if (outcome instanceof final FuzzOutcome.Broken broken) {
            throw new AssertionError(broken.property() + ": " + broken.detail());
        }
    }

    @Override
    public FuzzOutcome of(byte[] input) {
        final String document = new String(input, StandardCharsets.UTF_8);
        final Attempted.Answered<EventEncoder.Outcome> asked = Attempted.of(() ->
                EventEncoder.encode(event(), cursor(), document, EventEncoder.Buffered.NOTHING,
                        contract));
        if (asked.threw()) {
            return FuzzOutcome.broken("the encoder answers rather than throws",
                    "it threw " + asked.threwWhat());
        }
        final EventEncoder.Outcome outcome = asked.value().orElseThrow();
        if (!(outcome instanceof final EventEncoder.Encoded encoded)) {
            return FuzzOutcome.held();
        }
        final FuzzOutcome bounded = withinBounds(encoded);
        return bounded instanceof FuzzOutcome.Broken ? bounded : decodesBack(encoded, document);
    }

    /**
     * That nothing the encoder produced crosses a bound it declares.
     *
     * @param encoded what it produced
     * @return whether the property held
     */
    private FuzzOutcome withinBounds(EventEncoder.Encoded encoded) {
        final long lineBound = contract.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_LINE_BYTES);
        final long eventBound = contract.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_BYTES);
        if (encoded.bytes() > eventBound) {
            return FuzzOutcome.broken("no encoded event crosses the event bound",
                    encoded.bytes() + " bytes were produced under a bound of " + eventBound);
        }
        return Arrays.stream(encoded.wire().split(EventEncoder.LINE_END, -1))
                .anyMatch(line -> line.getBytes(StandardCharsets.UTF_8).length > lineBound)
                ? FuzzOutcome.broken("no encoded line crosses the line bound",
                        "a line longer than " + lineBound + " bytes was produced")
                : FuzzOutcome.held();
    }

    /**
     * That the payload comes back exactly as it went in, newlines and all.
     *
     * @param encoded what the encoder produced
     * @param document what it was given
     * @return whether the property held
     */
    private static FuzzOutcome decodesBack(EventEncoder.Encoded encoded, String document) {
        final List<String> data = Arrays.stream(
                        encoded.wire().split(EventEncoder.LINE_END, -1))
                .filter(line -> line.startsWith(EventEncoder.DATA_FIELD
                        + EventEncoder.FIELD_SEPARATOR))
                .map(line -> line.substring((EventEncoder.DATA_FIELD
                        + EventEncoder.FIELD_SEPARATOR).length()))
                .toList();
        return document.equals(String.join("\n", data))
                ? FuzzOutcome.held()
                : FuzzOutcome.broken("every encoded event decodes to the event it encoded",
                        "what came back was not what went in");
    }

    private JobEvent event() {
        return ((JobEvent.Held) JobEvent.read(document(), generation(), contract)).event();
    }

    private static rs.slingshot.agent.json.DocumentValue document() {
        final java.util.SequencedMap<String, rs.slingshot.agent.json.DocumentValue> members =
                new java.util.LinkedHashMap<>();
        members.put(JobEvent.GENERATION, new rs.slingshot.agent.json.DocumentValue.Whole(1));
        members.put(JobEvent.IDENTIFIER,
                new rs.slingshot.agent.json.DocumentValue.Text(THE_OPERATION));
        members.put(JobEvent.KIND,
                new rs.slingshot.agent.json.DocumentValue.Text(JobEventKind.values()[0].spelling()));
        members.put(JobEvent.SEQUENCE, new rs.slingshot.agent.json.DocumentValue.Whole(1));
        return new rs.slingshot.agent.json.DocumentValue.Mapping(members);
    }

    private static ReplayCursor cursor() {
        return ((ReplayCursor.Held) ReplayCursor.of(1, 1)).cursor();
    }

    private static EventStoreGeneration generation() {
        return ((EventStoreGeneration.Held) EventStoreGeneration.of(1)).generation();
    }
}
