// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import rs.slingshot.agent.execution.Outbox;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.StatePath;

/**
 * What the job system did with one operation's physical attempts, answered from the outbox alone.
 *
 * <p>Several physical records for one logical operation is the normal case rather than a fault, and
 * a client diagnosing a stuck operation needs to see them. What it must never see is anything about
 * the job system's own contents: a queue's name, a topic, another operation's identifier, or an
 * address. Those are facts about a customer's instance rather than about this caller's work, and a
 * route that answered them would be a route somebody could use to survey the instance.</p>
 *
 * <p>So the answer comes out of the outbox — the attempts this operation itself recorded — and
 * nothing here asks the job system anything. That also makes the answer stable: what the queue
 * currently holds changes for reasons that have nothing to do with this operation.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/jobs",
        "sling.servlet.methods=GET"
})
public final class PhysicalJobServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "physical-job-lookup";

    /** The query member naming which operation's attempts are wanted. */
    public static final String OPERATION_QUERY_MEMBER =
            OperationLookupServlet.OPERATION_QUERY_MEMBER;

    /** The member the attempts are carried in. */
    public static final String ATTEMPTS = "physical_sling_job_identifiers";

    /** The member saying whether the answer was cut at the bound. */
    public static final String BOUNDED = "bounded";

    /** Whether an answer carries everything there was, or was cut at the bound. */
    public enum Completeness {
        /** Everything this operation recorded is in the answer. */
        EVERYTHING_THERE_IS,
        /** The answer was cut at the bound, and says so rather than pretending otherwise. */
        CUT_AT_THE_BOUND
    }

    /** The member the operation the answer is about is carried in. */
    public static final String IDENTIFIER = "agent_operation_identifier";

    /** The member the incarnation is carried in. */
    public static final String GENERATION = "agent_event_store_generation";

    private static final long serialVersionUID = 1L;

    /** Holds a servlet with nothing in it. */
    public PhysicalJobServlet() {
        super();
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Answers what this operation's own attempts were.
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
        final Optional<AgentOperationIdentifier> asked = identifierIn(
                request.getParameter(OPERATION_QUERY_MEMBER), contract);
        final Optional<Session> session = sessionOf(request);
        if (asked.isEmpty() || session.isEmpty()) {
            refuse(response, asked.isEmpty()
                    ? OperationLookupServlet.REFUSED
                    : NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        final EventStoreGeneration serving = serving(session.get());
        final StatePath operation = StatePath.operation(serving, asked.get());
        if (!session.get().nodeExists(operation.path())) {
            // An operation nobody here holds and one belonging to somebody else are one answer:
            // a caller who could tell them apart could ask this route which identifiers exist.
            refuse(response, OperationLookupServlet.NOT_YET);
            return;
        }
        answered(response, session.get(), operation, new Asked(asked.get(), serving, contract));
    }

    /**
     * One lookup, as everything that decides its answer.
     *
     * @param identifier which operation
     * @param generation which incarnation it is in
     * @param contract the authenticated contract, which declares the match bound
     */
    private record Asked(AgentOperationIdentifier identifier, EventStoreGeneration generation,
                         AgentContract contract) {
    }

    private void answered(SlingHttpServletResponse response, Session session, StatePath operation,
                          Asked asked) throws IOException, RepositoryException {
        final List<String> held = attemptsAt(session, operation);
        final long bound = asked.contract().value(ContractLimit.MAXIMUM_PHYSICAL_SLING_JOB_MATCHES);
        final List<String> named = new ArrayList<>();
        for (final String attempt : held) {
            if (named.size() >= bound) {
                break;
            }
            named.add(attempt);
        }
        final Optional<String> rendered = rendered(asked, named, held.size() > bound
                ? Completeness.CUT_AT_THE_BOUND
                : Completeness.EVERYTHING_THERE_IS);
        if (rendered.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        response.setStatus(OperationLookupServlet.SERVED);
        response.setContentType(route().mediaType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(rendered.get());
    }

    /**
     * The attempts this operation recorded, read from its own outbox and from nowhere else.
     *
     * <p>Read by path rather than by identity, because a lookup knows which operation it is about
     * and does not know the four other fields an identity carries — and asking the caller for them
     * would be asking them to tell this side what its own record says.</p>
     *
     * @param session the caller's own session
     * @param operation where the record is
     * @return the job identifiers, in the order the store holds them
     * @throws RepositoryException if the repository fails
     */
    private static List<String> attemptsAt(Session session, StatePath operation)
            throws RepositoryException {
        final String outbox = operation.child(Outbox.NODE).path();
        if (!session.nodeExists(outbox)) {
            return List.of();
        }
        final List<String> held = new ArrayList<>();
        final javax.jcr.NodeIterator attempts = session.getNode(outbox).getNodes();
        while (attempts.hasNext()) {
            final javax.jcr.Node attempt = attempts.nextNode();
            if (attempt.hasProperty(Outbox.JOB_IDENTIFIER)) {
                held.add(attempt.getProperty(Outbox.JOB_IDENTIFIER).getString());
            }
        }
        java.util.Collections.sort(held);
        return held;
    }

    private static Optional<String> rendered(Asked asked, List<String> attempts,
                                             Completeness completeness) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ATTEMPTS, new DocumentValue.Sequence(attempts.stream()
                .map(DocumentValue.Text::new)
                .map(DocumentValue.class::cast)
                .toList()));
        members.put(BOUNDED, new DocumentValue.Flag(
                completeness == Completeness.CUT_AT_THE_BOUND
                        ? DocumentValue.Truth.TRUE
                        : DocumentValue.Truth.FALSE));
        members.put(GENERATION, new DocumentValue.Whole(asked.generation().number()));
        members.put(IDENTIFIER, new DocumentValue.Text(asked.identifier().rendered()));
        final CanonicalByteWriter.Outcome written =
                CanonicalByteWriter.write(new DocumentValue.Mapping(members));
        return written instanceof final CanonicalByteWriter.Written bytes
                ? Optional.of(bytes.rendered())
                : Optional.empty();
    }

    private static EventStoreGeneration serving(Session session) throws RepositoryException {
        final rs.slingshot.agent.store.GenerationStore.Outcome held =
                rs.slingshot.agent.store.GenerationStore.serving(session);
        if (held instanceof final rs.slingshot.agent.store.GenerationStore.Held serving) {
            return serving.generation();
        }
        final EventStoreGeneration.Outcome first =
                EventStoreGeneration.of(EventStoreGeneration.FIRST);
        return ((EventStoreGeneration.Held) first).generation();
    }

    private static Optional<AgentOperationIdentifier> identifierIn(String asked,
                                                                   AgentContract contract) {
        if (asked == null || asked.isBlank()) {
            return Optional.empty();
        }
        final AgentOperationIdentifier.Outcome held = AgentOperationIdentifier.of(asked, contract);
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
