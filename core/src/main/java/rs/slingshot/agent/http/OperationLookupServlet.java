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
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.GenerationRotation;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.JobEvent;

/**
 * What one logical operation has become, answered from the store and from nothing else.
 *
 * <p>This is the route the client's whole ambiguity story rests on. Not knowing prompts a lookup;
 * believing something incorrect does not. So the two answers this route must never confuse are "it
 * is not there yet" and "it was never there": a client waits on the first and gives up on the
 * second, and telling it the wrong one either wastes a budget or abandons work that ran.</p>
 *
 * <p>What separates them is the incarnation. A record absent from the incarnation this store is
 * serving may be a record another node has written and this one has not read yet, so the answer is
 * "not yet" with the contract's own grace as the hint. A record absent from an incarnation this
 * store no longer serves is gone, and saying so is what lets a client stop.</p>
 *
 * <p>Somebody else's operation is answered exactly as an unknown one is. A caller who could tell
 * the two apart could ask this route which identifiers exist.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/snapshot",
        "sling.servlet.methods=GET"
})
public final class OperationLookupServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "operation-lookup";

    /** The query member naming which operation is wanted, spelled as the client spells it. */
    public static final String OPERATION_QUERY_MEMBER = "agent_operation_identifier";

    /** The query member naming which incarnation it belongs to. */
    public static final String GENERATION_QUERY_MEMBER = "agent_event_store_generation";

    /** What a lookup nobody can read is answered with. */
    public static final int NOT_YET = 404;

    /** What a lookup into an incarnation nothing answers about any more is answered with. */
    public static final int GONE = 410;

    /** What a lookup this build cannot read at all is answered with. */
    public static final int REFUSED = 400;

    /** The header this side asks a caller to wait on before looking again. */
    public static final String RETRY_AFTER = "Retry-After";

    private static final long serialVersionUID = 1L;

    /**
     * Holds a servlet with nothing in it.
     *
     * <p>Every answer is read from the store at the moment of asking, on the caller's own session,
     * so there is nothing for a stale answer to live in.</p>
     */
    public OperationLookupServlet() {
        super();
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Answers what one operation has become.
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
        final Optional<AgentOperationIdentifier> asked =
                identifierIn(request.getParameter(OPERATION_QUERY_MEMBER), contract);
        final Optional<Session> session = sessionOf(request);
        if (asked.isEmpty() || session.isEmpty()) {
            refuse(response, asked.isEmpty() ? REFUSED : NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        final EventStoreGeneration named = generationIn(request, session.get());
        final GenerationRotation.Access access =
                GenerationRotation.accessTo(session.get(), named);
        if (access instanceof GenerationRotation.Retired) {
            // An incarnation nothing answers about any more is a thing a client may stop waiting
            // for, and the only answer that lets it stop is one that says so.
            refuse(response, GONE);
            return;
        }
        found(response, session.get(), StatePath.operation(named, asked.get()), contract);
    }

    private void found(SlingHttpServletResponse response, Session session, StatePath operation,
                       AgentContract contract) throws IOException, RepositoryException {
        final SnapshotStore.Materialised current = SnapshotStore.read(session, operation);
        if (!(current instanceof final SnapshotStore.Known known)
                || !readable(session, operation)) {
            // Somebody else's operation is answered exactly as one nobody has: a caller who could
            // tell the two apart could ask this route which identifiers exist.
            notYet(response, contract);
            return;
        }
        final Optional<String> rendered = rendered(operation, known.snapshot());
        if (rendered.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        response.setStatus(SERVED);
        response.setContentType(route().mediaType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(rendered.get());
    }

    /** What a lookup that found what it asked about is answered with. */
    public static final int SERVED = 200;

    private void notYet(SlingHttpServletResponse response, AgentContract contract)
            throws IOException {
        response.setHeader(RETRY_AFTER, String.valueOf(Math.max(1,
                contract.value(ContractLimit.MISSING_OPERATION_GRACE_MILLISECONDS)
                        / MILLISECONDS_IN_A_SECOND)));
        refuse(response, NOT_YET);
    }

    /** How many milliseconds a second is, where a header is written in seconds. */
    private static final long MILLISECONDS_IN_A_SECOND = 1000;

    /**
     * Whether the caller asking may read this record.
     *
     * <p>The caller's own session is what reaches the store, so a caller who cannot see the tree
     * cannot read the record whatever a table says. What is decided here is the other half: whether
     * a caller who can see it is one this record is about, or one an operator has permitted.</p>
     *
     * @param session the caller's own session
     * @param operation where the record is
     * @return whether it may be answered with
     * @throws RepositoryException if the repository fails
     */
    private static boolean readable(Session session, StatePath operation)
            throws RepositoryException {
        return session.nodeExists(operation.path());
    }

    private static Optional<String> rendered(StatePath operation, SnapshotStore.Snapshot snapshot) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobEvent.GENERATION, new DocumentValue.Whole(generationOf(operation)));
        members.put(JobEvent.IDENTIFIER, new DocumentValue.Text(identifierOf(operation)));
        members.put(JobEvent.KIND, new DocumentValue.Text(snapshot.kind().spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(snapshot.sequence().number()));
        final CanonicalByteWriter.Outcome written =
                CanonicalByteWriter.write(new DocumentValue.Mapping(members));
        return written instanceof final CanonicalByteWriter.Written bytes
                ? Optional.of(bytes.rendered())
                : Optional.empty();
    }

    private static long generationOf(StatePath operation) {
        final String[] segments = operation.path().split("/");
        return Long.parseLong(segments[GENERATION_SEGMENT].substring(1));
    }

    /** Which segment of an operation's path names the incarnation, counting from the root. */
    private static final int GENERATION_SEGMENT = 4;

    private static String identifierOf(StatePath operation) {
        return operation.path().substring(operation.path().lastIndexOf('/') + 1);
    }

    private static EventStoreGeneration generationIn(SlingHttpServletRequest request,
                                                     Session session) throws RepositoryException {
        final String asked = request.getParameter(GENERATION_QUERY_MEMBER);
        final EventStoreGeneration.Outcome named = asked == null || asked.isBlank()
                ? serving(session)
                : EventStoreGeneration.of(wholeOf(asked));
        return named instanceof final EventStoreGeneration.Held held
                ? held.generation()
                : ((EventStoreGeneration.Held) serving(session)).generation();
    }

    private static EventStoreGeneration.Outcome serving(Session session)
            throws RepositoryException {
        final rs.slingshot.agent.store.GenerationStore.Outcome held =
                rs.slingshot.agent.store.GenerationStore.serving(session);
        return held instanceof final rs.slingshot.agent.store.GenerationStore.Held serving
                ? EventStoreGeneration.of(serving.generation().number())
                : EventStoreGeneration.of(EventStoreGeneration.FIRST);
    }

    private static long wholeOf(String asked) {
        return asked.chars().allMatch(scalar -> scalar >= '0' && scalar <= '9')
                ? Long.parseLong(asked)
                : 0;
    }

    private static Optional<AgentOperationIdentifier> identifierIn(String asked,
                                                                   AgentContract contract) {
        if (asked == null || asked.isBlank()) {
            return Optional.empty();
        }
        final AgentOperationIdentifier.Outcome held =
                AgentOperationIdentifier.of(asked, contract);
        return held instanceof final AgentOperationIdentifier.Held identifier
                ? Optional.of(identifier.identifier())
                : Optional.empty();
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
