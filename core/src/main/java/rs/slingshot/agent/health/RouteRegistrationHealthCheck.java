// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import java.util.List;

/**
 * Whether the routes this build declares are actually registered on this instance.
 *
 * <p>This is the check that catches the failure nothing else can see. A path-bound servlet registers
 * only for path prefixes the servlet resolver has been configured to permit; a deployment whose
 * operator narrowed that list — for perfectly good reasons, years ago, in a configuration nobody
 * has read since — has an agent that installs, activates, and answers nothing. From every other
 * angle it looks like nothing was installed at all.</p>
 *
 * <p>So the answer names the permitted prefixes rather than only the missing route. An operator
 * reading "no route is registered" goes looking at this product; an operator reading "no route is
 * registered, and the resolver permits only /bin/wcm and /bin/dam" knows exactly which
 * configuration to open.</p>
 */
public final class RouteRegistrationHealthCheck {

    private RouteRegistrationHealthCheck() {
    }

    /**
     * Whether every declared route is registered here.
     *
     * @param declared the routes the committed table declares
     * @param registered the routes this instance actually answers
     * @param permittedPrefixes what the servlet resolver has been configured to permit
     * @return one result an operator can act on
     */
    public static AgentHealth.Result of(List<String> declared, List<String> registered,
                                        List<String> permittedPrefixes) {
        final List<String> absent = declared.stream()
                .filter(route -> !registered.contains(route))
                .toList();
        if (absent.isEmpty()) {
            return AgentHealth.healthy(AgentHealth.Check.ROUTE_REGISTRATION,
                    "all " + declared.size() + " declared routes are registered");
        }
        return AgentHealth.unhealthy(AgentHealth.Check.ROUTE_REGISTRATION, absent.size() + " of "
                + declared.size() + " declared routes are not registered — " + absent + ". The"
                + " servlet resolver permits " + permittedPrefixes + ", and a path-bound servlet"
                + " registers only for a prefix on that list: an agent outside it installs,"
                + " activates and answers nothing, which looks exactly like nothing being"
                + " installed.");
    }
}
