// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * Everything this agent counts, and the two bounds each of them is counted against.
 *
 * <p>The set is closed and compared with the contract in both directions: a quantity with no bound
 * would be a count nobody admits against, and a bound with no quantity would be a promise nothing
 * keeps. Every quantity has a total and a per-caller share, because every caller past
 * authentication shares one store — a bound that is only a total is a bound one caller can spend on
 * everybody else's behalf, and an agent that stopped admitting because one client was busy is
 * indistinguishable, from every other client, from one that is broken.</p>
 *
 * <p>Commands in flight is the one quantity here that is not about storage at all. A command runs
 * inside the request that submitted it, so an execution in flight is a request thread this agent is
 * holding in somebody else's author — the resource whose exhaustion is indistinguishable, from
 * outside, from an instance that has gone. A bound on what the store keeps with no bound on what it
 * is doing is a bound on the wrong thing.</p>
 */
public enum AccountedQuantity {

    /** The bytes live subscriptions occupy. */
    ACTIVE_SUBSCRIPTION_BYTES("active_subscription_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_BYTES),

    /** How many live subscriptions there are. */
    ACTIVE_SUBSCRIPTION_ROWS("active_subscription_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_ROWS),

    /** The bytes published artifacts occupy. */
    ARTIFACT_BYTES("artifact_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_ARTIFACT_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_ARTIFACT_BYTES),

    /** How many published artifacts there are. */
    ARTIFACT_ROWS("artifact_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_ARTIFACT_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_ARTIFACT_ROWS),

    /** The bytes the event ledger occupies. */
    EVENT_BYTES("event_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_EVENT_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_EVENT_BYTES),

    /** How many events the ledger holds. */
    EVENT_ROWS("event_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_EVENT_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_EVENT_ROWS),

    /** The bytes operation records occupy. */
    OPERATION_DETAIL_BYTES("operation_detail_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_OPERATION_DETAIL_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_DETAIL_BYTES),

    /** How many operation records there are. */
    OPERATION_DETAIL_ROWS("operation_detail_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_OPERATION_DETAIL_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_DETAIL_ROWS),

    /** The bytes reserved for work that has not produced them yet. */
    OPERATION_RESERVATION_BYTES("operation_reservation_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_OPERATION_RESERVATION_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_RESERVATION_BYTES),

    /** How many reservations are outstanding. */
    OPERATION_RESERVATION_ROWS("operation_reservation_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_OPERATION_RESERVATION_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_RESERVATION_ROWS),

    /** The bytes inline results occupy. */
    RESULT_BYTES("result_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_RESULT_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_RESULT_BYTES),

    /** How many results there are. */
    RESULT_ROWS("result_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_RESULT_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_RESULT_ROWS),

    /** The bytes snapshots occupy. */
    SNAPSHOT_BYTES("snapshot_bytes",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_SNAPSHOT_BYTES,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_SNAPSHOT_BYTES),

    /** How many snapshots there are. */
    SNAPSHOT_ROWS("snapshot_rows",
            ContractLimit.MAXIMUM_CURRENT_GENERATION_SNAPSHOT_ROWS,
            ContractLimit.MAXIMUM_CALLER_CURRENT_GENERATION_SNAPSHOT_ROWS),

    /** How many event streams are held open. */
    CONCURRENT_EVENT_STREAMS("concurrent_event_streams",
            ContractLimit.MAXIMUM_CONCURRENT_EVENT_STREAMS,
            ContractLimit.MAXIMUM_CALLER_CONCURRENT_EVENT_STREAMS),

    /** How many commands are running, which is how many request threads this agent is holding. */
    CONCURRENT_COMMAND_EXECUTIONS("concurrent_command_executions",
            ContractLimit.MAXIMUM_CONCURRENT_COMMAND_EXECUTIONS,
            ContractLimit.MAXIMUM_CALLER_CONCURRENT_COMMAND_EXECUTIONS);

    private final String spelling;
    private final ContractLimit total;
    private final ContractLimit callerShare;

    AccountedQuantity(String spelling, ContractLimit total, ContractLimit callerShare) {
        this.spelling = spelling;
        this.total = total;
        this.callerShare = callerShare;
    }

    /**
     * How this quantity is spelled where it is counted and where a refusal names it.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The bound the whole generation is held to.
     *
     * @return the limit
     */
    public ContractLimit total() {
        return total;
    }

    /**
     * The bound one submitting caller's share is held to.
     *
     * @return the limit
     */
    public ContractLimit callerShare() {
        return callerShare;
    }

    /**
     * How many shards this quantity's own total is spread over.
     *
     * @param contract the authenticated contract
     * @return the shard count
     */
    public int totalShards(AgentContract contract) {
        return ShardedCount.shardsFor(contract.value(total));
    }

    /**
     * How many shards one caller's share of this quantity is spread over.
     *
     * @param contract the authenticated contract
     * @return the shard count
     */
    public int callerShards(AgentContract contract) {
        return ShardedCount.shardsFor(contract.value(callerShare));
    }

    /**
     * What the whole generation may hold of this, less the margin its own sharding costs.
     *
     * <p>Compared against the declared bound less that margin so a race between nodes refuses early
     * rather than admitting past the bound: a decision may be conservative and may never be
     * wrong.</p>
     *
     * @param contract the authenticated contract
     * @return the number an admission compares against
     */
    public long admissibleTotal(AgentContract contract) {
        return contract.value(total) - ShardedCount.inFlightMargin(totalShards(contract));
    }

    /**
     * What one caller may hold of this, less the margin its own sharding costs.
     *
     * @param contract the authenticated contract
     * @return the number an admission compares against
     */
    public long admissibleCallerShare(AgentContract contract) {
        return contract.value(callerShare) - ShardedCount.inFlightMargin(callerShards(contract));
    }

    /**
     * The quantity one spelling names.
     *
     * @param spelling the spelling
     * @return the quantity, or nothing where this build counts no such thing
     */
    public static Optional<AccountedQuantity> named(String spelling) {
        return Arrays.stream(values())
                .filter(quantity -> quantity.spelling.equals(spelling))
                .findFirst();
    }

    /**
     * Every bound this build accounts against, as the contract spells them.
     *
     * @return the limit names, sorted
     */
    public static List<String> accountedBounds() {
        return Arrays.stream(values())
                .flatMap(quantity -> java.util.stream.Stream.of(quantity.total.key(),
                        quantity.callerShare.key()))
                .sorted()
                .toList();
    }
}
