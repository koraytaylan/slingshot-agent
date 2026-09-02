// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.store.AccountedQuantity;

/**
 * What the stores hold, what the sweep will take, and whether taking it would be enough.
 *
 * <p>Capacity says how full a store is now; retention says when things leave. An operator who can
 * see one without the other cannot answer the only question that matters, which is whether it will
 * still be full tomorrow. So each retained kind is shown with what it holds, the bound it is held
 * to, what the next sweep would release, and the instant the oldest record is retained until.</p>
 *
 * <p>The case worth naming is the one patience does not fix: a kind that would still be over its
 * bound after everything eligible had expired needs a decision — a wider bound, or less being
 * kept — and the page says so rather than leaving somebody to do the subtraction. Every bound is
 * read from the contract, so changing the contract changes this page and nothing here has to be
 * changed with it.</p>
 *
 * <p>Broken down by kind rather than shown as one total, because the fix for too many events and
 * the fix for too many artifacts are different things done by different people. The total is
 * offered only as the sum of the parts, so it can never say something the parts do not.</p>
 */
public final class RetentionDataSource implements ConsoleDataSource.Rows {

    /** How a kind that patience will bring back within its bound is spelled. */
    public static final String WITHIN_BOUND_AFTER_EXPIRY = "within bound once retention passes";

    /** How the one case patience does not fix is spelled. */
    public static final String NEEDS_A_DECISION =
            "still over its bound after full expiry — needs a wider bound or less kept";

    /** How the readings name what one kind holds now. */
    public static final String RETAINED = "_retained";

    /** How they name the bound it is held to, which the contract states. */
    public static final String BOUND = "_bound";

    /** How they name what the next sweep would release. */
    public static final String RELEASABLE = "_releasable";

    /** How they name the instant its oldest record is retained until. */
    public static final String RETAINED_UNTIL = "_retained_until";

    /** How they name whether full expiry would be enough. */
    public static final String AFTER_FULL_EXPIRY = "_after_full_expiry";

    /** How the sum of the parts is named, which is the only total this page offers. */
    public static final String RETAINED_TOTAL = "retained_total";

    private final Supplier<Retention> retention;

    private final AgentContract contract;

    /**
     * Holds one source over what is retained and the contract every bound is read from.
     *
     * @param retention where the answer comes from, asked only after the authority said yes
     * @param contract the authenticated contract, which states every bound this page shows
     */
    public RetentionDataSource(Supplier<Retention> retention, AgentContract contract) {
        this.retention = retention;
        this.contract = contract;
    }

    /**
     * One retained kind, as the stores hold it.
     *
     * @param quantity which counted thing this is, which is also where its bound comes from
     * @param retained how much of it there is now
     * @param releasable how much of that the next sweep would release, being past its retention
     * @param retainedUntilUnixMilliseconds when the oldest record of this kind stops being retained
     */
    public record Held(AccountedQuantity quantity, long retained, long releasable,
                       long retainedUntilUnixMilliseconds) {

        /**
         * Whether waiting is enough, or somebody has to decide something.
         *
         * @param contract the authenticated contract, which states this kind's bound
         * @return whether full expiry would bring this kind back within its bound
         */
        public Sufficiency sufficiency(AgentContract contract) {
            return retained - releasable > quantity.admissibleTotal(contract)
                    ? Sufficiency.NEEDS_A_DECISION : Sufficiency.PATIENCE_IS_ENOUGH;
        }
    }

    /** Whether waiting for retention to pass would be enough for one kind. */
    public enum Sufficiency {
        /** It would. */
        PATIENCE_IS_ENOUGH,
        /** It would not, and the page says so rather than leaving somebody to subtract. */
        NEEDS_A_DECISION
    }

    /**
     * What every retained kind holds.
     *
     * @param kinds one entry per kind, in the order they are worth being asked about
     */
    public record Retention(List<Held> kinds) {

        /** Holds a retention whose kinds nothing can change afterwards. */
        public Retention {
            kinds = List.copyOf(kinds);
        }
    }

    @Override
    public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
        final List<MaintenanceDataSource.Reading> readings = readingsOf(retention.get(), contract);
        return new ConsoleDataSource.Rendered(new ConsolePage<>(readings, 0,
                new ConsolePage.Counted(readings.size())));
    }

    /**
     * What one retention state reads as.
     *
     * @param retention what the stores hold
     * @param contract the authenticated contract, which states every bound
     * @return the readings, five per kind and then the sum of the parts
     */
    public static List<MaintenanceDataSource.Reading> readingsOf(Retention retention,
                                                                 AgentContract contract) {
        final List<MaintenanceDataSource.Reading> readings = new ArrayList<>();
        long total = 0;
        for (final Held kind : retention.kinds()) {
            final String name = kind.quantity().spelling();
            readings.add(new MaintenanceDataSource.Reading(name + RETAINED,
                    String.valueOf(kind.retained())));
            readings.add(new MaintenanceDataSource.Reading(name + BOUND,
                    String.valueOf(kind.quantity().admissibleTotal(contract))));
            readings.add(new MaintenanceDataSource.Reading(name + RELEASABLE,
                    String.valueOf(kind.releasable())));
            readings.add(new MaintenanceDataSource.Reading(name + RETAINED_UNTIL,
                    String.valueOf(kind.retainedUntilUnixMilliseconds())));
            readings.add(new MaintenanceDataSource.Reading(name + AFTER_FULL_EXPIRY,
                    kind.sufficiency(contract) == Sufficiency.NEEDS_A_DECISION
                            ? NEEDS_A_DECISION : WITHIN_BOUND_AFTER_EXPIRY));
            total = total + kind.retained();
        }
        readings.add(new MaintenanceDataSource.Reading(RETAINED_TOTAL, String.valueOf(total)));
        return List.copyOf(readings);
    }
}
