// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * What each route requires of whoever reached it, as one table rather than as conditions spread
 * through servlets.
 *
 * <p>A condition written inside a servlet is a condition that applies to the servlets somebody
 * remembered. This is a row per route, compared against the committed route table in both
 * directions, so a route added without a requirement fails the build rather than serving
 * everybody.</p>
 */
public enum RouteAuthority {

    /** Anybody the platform authenticated, which is discovery and nothing else. */
    ANY_AUTHENTICATED_CALLER("any_authenticated_caller"),

    /** A member of one of the groups an operator has permitted, which is what starting work needs. */
    A_MEMBER_OF_A_PERMITTED_GROUP("a_member_of_a_permitted_group"),

    /** That, or the caller whose own operation it is — which is how somebody follows their work. */
    THE_SUBMITTING_CALLER_OR_A_MEMBER("the_submitting_caller_or_a_member");

    private final String spelling;

    RouteAuthority(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this requirement is spelled where it is written down.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The requirement one spelling names.
     *
     * @param spelling the spelling
     * @return the requirement, or nothing where this build has no such requirement
     */
    public static Optional<RouteAuthority> named(String spelling) {
        return Arrays.stream(values())
                .filter(authority -> authority.spelling.equals(spelling))
                .findFirst();
    }

    /**
     * What every route requires, by the name the committed route table gives it.
     *
     * @return the table, in the order the routes are declared in
     */
    public static SequencedMap<String, RouteAuthority> table() {
        final SequencedMap<String, RouteAuthority> required = new LinkedHashMap<>();
        required.put("capabilities", ANY_AUTHENTICATED_CALLER);
        required.put("submit", A_MEMBER_OF_A_PERMITTED_GROUP);
        required.put("operation-lookup", THE_SUBMITTING_CALLER_OR_A_MEMBER);
        required.put("physical-job-lookup", THE_SUBMITTING_CALLER_OR_A_MEMBER);
        required.put("subscription-high-water", THE_SUBMITTING_CALLER_OR_A_MEMBER);
        required.put("events", THE_SUBMITTING_CALLER_OR_A_MEMBER);
        required.put("artifact-transfer", THE_SUBMITTING_CALLER_OR_A_MEMBER);
        required.put("artifact-intake", THE_SUBMITTING_CALLER_OR_A_MEMBER);
        return java.util.Collections.unmodifiableSequencedMap(required);
    }

    /**
     * What one route requires.
     *
     * @param routeName the route's own name in the committed table
     * @return the requirement, or nothing where this build declares none for that route
     */
    public static Optional<RouteAuthority> forRoute(String routeName) {
        return Optional.ofNullable(table().get(routeName));
    }
}
