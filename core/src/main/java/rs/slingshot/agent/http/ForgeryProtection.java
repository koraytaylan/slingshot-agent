// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.route.AgentRoute;

/**
 * The two platform prerequisites a state-changing request has to satisfy, and this side's part in
 * them.
 *
 * <p>The first is the one the client already knows about: Adobe documents that an authenticated
 * author POST carries a short-lived token fetched immediately beforehand, and the client fetches
 * and sends one. What this side does is make sure the requirement actually applies to these routes
 * rather than being quietly excluded — a route on an exclusion list is a route where the
 * documentation is true and the deployment is not.</p>
 *
 * <p>The second is the one that surprises people. Sling's referrer filter refuses a state-changing
 * request whose {@code Referer} is absent or names a host the deployment does not allow, and it
 * refuses it before any servlet is reached — which is exactly the shape of a command-line client
 * that sends none. Discovering that as an unexplained refusal from a deployment nobody has changed
 * is the most expensive way to learn it. So it is written down, proved against a running instance,
 * and not relaxed: a deployment that accepts an empty referrer accepts it for everything, and this
 * agent is not worth that.</p>
 *
 * <p>Whether a token is genuine is not decided here. The platform's own filter decides it, before a
 * servlet is reached and with a key this bundle has no business holding; what is here is what
 * happens to the answer, which is that all three ways of not having one are answered identically.
 * A caller who can tell an absent token from an expired one has been told that tokens expire, and a
 * caller who can tell a foreign one from an absent one has been told whose it was.</p>
 */
public final class ForgeryProtection {

    /** The header the platform's own filter reads a token from. */
    public static final String TOKEN_HEADER = "CSRF-Token";

    /** Where a client fetches one immediately before a state-changing request. */
    public static final String TOKEN_ROUTE = "/libs/granite/csrf/token.json";

    /** What every request without a good token is answered with, whichever way it lacked one. */
    public static final int STATUS = 403;

    /** The method a route answers when it changes nothing. */
    private static final String READ = "GET";

    private ForgeryProtection() {
    }

    /**
     * The platform configurations these requirements depend on, by their own identifiers.
     *
     * <p>Named rather than described, so an operator who has changed one knows what they changed.
     * The third is the one whose absence makes this product simply not there: a path-bound servlet
     * outside the resolver's permitted prefixes registers nowhere, answers nothing, and logs
     * nothing about why.</p>
     */
    public static final List<String> PLATFORM_CONFIGURATIONS = List.of(
            "com.adobe.granite.csrf.impl.CSRFFilter",
            "org.apache.sling.security.impl.ReferrerFilter",
            "org.apache.sling.servlets.resolver.impl.SlingServletResolver");

    /** What the platform said about the token a request carried. */
    public enum Verdict {
        /** It carried one the platform accepted. */
        ACCEPTED,
        /** It carried none at all. */
        ABSENT,
        /** It carried one belonging to somebody else. */
        FOREIGN,
        /** It carried one that has run out. */
        EXPIRED
    }

    /** Why a request is not served. */
    public enum Refusal {
        /** No token at all. */
        NO_TOKEN,
        /** A token that is not this caller's. */
        ANOTHER_CALLERS_TOKEN,
        /** A token that has run out. */
        AN_EXPIRED_TOKEN
    }

    /** The result: a request the platform's prerequisites are satisfied for, or the reason not. */
    public sealed interface Outcome permits Satisfied, Refused {
    }

    /**
     * A request that may proceed as far as this requirement is concerned.
     *
     * @param route the route it reached
     */
    public record Satisfied(AgentRoute route) implements Outcome {
    }

    /**
     * One that may not.
     *
     * @param refusal why not, which is this side's own record rather than anything answered with
     * @param detail what was observed, likewise never answered with
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether a route requires a token at all.
     *
     * <p>Read routes do not, deliberately and in writing: a token on a read is a prerequisite that
     * buys nothing and one more thing for a client to get wrong.</p>
     *
     * @param route the route
     * @return whether a request to it carries a token
     */
    public static boolean requiresAtoken(AgentRoute route) {
        return !READ.equals(route.method());
    }

    /**
     * What happens to a request given what the platform said about its token.
     *
     * @param route the route it reached
     * @param verdict what the platform said
     * @return that the requirement is satisfied, or the one reason it is not
     */
    public static Outcome of(AgentRoute route, Verdict verdict) {
        if (!requiresAtoken(route)) {
            return new Satisfied(route);
        }
        return switch (verdict) {
            case ACCEPTED -> new Satisfied(route);
            case ABSENT -> new Refused(Refusal.NO_TOKEN,
                    "a state-changing request arrived with no token");
            case FOREIGN -> new Refused(Refusal.ANOTHER_CALLERS_TOKEN,
                    "a state-changing request arrived with a token that is not this caller's");
            case EXPIRED -> new Refused(Refusal.AN_EXPIRED_TOKEN,
                    "a state-changing request arrived with a token that has run out");
        };
    }

    /**
     * Every route that carries a token, by the name the committed table gives it.
     *
     * @param routes the routes to divide
     * @return the names of the ones that require a token, in the order they were given
     */
    public static List<String> requiring(List<AgentRoute> routes) {
        return routes.stream()
                .filter(ForgeryProtection::requiresAtoken)
                .map(AgentRoute::name)
                .toList();
    }

    /**
     * The refusal one name spells, for a suite comparing what this build refuses.
     *
     * @param spelling the refusal's own name
     * @return the refusal, or nothing where this build has no such refusal
     */
    public static Optional<Refusal> named(String spelling) {
        return Arrays.stream(Refusal.values())
                .filter(refusal -> refusal.name().equals(spelling))
                .findFirst();
    }

    /**
     * The one reason a request is not served, where it is not.
     *
     * @param outcome what this decided
     * @return the refusal, or nothing where the requirement is satisfied
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
