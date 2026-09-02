// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The only way anything in this repository writes a log line.
 *
 * <p>An operator with a console row should be able to find the logs, and an operator with a log
 * line should be able to find the console row. That works only if every line carries the operation
 * identifier — which means it cannot be something somebody remembers to add, because the lines that
 * matter most are written on the paths nobody was thinking about.</p>
 *
 * <p>An event carries named fields rather than a formatted sentence, and the difference is not
 * stylistic. A formatted sentence is a place where somebody concatenates a value, and the values
 * that get concatenated into log lines are the ones somebody had in hand — which, on a failure
 * path, is the caller's own content. Fields go through the redaction rule; a sentence somebody
 * built has already happened by the time anything could look at it.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.log;
