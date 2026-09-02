// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

/**
 * Which key a validation succeeded under.
 *
 * <p>Reported rather than hidden, because "this token is valid under the prior key" is the signal
 * that a rotation is under way and a client should expect a new token — not merely an internal
 * detail of how it was checked.</p>
 */
public enum ValidatingKey {

    /** The key tokens are issued under now. */
    CURRENT,

    /** The key retained from before the last rotation, while it lives. */
    PRIOR
}
