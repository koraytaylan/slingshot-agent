// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Optional;
import org.apache.sling.api.SlingHttpServletRequest;

/**
 * Every route requires somebody in particular, and there is no route that does not.
 *
 * <p>This repository establishes no identity. The platform does that, and what happens here is that
 * a request the platform bound to nobody is refused — on every route, including the discovery route
 * somebody would most easily leave open on the grounds that it says so little. What discovery says
 * is what this agent is, which is exactly the thing an unauthenticated caller has no business
 * learning.</p>
 *
 * <p>There is no way to exempt a route. Not "no route is exempt today": the gate takes a request
 * and the service user's name and nothing else, so there is no argument, no configuration, and no
 * flag through which an exemption could arrive.</p>
 *
 * <p>The two refusals are one answer. A request from nobody and a request whose credentials the
 * platform rejected receive byte-identical responses, because telling them apart tells somebody
 * which names exist.</p>
 */
public final class AuthenticationGate {

    /**
     * The service user this agent's own bookkeeping runs as.
     *
     * <p>Spelled here as well as in the access policy and in the deployment's own mapping, because
     * this bundle has to recognise it at runtime and cannot read a policy file that is not shipped.
     * That the three agree is asserted rather than assumed.</p>
     */
    public static final String SERVICE_USER = "slingshot-agent-state";

    /** What the platform calls a request nobody in particular made. */
    public static final String ANONYMOUS = "anonymous";

    /** What every refused request is answered with, whichever refusal it was. */
    public static final int STATUS = 401;

    private AuthenticationGate() {
    }

    /** Why a request is not one this agent serves. */
    public enum Refusal {
        /** Nobody in particular is asking, which includes credentials the platform rejected. */
        NOBODY_IN_PARTICULAR,
        /** The agent's own service user is asking, which is a deputy somebody has confused. */
        THE_SERVICE_USER
    }

    /** The result of the gate: who is asking, or the one reason nobody is being served. */
    public sealed interface Outcome permits Admitted, Refused {
    }

    /**
     * A request from somebody the platform established.
     *
     * @param caller who is asking
     */
    public record Admitted(CallerIdentity caller) implements Outcome {
    }

    /**
     * One that is not served.
     *
     * @param refusal why not, which is for this side's own record rather than for the wire
     * @param detail what was observed, which is likewise never answered with
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether a request is from somebody this agent serves.
     *
     * @param request the request, whose user the platform has already decided
     * @return who is asking, or the one reason nobody is being served
     */
    public static Outcome of(SlingHttpServletRequest request) {
        // The platform's answer may be absent: a resolver that was never bound to anybody reports
        // no user at all rather than reporting the anonymous one. Absent and anonymous mean the
        // same thing here, and this is the boundary where a foreign answer is read defensively.
        final String user = Optional.ofNullable(request.getResourceResolver().getUserID())
                .orElse("");
        return established(user);
    }

    /**
     * Whether a name the platform established is one this agent serves.
     *
     * @param user the name the platform decided on
     * @return who is asking, or the one reason nobody is being served
     */
    public static Outcome established(String user) {
        if (user.isBlank() || ANONYMOUS.equals(user)) {
            return new Refused(Refusal.NOBODY_IN_PARTICULAR,
                    "the platform bound this request to nobody in particular");
        }
        if (SERVICE_USER.equals(user)) {
            return new Refused(Refusal.THE_SERVICE_USER, "this request arrived as " + SERVICE_USER
                    + ", which is either a misconfiguration or a deputy somebody has confused");
        }
        return new Admitted(new CallerIdentity(user));
    }

    /**
     * The one reason a request is not served, where it is not.
     *
     * @param outcome what the gate decided
     * @return the refusal, or nothing where somebody is asking
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
