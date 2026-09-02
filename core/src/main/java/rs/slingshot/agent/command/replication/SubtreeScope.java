// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.Optional;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Whether one address travels alone or brings everything beneath it.
 *
 * <p>The client carries this as a flag and this side reads it into two named values, for the same
 * reason the reference adjustment on a move is read into two: {@code recursive} at a call site says
 * nothing, and offering one page and offering ten thousand of them are not a true and a false
 * apart. What arrives on the wire is the client's shape; what the rest of this build passes around
 * is a name.</p>
 */
public enum SubtreeScope {

    /** The address alone. */
    ONE_ITEM,

    /** The address and everything beneath it. */
    ITEM_AND_DESCENDANTS;

    /** The member the scope is carried in. */
    public static final String ARGUMENT_MEMBER = "recursive";

    /**
     * The scope one argument names.
     *
     * @param scope what the caller sent
     * @return the scope, or nothing where that is not a flag
     */
    public static Optional<SubtreeScope> of(DocumentValue scope) {
        if (!(scope instanceof final DocumentValue.Flag flag)) {
            return Optional.empty();
        }
        return Optional.of(flag.value() == DocumentValue.Truth.TRUE
                ? ITEM_AND_DESCENDANTS : ONE_ITEM);
    }
}
