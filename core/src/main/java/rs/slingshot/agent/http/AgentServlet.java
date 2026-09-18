// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.execution.CommandJobTopic;
import rs.slingshot.agent.log.AgentLog;
import rs.slingshot.agent.repository.AgentSession;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.StatePath;

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

    /** The refusal a line names where the agent's own state session could not be opened. */
    public static final String STATE_UNAVAILABLE = "STATE_UNAVAILABLE";

    /** The refusal a line names where the agent's own state failed while it was in use. */
    public static final String STATE_UNREADABLE = "STATE_UNREADABLE";

    /** The refusal a line names where the platform bound the request to no repository session. */
    protected static final String NO_CALLER_SESSION = "NO_CALLER_SESSION";

    /** The refusal a line names where a caller's name is not one the capacity ledger can count. */
    protected static final String UNCOUNTED_CALLER = "UNCOUNTED_CALLER";

    /** The refusal a line names where the room a request needs is not there yet. */
    protected static final String AT_CAPACITY_REFUSAL = "AT_CAPACITY";

    /** The refusal a line names where an answer this build produced could not be rendered. */
    protected static final String UNRENDERABLE = "UNRENDERABLE";

    /** The refusal a line names where a request names something this build does not read. */
    protected static final String UNREADABLE_REQUEST = "UNREADABLE_REQUEST";

    /**
     * The refusal a line names where what was asked for is not held for this caller.
     *
     * <p>One name for nothing held and somebody else's, in the log as on the wire: the line is
     * written for an operator, and what tells the two apart is the record, not the refusal.</p>
     */
    protected static final String NOT_HELD_FOR_THIS_CALLER = "NOT_HELD_FOR_THIS_CALLER";

    /** What every refusal's line says, whichever refusal it was; the fields say which. */
    public static final String REFUSED_A_REQUEST = "refused a request";

    /** The field a refusal's line names the route under. */
    public static final String ROUTE_FIELD = "route";

    /** The field a refusal's line names the status it answered under. */
    public static final String STATUS_FIELD = "status";

    /** The field a refusal's line names which refusal it was under. */
    public static final String REFUSAL_FIELD = "refusal";

    /** The field a refusal's line carries what was observed under, last because it has spaces. */
    public static final String DETAIL_FIELD = "detail";

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
     * Answers with a scoped internal state session and closes it on every exit path.
     *
     * @param response the response to refuse when state access fails
     * @param work internal bookkeeping, retaining the original request separately for content effects
     * @throws IOException if the response cannot be written
     */
    protected final void withState(SlingHttpServletResponse response, AgentSession.StateWork work)
            throws IOException {
        try {
            if (AgentSession.current().withState(work) == AgentSession.Completion.UNAVAILABLE) {
                refuse(response, NOTHING_THIS_BUILD_CAN_SERVE, STATE_UNAVAILABLE,
                        "the agent's own state session could not be opened");
            }
        } catch (final RepositoryException unavailable) {
            // The exception's own message is not written: a repository names the node it failed
            // on, and where this agent keeps things is one of the values a line never carries.
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE, STATE_UNREADABLE,
                    "the agent's own state could not be read or written");
        }
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
        if (table instanceof final AgentRouteTable.Refused unreadable) {
            // A build that cannot read its own route table cannot know what it serves, and
            // answering anything at all would be answering for a route nobody declared.
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE, routeName(request),
                    unreadable.failure().name(), unreadable.detail());
            return;
        }
        final AgentRoute route = ((AgentRouteTable.Loaded) table).table().route(routeName(request));
        final RequestShape.Outcome shape = shapeOf(request).against(route);
        final java.util.Optional<RequestShape.Refused> refused = RequestShape.refusalIn(shape);
        if (refused.isPresent()) {
            refuse(response, refused.get().refusal().status(), routeName(request),
                    refused.get().refusal().name(), refused.get().detail());
            return;
        }
        serve(request, response);
    }

    /**
     * Refuses a request on this servlet's route, and writes down why for whoever operates it.
     *
     * @param response what to answer with
     * @param status the answer
     * @param refusal which refusal it was, by the name of the refusal that decided it
     * @param detail what was observed, or empty where nothing more than the refusal is known
     * @throws IOException if the answer cannot be written
     */
    protected final void refuse(SlingHttpServletResponse response, int status, String refusal,
                                String detail) throws IOException {
        refuse(response, status, routeName(), refusal, detail);
    }

    /**
     * Refuses a request with a status and nothing else at all, and writes down why.
     *
     * <p>Not the platform's own error page. That page names the servlet that refused, lists the
     * filters the request went through, and prints a timing trace — to an unauthenticated caller,
     * on a running instance, which is where this was found. It also differs between two refusals
     * that are meant to be indistinguishable, so a caller could tell an unknown user from a wrong
     * password by the shape of the trace. A status and an empty body are the whole answer.</p>
     *
     * <p>What the caller is not told, the operator is. Every refusal writes one warning naming the
     * route, the status, the refusal and what was observed, because a refusal that is
     * indistinguishable on the wire and absent from the log is one nobody can diagnose — an
     * operator whose permitted group is spelled for another environment sees a bare status and
     * nothing else anywhere. The line carries no request body, no parameter and no credential, and
     * a detail naming anything a line must never carry is withheld whole.</p>
     *
     * @param response what to answer with
     * @param status the answer
     * @param route which route refused
     * @param refusal which refusal it was, by the name of the refusal that decided it
     * @param detail what was observed, or empty where nothing more than the refusal is known
     * @throws IOException if the answer cannot be written
     */
    public static void refuse(SlingHttpServletResponse response, int status, String route,
                              String refusal, String detail) throws IOException {
        AgentLog.warn(AgentLog.event(REFUSED_A_REQUEST, refusal(route, status, refusal, detail)),
                AgentServlet::internal, messageBound());
        response.setStatus(status);
        response.setContentLength(0);
        response.getOutputStream().flush();
    }

    /**
     * The fields one refusal's line carries, in the order it carries them.
     *
     * @param route which route refused
     * @param status the answer
     * @param refusal which refusal it was
     * @param detail what was observed, or empty where nothing more is known
     * @return the fields, with no detail where there is no detail
     */
    static SequencedMap<String, String> refusal(String route, int status, String refusal,
                                                String detail) {
        final SequencedMap<String, String> fields = new LinkedHashMap<>();
        fields.put(ROUTE_FIELD, route);
        fields.put(STATUS_FIELD, String.valueOf(status));
        fields.put(REFUSAL_FIELD, refusal);
        if (!detail.isEmpty()) {
            fields.put(DETAIL_FIELD, detail);
        }
        return fields;
    }

    /**
     * Whether a value names where this agent keeps things or what it is built out of.
     *
     * <p>The redaction corpus is a build-time document and is not in the bundle, so the values it
     * covers that a refusal could actually carry at run time are named here from the constants
     * that define them: the state tree, the job topic, and the package every class is in. Nothing
     * a caller sends reaches a refusal's line except the spelling of the path they asked for, the
     * method they used and the media type they declared, and none of those is a secret.</p>
     *
     * @param value one field's value
     * @return whether it must be withheld
     */
    static boolean internal(String value) {
        final String builtOutOf = AgentServlet.class.getPackageName()
                .substring(0, AgentServlet.class.getPackageName().lastIndexOf('.'));
        return java.util.stream.Stream.of(StatePath.ROOT, AgentSession.AGENT_TREE,
                        CommandJobTopic.TOPIC, builtOutOf, builtOutOf.replace('.', '/'))
                .anyMatch(value::contains);
    }

    /**
     * The most one log message may be, as the contract states it.
     *
     * <p>A contract this build cannot read is itself a refusal that has to be written down, so it
     * cannot be what stops the line: the fixed message is then held to its own length, which it
     * always fits.</p>
     *
     * @return the bound
     */
    private static long messageBound() {
        return AgentContract.load() instanceof final AgentContract.Loaded loaded
                ? loaded.contract().value(ContractLimit.MAXIMUM_LOG_MESSAGE_BYTES)
                : REFUSED_A_REQUEST.length();
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
