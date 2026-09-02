// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The bytes that say only that the connection is alive, on the interval the contract declares.
 *
 * <p>A stream that has stopped sending heartbeats is a stream that has stopped, which is a
 * different thing from a stream that has nothing to say — and the client cannot tell those apart
 * without them. So they go whether or not there is news, and they go from this bundle's own bounded
 * executor rather than from the platform's shared scheduler: that scheduler exists for periodic
 * work, every other feature on the instance is also using it, and one entry per open stream is this
 * agent spending somebody else's capacity.</p>
 */
public final class Heartbeat {

    private Heartbeat() {
    }

    /**
     * How often a heartbeat goes, which the contract declares and this build does not.
     *
     * @param contract the authenticated contract
     * @return the interval in milliseconds
     */
    public static long intervalMilliseconds(AgentContract contract) {
        return contract.value(ContractLimit.HEARTBEAT_INTERVAL_MILLISECONDS);
    }

    /**
     * How long a client waits for one before it treats the stream as gone.
     *
     * @param contract the authenticated contract
     * @return the timeout in milliseconds
     */
    public static long timeoutMilliseconds(AgentContract contract) {
        return contract.value(ContractLimit.HEARTBEAT_TIMEOUT_MILLISECONDS);
    }

    /**
     * Whether a heartbeat is due, given when the last thing was written.
     *
     * @param lastWrittenAtUnixMilliseconds when this stream last wrote anything at all
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the interval
     * @return whether one is due
     */
    public static boolean isDue(long lastWrittenAtUnixMilliseconds, long nowUnixMilliseconds,
                                AgentContract contract) {
        return nowUnixMilliseconds - lastWrittenAtUnixMilliseconds
                >= intervalMilliseconds(contract);
    }

    /**
     * What one heartbeat is on the wire.
     *
     * @return the bytes, which a decoder reads as the absence of news rather than as an event
     */
    public static String bytes() {
        return EventEncoder.heartbeat();
    }
}
