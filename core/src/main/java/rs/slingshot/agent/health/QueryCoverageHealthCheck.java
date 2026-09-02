// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import java.util.List;

/**
 * Whether every query this build declares is still answered by an index.
 *
 * <p>The other silent one. A query is cheap while the index covering it exists, and whether it
 * exists is a property of the customer's repository rather than of this build — an index somebody
 * removed, or one that never got built after a restore, turns a command that answered in
 * milliseconds into one that walks the repository. Nothing fails; everything gets slow, and the
 * slowness is attributed to whatever else changed that week.</p>
 *
 * <p>The answer names the query and the index it wants, because those are the two things an
 * operator needs to type into a search.</p>
 */
public final class QueryCoverageHealthCheck {

    private QueryCoverageHealthCheck() {
    }

    /**
     * One declared query and what the platform said it would do with it.
     *
     * @param statement the query this build declares
     * @param index the index that covers it, or {@link #TRAVERSES} where the platform would walk
     */
    public record Plan(String statement, String index) {
    }

    /** What a plan says when the platform would walk rather than use an index. */
    public static final String TRAVERSES = "";

    /**
     * Whether every declared query is covered here.
     *
     * @param plans what the platform says it would do with each declared query
     * @return one result an operator can act on
     */
    public static AgentHealth.Result of(List<Plan> plans) {
        if (plans.isEmpty()) {
            return AgentHealth.unknown(AgentHealth.Check.QUERY_COVERAGE,
                    "the platform would not explain any declared query, so nothing here can say"
                            + " whether they are covered — which is not the same as saying they"
                            + " are not");
        }
        final List<String> walking = plans.stream()
                .filter(plan -> TRAVERSES.equals(plan.index()))
                .map(Plan::statement)
                .toList();
        if (walking.isEmpty()) {
            return AgentHealth.healthy(AgentHealth.Check.QUERY_COVERAGE,
                    "all " + plans.size() + " declared queries are answered by an index");
        }
        return AgentHealth.unhealthy(AgentHealth.Check.QUERY_COVERAGE, walking.size() + " of "
                + plans.size() + " declared queries would walk the repository rather than use an"
                + " index — " + walking + ". Nothing fails when this happens; everything gets slow,"
                + " and the slowness gets attributed to whatever else changed that week.");
    }
}
