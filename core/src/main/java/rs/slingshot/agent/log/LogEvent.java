// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * One thing worth writing down, as fields rather than as a sentence.
 *
 * <p>The operation is a field of its own rather than one of the named ones, because it is the field
 * that makes a log line findable: somebody with a console row searches for it, and somebody with a
 * line pastes it into the console. Everything else about the line is negotiable; that is not.</p>
 *
 * @param message what happened, in words nobody interpolated anything into
 * @param operation which operation it happened during, or {@link #OUTSIDE_AN_OPERATION}
 * @param fields what else is worth knowing, by name
 */
public record LogEvent(String message, String operation, SequencedMap<String, String> fields) {

    /** What the operation says on a line written when no operation was in scope. */
    public static final String OUTSIDE_AN_OPERATION = "";

    /** Holds an event whose fields nothing can change afterwards. */
    public LogEvent {
        fields = new LinkedHashMap<>(fields);
    }

    /**
     * What else is worth knowing.
     *
     * @return the fields by name, which nothing may add to
     */
    @Override
    public SequencedMap<String, String> fields() {
        return Collections.unmodifiableSequencedMap(fields);
    }

    /**
     * One event about nothing in particular.
     *
     * @param message what happened
     * @return the event
     */
    public static LogEvent of(String message) {
        return new LogEvent(message, OUTSIDE_AN_OPERATION, new LinkedHashMap<>());
    }

    /**
     * The same event, said to be about one operation.
     *
     * @param operationIdentifier which operation
     * @return the event
     */
    public LogEvent during(String operationIdentifier) {
        return new LogEvent(message, operationIdentifier, fields);
    }

    /**
     * The same event with one more field.
     *
     * @param name what the field is called
     * @param value what it holds, which the writer will put through the redaction rule
     * @return the event
     */
    public LogEvent with(String name, String value) {
        final SequencedMap<String, String> held = new LinkedHashMap<>(fields);
        held.put(name, value);
        return new LogEvent(message, operation, held);
    }
}
