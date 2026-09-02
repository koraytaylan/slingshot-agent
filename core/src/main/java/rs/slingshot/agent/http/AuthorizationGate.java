// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.List;
import java.util.Optional;

/**
 * Whether the caller the platform established may use this agent, and for this route.
 *
 * <p>A path-bound servlet is reached before the access control that would otherwise decide the
 * request. That is the point of binding by path and it is the hazard: without this the agent is a
 * way to do things the caller could not do themselves.</p>
 *
 * <p>The arrangement is the one Adobe operators already recognise from the Groovy Console. The tool
 * is available to administrators and to nobody else until somebody deliberately says otherwise, and
 * what widens it is a configuration naming further groups. A configuration naming no group refuses
 * every submission rather than admitting everybody — the failure that looks like a broken install
 * is better than the one that looks like nothing — and a configuration naming a group that does not
 * exist is refused naming it, because an operator who believes they have granted access to a group
 * that is spelled differently has granted it to nobody and has no way to find out.</p>
 *
 * <p>Reading is not submitting. Somebody who started work may follow it without being a member of
 * anything, because it is theirs; and a member may read anybody's, because that is what operating
 * the thing means. What nobody may do is read work that is not theirs without being permitted.</p>
 */
public final class AuthorizationGate {

    /** What every refused request is answered with, whichever refusal it was. */
    public static final int STATUS = 403;

    private AuthorizationGate() {
    }

    /** Where a caller stands with respect to one group. */
    public enum Standing {
        /** They are in it. */
        A_MEMBER,
        /** They are not. */
        NOT_A_MEMBER,
        /** Nothing on this instance is called that. */
        NO_SUCH_GROUP
    }

    /** Whose operation a request is about, where it is about one at all. */
    public enum Ownership {
        /** It is the caller's own. */
        THE_CALLERS_OWN,
        /** It is somebody else's. */
        SOMEBODY_ELSES,
        /** The request is not about an operation at all. */
        NOT_ABOUT_AN_OPERATION
    }

    /** Where a caller stands, asked of the platform at the moment of asking. */
    @FunctionalInterface
    public interface Groups {

        /**
         * Where the caller stands with respect to one group.
         *
         * @param group the group's own name
         * @return whether they are in it, are not, or whether there is no such group
         */
        Standing standing(String group);
    }

    /** Why a caller is not served. */
    public enum Refusal {
        /** No group is permitted at all, so nobody may start work. */
        NO_GROUP_IS_PERMITTED,
        /** A permitted group is named that nothing on this instance is called. */
        NO_SUCH_GROUP,
        /** They are in none of the permitted groups, and this is not their own work. */
        NOT_PERMITTED,
        /** This build declares no requirement for that route at all. */
        NO_REQUIREMENT_DECLARED
    }

    /** The result of the gate. */
    public sealed interface Outcome permits Admitted, Refused {
    }

    /**
     * A caller this route serves.
     *
     * @param required what the route required of them
     */
    public record Admitted(RouteAuthority required) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why not
     * @param detail what was observed, naming the group where a group is the reason
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * One request, as everything that decides it.
     *
     * @param routeName which route was reached
     * @param permitted the groups an operator has permitted, in the order they configured them
     * @param groups where the caller stands with respect to each of them
     * @param ownership whose operation this request is about
     */
    public record Request(String routeName, List<String> permitted, Groups groups,
                          Ownership ownership) {

        /** Holds a list nothing can change afterwards. */
        public Request {
            permitted = List.copyOf(permitted);
        }
    }

    /**
     * Whether this caller may do this, on this route.
     *
     * @param request everything that decides it
     * @return that they may, or the one reason they may not
     */
    public static Outcome of(Request request) {
        final Optional<RouteAuthority> required = RouteAuthority.forRoute(request.routeName());
        if (required.isEmpty()) {
            return new Refused(Refusal.NO_REQUIREMENT_DECLARED, "this build declares no"
                    + " requirement for the route named " + request.routeName() + ", and a route"
                    + " nobody wrote a requirement for is not one this agent serves");
        }
        if (required.get() == RouteAuthority.ANY_AUTHENTICATED_CALLER) {
            return new Admitted(required.get());
        }
        return againstTheGroups(request, required.get());
    }

    private static Outcome againstTheGroups(Request request, RouteAuthority required) {
        if (request.permitted().isEmpty()) {
            return new Refused(Refusal.NO_GROUP_IS_PERMITTED, "no group is permitted to use this"
                    + " agent, so nobody may — which is a configuration nobody has made rather than"
                    + " an agent that admits everybody");
        }
        for (final String group : request.permitted()) {
            final Standing standing = request.groups().standing(group);
            if (standing == Standing.NO_SUCH_GROUP) {
                return new Refused(Refusal.NO_SUCH_GROUP, "the permitted group " + group
                        + " is not a group on this instance, so whoever named it has permitted"
                        + " nobody and has no way to notice");
            }
            if (standing == Standing.A_MEMBER) {
                return new Admitted(required);
            }
        }
        return againstOwnership(request, required);
    }

    private static Outcome againstOwnership(Request request, RouteAuthority required) {
        if (required == RouteAuthority.THE_SUBMITTING_CALLER_OR_A_MEMBER
                && request.ownership() == Ownership.THE_CALLERS_OWN) {
            return new Admitted(required);
        }
        return new Refused(Refusal.NOT_PERMITTED, "this caller is in none of the permitted groups"
                + " and this is " + (request.ownership() == Ownership.NOT_ABOUT_AN_OPERATION
                        ? "not their own work" : "somebody else's work"));
    }

    /**
     * The one reason a caller is not served, where they are not.
     *
     * @param outcome what the gate decided
     * @return the refusal, or nothing where they are served
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
