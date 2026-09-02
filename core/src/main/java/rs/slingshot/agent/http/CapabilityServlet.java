// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.CommittedResource;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;
import rs.slingshot.agent.discovery.CapabilityDocument;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * The discovery route: what this agent is, answered before a client sends it anything.
 *
 * <p>It is bound by the path the committed route table gives it and by no path written here. The
 * registration creates no repository node, so the choice of namespace collides with nothing today —
 * which is exactly why the collision would arrive later, on somebody else's instance, at an upgrade
 * nobody connected to this decision.</p>
 *
 * <p>Three refusals, each distinct and none disclosing a capability field: a request that is not
 * this route's method, a request carrying a body the route does not take, and a request whose user
 * Sling did not authenticate. What that user must additionally be permitted is Plan 0004's, and the
 * absence of that check here is a gap this commit has rather than a decision it made.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/capabilities",
        "sling.servlet.methods=GET"
})
public final class CapabilityServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "capabilities";

    /** Where this build's canonical-byte contract and its digest are embedded. */
    public static final String CANONICAL_CONTRACT_RESOURCE =
            "/rs/slingshot/agent/contract/command-canonical-json-1.json";

    /** Where the digest committed beside those bytes is embedded. */
    public static final String CANONICAL_DIGEST_RESOURCE =
            "/rs/slingshot/agent/contract/command-canonical-json-1.sha256";

    /** The event-store generation this build serves, until Plan 0003 gives it one to rotate. */
    public static final long EVENT_STORE_GENERATION = 1;

    private static final long serialVersionUID = 1L;

    /**
     * Holds a servlet with nothing in it.
     *
     * <p>A declarative-services component is a singleton the container hands to every caller at
     * once, so this one holds no state at all: everything it answers with, it reads from the
     * committed contract and the committed route table at the moment it is asked.</p>
     */
    public CapabilityServlet() {
        super();
    }

    /** What a request refused for its method is answered with. */
    private static final int METHOD_NOT_ALLOWED = 405;

    /** What a request carrying a body this route does not take is answered with. */
    private static final int BAD_REQUEST = 400;

    /** What the platform's own user is called, which is nobody in particular. */
    private static final String ANONYMOUS = "anonymous";

    /**
     * Which route this servlet answers, by the name the committed table gives it.
     *
     * @return the route's name
     */
    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Answers a request whose shape the base has already settled.
     *
     * @param request the request as Sling resolved it
     * @param response the response to write
     * @throws IOException if the response cannot be written, which is the caller having gone
     */
    @Override
    protected void serve(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        answer(request, response);
    }

    /**
     * Answers the discovery document, or refuses for exactly one reason.
     *
     * @param request the request as Sling resolved it
     * @param response the response to write
     * @throws IOException if the response cannot be written, which is the caller having gone
     */
    public void answer(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        final AgentRoute route = route();
        final RequestShape.Outcome shape =
                AgentServlet.shapeOf(request).against(route);
        final java.util.Optional<RequestShape.Refused> refused =
                RequestShape.refusalIn(shape);
        if (refused.isPresent()) {
            // The base settles this before a servlet is reached at all. It is decided again here
            // because `answer` is also what a suite calls directly, and a check a suite can walk
            // around is a check that proves nothing about the route.
            refuse(response, refused.get().refusal().status());
            return;
        }
        if (AuthenticationGate.refusalIn(
                AuthenticationGate.of(request)).isPresent()) {
            refuse(response, AuthenticationGate.STATUS);
            return;
        }
        final String document = document(readiness()).render();
        response.setContentType(route.mediaType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(document);
    }

    /**
     * Where this build observes the continuation-key authority's readiness.
     *
     * <p>Nothing in this build implements that authority yet, so what is observed is that it is not
     * ready. It is observed rather than written into the document because an agent advertising a
     * readiness it does not have is answered with a paged query whose token nothing can validate —
     * and this servlet holds no state at all, so there is nowhere for a stale answer to live.</p>
     *
     * @return where readiness is read from
     */
    public static AdvertisedCapabilities.Readiness readiness() {
        return () -> AdvertisedCapabilities.ContinuationAuthority.NOT_READY;
    }

    /**
     * What this agent advertises itself as, observed rather than remembered.
     *
     * @param observing where the authority's readiness is read from
     * @return the document, bounded below the contract's own document limit
     * @throws IllegalStateException if the embedded contract cannot be authenticated or the
     *     document cannot be answered, because an agent that cannot say what it is must not say
     *     that it can be used
     */
    public static CapabilityDocument document(AdvertisedCapabilities.Readiness observing) {
        final AgentContract.Outcome outcome = AgentContract.load();
        if (outcome instanceof final AgentContract.Refused refused) {
            throw new IllegalStateException("no contract: " + refused.failure() + refused.detail());
        }
        final AgentContract contract = ((AgentContract.Loaded) outcome).contract();
        final AdvertisedCapabilities capabilities = new AdvertisedCapabilities(
                generation(),
                canonicalContractDigest(),
                List.of(),
                observing.observe(),
                digest(AgentContract.transportContractDigest()));
        final CapabilityDocument.Outcome built = CapabilityDocument.of(capabilities,
                contract.value(ContractLimit.MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES));
        if (built instanceof final CapabilityDocument.Refused refused) {
            throw new IllegalStateException("cannot answer: " + refused.refusal() + refused.detail());
        }
        return ((CapabilityDocument.Held) built).document();
    }

    /**
     * The incarnation of the event store this build serves.
     *
     * @return the generation
     */
    public static EventStoreGeneration generation() {
        final EventStoreGeneration.Outcome held = EventStoreGeneration.of(EVENT_STORE_GENERATION);
        if (held instanceof final EventStoreGeneration.Refused refused) {
            throw new IllegalStateException("no generation: " + refused.detail());
        }
        return ((EventStoreGeneration.Held) held).generation();
    }

    /**
     * The digest of the canonical-byte contract this build's documents are written under.
     *
     * @return the digest
     * @throws IllegalStateException if the contract does not authenticate, because a digest read
     *     from bytes nobody checked is a claim rather than a fact
     */
    public static DigestValue canonicalContractDigest() {
        final CommittedResource.Outcome outcome =
                CommittedResource.load(CANONICAL_CONTRACT_RESOURCE, CANONICAL_DIGEST_RESOURCE);
        if (outcome instanceof final CommittedResource.Refused refused) {
            throw new IllegalStateException("no canonical form: " + refused.failure() + refused.detail());
        }
        return ((CommittedResource.Loaded) outcome).resource().digest();
    }

    private static DigestValue digest(String rendered) {
        final DigestValue.Outcome held = DigestValue.of(rendered);
        if (held instanceof final DigestValue.Refused refused) {
            throw new IllegalStateException("not a digest: " + refused.detail());
        }
        return ((DigestValue.Held) held).digest();
    }

    /**
     * The route this servlet answers, read from the committed table.
     *
     * @return the route
     * @throws IllegalStateException if the table cannot be read, because a servlet that invented
     *     its own path is the defect the table exists to prevent
     */
    public static AgentRoute route() {
        final AgentRouteTable.Outcome outcome = AgentRouteTable.load();
        if (outcome instanceof final AgentRouteTable.Refused refused) {
            throw new IllegalStateException("no route table: " + refused.failure() + refused.detail());
        }
        return ((AgentRouteTable.Loaded) outcome).table().route(ROUTE_NAME);
    }

    /**
     * Whether the platform authenticated somebody in particular for this request.
     *
     * <p>Kept as this route's own way of asking so that a suite can ask it too, and answered by the
     * one gate every route goes through rather than by anything written here.</p>
     *
     * @param request the request as Sling resolved it
     * @return whether somebody in particular is asking
     */
    public static boolean isAuthenticated(SlingHttpServletRequest request) {
        return AuthenticationGate.of(request)
                instanceof AuthenticationGate.Admitted;
    }
}
