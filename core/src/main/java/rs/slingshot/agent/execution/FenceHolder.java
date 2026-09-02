// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

/**
 * Who is executing one operation, and until when.
 *
 * <p>The instant is when the hold runs out rather than when it was taken: a worker that stopped
 * cannot tell anybody, so what another worker reads has to be enough on its own to decide whether
 * anybody is still there.</p>
 *
 * @param worker the worker's own name
 * @param heldUntilUnixMilliseconds when the hold runs out
 */
public record FenceHolder(String worker, long heldUntilUnixMilliseconds) {

    /**
     * Whether this hold is still live at an instant.
     *
     * @param nowUnixMilliseconds what the asking side's clock says
     * @return whether somebody still holds it
     */
    public boolean liveAt(long nowUnixMilliseconds) {
        return nowUnixMilliseconds < heldUntilUnixMilliseconds;
    }
}
