// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.log;

import java.util.List;
import java.util.function.Predicate;

/**
 * What turns one event into one line, and what it refuses to write.
 *
 * <p>Two rules and both of them are about what does not reach the line. A field whose value the
 * redaction corpus covers is replaced by a note that it was withheld — the corpus already exists
 * and this reuses it rather than growing a second idea of what a secret looks like. And a message
 * longer than the contract allows is refused rather than truncated, because a truncated log line is
 * one whose most interesting half is missing exactly when somebody is reading it.</p>
 *
 * <p>Refusing is safe here in a way it is not elsewhere: the line does not get written, and a line
 * that does not get written is a line nobody misreads. What gets written instead says which event
 * was refused and why, which is the same information minus the part that would not fit.</p>
 */
public final class AgentLog {

    private AgentLog() {
    }

    /** What a withheld field says instead of its value. */
    public static final String WITHHELD = "<withheld>";

    /** What separates a field's name from its value on a line. */
    public static final String ASSIGNS = "=";

    /** What separates one field from the next. */
    public static final String BETWEEN = " ";

    /** The field an operation identifier is written under, which is what makes a line findable. */
    public static final String OPERATION = "operation";

    /**
     * One line, with everything the redaction rule covers already gone.
     *
     * @param event what happened
     * @param covered whether one value is something the corpus covers
     * @param messageBound the most a message may be, which the contract states
     * @return the line
     */
    public static String lineOf(LogEvent event, Predicate<String> covered, long messageBound) {
        if (event.message().length() > messageBound) {
            return refusedLine(event, messageBound);
        }
        final StringBuilder line = new StringBuilder(event.message());
        if (!LogEvent.OUTSIDE_AN_OPERATION.equals(event.operation())) {
            line.append(BETWEEN).append(OPERATION).append(ASSIGNS).append(event.operation());
        }
        event.fields().forEach((name, value) -> line.append(BETWEEN).append(name).append(ASSIGNS)
                .append(covered.test(value) ? WITHHELD : value));
        return line.toString();
    }

    /**
     * What is written instead of a line that would not fit.
     *
     * <p>It carries the operation and the bound and no part of the message, because half a message
     * is the half somebody quotes.</p>
     *
     * @param event what happened
     * @param messageBound the most a message may be
     * @return the line
     */
    private static String refusedLine(LogEvent event, long messageBound) {
        final String said = "a log message longer than the " + messageBound
                + " one may be was refused rather than truncated";
        return LogEvent.OUTSIDE_AN_OPERATION.equals(event.operation()) ? said
                : said + BETWEEN + OPERATION + ASSIGNS + event.operation();
    }

    /**
     * Whether one line carries an operation identifier at all.
     *
     * <p>Asked by the check that holds every line written during an operation to carrying one: a
     * line without it is findable by nobody, which on a failure path is the same as not existing.
     * </p>
     *
     * @param line one written line
     * @return whether it does
     */
    public static boolean carriesAnOperation(String line) {
        return line.contains(BETWEEN + OPERATION + ASSIGNS);
    }

    /**
     * Every field name one line carries, so a check can look at them without parsing prose.
     *
     * @param line one written line
     * @return the names, in the order the line carries them
     */
    public static List<String> fieldsIn(String line) {
        return java.util.Arrays.stream(line.split(BETWEEN))
                .filter(part -> part.contains(ASSIGNS))
                .map(part -> part.substring(0, part.indexOf(ASSIGNS)))
                .toList();
    }
}
