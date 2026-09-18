// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.log.AgentLog;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * What the one base does before a servlet of its own sees anything.
 *
 * <p>Exercised through a route that actually exists rather than through a servlet written for the
 * occasion: the base is sealed, so the set of things that extend it is the set of routes this
 * product serves, and a suite that could add a fourteenth would be proving something about a
 * servlet nobody ships.</p>
 *
 * <p>The request that refuses to be read is the test worth reading: it throws if anybody asks it
 * for a parameter, a header, or a byte. Every refusal still happens, which is the property — the
 * shape of a request is settled from what the request is, and a servlet that had to read it first
 * would be one doing work for a caller it was about to turn away.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class AgentServletTest {

    private static final AgentRoute ROUTE = route();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    @DisplayName("a request at the exact path, with the route's method, reaches the servlet")
    void arequestAtTheExactPathReachesTheServlet()
            throws IOException, javax.servlet.ServletException {
        final var response = answering(request(ROUTE.path(), ROUTE.method()));
        assertEquals(OK, response.getStatus(), response.getOutputAsString());
        assertTrue(response.getOutputAsString().contains("agent_event_store_generation"),
                "a request for this route was answered with something other than its document");
    }

    /** What a request this route answers is answered with. */
    private static final int OK = 200;

    @Test
    @DisplayName("each of the five other spellings is refused, and the servlet never sees one")
    void thefiveOtherSpellingsAreRefused() throws IOException, javax.servlet.ServletException {
        for (final List<String> spelling : List.of(
                List.of(ROUTE.path(), "json", "", ""),
                List.of(ROUTE.path(), "", "json", ""),
                List.of(ROUTE.path(), "", "", "/anything"),
                List.of(ROUTE.path() + "/anything", "", "", ""),
                List.of(ROUTE.path().toUpperCase(java.util.Locale.ROOT), "", "", ""))) {
            final MockSlingHttpServletRequest request =
                    request(spelling.get(0), ROUTE.method());
            final MockRequestPathInfo resolved =
                    (MockRequestPathInfo) request.getRequestPathInfo();
            resolved.setSelectorString(spelling.get(1).isEmpty() ? null : spelling.get(1));
            resolved.setExtension(spelling.get(2).isEmpty() ? null : spelling.get(2));
            resolved.setSuffix(spelling.get(3).isEmpty() ? null : spelling.get(3));
            final var response = answering(request);
            assertEquals(ShapeRefusal.NOT_THE_EXACT_PATH.status(), response.getStatus(),
                    spelling + " was answered rather than refused");
            assertEquals("", response.getOutputAsString(), spelling + " reached the servlet");
        }
    }

    @Test
    @DisplayName("a method the route does not answer is refused with the method's own answer")
    void amethodTheRouteDoesNotAnswerIsRefused() throws IOException, javax.servlet.ServletException {
        final var response = answering(request(ROUTE.path(), "DELETE"));
        assertEquals(ShapeRefusal.WRONG_METHOD.status(), response.getStatus());
        assertEquals("", response.getOutputAsString(),
                "a request with the wrong method reached the servlet");
    }

    @Test
    @DisplayName("a body where the route takes none is refused before anything reads it")
    void abodyWhereNoneIsTakenIsRefused() throws IOException, javax.servlet.ServletException {
        final MockSlingHttpServletRequest request = request(ROUTE.path(), ROUTE.method());
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final var response = answering(request);
        assertEquals(ShapeRefusal.WRONG_MEDIA_TYPE.status(), response.getStatus());
        assertEquals("", response.getOutputAsString(),
                "a request carrying a body reached the servlet");
    }

    @Test
    @DisplayName("nothing is read from a request whose shape is refused")
    void nothingIsReadFromArefusedRequest() throws IOException, javax.servlet.ServletException {
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new CapabilityServlet().service(unreadable(request(ROUTE.path(), "DELETE")), response);
        assertEquals(ShapeRefusal.WRONG_METHOD.status(), response.getStatus(),
                "a refusal read the request it was refusing");
        assertEquals("", response.getOutputAsString());
    }

    @Test
    @DisplayName("a refusal's line names the route, the status, the refusal and what was observed")
    void arefusalsLineNamesWhatAnOperatorNeeds() {
        final String line = AgentLog.lineOf(AgentLog.event(
                AgentServlet.REFUSED_A_REQUEST, AgentServlet.refusal("submit",
                AuthorizationGate.STATUS, AuthorizationGate.Refusal.NO_SUCH_GROUP.name(),
                "no group on this instance is called operators")), AgentServlet::internal, BOUND);
        assertEquals(AgentServlet.REFUSED_A_REQUEST + " route=submit status=403"
                + " refusal=NO_SUCH_GROUP detail=no group on this instance is called operators",
                line);
        assertEquals(List.of(AgentServlet.ROUTE_FIELD, AgentServlet.STATUS_FIELD,
                        AgentServlet.REFUSAL_FIELD),
                List.copyOf(AgentServlet.refusal("submit", 400, "UNREADABLE_REQUEST", "")
                        .keySet()),
                "a refusal with nothing more to say carried an empty detail");
    }

    @Test
    @DisplayName("a refusal's detail naming where things are kept or what this is built of is withheld")
    void arefusalsDetailNamingAnInternalIsWithheld() {
        for (final String internal : List.of(rs.slingshot.agent.store.StatePath.ROOT + "/operations",
                rs.slingshot.agent.execution.CommandJobTopic.TOPIC,
                AgentServlet.class.getName())) {
            assertTrue(AgentServlet.internal("failed at " + internal), internal);
        }
        assertFalse(AgentServlet.internal("no group on this instance is called administrators"));
        final String line = AgentLog.lineOf(AgentLog.event(
                AgentServlet.REFUSED_A_REQUEST, AgentServlet.refusal("submit", 500, "UNREADABLE",
                        "the contract at " + AgentServlet.class.getName())),
                AgentServlet::internal, BOUND);
        assertTrue(line.endsWith("detail=" + AgentLog.WITHHELD), line);
    }

    @Test
    @DisplayName("a logged refusal is still a status and an empty body on the wire")
    void aloggedRefusalIsStillIndistinguishableOnTheWire() throws IOException {
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        AgentServlet.refuse(response, AuthorizationGate.STATUS, "submit",
                AuthorizationGate.Refusal.NOT_PERMITTED.name(), "this caller is in none of them");
        assertEquals(AuthorizationGate.STATUS, response.getStatus());
        assertEquals("", response.getOutputAsString(),
                "a refusal said on the wire what it wrote to the log");
    }

    /** The most one log message may be, which the contract declares and this suite need not load. */
    private static final long BOUND = 4096;

    @Test
    @DisplayName("the shape a request has is taken from the request and nothing else")
    void theshapeIsTakenFromTheRequest() {
        final MockSlingHttpServletRequest request = request(ROUTE.path(), ROUTE.method());
        final MockRequestPathInfo resolved = (MockRequestPathInfo) request.getRequestPathInfo();
        resolved.setSelectorString("one.two");
        resolved.setExtension("json");
        resolved.setSuffix("/trailing");
        final RequestShape shape = AgentServlet.shapeOf(request);
        assertEquals(ROUTE.path(), shape.resourcePath());
        assertEquals("one.two", shape.selectors());
        assertEquals("json", shape.extension());
        assertEquals("/trailing", shape.suffix());
        assertEquals(RequestShape.Body.ABSENT, shape.body());
        assertTrue(shape.spelled().startsWith(ROUTE.path()), shape.spelled());
        assertFalse(shape.spelled().equals(ROUTE.path()),
                "a request carrying selectors spelled the path the same way an exact one does");
        assertInstanceOf(RequestShape.Refused.class, shape.against(ROUTE));
    }

    private MockSlingHttpServletRequest request(String path, String method) {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod(method);
        ((MockRequestPathInfo) request.getRequestPathInfo()).setResourcePath(path);
        return request;
    }

    private static MockSlingHttpServletResponse answering(MockSlingHttpServletRequest request)
            throws IOException, javax.servlet.ServletException {
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new CapabilityServlet().service(request, response);
        return response;
    }

    /**
     * A request that throws if anybody reads anything out of it.
     *
     * @param request the request whose shape is still readable
     * @return the request, with everything but its shape refusing to be read
     */
    private static SlingHttpServletRequest unreadable(SlingHttpServletRequest request) {
        final List<String> reading = List.of("getParameter", "getParameterMap", "getInputStream",
                "getReader", "getHeader", "getResourceResolver", "getResource");
        return (SlingHttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {SlingHttpServletRequest.class},
                (proxy, method, arguments) -> {
                    if (reading.contains(method.getName())) {
                        throw new IllegalStateException(method.getName()
                                + " was read from a request that was about to be refused");
                    }
                    return method.invoke(request, arguments);
                });
    }

    private static AgentRoute route() {
        return assertInstanceOf(AgentRouteTable.Loaded.class, AgentRouteTable.load(),
                "the route table was refused").table().route("capabilities");
    }
}
