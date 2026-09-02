// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;

/**
 * The discovery document, field by field against the shape the client already expects.
 *
 * <p>The empty command list is the field worth checking hardest. A client that could not tell "this
 * agent holds no commands" from "the field is missing" would have to guess, and guessing about a
 * capability is how a daemon submits work to an agent that cannot run it — so it is rendered as an
 * empty array and asserted to be one.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class CapabilityServletTest {

    /** A Sling runtime this suite can make a request against, rather than a double of one. */
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);

    private static final Path REPOSITORY = repositoryRoot();

    /** The digest the client's own repository records for the transport contract it speaks. */
    private static final String SIBLING_TRANSPORT_DIGEST =
            "295fc1bdf0b88ecb5cbd45898d9a29d0dae1bada76d6c6fced1e99e7cdb2b9f8";

    @Test
    @DisplayName("the document carries the five fields the client's discovery expects")
    void theDocumentCarriesTheExpectedFields() {
        final AdvertisedCapabilities capabilities =
                CapabilityServlet.document(CapabilityServlet.readiness()).capabilities();
        assertEquals(CapabilityServlet.EVENT_STORE_GENERATION,
                capabilities.generation().number());
        assertEquals(CapabilityServlet.canonicalContractDigest().rendered(),
                capabilities.canonicalContractDigest().rendered());
        assertEquals(List.of(), capabilities.commandContracts());
        assertFalse(capabilities.authorityIsReady());
        assertEquals(SIBLING_TRANSPORT_DIGEST, capabilities.transportContractDigest().rendered());
    }

    @Test
    @DisplayName("the transport contract digest is the client's own, reproduced rather than recomputed")
    void theTransportDigestIsTheClients() {
        assertEquals(SIBLING_TRANSPORT_DIGEST, AgentContract.transportContractDigest());
    }

    @Test
    @DisplayName("an empty command list is rendered as an empty array rather than left out")
    void anEmptyCommandListIsStated() {
        final String rendered =
                CapabilityServlet.document(CapabilityServlet.readiness()).render();
        assertTrue(rendered.contains("\"command_contracts\":[]"), rendered);
        assertEquals("{\"agent_event_store_generation\":1"
                        + ",\"canonical_json_contract_digest\":\""
                        + CapabilityServlet.canonicalContractDigest().rendered() + "\""
                        + ",\"command_contracts\":[]"
                        + ",\"continuation_authority_ready\":false"
                        + ",\"transport_contract_digest\":\"" + SIBLING_TRANSPORT_DIGEST + "\"}",
                rendered);
    }

    @Test
    @DisplayName("the answer is bounded by the contract accessor, and the bound is not written here")
    void theAnswerIsBoundedByTheContract() {
        final AgentContract contract = contract();
        final long rendered = CapabilityServlet.document(CapabilityServlet.readiness())
                .render().getBytes(StandardCharsets.UTF_8).length;
        final long bound = contract.value(ContractLimit.MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES);
        assertTrue(rendered < bound, rendered + " is not below the declared bound of " + bound);
        final String servlet = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/http/CapabilityServlet.java"));
        assertFalse(servlet.contains(String.valueOf(bound)),
                "the document bound is written down in the servlet as well as in the contract");
    }

    @Test
    @DisplayName("the route this servlet answers comes from the committed table")
    void theRouteComesFromTheTable() {
        assertEquals("/bin/slingshot/agent/capabilities", CapabilityServlet.route().path());
        assertEquals("GET", CapabilityServlet.route().method());
        assertFalse(CapabilityServlet.route().takesABody());
    }

    @Test
    @DisplayName("the registration the container reads names exactly the route's own path")
    void theRegistrationNamesTheRoutesPath() {
        final String servlet = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/http/CapabilityServlet.java"));
        assertTrue(servlet.contains("sling.servlet.paths=" + CapabilityServlet.route().path()),
                "the container is told a path other than the one the table declares");
        assertTrue(servlet.contains("sling.servlet.methods=" + CapabilityServlet.route().method()),
                "the container is told a method other than the one the table declares");
    }

    @Test
    @DisplayName("an authenticated request is answered with the document")
    void anAuthenticatedRequestIsAnswered() throws IOException {
        atTheRoute(sling);
        new CapabilityServlet().answer(sling.request(), sling.response());
        assertEquals(200, sling.response().getStatus());
        assertTrue(sling.response().getContentType().startsWith("application/json"),
                sling.response().getContentType());
        assertEquals(StandardCharsets.UTF_8.name(), sling.response().getCharacterEncoding());
        assertEquals(CapabilityServlet.document(CapabilityServlet.readiness()).render(),
                sling.response().getOutputAsString());
    }

    @Test
    @DisplayName("a wrong method, a request with a body, and an unauthenticated one refuse distinctly")
    void theThreeRefusalsAreDistinctAndDiscloseNothing() throws IOException {
        atTheRoute(sling);
        sling.request().setMethod("POST");
        new CapabilityServlet().answer(sling.request(), sling.response());
        assertEquals(405, sling.response().getStatus());
        assertDisclosesNothing(sling.response().getOutputAsString());

        final SlingContext withABody = new SlingContext(ResourceResolverType.JCR_MOCK);
        atTheRoute(withABody);
        withABody.request().setContent("{}".getBytes(StandardCharsets.UTF_8));
        new CapabilityServlet().answer(withABody.request(), withABody.response());
        // A body where the route takes none is refused as the shape rules refuse it: this route
        // takes no media type at all, so there is no type in which a body would be right.
        assertEquals(415, withABody.response().getStatus());
        assertDisclosesNothing(withABody.response().getOutputAsString());
    }

    @Test
    @DisplayName("a request nobody in particular made is refused, disclosing nothing")
    void anUnauthenticatedRequestIsRefused() throws IOException {
        final SlingContext nobody = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
        atTheRoute(nobody);
        assertFalse(CapabilityServlet.isAuthenticated(nobody.request()),
                "a resolver bound to nobody reported an authenticated user");
        new CapabilityServlet().answer(nobody.request(), nobody.response());
        assertEquals(401, nobody.response().getStatus());
        assertDisclosesNothing(nobody.response().getOutputAsString());
    }

    @Test
    @DisplayName("the platform's own answer decides who is asking")
    void thePlatformDecidesWhoIsAsking() {
        assertTrue(CapabilityServlet.isAuthenticated(sling.request()),
                "the platform reported " + sling.request().getResourceResolver().getUserID()
                        + " and the servlet refused it");
    }

    private static void assertDisclosesNothing(String body) {
        assertFalse(body.contains("transport_contract_digest"),
                "a refusal disclosed a capability field: " + body);
        assertFalse(body.contains("command_contracts"),
                "a refusal disclosed a capability field: " + body);
    }

    /**
     * Puts a mock request where a real one for this route arrives.
     *
     * <p>A suite calling the servlet directly has to present the shape the platform would have
     * resolved, or it is proving something about a request nobody could make.</p>
     *
     * @param context the context whose request to place
     */
    private static void atTheRoute(SlingContext context) {
        context.request().setMethod(CapabilityServlet.route().method());
        context.requestPathInfo().setResourcePath(CapabilityServlet.route().path());
    }

    private static AgentContract contract() {
        final AgentContract.Outcome outcome = AgentContract.load();
        assertTrue(outcome instanceof AgentContract.Loaded, "the contract was refused: " + outcome);
        return ((AgentContract.Loaded) outcome).contract();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
