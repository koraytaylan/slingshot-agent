// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;

/**
 * Where a declared payload arrives, addressed by operation and slot and never by a repository path.
 *
 * <p>Addressing matters here more than anywhere else. A route that took a path would be a route
 * that decides where somebody else's bytes go; this one takes the operation a caller already has an
 * acknowledgement for and the slot that operation's own manifest declared, and the store decides
 * where those bytes live.</p>
 *
 * <p>Not a component of its own. Sling registers a path-bound servlet by its path alone, so the
 * two rows the committed table gives this path are one registration here: {@link ArtifactServlet}
 * is what the platform holds, and it hands a request over when the method is the one this row
 * answers.</p>
 *
 * <p>Everything the payload is held to was decided before it started arriving: the byte count and
 * the digest are the manifest's, the room was taken when the submission was admitted, and the slot
 * is one the record is already waiting for. What this route adds is that nothing partial is ever
 * reachable and nothing is charged twice for a retry.</p>
 */
public final class ArtifactIntakeServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "artifact-intake";

    /** The query member naming which operation the payload belongs to. */
    public static final String OPERATION_QUERY_MEMBER =
            OperationLookupServlet.OPERATION_QUERY_MEMBER;

    /** The query member naming which declared slot it goes into. */
    public static final String SLOT_QUERY_MEMBER = "artifact_slot";

    /** What a payload this side will not take is answered with. */
    public static final int REFUSED = 400;

    /** What a payload for a slot nothing is waiting for is answered with. */
    public static final int NOT_WAITED_FOR = 404;

    /** What a payload for a slot that already holds one is answered with. */
    public static final int ALREADY_COMPLETE = 409;

    /** What a payload this side took is answered with. */
    public static final int TAKEN = 204;

    private static final long serialVersionUID = 1L;

    /** Holds a servlet with nothing in it. */
    public ArtifactIntakeServlet() {
        super();
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Takes one declared payload, or says exactly why it did not.
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
            answer(request, response, admitted.caller(), held.contract());
        } catch (final RepositoryException unwritable) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
        }
    }

    /** What a request is answered with when this build cannot read its own contract or store. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    private void answer(SlingHttpServletRequest request, SlingHttpServletResponse response,
                        CallerIdentity caller, AgentContract contract)
            throws IOException, RepositoryException {
        final Optional<AgentOperationIdentifier> named = identifierIn(
                request.getParameter(OPERATION_QUERY_MEMBER), contract);
        final Optional<ArtifactSlot> slot = slotIn(request.getParameter(SLOT_QUERY_MEMBER));
        final Optional<Session> session = sessionOf(request);
        final Optional<StatePath.Caller> counted = caller.counted();
        if (named.isEmpty() || slot.isEmpty() || counted.isEmpty()) {
            refuse(response, REFUSED);
            return;
        }
        if (session.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        written(response, session.get(), counted.get(),
                new IntakeSlotWrite.Arriving(
                        StatePath.operation(serving(session.get()), named.get()), slot.get(),
                        request.getInputStream(), System.currentTimeMillis()),
                contract);
    }

    private void written(SlingHttpServletResponse response, Session session,
                         StatePath.Caller caller, IntakeSlotWrite.Arriving arriving,
                         AgentContract contract) throws IOException, RepositoryException {
        final IntakeSlotWrite.Outcome outcome =
                IntakeSlotWrite.write(session, caller, arriving, contract);
        final Optional<IntakeSlotWrite.Refused> refused = IntakeSlotWrite.refusalIn(outcome);
        if (refused.isEmpty()) {
            response.setStatus(TAKEN);
            response.setContentLength(0);
            response.getOutputStream().flush();
            return;
        }
        refuse(response, statusFor(refused.get().refusal()));
    }

    /**
     * What each refusal is answered with.
     *
     * <p>A slot nothing is waiting for and an operation nobody here holds are one answer, because a
     * caller who could tell them apart could learn what somebody else's manifest declared by
     * sending payloads at it.</p>
     *
     * @param refusal why nothing was written
     * @return the status
     */
    public static int statusFor(IntakeSlotWrite.IntakeRefusal refusal) {
        return switch (refusal) {
            case NO_OPERATION, UNDECLARED_SLOT -> NOT_WAITED_FOR;
            case ALREADY_COMPLETE, TERMINAL_OPERATION -> ALREADY_COMPLETE;
            case LENGTH_MISMATCH, DIGEST_MISMATCH -> REFUSED;
        };
    }

    private static Optional<ArtifactSlot> slotIn(String asked) {
        if (asked == null || asked.isBlank()) {
            return Optional.empty();
        }
        final ArtifactSlot.Outcome held = ArtifactSlot.of(asked);
        return held instanceof final ArtifactSlot.Held slot
                ? Optional.of(slot.slot())
                : Optional.empty();
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

    private static EventStoreGeneration serving(Session session) throws RepositoryException {
        final GenerationStore.Outcome held = GenerationStore.serving(session);
        return held instanceof final GenerationStore.Held serving
                ? serving.generation()
                : ((EventStoreGeneration.Held) EventStoreGeneration
                        .of(EventStoreGeneration.FIRST)).generation();
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
