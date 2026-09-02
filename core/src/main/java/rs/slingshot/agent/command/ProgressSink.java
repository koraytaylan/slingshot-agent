// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * Where a long command's progress goes, which is the stream rather than nowhere.
 *
 * <p>A command that takes a minute and says nothing is indistinguishable from one that has stopped.
 * So progress becomes events a subscriber sees — and it is bounded by the same per-operation event
 * count everything else is, because a chatty handler filling the ledger is a handler deciding how
 * much of somebody's repository this product uses.</p>
 */
public final class ProgressSink {

    private final long bound;
    private final List<String> reported = new ArrayList<>();

    private ProgressSink(long bound) {
        this.bound = bound;
    }

    /** Whether a report was taken or refused. */
    public enum Taken {
        /** It was taken, and a subscriber will see it. */
        REPORTED,
        /** It was not: this operation has said as much as it may. */
        PAST_THE_EVENT_BOUND
    }

    /**
     * A sink bounded by what one operation's ledger may hold.
     *
     * @param contract the authenticated contract, which declares the bound
     * @return the sink
     */
    public static ProgressSink under(AgentContract contract) {
        return new ProgressSink(contract.value(ContractLimit.MAXIMUM_OPERATION_EVENT_ROWS));
    }

    /**
     * Reports one step of a command's progress.
     *
     * @param step what happened, in the command's own words
     * @return whether it was taken
     */
    public Taken report(String step) {
        if (reported.size() >= bound) {
            return Taken.PAST_THE_EVENT_BOUND;
        }
        reported.add(step);
        return Taken.REPORTED;
    }

    /**
     * Everything reported so far, in the order it was reported.
     *
     * @return the steps
     */
    public List<String> reported() {
        return Collections.unmodifiableList(reported);
    }

    /**
     * How much more this operation may say.
     *
     * @return the number of further reports it may make
     */
    public long remaining() {
        return bound - reported.size();
    }
}
