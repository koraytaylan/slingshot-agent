// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.stream.DefaultStreamTicker;
import rs.slingshot.agent.stream.EventEncoder;
import rs.slingshot.agent.stream.StreamAdmission;
import rs.slingshot.agent.stream.StreamExecutor;
import rs.slingshot.agent.stream.StreamHandoff;
import rs.slingshot.agent.stream.StreamResumption;
import rs.slingshot.agent.stream.StreamSession;
import rs.slingshot.agent.stream.StreamTicker;
import rs.slingshot.agent.stream.StreamWriter;

/**
 * One subscriber's live view of one operation, held without holding a request thread.
 *
 * <p>Every refusal happens before the stream opens and is an ordinary response: a client that never
 * got a stream should not have to parse one to find out why, and a client that got a stream should
 * be able to assume the stream is the answer. An unknown subscription, an incarnation this store
 * does not serve, and an operation this caller cannot see are each answered and nothing is
 * opened.</p>
 *
 * <p>What is opened is handed to this bundle's own bounded pool and written there, with the request
 * thread released first. The pool is bounded by the same number that bounds admission, so no
 * arrangement of subscribers makes it grow, and it dies with this component rather than outliving
 * the bundle that made it.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/events",
        "sling.servlet.methods=GET"
})
public final class EventStreamServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "events";

    /** The query member naming whose subscription is being followed. */
    public static final String SUBSCRIPTION = "daemon_subscription_identifier";

    /** The query member naming which operation is being followed. */
    public static final String OPERATION = "agent_operation_identifier";

    /** The query member naming which incarnation of the store it belongs to. */
    public static final String GENERATION = "agent_event_store_generation";

    /** What a stream this build will not open at all is answered with. */
    public static final int REFUSED = 400;

    /** What a stream on something nobody here holds is answered with. */
    public static final int UNKNOWN = 404;

    /** What a stream into an incarnation this store does not serve is answered with. */
    public static final int RESET = 409;

    /** What a stream this instance has no room for is answered with. */
    public static final int AT_CAPACITY = 503;

    /** What a stream that is being served is answered with. */
    public static final int SERVING = 200;

    /** The header a caller is asked to wait through before opening another stream. */
    public static final String RETRY_AFTER = "Retry-After";

    /** What a request is answered with when this build cannot read its own contract or store. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    /** How many milliseconds a second is, where a header is written in seconds. */
    private static final long MILLISECONDS_IN_A_SECOND = 1000;

    private static final long serialVersionUID = 1L;

    /** What time it is to this servlet's streams, and how they wait between looking. */
    private final StreamTicker ticker;

    /** Holds a servlet running on this instance's own clock. */
    public EventStreamServlet() {
        this(new DefaultStreamTicker());
    }

    /**
     * Holds a servlet running on a clock somebody else keeps.
     *
     * <p>The seam a suite needs: a heartbeat interval and a session bound are proved by advancing
     * time rather than by waiting through it, and a suite that waited would be proving what one
     * machine did one afternoon.</p>
     *
     * @param ticker what time it is to this servlet's streams, and how they wait
     */
    public EventStreamServlet(StreamTicker ticker) {
        super();
        this.ticker = ticker;
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Gives back the threads this bundle was holding, when the component goes.
     *
     * <p>A pool that outlived its bundle would be threads nobody can reach running code nobody can
     * replace, which is what makes an instance need a restart to be upgraded.</p>
     */
    @Deactivate
    public void stopped() {
        StreamExecutor.closed();
    }

    /**
     * Opens one stream, or answers why it did not.
     *
     * @param request the request, whose shape the base has already settled
     * @param response what to answer with
     * @throws IOException if the answer cannot be written
     */
    @Override
    protected void serve(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        final AgentContract.Outcome loaded = AgentContract.load();
        if (!(loaded instanceof final AgentContract.Loaded held)) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        final AuthenticationGate.Outcome asking = AuthenticationGate.of(request);
        if (!(asking instanceof final AuthenticationGate.Admitted admitted)) {
            refuse(response, AuthenticationGate.STATUS);
            return;
        }
        try {
            opened(request, response, admitted.caller(), held.contract());
        } catch (final RepositoryException unreadable) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
        }
    }

    private void opened(SlingHttpServletRequest request, SlingHttpServletResponse response,
                        CallerIdentity caller, AgentContract contract)
            throws IOException, RepositoryException {
        final Optional<Session> store = sessionOf(request);
        final Optional<StatePath.Caller> counted = caller.counted();
        if (store.isEmpty() || counted.isEmpty()) {
            refuse(response, store.isEmpty() ? NOTHING_THIS_BUILD_CAN_SERVE : REFUSED);
            return;
        }
        final StreamSession.Outcome opening = StreamSession.of(store.get(),
                new StreamSession.Asked(text(request.getParameter(SUBSCRIPTION)),
                        text(request.getParameter(OPERATION)),
                        whole(request.getParameter(GENERATION)), counted.get()),
                contract);
        final Optional<StreamSession.Refused> refused = StreamSession.refusalIn(opening);
        if (refused.isPresent()) {
            refuse(response, statusFor(refused.get().refusal()));
            return;
        }
        admitted(request, response, store.get(), ((StreamSession.Held) opening).session(),
                contract);
    }

    /**
     * What each refusal to open a stream is answered with, which is an ordinary response.
     *
     * @param refusal why no stream was opened
     * @return the status
     */
    public static int statusFor(StreamSession.Refusal refusal) {
        return switch (refusal) {
            case NOT_READABLE -> REFUSED;
            case UNKNOWN_SUBSCRIPTION, NOT_THIS_CALLERS_OPERATION -> UNKNOWN;
            case FOREIGN_GENERATION -> RESET;
        };
    }

    private void admitted(SlingHttpServletRequest request, SlingHttpServletResponse response,
                          Session store, StreamSession session, AgentContract contract)
            throws IOException, RepositoryException {
        final StreamAdmission.Outcome room =
                StreamAdmission.open(store, session.caller(), contract);
        if (!(room instanceof StreamAdmission.Admitted)) {
            // The honest answer: there is room for a bounded number of subscribers and this caller
            // is past it. The hint is the contract's own cap rather than a number chosen here.
            response.setHeader(RETRY_AFTER, String.valueOf(Math.max(1,
                    contract.value(ContractLimit.RETRY_AFTER_CAP_MILLISECONDS)
                            / MILLISECONDS_IN_A_SECOND)));
            refuse(response, AT_CAPACITY);
            return;
        }
        response.setStatus(SERVING);
        response.setContentType(EventEncoder.MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.flushBuffer();
        writing(request, response, store, session, contract);
    }

    private void writing(SlingHttpServletRequest request, SlingHttpServletResponse response,
                         Session store, StreamSession session, AgentContract contract)
            throws IOException {
        final StreamWriter writer = new StreamWriter(session, contract);
        final java.io.Writer bytes = response.getWriter();
        final String resumption = text(request.getHeader(StreamResumption.RESUMPTION_HEADER));
        if (StreamHandoff.from(request, contract)
                == StreamHandoff.Outcome.THE_THREAD_IS_RELEASED) {
            // The request thread goes back to the pool here, before anything is waited for.
            final javax.servlet.AsyncContext released = request.getAsyncContext();
            StreamExecutor.open().execute(() -> {
                try {
                    writer.serve(bytes, store, ticker, resumption);
                } finally {
                    released.complete();
                }
            });
            return;
        }
        writer.serve(bytes, store, ticker, resumption);
    }

    private static Optional<Session> sessionOf(SlingHttpServletRequest request) {
        return Optional.ofNullable(request.getResourceResolver().adaptTo(Session.class));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static long whole(String value) {
        return value != null && !value.isBlank()
                && value.chars().allMatch(scalar -> scalar >= '0' && scalar <= '9')
                ? Long.parseLong(value)
                : 0;
    }

    /**
     * The route this servlet answers, read from the committed table.
     *
     * @return the route
     */
    public static AgentRoute route() {
        final AgentRouteTable.Outcome outcome = AgentRouteTable.load();
        if (outcome instanceof final AgentRouteTable.Refused refused) {
            throw new IllegalStateException("no route table: " + refused.detail());
        }
        return ((AgentRouteTable.Loaded) outcome).table().route(ROUTE_NAME);
    }
}
