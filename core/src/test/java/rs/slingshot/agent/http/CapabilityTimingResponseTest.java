// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The real answer path emits only durations on authenticated success and preserves body bytes. */
@ExtendWith(SlingContextExtension.class)
final class CapabilityTimingResponseTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    void authenticatedSuccessHasOneNumericHeaderAndTheSameCanonicalBody() throws IOException {
        atTheRoute(sling);
        new CapabilityServlet().answer(sling.request(), sling.response());
        assertEquals(200, sling.response().getStatus());
        assertEquals(1, sling.response().getHeaders(CapabilityServlet.SERVER_TIMING).size());
        assertTrue(sling.response().getHeader(CapabilityServlet.SERVER_TIMING).matches(
                "cap_shape;dur=[0-9]+,cap_identity;dur=[0-9]+,cap_document;dur=[0-9]+"));
        assertEquals(CapabilityServlet.document(CapabilityServlet.readiness()).render(),
                sling.response().getOutputAsString());
    }

    @Test
    void shapeRefusalDisclosesNoTimingHeader() throws IOException {
        atTheRoute(sling);
        sling.request().setMethod("POST");
        new CapabilityServlet().answer(sling.request(), sling.response());
        assertEquals(405, sling.response().getStatus());
        assertNull(sling.response().getHeader(CapabilityServlet.SERVER_TIMING));
        assertEquals("", sling.response().getOutputAsString());
    }

    @Test
    void unauthenticatedRefusalDisclosesNoTimingHeader() throws IOException {
        final SlingContext nobody = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
        atTheRoute(nobody);
        new CapabilityServlet().answer(nobody.request(), nobody.response());
        assertEquals(401, nobody.response().getStatus());
        assertNull(nobody.response().getHeader(CapabilityServlet.SERVER_TIMING));
        assertEquals("", nobody.response().getOutputAsString());
    }

    private static void atTheRoute(SlingContext context) {
        context.request().setMethod(CapabilityServlet.route().method());
        context.requestPathInfo().setResourcePath(CapabilityServlet.route().path());
    }
}
