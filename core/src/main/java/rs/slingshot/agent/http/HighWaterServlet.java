// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.HighWaterMark;
import rs.slingshot.agent.store.SubscriptionLedger;
import rs.slingshot.agent.store.SubscriptionRecord;

/**
 * How far a subscription has actually been served, which is not the number its subscriber last saw.
 *
 * <p>That difference is the whole reason this route exists. A client reconciling after a
 * disconnection knows what it received; what it needs is what this side recorded, because the gap
 * between the two is exactly the set of events it has to be sent again.</p>
 *
 * <p>Three answers rather than two. A subscription this side never had and one it swept are
 * different situations for the subscriber: the first is a mistake and the second is a subscription
 * that has to be taken again. And a cursor into an incarnation this store no longer serves is a
 * reset naming the incarnation it does serve, because a client that knows which one is current can
 * rebuild rather than guess.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/subscriptions/high-water",
        "sling.servlet.methods=POST"
})
public final class HighWaterServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "subscription-high-water";

    /** The member the subscription's own name arrives and is answered in. */
    public static final String SUBSCRIPTION = "daemon_subscription_identifier";

    /** The member the incarnation arrives and is answered in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The member the cursor is answered in. */
    public static final String EVENTS_SHOWN = "events_shown";

    /** What a subscription this side has swept is answered with. */
    public static final int EXPIRED = 410;

    /** What a subscription this side never had is answered with. */
    public static final int UNKNOWN = 404;

    /** What a cursor into another incarnation is answered with. */
    public static final int RESET = 409;

    private static final long serialVersionUID = 1L;

    /** Holds a servlet with nothing in it. */
    public HighWaterServlet() {
        super();
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Answers how far one subscription has been served.
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
        if (AuthenticationGate.refusalIn(AuthenticationGate.of(request)).isPresent()) {
            refuse(response, AuthenticationGate.STATUS);
            return;
        }
        try {
            answer(request, response, held.contract());
        } catch (final RepositoryException unreadable) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
        }
    }

    /** What a request is answered with when this build cannot read its own contract or store. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    private void answer(SlingHttpServletRequest request, SlingHttpServletResponse response,
                        AgentContract contract) throws IOException, RepositoryException {
        final Optional<Session> session = sessionOf(request);
        if (session.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        final BoundedRequestBody.Outcome body = BoundedRequestBody.read(request.getInputStream(),
                request.getContentLength(), contract);
        if (BoundedRequestBody.refusalIn(body).isPresent()) {
            refuse(response, OperationLookupServlet.REFUSED);
            return;
        }
        asked(response, session.get(), ((BoundedRequestBody.Read) body).bytes(), contract);
    }

    private void asked(SlingHttpServletResponse response, Session session, byte[] body,
                       AgentContract contract) throws IOException, RepositoryException {
        final rs.slingshot.agent.json.BoundedDocumentReader.Outcome read =
                rs.slingshot.agent.json.BoundedDocumentReader.read(body,
                        rs.slingshot.agent.json.BoundedDocumentReader.Bounds.from(contract));
        if (!(read instanceof final rs.slingshot.agent.json.BoundedDocumentReader.Read document)
                || !(document.value() instanceof final DocumentValue.Mapping asked)) {
            refuse(response, OperationLookupServlet.REFUSED);
            return;
        }
        final SubscriptionRecord.Outcome named = SubscriptionRecord.identifier(
                text(asked, SUBSCRIPTION), contract);
        if (!(named instanceof final SubscriptionRecord.Held identifier)) {
            refuse(response, OperationLookupServlet.REFUSED);
            return;
        }
        found(response, session, identifier.identifier(), whole(asked, GENERATION), contract);
    }

    private void found(SlingHttpServletResponse response, Session session,
                       SubscriptionRecord.Identifier identifier, long askedGeneration,
                       AgentContract contract) throws IOException, RepositoryException {
        final EventStoreGeneration serving = serving(session);
        if (askedGeneration > 0 && askedGeneration != serving.number()) {
            // A cursor into an incarnation this store does not serve is not a position at all. The
            // answer names the one it does serve, so a client rebuilds rather than guesses.
            answered(response, identifier, serving, RESET, 0);
            return;
        }
        final String path = SubscriptionRecord.pathOf(identifier).path();
        if (!session.nodeExists(path)) {
            refuse(response, UNKNOWN);
            return;
        }
        if (expired(session.getNode(path), contract)) {
            refuse(response, EXPIRED);
            return;
        }
        answered(response, identifier, serving, OperationLookupServlet.SERVED,
                shownBy(session, identifier));
    }

    private static boolean expired(javax.jcr.Node record, AgentContract contract)
            throws RepositoryException {
        final long lastAdvanced = record.hasProperty(SubscriptionRecord.LAST_ADVANCED_AT)
                ? record.getProperty(SubscriptionRecord.LAST_ADVANCED_AT).getLong()
                : 0;
        return SubscriptionLedger.expired(new SubscriptionRecord(
                        new SubscriptionRecord.Identifier(record.getName()),
                        ((EventStoreGeneration.Held) EventStoreGeneration
                                .of(EventStoreGeneration.FIRST)).generation(),
                        SubscriptionRecord.Unread.NOTHING_SHOWN_YET, lastAdvanced),
                System.currentTimeMillis(), contract);
    }

    private static long shownBy(Session session, SubscriptionRecord.Identifier identifier)
            throws RepositoryException {
        final SubscriptionRecord.Cursor cursor = HighWaterMark.read(session, identifier);
        return cursor instanceof final SubscriptionRecord.Shown shown
                ? shown.sequence().number() + 1
                : 0;
    }

    private void answered(SlingHttpServletResponse response,
                          SubscriptionRecord.Identifier identifier, EventStoreGeneration serving,
                          int status, long shown) throws IOException {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(EVENTS_SHOWN, new DocumentValue.Whole(shown));
        members.put(GENERATION, new DocumentValue.Whole(serving.number()));
        members.put(SUBSCRIPTION, new DocumentValue.Text(identifier.rendered()));
        final CanonicalByteWriter.Outcome written =
                CanonicalByteWriter.write(new DocumentValue.Mapping(members));
        if (!(written instanceof final CanonicalByteWriter.Written bytes)) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        response.setStatus(status);
        response.setContentType(route().mediaType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(bytes.rendered());
    }

    private static EventStoreGeneration serving(Session session) throws RepositoryException {
        final GenerationStore.Outcome held = GenerationStore.serving(session);
        return held instanceof final GenerationStore.Held serving
                ? serving.generation()
                : ((EventStoreGeneration.Held) EventStoreGeneration
                        .of(EventStoreGeneration.FIRST)).generation();
    }

    private static String text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse("");
    }

    private static long whole(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Whole.class::isInstance)
                .map(value -> ((DocumentValue.Whole) value).value())
                .orElse(0L);
    }

    private static Optional<Session> sessionOf(SlingHttpServletRequest request) {
        return Optional.ofNullable(request.getResourceResolver().adaptTo(Session.class));
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
