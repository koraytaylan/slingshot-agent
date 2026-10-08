// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Numeric operator observations for one slow, authenticated, already-flushed lookup refusal.
 *
 * <p>Starts inside the servlet, after platform dispatch and the base request-shape check. State
 * dispatch ends when the service work begins; return covers resolver cleanup and any outer state
 * refusal after that work exits. The work flags distinguish entered and normally returned work.
 * Nothing from a request, record, caller or absolute clock reading enters an event. Successful,
 * anonymous and other refusal responses produce no event, so their response path is unchanged.</p>
 */
final class LookupTimings {

    private static final long NANOSECONDS_IN_A_MILLISECOND = 1_000_000;
    private static final long SLOW_NANOSECONDS = 1_000_000_000;
    private final LongSupplier source;
    private final long[] durations = new long[Phase.values().length];
    private final AtomicLong previousReading;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.SETUP);
    private final AtomicBoolean workEntered = new AtomicBoolean();
    private final AtomicBoolean workCompleted = new AtomicBoolean();
    private final AtomicBoolean refusalWritten = new AtomicBoolean();

    /**
     * Starts one lookup's observation with a monotonic nanosecond source.
     * @param source the duration source, whose absolute readings are never emitted
     */
    LookupTimings(LongSupplier source) {
        this.source = source;
        previousReading = new AtomicLong(source.getAsLong());
    }

    /** Begins state-session dispatch after authentication. */
    void dispatching() {
        advance(Phase.DISPATCH);
    }

    /** Ends state dispatch when service work begins generation inspection. */
    void generating() {
        workEntered.set(true);
        advance(Phase.GENERATION);
    }

    /** Begins caller ownership inspection after generation access is settled. */
    void owning() {
        advance(Phase.OWNER);
    }

    /** Begins retained snapshot inspection after ownership admission. */
    void snapshotting() {
        advance(Phase.SNAPSHOT);
    }

    /** Begins existing refusal logging or response writing. */
    void responding() {
        advance(Phase.RESPONSE);
    }

    /** Marks an opaque 404 only after its existing empty response was flushed. */
    void refused() {
        refusalWritten.set(true);
    }

    /** Marks normally returned state work independently of later resolver cleanup. */
    void completedWork() {
        workCompleted.set(true);
    }

    /** Ends state work before service return or cleanup, including exceptional exits. */
    void returning() {
        advance(Phase.RETURN);
    }

    /**
     * Holds fixed numeric fields only for an already-flushed slow opaque refusal.
     * @return the numeric fields or absence for other responses and observations below one second
     */
    Optional<SequencedMap<String, String>> slowFields() {
        if (!refusalWritten.get()) {
            return Optional.empty();
        }
        advance(phase.get());
        if (Arrays.stream(durations).reduce(0, LookupTimings::added) < SLOW_NANOSECONDS) {
            return Optional.empty();
        }
        final var fields = new LinkedHashMap<String, String>();
        Arrays.stream(Phase.values()).forEach(one -> fields.put(one.metric,
                String.valueOf(durations[one.ordinal()] / NANOSECONDS_IN_A_MILLISECOND)));
        fields.put("lookup_work_entered", workEntered.get() ? "1" : "0");
        fields.put("lookup_work_completed", workCompleted.get() ? "1" : "0");
        return Optional.of(fields);
    }

    private void advance(Phase next) {
        final long reading = source.getAsLong();
        final long duration = reading - previousReading.get();
        if (duration >= 0) {
            previousReading.set(reading);
            durations[phase.get().ordinal()] = added(durations[phase.get().ordinal()], duration);
        }
        phase.set(next);
    }

    private static long added(long held, long duration) {
        return held > Long.MAX_VALUE - duration ? Long.MAX_VALUE : held + duration;
    }

    private enum Phase {
        SETUP("lookup_setup_ms"),
        DISPATCH("lookup_dispatch_ms"),
        GENERATION("lookup_generation_ms"),
        OWNER("lookup_owner_ms"),
        SNAPSHOT("lookup_snapshot_ms"),
        RESPONSE("lookup_response_ms"),
        RETURN("lookup_return_ms");

        private final String metric;

        Phase(String metric) {
            this.metric = metric;
        }
    }
}
