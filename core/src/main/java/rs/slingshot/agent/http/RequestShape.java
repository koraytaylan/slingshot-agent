// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Optional;
import rs.slingshot.agent.route.AgentRoute;

/**
 * What a request looks like, and whether that is the one shape a route answers.
 *
 * <p>Sling resolves a path-bound servlet through more than the path it was bound to. A request for
 * the path with a selector, with an extension, with a suffix, or with a trailing segment all reach
 * the same servlet, and each of them is a second spelling of the route. A servlet that reads a
 * parameter before noticing is a servlet whose policy applied to one spelling and not the
 * others.</p>
 *
 * <p>So the shape is decided first, from what the request is rather than from what it carries: the
 * path the request actually asked for, exactly; the path Sling resolved; no selector, no extension,
 * no suffix; the one method; and — where the route takes a body at all — the one media type.
 * Nothing here reads a parameter, a header, or a byte of the body, because the whole point is that
 * this happens before any of that.</p>
 *
 * <p>The request's own path is compared as well as the resolved one. Sling matches a path-bound
 * servlet by its registered path, and what it reports as resolved is that registered path — so the
 * thing that differs between the spellings of a route is what the caller asked for rather than what
 * came back from resolution. Which of the two comparisons catches a given spelling is not something
 * to rely on: both are made, and a scenario asks a running instance for every spelling rather than
 * trusting either.</p>
 *
 * @param requestPath the path the request itself asked for, without the context path or the query
 * @param resourcePath the path Sling resolved, without selectors, extension, or suffix
 * @param selectors the selectors the request carried, empty where it carried none
 * @param extension the extension it carried, empty where it carried none
 * @param suffix the suffix it carried, empty where it carried none
 * @param method the method it used
 * @param contentType what it says its body is, empty where it says nothing
 * @param body whether it carries one at all
 */
public record RequestShape(String requestPath, String resourcePath, String selectors,
                           String extension, String suffix, String method, String contentType,
                           Body body) {

    /** Whether a request carries a body, which is a fact about the request rather than a choice. */
    public enum Body {
        /** It carries one. */
        PRESENT,
        /** It carries none. */
        ABSENT
    }

    /** The result of deciding a shape: the route it is for, or the one reason it is for none. */
    public sealed interface Outcome permits Accepted, Refused {
    }

    /**
     * A request shaped the way its route is answered.
     *
     * @param route the route it reached
     */
    public record Accepted(AgentRoute route) implements Outcome {
    }

    /**
     * One that is not.
     *
     * @param refusal which of the three it is
     * @param detail what was observed, naming what arrived rather than what would have been right
     */
    public record Refused(ShapeRefusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether this request is the one shape a route answers.
     *
     * @param route the route it reached
     * @return the route, or the one reason this is not a request for it
     */
    public Outcome against(AgentRoute route) {
        if (!reaches(route) || !selectors.isEmpty() || !extension.isEmpty()
                || !suffix.isEmpty()) {
            return new Refused(ShapeRefusal.NOT_THE_EXACT_PATH, "this route is reached at "
                    + route.path() + " exactly, and this request arrived at " + spelled());
        }
        if (!method.equals(route.method())) {
            return new Refused(ShapeRefusal.WRONG_METHOD,
                    route.path() + " answers " + route.method() + " and this request used "
                            + method);
        }
        return againstTheBody(route);
    }

    /**
     * Whether this request arrived at a path that reaches one route.
     *
     * <p>The exact path, or one of the client's old spellings where a deployment has said to serve
     * it. What the request asked for and what the platform resolved have to be the same either
     * way: the two differ exactly when a request reached a servlet by a spelling nobody
     * enumerated.</p>
     *
     * @param route the route being considered
     * @return whether it reaches it
     */
    private boolean reaches(AgentRoute route) {
        return requestPath.equals(resourcePath)
                && (requestPath.equals(route.path())
                        || RouteAliasSwitch.serves(requestPath, route));
    }

    private Outcome againstTheBody(AgentRoute route) {
        if (!route.takesABody()) {
            return body == Body.ABSENT
                    ? new Accepted(route)
                    : new Refused(ShapeRefusal.WRONG_MEDIA_TYPE,
                            route.path() + " takes no body and this request carried one");
        }
        if (body == Body.ABSENT) {
            return new Refused(ShapeRefusal.WRONG_MEDIA_TYPE, route.path() + " takes a "
                    + route.mediaType() + " body and this request carried none");
        }
        return named().filter(type -> type.equals(route.mediaType()))
                .<Outcome>map(type -> new Accepted(route))
                .orElseGet(() -> new Refused(ShapeRefusal.WRONG_MEDIA_TYPE, route.path()
                        + " takes " + route.mediaType() + " and this request carried "
                        + (contentType.isEmpty() ? "nothing that says what it is" : contentType)));
    }

    /**
     * The media type this request declares, without the parameters that follow it.
     *
     * @return the type, or nothing where the request declares none
     */
    public Optional<String> named() {
        if (contentType.isEmpty()) {
            return Optional.empty();
        }
        final int parameters = contentType.indexOf(';');
        final String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
        // A media type is written in the protocol's own alphabet, so it is folded with that
        // alphabet rather than with a locale's: a Turkish instance lower-casing "APPLICATION"
        // would produce a word that is not the media type anybody sent.
        return Optional.of(asciiFolded(type.strip()));
    }

    private static String asciiFolded(String value) {
        final StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index = index + 1) {
            final char character = value.charAt(index);
            folded.append(character >= 'A' && character <= 'Z'
                    ? (char) (character - 'A' + 'a')
                    : character);
        }
        return folded.toString();
    }

    /**
     * How this request spelled the path it arrived at, for a refusal to name.
     *
     * @return the spelling, with whatever the request added to the path
     */
    public String spelled() {
        return requestPath.equals(resourcePath)
                ? resourcePath + (selectors.isEmpty() ? "" : "." + selectors)
                        + (extension.isEmpty() ? "" : "." + extension) + suffix
                : requestPath;
    }

    /**
     * The same request with another method, for a suite proving what a route refuses.
     *
     * @param another the method
     * @return the shape
     */
    public RequestShape withMethod(String another) {
        return new RequestShape(requestPath, resourcePath, selectors, extension, suffix, another,
                contentType, body);
    }

    /**
     * The same request carrying a body of a given type.
     *
     * @param type what the body says it is
     * @return the shape
     */
    public RequestShape withBody(String type) {
        return new RequestShape(requestPath, resourcePath, selectors, extension, suffix, method,
                type, Body.PRESENT);
    }

    /**
     * The same request carrying no body at all.
     *
     * @return the shape
     */
    public RequestShape withoutABody() {
        return new RequestShape(requestPath, resourcePath, selectors, extension, suffix, method, "",
                Body.ABSENT);
    }

    /**
     * The one reason a request is not for the route it reached, where that is so.
     *
     * @param outcome what deciding it produced
     * @return the refusal, or nothing where the request is for the route
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
