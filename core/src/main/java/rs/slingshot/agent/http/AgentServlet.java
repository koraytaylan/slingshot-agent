// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import javax.servlet.ServletException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * The one base every route extends, so the shape of a request is decided in exactly one place.
 *
 * <p>What it does before a servlet of its own sees anything: takes the route out of the committed
 * table by name, compares the request's shape with it, and answers the refusal itself where they
 * disagree. A subclass therefore cannot be reached through a selector, an extension, a suffix, or a
 * method the table does not give it — not because each subclass remembered to check, but because
 * the check is not theirs to forget.</p>
 *
 * <p>The order matters as much as the checks. Nothing is read from the request until the shape is
 * settled: a servlet that parsed a parameter first would be a servlet doing work for a request it
 * was about to refuse, and every such servlet is one bug away from doing that work for a spelling
 * nobody enumerated.</p>
 */
public abstract sealed class AgentServlet extends SlingAllMethodsServlet
        permits ArtifactIntakeServlet, ArtifactServlet, CapabilityServlet, HighWaterServlet,
                OperationLookupServlet, PhysicalJobServlet,
                EventStreamServlet, SubmitServlet {

    /** What a request is answered with when this build cannot read its own route table. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    private static final long serialVersionUID = 1L;

    /**
     * Holds a servlet with nothing in it.
     *
     * <p>A declarative-services component is one object the container hands to every caller at
     * once, so nothing here is held between requests: the route is read from the committed table at
     * the moment a request arrives.</p>
     */
    protected AgentServlet() {
        super();
    }

    /**
     * Which route this servlet answers, by the name the committed table gives it.
     *
     * @return the route's name
     */
    protected abstract String routeName();

    /**
     * Which route one request is for, where a path answers more than one.
     *
     * <p>Sling registers a path-bound servlet by its path alone: the methods a component declares
     * are read for a resource-type registration and ignored for this one. So a path the committed
     * table gives two rows — one per method — is one servlet here, and which row a request is held
     * to is decided by the request rather than by which component the resolver happened to pick.
     * </p>
     *
     * @param request the request
     * @return the route's name
     */
    protected String routeName(SlingHttpServletRequest request) {
        return routeName();
    }

    /**
     * Answers a request whose shape is already settled.
     *
     * @param request the request, which is for this route and no other spelling of it
     * @param response what to answer with
     * @throws IOException if the answer cannot be written
     * @throws ServletException if answering fails for a reason that is not the request
     */
    protected abstract void serve(SlingHttpServletRequest request,
                                  SlingHttpServletResponse response)
            throws IOException, ServletException;

    /**
     * Decides the shape of every request before anything else looks at it.
     *
     * @param request the request
     * @param response what to answer with
     * @throws IOException if the answer cannot be written
     * @throws ServletException if answering fails for a reason that is not the request
     */
    @Override
    protected final void service(SlingHttpServletRequest request,
                                 SlingHttpServletResponse response)
            throws IOException, ServletException {
        final AgentRouteTable.Outcome table = AgentRouteTable.load();
        if (!(table instanceof final AgentRouteTable.Loaded loaded)) {
            // A build that cannot read its own route table cannot know what it serves, and
            // answering anything at all would be answering for a route nobody declared.
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        final AgentRoute route = loaded.table().route(routeName(request));
        final RequestShape.Outcome shape = shapeOf(request).against(route);
        final java.util.Optional<RequestShape.Refused> refused = RequestShape.refusalIn(shape);
        if (refused.isPresent()) {
            refuse(response, refused.get().refusal().status());
            return;
        }
        serve(request, response);
    }

    /**
     * Refuses a request with a status and nothing else at all.
     *
     * <p>Not the platform's own error page. That page names the servlet that refused, lists the
     * filters the request went through, and prints a timing trace — to an unauthenticated caller,
     * on a running instance, which is where this was found. It also differs between two refusals
     * that are meant to be indistinguishable, so a caller could tell an unknown user from a wrong
     * password by the shape of the trace. A status and an empty body are the whole answer.</p>
     *
     * @param response what to answer with
     * @param status the answer
     * @throws IOException if the answer cannot be written
     */
    public static void refuse(SlingHttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentLength(0);
        response.getOutputStream().flush();
    }

    /**
     * What a request looks like, taken from the request and nothing else.
     *
     * @param request the request
     * @return its shape
     */
    public static RequestShape shapeOf(SlingHttpServletRequest request) {
        final var resolved = request.getRequestPathInfo();
        return new RequestShape(asked(request),
                text(resolved.getResourcePath()),
                text(resolved.getSelectorString()),
                text(resolved.getExtension()),
                text(resolved.getSuffix()),
                text(request.getMethod()),
                text(request.getContentType()),
                request.getContentLength() > 0
                        ? RequestShape.Body.PRESENT
                        : RequestShape.Body.ABSENT);
    }

    /**
     * What a request said, where the platform's own answer for "it said nothing" is a null.
     *
     * <p>This is the one boundary where that is converted. Nothing past it holds an absent value:
     * a selector nobody sent is the empty text, which is a thing the shape rules can compare.</p>
     *
     * @param value what the platform returned
     * @return the same text, or empty where it returned nothing
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * The path the caller actually asked for, without the context path or the query.
     *
     * <p>This is what differs between the spellings of a route, because what Sling resolves is the
     * registered path whichever spelling was used.</p>
     *
     * @param request the request
     * @return the path as it arrived
     */
    private static String asked(SlingHttpServletRequest request) {
        final String uri = text(request.getRequestURI());
        final String context = text(request.getContextPath());
        final String path = !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
        final int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }
}
