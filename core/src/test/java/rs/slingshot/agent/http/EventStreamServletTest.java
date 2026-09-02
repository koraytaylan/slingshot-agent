// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
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
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionLedger;
import rs.slingshot.agent.stream.EventEncoder;
import rs.slingshot.agent.stream.Heartbeat;
import rs.slingshot.agent.stream.ResetNotice;
import rs.slingshot.agent.stream.SessionBound;
import rs.slingshot.agent.stream.StreamAdmission;
import rs.slingshot.agent.stream.StreamExecutor;
import rs.slingshot.agent.stream.StreamResumption;
import rs.slingshot.agent.stream.StreamTicker;
import rs.slingshot.agent.wire.JobEvent;

/**
 * One subscriber's live view of one operation, and every way it is refused one.
 *
 * <p>Time is advanced rather than waited through. A suite that slept for a heartbeat interval would
 * be proving what one machine did one afternoon, and would take the session bound to say it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class EventStreamServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/event-stream");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> IN_ORDER =
            List.of("accepted.json", "started.json", "succeeded.json");

    private static final String SUBSCRIPTION = "following-daemon-one";

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("events after a cursor are delivered in sequence order and its own is not")
    void eventsafterAcursorAreDeliveredInSequenceOrder() throws RepositoryException, IOException,
            ServletException {
        recorded();
        final MockSlingHttpServletResponse served = asking("with-events-waiting", "1:0");
        assertEquals(EventStreamServlet.SERVING, served.getStatus(), served.getOutputAsString());
        assertTrue(served.getContentType().startsWith(EventEncoder.MEDIA_TYPE),
                "a stream was served as " + served.getContentType());
        final String wire = served.getOutputAsString();
        assertEquals(List.of("1:1", "1:2"), identifiersIn(wire),
                "the events were not delivered in sequence order, or the cursor's own was: "
                        + wire);
        assertTrue(wire.indexOf("event:started") < wire.indexOf("event:succeeded"),
                "the kinds are not in the order the events were recorded in: " + wire);
    }

    @Test
    @DisplayName("a subscriber with no position at all is told where it is before anything else")
    void asubscriberWithNoPositionIsToldWhereItIs() throws RepositoryException, IOException,
            ServletException {
        recorded();
        final String wire = ask("with-events-waiting").getOutputAsString();
        assertTrue(ResetNotice.isAreset(wire),
                "a subscriber with no position was served news before it was told where it is: "
                        + wire);
        assertTrue(wire.contains("\"sequence\":2"),
                "the notice does not carry what to resynchronise from: " + wire);
        assertEquals(List.of(), identifiersIn(wire),
                "a subscriber with no position was shown events its own snapshot accounts for: "
                        + wire);
    }

    @Test
    @DisplayName("a stream with nothing waiting delivers heartbeats and no events at all")
    void astreamWithNothingWaitingDeliversHeartbeats() throws RepositoryException, IOException,
            ServletException {
        recorded();
        final MockSlingHttpServletResponse served = ask("with-nothing-waiting");
        assertEquals(EventStreamServlet.SERVING, served.getStatus(), served.getOutputAsString());
        final String wire = served.getOutputAsString();
        assertEquals(List.of(), identifiersIn(wire),
                "a stream with nothing waiting delivered an event: " + wire);
        assertTrue(heartbeatsIn(wire) > 1,
                "a stream with nothing to say said nothing at all, which is a stream that has"
                        + " stopped: " + wire);
    }

    @Test
    @DisplayName("the three refusals before a stream opens are ordinary responses and open nothing")
    void thethreeRefusalsBeforeAstreamOpensAreOrdinary() throws RepositoryException, IOException,
            ServletException {
        recorded();
        final Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("on-an-unknown-subscription", EventStreamServlet.UNKNOWN);
        expected.put("on-a-foreign-generation", EventStreamServlet.RESET);
        expected.put("on-an-operation-this-caller-cannot-see", EventStreamServlet.UNKNOWN);
        expected.put("named-with-nonsense", EventStreamServlet.REFUSED);
        for (final Map.Entry<String, Integer> refusal : expected.entrySet()) {
            final MockSlingHttpServletResponse answered = ask(refusal.getKey());
            assertEquals(refusal.getValue().intValue(), answered.getStatus(),
                    refusal.getKey() + " was answered with something else");
            assertNotEquals(EventEncoder.MEDIA_TYPE, contentTypeOf(answered),
                    refusal.getKey() + " opened a stream to say it would not open one");
            assertEquals("", answered.getOutputAsString(),
                    refusal.getKey() + " was told more than the status");
        }
        assertEquals(0, CapacityLedger.held(session(), AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                        CONTRACT),
                "a refusal took room for a stream nobody opened");
    }

    @Test
    @DisplayName("no request shape reaches an operation the caller did not name")
    void norequestShapeReachesAnotherOperation() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        walked(session, operation("another-operation.json").path());
        record(session, "another-operation.json");
        final String elsewhere = identifierOf("another-operation.json");
        for (final String crafted : List.of("with-events-waiting", "with-nothing-waiting",
                "on-a-foreign-generation", "on-an-operation-this-caller-cannot-see")) {
            assertFalse(ask(crafted).getOutputAsString().contains(elsewhere),
                    crafted + " reached an event belonging to an operation it did not name");
        }
        assertEquals(1, EventLedger.held(session,
                        operation("another-operation.json").child(EventLedger.NODE)).size(),
                "the other operation holds no events, so nothing above was kept from reaching one");
    }

    @Test
    @DisplayName("a stream that ended gave back the room it took, however many were served")
    void astreamThatEndedGaveBackItsRoom() throws RepositoryException, IOException,
            ServletException {
        recorded();
        assertEquals(EventStreamServlet.SERVING, ask("with-events-waiting").getStatus());
        assertEquals(0, CapacityLedger.held(session(), AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                        CONTRACT),
                "a stream that ended is still counted as open");
        assertEquals(0, CapacityLedger.heldBy(session(),
                        AccountedQuantity.CONCURRENT_EVENT_STREAMS, caller(), CONTRACT),
                "a stream that ended is still counted against the caller who opened it");
        assertEquals(EventStreamServlet.SERVING, ask("with-events-waiting").getStatus(),
                "a second stream was refused, which is a room that was never given back");
    }

    @Test
    @DisplayName("a caller past the stream bound is refused with a hint it can wait through")
    void acallerPastTheStreamBoundIsRefused() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        for (long taken = 0; taken < CONTRACT.value(
                rs.slingshot.agent.contract.ContractLimit.MAXIMUM_CALLER_CONCURRENT_EVENT_STREAMS);
                taken = taken + 1) {
            assertInstanceOf(StreamAdmission.Admitted.class,
                    StreamAdmission.open(session, caller(), CONTRACT), "room ran out early");
        }
        final MockSlingHttpServletResponse refused = ask("with-events-waiting");
        assertEquals(EventStreamServlet.AT_CAPACITY, refused.getStatus(),
                "a caller past their share of the streams was served one");
        assertEquals("60", refused.getHeader(EventStreamServlet.RETRY_AFTER),
                "the hint is not the cap the contract declares");
        assertEquals("", refused.getOutputAsString(),
                "a refusal at capacity opened a stream to say there was no room for one");
    }

    @Test
    @DisplayName("the request thread is released before anything is waited for")
    void therequestThreadIsReleasedBeforeAnythingIsWaitedFor() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/http/EventStreamServlet.java"));
        assertTrue(source.indexOf("StreamHandoff.from") < source.indexOf("StreamExecutor.open"),
                "the thread is not released before the stream is handed to the pool");
        assertTrue(source.indexOf("StreamExecutor.open") < source.lastIndexOf("writer.serve"),
                "the stream is written before it is handed to the pool");
        assertFalse(source.contains("Thread.sleep"),
                "a route waits on the thread the request arrived on");
        assertFalse(read(REPOSITORY.resolve(
                        "core/src/main/java/rs/slingshot/agent/stream/StreamExecutor.java"))
                        .contains("Scheduler"),
                "the streams are written from the platform's shared scheduler");
    }


    @Test
    @DisplayName("a container that releases the thread has its stream written from this pool")
    void acontainerThatReleasesTheThreadHasItsStreamWrittenFromThisPool()
            throws RepositoryException, IOException, ServletException, InterruptedException {
        recorded();
        final ReleasingRequest request = new ReleasingRequest(sling.resourceResolver());
        request.setMethod("GET");
        request.setParameterMap(Map.of(
                EventStreamServlet.SUBSCRIPTION, SUBSCRIPTION,
                EventStreamServlet.OPERATION, identifierOf("nothing-waiting.json"),
                EventStreamServlet.GENERATION, String.valueOf(EventStoreGeneration.FIRST)));
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(EventStreamServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        final EventStreamServlet servlet = new EventStreamServlet(new AdvancingTicker());
        servlet.service(request, response);
        assertTrue(request.ended(WHILE_A_SUITE_WAITS),
                "the stream never ended, which is a request nobody completed");
        assertEquals(EventStreamServlet.SERVING, response.getStatus());
        assertTrue(request.wroteFrom().startsWith(StreamExecutor.THREAD_NAME),
                "the stream was written from " + request.wroteFrom()
                        + " rather than from this bundle's own pool");
        assertEquals(SessionBound.milliseconds(CONTRACT)
                        + Heartbeat.intervalMilliseconds(CONTRACT), request.timeout(),
                "the container would end the session before this side does");
        servlet.stopped();
    }

    /** How long a suite waits for a stream that is being written somewhere else. */
    private static final long WHILE_A_SUITE_WAITS = 30;

    /** A container that releases the request thread, which is every container but a suite's. */
    private static final class ReleasingRequest extends MockSlingHttpServletRequest {

        private final java.util.concurrent.CountDownLatch completed =
                new java.util.concurrent.CountDownLatch(1);

        private final java.util.concurrent.atomic.AtomicLong timeout =
                new java.util.concurrent.atomic.AtomicLong();

        private final java.util.concurrent.atomic.AtomicReference<String> wroteFrom =
                new java.util.concurrent.atomic.AtomicReference<>("");

        private ReleasingRequest(org.apache.sling.api.resource.ResourceResolver resolver) {
            super(resolver);
        }

        @Override
        public javax.servlet.AsyncContext startAsync() {
            return new RecordingContext();
        }

        @Override
        public javax.servlet.AsyncContext getAsyncContext() {
            return new RecordingContext();
        }

        private boolean ended(long seconds) throws InterruptedException {
            return completed.await(seconds, java.util.concurrent.TimeUnit.SECONDS);
        }

        private String wroteFrom() {
            return wroteFrom.get();
        }

        private long timeout() {
            return timeout.get();
        }

        /**
         * The bookkeeping a container does for a released request, remembered rather than done.
         *
         * <p>What a suite needs from it is what a container would decide: how long it would have
         * let the request run, whether the stream ever ended, and which thread ended it.</p>
         */
        private final class RecordingContext implements javax.servlet.AsyncContext {

            @Override
            public void complete() {
                wroteFrom.set(Thread.currentThread().getName());
                completed.countDown();
            }

            @Override
            public void setTimeout(long milliseconds) {
                timeout.set(milliseconds);
            }

            @Override
            public long getTimeout() {
                return timeout.get();
            }

            @Override
            public javax.servlet.ServletRequest getRequest() {
                throw new UnsupportedOperationException("a suite asks a container nothing");
            }

            @Override
            public javax.servlet.ServletResponse getResponse() {
                throw new UnsupportedOperationException("a suite asks a container nothing");
            }

            @Override
            public boolean hasOriginalRequestAndResponse() {
                return true;
            }

            @Override
            public void dispatch() {
                throw new UnsupportedOperationException("a stream is not dispatched anywhere");
            }

            @Override
            public void dispatch(String path) {
                throw new UnsupportedOperationException("a stream is not dispatched anywhere");
            }

            @Override
            public void dispatch(javax.servlet.ServletContext context, String path) {
                throw new UnsupportedOperationException("a stream is not dispatched anywhere");
            }

            @Override
            public void start(Runnable runnable) {
                throw new UnsupportedOperationException("a stream runs on this bundle's own pool");
            }

            @Override
            public void addListener(javax.servlet.AsyncListener listener) {
                throw new UnsupportedOperationException("a suite listens for nothing");
            }

            @Override
            public void addListener(javax.servlet.AsyncListener listener,
                                    javax.servlet.ServletRequest request,
                                    javax.servlet.ServletResponse response) {
                throw new UnsupportedOperationException("a suite listens for nothing");
            }

            @Override
            public <T extends javax.servlet.AsyncListener> T createListener(Class<T> kind) {
                throw new UnsupportedOperationException("a suite listens for nothing");
            }
        }
    }

    private static String contentTypeOf(MockSlingHttpServletResponse answered) {
        final String declared = answered.getContentType();
        return declared == null ? "" : declared;
    }

    private static List<String> identifiersIn(String wire) {
        return wire.lines()
                .filter(line -> line.startsWith(EventEncoder.IDENTIFIER_FIELD
                        + EventEncoder.FIELD_SEPARATOR))
                .map(line -> line.substring(EventEncoder.IDENTIFIER_FIELD.length()
                        + EventEncoder.FIELD_SEPARATOR.length()))
                .toList();
    }

    private static long heartbeatsIn(String wire) {
        return wire.lines().filter(line -> line.startsWith(EventEncoder.FIELD_SEPARATOR)).count();
    }

    private MockSlingHttpServletResponse ask(String fixture) throws IOException, ServletException {
        return asking(fixture, "");
    }

    private MockSlingHttpServletResponse asking(String fixture, String resumption)
            throws IOException, ServletException {
        final DocumentValue.Mapping asked = assertInstanceOf(DocumentValue.Mapping.class,
                asks().member(fixture).orElseThrow(), fixture + " is not an ask");
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("GET");
        request.setParameterMap(Map.of(
                EventStreamServlet.SUBSCRIPTION, text(asked, EventStreamServlet.SUBSCRIPTION),
                EventStreamServlet.OPERATION, text(asked, EventStreamServlet.OPERATION),
                EventStreamServlet.GENERATION,
                String.valueOf(whole(asked, EventStreamServlet.GENERATION))));
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(EventStreamServlet.route().path());
        if (!resumption.isEmpty()) {
            request.setHeader(StreamResumption.RESUMPTION_HEADER, resumption);
        }
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new EventStreamServlet(new AdvancingTicker()).service(request, response);
        return response;
    }

    /**
     * A clock a suite moves rather than waits for.
     *
     * <p>Every pause is time passing at once, so a session that ends at its bound ends here in the
     * same number of rounds it would on an instance, and in no time at all.</p>
     */
    private static final class AdvancingTicker implements StreamTicker {

        @Override
        public long elapsedMilliseconds() {
            return milliseconds();
        }


        private static final long serialVersionUID = 1L;

        private long now = NOW;

        @Override
        public long milliseconds() {
            return now;
        }

        @Override
        public void pause(long milliseconds) {
            now = now + milliseconds;
        }
    }

    private Session recorded() throws RepositoryException {
        final Session session = prepared();
        for (final String held : IN_ORDER) {
            record(session, held);
        }
        return session;
    }

    private void record(Session session, String fixture) throws RepositoryException {
        assertInstanceOf(EventLedger.Appended.class, SnapshotStore.record(session, caller(),
                event(fixture), canonical(fixture), NOW, CONTRACT), fixture + " was not recorded");
    }

    private Session prepared() throws RepositoryException {
        final Session session = session();
        walked(session, StatePath.ROOT);
        walked(session, operation("accepted.json").path());
        walked(session, operation("nothing-waiting.json").path());
        GenerationStore.establish(session);
        SubscriptionLedger.prepare(session, caller());
        StreamAdmission.prepare(session, caller());
        rs.slingshot.agent.store.LedgerAdmission.prepare(session, caller());
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                SubscriptionLedger.subscribe(session, caller(), SUBSCRIPTION, generation(), NOW,
                        CONTRACT), "the subscription was not taken");
        return session;
    }

    private Session session() {
        return java.util.Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
    }

    private StatePath.Caller caller() {
        final String user = sling.resourceResolver().getUserID();
        return assertInstanceOf(StatePath.Held.class,
                StatePath.caller(user == null ? "admin" : user), "the caller was refused").caller();
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }

    private static StatePath operation(String fixture) {
        final JobEvent held = event(fixture);
        return StatePath.operation(held.generation(), held.identifier());
    }

    private static String identifierOf(String fixture) {
        return event(fixture).identifier().rendered();
    }

    private static JobEvent event(String fixture) {
        return assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document(fixture), generation(), CONTRACT),
                fixture + " is not an event").event();
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static byte[] canonical(String fixture) {
        return assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(document(fixture)),
                fixture + " has no canonical form").bytes();
    }

    private static DocumentValue.Mapping asks() {
        return assertInstanceOf(DocumentValue.Mapping.class, document("asks.json"),
                "the ask fixtures are not an object");
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static String text(DocumentValue.Mapping mapping, String member) {
        return assertInstanceOf(DocumentValue.Text.class, mapping.member(member).orElseThrow(),
                member + " is not text").value();
    }

    private static long whole(DocumentValue.Mapping mapping, String member) {
        return assertInstanceOf(DocumentValue.Whole.class, mapping.member(member).orElseThrow(),
                member + " is not a number").value();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
