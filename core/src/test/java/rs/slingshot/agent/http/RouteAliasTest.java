// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.route.RouteAlias;

/**
 * The client's old spellings, and the fact that nobody gets them by default.
 *
 * <p>Two properties, and the second is the one that matters. An alias answers byte for byte what
 * its canonical route answers, because it is a second path to one servlet rather than a second
 * implementation. And an alias nobody turned on is not there at all: {@code /libs} is a namespace
 * a dispatcher passes more freely than anything else, and a state-changing route sitting in it is
 * a wider surface than this agent asked for.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class RouteAliasTest {

    /** An alias the committed table declares, which is the stream the client asks for. */
    private static final String ALIAS = "/libs/slingshot/agent/events";

    /** A subscription nobody took, so every answer below is the same refusal. */
    private static final String SUBSCRIPTION = "a-subscription-nobody-took";

    /** An identifier this build reads, which nothing here holds. */
    private static final String OPERATION =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("nothing serves an alias until a deployment says to")
    void nothingServesAnaliasUntilAdeploymentSaysTo() throws IOException, ServletException {
        assertEquals(List.of(), RouteAliasSwitch.served(),
                "an alias is served by a build nobody configured");
        final MockSlingHttpServletResponse refused = asking(ALIAS);
        assertEquals(ShapeRefusal.NOT_THE_EXACT_PATH.status(), refused.getStatus(),
                "an alias nobody turned on was answered");
        assertEquals("", refused.getOutputAsString(),
                "a refusal at an alias said more than the status");
    }

    @Test
    @DisplayName("an alias a deployment turned on answers byte for byte what its route answers")
    void analiasAdeploymentTurnedOnAnswersIdentically() throws IOException, ServletException {
        sling.registerInjectActivateService(new RouteAliasSwitch(),
                Map.of("served.paths", new String[] {ALIAS}));
        assertEquals(List.of(ALIAS), RouteAliasSwitch.served(),
                "the deployment's own list is not what is being served");
        final MockSlingHttpServletResponse canonical =
                asking(EventStreamServlet.route().path());
        final MockSlingHttpServletResponse alias = asking(ALIAS);
        assertEquals(canonical.getStatus(), alias.getStatus(),
                "the alias and the route it is a second path to answered differently");
        assertArrayEquals(canonical.getOutput(), alias.getOutput(),
                "the two answers differ in their bytes, so the alias is a second implementation");
        assertEquals(EventStreamServlet.UNKNOWN, alias.getStatus(),
                "the refusal being compared is not the one this suite arranged");
    }

    @Test
    @DisplayName("an alias reaches the servlet its route reaches, and no second one")
    void analiasReachesTheServletItsRouteReaches() {
        for (final RouteAlias alias : RouteAliasSwitch.declared()) {
            final Servlet servlet = RouteAliasSwitch.servletFor(alias.routeName()).orElseThrow();
            assertTrue(servlet instanceof AgentServlet,
                    alias.path() + " reaches something that is not one of this product's routes");
            assertEquals(servletFor(alias.routeName()).getClass(), servlet.getClass(),
                    alias.path() + " reaches a different implementation from its own route");
        }
        assertTrue(RouteAliasSwitch.servletFor("a-route-nobody-declared").isEmpty(),
                "a route nobody declared has a servlet");
    }

    @Test
    @DisplayName("every declared alias is a second path to a route this table declares")
    void everydeclaredAliasIsAsecondPathToArealRoute() {
        final AgentRouteTable table = assertInstanceOf(AgentRouteTable.Loaded.class,
                AgentRouteTable.load(), "the route table was refused").table();
        assertFalse(table.aliases().isEmpty(), "the committed table declares no alias at all");
        table.aliases().forEach(alias -> {
            assertFalse(alias.path().startsWith(table.prefix()),
                    alias.path() + " is inside the prefix, where it would be a second spelling of"
                            + " a served path");
            assertEquals(alias.routeName(), table.route(alias.routeName()).name());
            assertEquals(1, table.aliasesOf(alias.routeName()).size(),
                    alias.routeName() + " is reached by more than one second path");
        });
        assertTrue(RouteAliasSwitch.aliasAt(ALIAS).isPresent());
        assertTrue(RouteAliasSwitch.aliasAt("/libs/slingshot/agent/nothing").isEmpty());
    }

    private static Servlet servletFor(String routeName) {
        return RouteAliasSwitch.servletFor(routeName).orElseThrow();
    }

    private MockSlingHttpServletResponse asking(String path) throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("GET");
        request.setParameterMap(Map.of(
                EventStreamServlet.SUBSCRIPTION, SUBSCRIPTION,
                EventStreamServlet.OPERATION, OPERATION));
        ((MockRequestPathInfo) request.getRequestPathInfo()).setResourcePath(path);
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new EventStreamServlet().service(request, response);
        return response;
    }
}
