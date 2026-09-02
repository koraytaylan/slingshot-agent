// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.route;

/**
 * One route this agent serves, exactly as the committed route table declares it.
 *
 * <p>A route is a value rather than a constant somebody wrote in a servlet, because the second
 * spelling of a path is the one that disagrees with the first. Every path this product answers on
 * comes from {@link AgentRouteTable} and from nowhere else, and a source-level rule refuses a
 * string literal matching the agent prefix anywhere but there.</p>
 *
 * @param name the route's own name, which is how anything here refers to it
 * @param path the exact path it is reached at
 * @param method the one method it answers
 * @param mediaType what it answers with
 * @param body whether a request to it may carry one
 * @param owningPlan the plan that builds it, so a route nobody has committed to cannot appear
 * @param reason why the route exists at all
 */
public record AgentRoute(String name, String path, String method, String mediaType,
                         RequestBody body, String owningPlan, String reason) {

    /** Whether a request to a route may carry a body. */
    public enum RequestBody {
        /** The route takes a body, and refuses a request that carries none. */
        REQUIRED,
        /** The route takes no body, and refuses a request that carries one. */
        REFUSED
    }

    /**
     * Holds a route whose every part is stated.
     *
     * @throws IllegalArgumentException if any part is blank, because a route nobody can reach or
     *     nobody committed to building is not a route
     */
    public AgentRoute {
        requireStated(name, "name");
        requireStated(path, "path");
        requireStated(method, "method");
        requireStated(mediaType, "media type");
        requireStated(owningPlan, "owning plan");
        requireStated(reason, "reason");
    }

    /**
     * Whether this route takes a request body.
     *
     * @return whether a request to it may carry one
     */
    public boolean takesABody() {
        return body == RequestBody.REQUIRED;
    }

    private static void requireStated(String value, String part) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("a route states no " + part);
        }
    }
}
