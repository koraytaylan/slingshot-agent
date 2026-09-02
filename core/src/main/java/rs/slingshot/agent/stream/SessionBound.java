// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * When this side ends its own stream, which is before anybody else ends it for us.
 *
 * <p>A stream a gateway severs at an interval nobody declared is a stream that ends at a moment
 * nobody chose, and a severed connection is not an ending a decoder can tell from a fault. So this
 * side ends its own first, cleanly, after a final heartbeat, inside a bound it publishes.</p>
 *
 * <p>The bound has to be resumable inside the client's own policy: strictly below the heartbeat
 * timeout multiplied by the retry attempts the client is allowed. A session that ended on schedule
 * and could not be resumed would be a stream that ends work rather than one that pauses it, and the
 * relation is asserted here rather than hoped for.</p>
 */
public final class SessionBound {

    private SessionBound() {
    }

    /**
     * How long one session is held before this side ends it.
     *
     * @param contract the authenticated contract
     * @return the milliseconds
     */
    public static long milliseconds(AgentContract contract) {
        return contract.value(ContractLimit.MAXIMUM_EVENT_STREAM_SESSION_MILLISECONDS);
    }

    /**
     * Whether this session's time is up.
     *
     * @param openedAtUnixMilliseconds when it opened
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the bound
     * @return whether it is time to end cleanly
     */
    public static boolean isReached(long openedAtUnixMilliseconds, long nowUnixMilliseconds,
                                    AgentContract contract) {
        return nowUnixMilliseconds - openedAtUnixMilliseconds >= milliseconds(contract);
    }

    /**
     * Whether a session ending on schedule is resumable inside the client's own policy.
     *
     * <p>The relation this build is held to: the session bound is strictly below what the client
     * will wait through, which is its heartbeat timeout times the attempts it is allowed.</p>
     *
     * @param contract the authenticated contract
     * @return whether the relation holds
     */
    public static boolean isResumable(AgentContract contract) {
        return milliseconds(contract) < resumableWindowMilliseconds(contract);
    }

    /**
     * How long a client will keep trying before it gives up, which this bound must stay under.
     *
     * @param contract the authenticated contract
     * @return the milliseconds
     */
    public static long resumableWindowMilliseconds(AgentContract contract) {
        return Heartbeat.timeoutMilliseconds(contract)
                * contract.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS);
    }
}
