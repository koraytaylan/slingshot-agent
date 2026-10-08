// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.log.AgentLog;
import rs.slingshot.agent.log.LogEvent;
import rs.slingshot.agent.repository.AgentSession;

/** Slow real service dispatch is attributed without changing the flushed opaque response. */
@ExtendWith(SlingContextExtension.class)
final class LookupStageIntegrationTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @AfterEach
    void stopped() {
        new AgentSession().stopped();
    }

    @Test
    void delayedServiceDispatchIsReportedOnlyAfterTheExistingEmptyResponseFlush()
            throws java.io.IOException, javax.servlet.ServletException {
        final var clock = new AtomicLong();
        final var events = new ArrayList<LogEvent>();
        final var flushed = new AtomicBoolean();
        final var response = new FlushedResponse(flushed);
        bind(clock, 6626);
        new OperationLookupServlet().observe(request("1".repeat(64)), response, clock::get, (fields, bound)
                -> {
            assertTrue(flushed.get(), "a diagnostic was emitted before the original refusal flushed");
            events.add(AgentLog.event("slow logical lookup refusal", fields));
        });
        assertEquals(404, response.getStatus());
        assertEquals("", response.getOutputAsString());
        assertEquals(1, events.size());
        assertEquals("6626", events.getFirst().fields().get("lookup_dispatch_ms"));
        assertEquals("0", events.getFirst().fields().get("lookup_owner_ms"));
        assertEquals("1", events.getFirst().fields().get("lookup_work_completed"));
        assertEquals(null, response.getHeader("Server-Timing"));
    }

    @Test
    void aFastOpaqueRefusalRetainsItsResponseWithoutAnotherOperatorEvent()
            throws java.io.IOException, javax.servlet.ServletException {
        final var clock = new AtomicLong();
        final var events = new ArrayList<LogEvent>();
        bind(clock, 999);
        final var response = new MockSlingHttpServletResponse();
        new OperationLookupServlet().observe(request("1".repeat(64)), response, clock::get,
                (fields, bound) -> events.add(AgentLog.event("slow logical lookup refusal", fields)));
        assertEquals(404, response.getStatus());
        assertEquals("", response.getOutputAsString());
        assertTrue(events.isEmpty());
    }

    @Test
    void otherSlowRefusalsDoNotGainAnOperatorTimingEvent()
            throws java.io.IOException, javax.servlet.ServletException {
        final var clock = new AtomicLong();
        final var events = new ArrayList<LogEvent>();
        bind(clock, 6626);
        final var response = new MockSlingHttpServletResponse();
        new OperationLookupServlet().observe(request("not-an-operation-identifier"), response, clock::get,
                (fields, bound) -> events.add(AgentLog.event("slow logical lookup refusal", fields)));
        assertEquals(400, response.getStatus());
        assertEquals("", response.getOutputAsString());
        assertTrue(events.isEmpty());
    }

    @Test
    void theStatelessServletRemainsSerializableAndServiceable()
            throws java.io.IOException, javax.servlet.ServletException {
        final var servlet = new OperationLookupServlet();
        final var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(servlet);
        }
        assertTrue(bytes.size() > 0);
        bind(new AtomicLong(), 0);
        final var response = new MockSlingHttpServletResponse();
        servlet.service(request("1".repeat(64)), response);
        assertEquals(404, response.getStatus());
        assertEquals("", response.getOutputAsString());
    }

    private MockSlingHttpServletRequest request(String identifier) {
        final var request = new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("GET");
        ((MockRequestPathInfo)
                request.getRequestPathInfo()).setResourcePath(OperationLookupServlet.route().path());
        request.setParameterMap(Map.of(OperationLookupServlet.OPERATION_QUERY_MEMBER, identifier));
        return request;
    }

    private void bind(AtomicLong clock, long milliseconds) {
        final ResourceResolverFactory delegate = sling.getService(ResourceResolverFactory.class);
        final var delayed = (ResourceResolverFactory) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]
                        {ResourceResolverFactory.class},
                (proxy, method, arguments) -> {
                    if ("getServiceResourceResolver".equals(method.getName())) {
                        clock.addAndGet(milliseconds * 1_000_000);
                    }
                    return method.invoke(delegate, arguments);
                });
        new AgentSession().available(delayed);
    }

    private static final class FlushedResponse extends MockSlingHttpServletResponse {
        private final AtomicBoolean flushed;

        private FlushedResponse(AtomicBoolean flushed) {
            this.flushed = flushed;
        }

        @Override
        public javax.servlet.ServletOutputStream getOutputStream() {
            final var delegate = super.getOutputStream();
            return new javax.servlet.ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setWriteListener(javax.servlet.WriteListener listener) {
                    delegate.setWriteListener(listener);
                }

                @Override
                public void write(int value) throws java.io.IOException {
                    delegate.write(value);
                }

                @Override
                public void flush() throws java.io.IOException {
                    delegate.flush();
                    flushed.set(true);
                }
            };
        }
    }
}
