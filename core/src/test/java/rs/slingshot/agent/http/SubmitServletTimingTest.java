// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.ServletException;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionLedger;

/** Request-local phase headers follow the real servlet's execution and resend boundaries. */
@ExtendWith(SlingContextExtension.class)
final class SubmitServletTimingTest {

    private static final long COMMAND_DELAY_MILLISECONDS = 100;
    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @BeforeEach
    void configureOperators() {
        new rs.slingshot.agent.repository.AgentSession().available(
                sling.getService(org.apache.sling.api.resource.ResourceResolverFactory.class));
        MockOsgi.activate(new AuthorizationGate(), MockOsgi.newBundleContext(),
                java.util.Map.of("permitted.groups", new String[] { "administrators" }));
    }

    @AfterEach
    void revokeOperators() {
        new rs.slingshot.agent.repository.AgentSession().stopped();
        new AuthorizationGate().stopped();
    }

    @Test
    void timingHeadersAreNumericAndBelongToEachRequest()
            throws RepositoryException, IOException, ServletException {
        prepared();
        final Counting commands = new Counting(0);
        final SubmitServlet servlet = new SubmitServlet(commands);
        final var first = new MockSlingHttpServletResponse();
        servlet.service(request(), first);
        assertEquals(SubmitServlet.ACCEPTED, first.getStatus());
        timingHeader(first);
        final var again = new MockSlingHttpServletResponse();
        servlet.service(request(), again);
        assertEquals(SubmitServlet.ACCEPTED, again.getStatus());
        assertTrue(timingHeader(again).endsWith(",execution;dur=0,persistence;dur=0"));
        assertEquals(first.getOutputAsString().replace("\"already_accepted\":false",
                "\"already_accepted\":true"), again.getOutputAsString());
        assertEquals(1, commands.ran(), "timing a resend must never repeat its command");
    }

    @Test
    void timeSpentInsideTheRuntimeIsAttributedToExecution()
            throws RepositoryException, IOException, ServletException {
        prepared();
        final Counting commands = new Counting(COMMAND_DELAY_MILLISECONDS);
        final var response = new MockSlingHttpServletResponse();
        new SubmitServlet(commands).service(request(), response);
        assertEquals(SubmitServlet.ACCEPTED, response.getStatus());
        final String execution = timingHeader(response).split(",")[1].split("=")[1];
        assertTrue(Long.parseLong(execution) >= COMMAND_DELAY_MILLISECONDS,
                "the inline command's wait was charged to a different phase");
        assertEquals(1, commands.ran());
    }

    static String timingHeader(MockSlingHttpServletResponse response) {
        final String header = response.getHeader(SubmitServlet.SERVER_TIMING);
        assertNotNull(header, "an accepted response omitted its request timing");
        assertTrue(header.matches("admission;dur=[0-9]+,execution;dur=[0-9]+,"
                + "persistence;dur=[0-9]+"), "a timing header carried a value outside its grammar");
        assertTrue(header.length() < 100);
        return header;
    }

    private static final class Counting implements SubmitServlet.Commands {
        private static final long serialVersionUID = 1L;
        private final AtomicInteger ran = new AtomicInteger();
        private final long pauseMilliseconds;

        private Counting(long pauseMilliseconds) {
            this.pauseMilliseconds = pauseMilliseconds;
        }

        @Override
        public boolean serves(String wireName) {
            return "query_paths".equals(wireName);
        }

        @Override
        public ExecutionOutcome.Completion run(LogicalOperation operation,
                DocumentValue.Mapping submission, Session session) {
            try {
                Thread.sleep(pauseMilliseconds);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the timing test was interrupted", interrupted);
            }
            ran.incrementAndGet();
            return new ExecutionOutcome.Succeeded(new ExecutionOutcome.Inline("{\"matches\":[]}"));
        }

        int ran() {
            return ran.get();
        }
    }

    private MockSlingHttpServletRequest request() throws IOException {
        final String declared = System.getProperty("slingshot.repository.root");
        assertNotNull(declared);
        final String fixture = Files.readString(Path.of(declared).resolve(
                "core/src/test/resources/fixtures/submit-servlet/a-submission.json"),
                StandardCharsets.UTF_8);
        final var request = new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(fixture.replaceAll("\"request_start_unix_milliseconds\": [0-9]+",
                "\"request_start_unix_milliseconds\": " + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8));
        ((MockRequestPathInfo) request.getRequestPathInfo()).setResourcePath(SubmitServlet.route().path());
        return request;
    }

    private void prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class));
        final StatePath.Caller caller = assertInstanceOf(StatePath.Held.class,
                StatePath.caller(session.getUserID())).caller();
        SubmissionTestFixture.walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        LedgerAdmission.prepare(session, caller);
        SubscriptionLedger.prepare(session, caller);
        SubmissionTestFixture.walked(session, StatePath.deployment(StatePath.OPERATIONS).path());
        SubmissionTestFixture.permitted(session);
        for (final AccountedQuantity quantity : java.util.List.of(AccountedQuantity.OPERATION_DETAIL_ROWS,
                AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS, AccountedQuantity.RESULT_ROWS,
                AccountedQuantity.RESULT_BYTES, AccountedQuantity.SNAPSHOT_ROWS,
                AccountedQuantity.SNAPSHOT_BYTES)) {
            CapacityLedger.prepare(session, quantity, caller);
        }
    }
}
