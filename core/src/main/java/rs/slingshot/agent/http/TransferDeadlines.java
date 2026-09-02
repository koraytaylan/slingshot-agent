// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * How long a transfer may be idle, and how long it may take altogether.
 *
 * <p>A separate pair from a finite response's on purpose. A finite response is a document: if it
 * has not arrived in a few seconds something is wrong. An artifact is as large as the work that
 * produced it, and a transfer that is still moving is not a stalled one — a build that ended it on
 * a document's deadline would be a build that cannot return anything big, which is the whole reason
 * this route exists.</p>
 *
 * <p>So the idle bound is what decides. A transfer that has moved nothing for that long has
 * stopped, whatever its size; a transfer that keeps moving runs until the total bound, which is
 * there so a connection that trickles forever is still an ending rather than a resource somebody
 * holds indefinitely.</p>
 */
public final class TransferDeadlines {

    private TransferDeadlines() {
    }

    /**
     * How long a transfer may move nothing before it has stopped.
     *
     * @param contract the authenticated contract
     * @return the milliseconds
     */
    public static long idleMilliseconds(AgentContract contract) {
        return contract.value(ContractLimit.ARTIFACT_TRANSFER_IDLE_TIMEOUT_MILLISECONDS);
    }

    /**
     * How long a transfer may take altogether, however well it is moving.
     *
     * @param contract the authenticated contract
     * @return the milliseconds
     */
    public static long totalMilliseconds(AgentContract contract) {
        return contract.value(ContractLimit.ARTIFACT_TRANSFER_TOTAL_TIMEOUT_MILLISECONDS);
    }

    /**
     * Whether a transfer has stopped moving.
     *
     * @param lastMovedAtUnixMilliseconds when it last wrote anything
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the bound
     * @return whether it has stalled
     */
    public static boolean isStalled(long lastMovedAtUnixMilliseconds, long nowUnixMilliseconds,
                                    AgentContract contract) {
        return nowUnixMilliseconds - lastMovedAtUnixMilliseconds >= idleMilliseconds(contract);
    }

    /**
     * Whether a transfer has taken as long as it may.
     *
     * @param startedAtUnixMilliseconds when it began
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the bound
     * @return whether it is over
     */
    public static boolean isOverdue(long startedAtUnixMilliseconds, long nowUnixMilliseconds,
                                    AgentContract contract) {
        return nowUnixMilliseconds - startedAtUnixMilliseconds >= totalMilliseconds(contract);
    }

    /**
     * Whether a transfer that is still moving is inside both bounds.
     *
     * @param startedAtUnixMilliseconds when it began
     * @param lastMovedAtUnixMilliseconds when it last wrote anything
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares both bounds
     * @return whether it may go on
     */
    public static boolean isMoving(long startedAtUnixMilliseconds,
                                   long lastMovedAtUnixMilliseconds, long nowUnixMilliseconds,
                                   AgentContract contract) {
        return !isStalled(lastMovedAtUnixMilliseconds, nowUnixMilliseconds, contract)
                && !isOverdue(startedAtUnixMilliseconds, nowUnixMilliseconds, contract);
    }
}
