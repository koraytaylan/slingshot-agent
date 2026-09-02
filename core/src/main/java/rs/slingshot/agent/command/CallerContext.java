// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Optional;
import rs.slingshot.agent.identity.AgentOperationIdentifier;

/**
 * Everything a handler may reach, and nothing that would let it reach anything else.
 *
 * <p>The caller's own resolver is handed to a handler beside this rather than held on it. That is
 * deliberate: this is a value, and a value a handler was given is a value it could keep — a live
 * session kept past the request it belongs to is exactly the thing this design exists to prevent.
 * So the resolver arrives as an argument, for the length of one call, and there is no member here
 * that yields a second one, a service, a factory, or a bundle context. The source policy refuses
 * one being written.</p>
 *
 * <p>A staging area is not on it either, and for the same reason: {@link StagingArea#forRow} opens
 * one where a command's own row declared room, the framework hands it in for the length of one call
 * and gives it back however the command ended. A command that needs scratch space is handed a place
 * rather than the means to find one, and never a place it could keep.</p>
 *
 * @param operation which operation this is
 * @param discovery how many rows it may examine
 * @param time how long it may run
 * @param result how large its result may be
 * @param progress where its progress goes
 */
public record CallerContext(AgentOperationIdentifier operation, Budget discovery, Budget time,
                            Budget result, ProgressSink progress) {

    /**
     * Whether one spend is inside every budget this context carries.
     *
     * @param rowsExamined how many rows have been examined
     * @param millisecondsSpent how long it has run
     * @param resultBytes how large the result is so far
     * @return the budget that was exceeded, or nothing where all three are inside
     */
    public Optional<Budget> exceeded(long rowsExamined, long millisecondsSpent, long resultBytes) {
        if (!discovery.allows(rowsExamined)) {
            return Optional.of(discovery);
        }
        if (!time.allows(millisecondsSpent)) {
            return Optional.of(time);
        }
        return result.allows(resultBytes) ? Optional.empty() : Optional.of(result);
    }
}
