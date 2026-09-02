// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

/**
 * One operation, as a person reading a list of them needs it.
 *
 * <p>Four columns and no more. An operator scanning this list is looking for the one row that is
 * wrong, and every column that is not part of telling a wrong row from a right one makes that
 * harder rather than easier — a table wide enough to answer every question is a table nobody reads
 * to answer any of them.</p>
 *
 * <p>Nothing here maps from what a store holds. Whatever reads the store makes these, because the
 * mapping belongs with the reading: a converter sitting here would need every store type on this
 * module's path, and this module's path is the one thing keeping the console out of the
 * Sling-only bundle's business.</p>
 *
 * @param operationIdentifier what the caller called it, which is how they will ask about it
 * @param commandWireName which command it is running
 * @param state what state it is in
 * @param startedAtUnixMilliseconds when the caller's own request started
 * @param attempts how many times this side has tried it
 */
public record OperationRow(String operationIdentifier, String commandWireName, String state,
                           long startedAtUnixMilliseconds, long attempts) {
}
