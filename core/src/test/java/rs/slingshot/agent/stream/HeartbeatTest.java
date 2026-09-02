// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionLedger;
import rs.slingshot.agent.wire.JobEvent;

/**
 * The bytes that say only that the connection is alive, and the moment this side ends its own
 * stream.
 *
 * <p>Both are proved by advancing a clock rather than by waiting on one. A suite that slept for a
 * heartbeat interval would take a session bound to say what it proved, and would be proving what
 * one machine did one afternoon.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class HeartbeatTest {

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
    @DisplayName("heartbeats go on the interval the contract declares and nowhere else")
    void heartbeatsGoOnTheIntervalTheContractDeclares() throws RepositoryException {
        recorded();
        assertEquals(SessionBound.milliseconds(CONTRACT)
                        / Heartbeat.intervalMilliseconds(CONTRACT),
                heartbeatsIn(quiet(CONTRACT)),
                "a quiet stream did not beat once per declared interval");
        final AgentContract twiceAsOften =
                contractWith(Map.of("heartbeat_interval_milliseconds", HALF_AN_INTERVAL));
        assertEquals(SessionBound.milliseconds(twiceAsOften) / HALF_AN_INTERVAL,
                heartbeatsIn(quiet(twiceAsOften)),
                "the interval is this build's own rather than the contract's");
    }

    /** Half the interval the shipped contract declares, which no line of this build knows. */
    private static final long HALF_AN_INTERVAL = 7500;

    @Test
    @DisplayName("a session ends at exactly its bound, after a final heartbeat and a clean close")
    void asessionEndsAtExactlyItsBound() throws RepositoryException {
        recorded();
        final AdvancingTicker ticker = new AdvancingTicker();
        try (ClosingWriter writer = new ClosingWriter()) {
            assertEquals(StreamWriter.Ending.REACHED_THE_SESSION_BOUND,
                    new StreamWriter(following(identifierOf("nothing-waiting.json")), CONTRACT)
                            .serve(writer, session(), ticker, ""),
                    "a quiet session ended some way other than at its own bound");
            assertEquals(NOW + SessionBound.milliseconds(CONTRACT), ticker.milliseconds(),
                    "a session ended at a moment other than the bound it publishes");
            assertTrue(writer.written().endsWith(Heartbeat.bytes()),
                    "a session ended without a final heartbeat: " + writer.written());
            assertEquals(Closing.CLOSED, writer.closing(),
                    "a session ended by severing the connection rather than by closing it");
        }
    }

    @Test
    @DisplayName("a shorter bound ends one interval earlier and no earlier than that")
    void ashorterBoundEndsOneIntervalEarlier() throws RepositoryException {
        recorded();
        final AgentContract shorter = contractWith(Map.of(
                "maximum_event_stream_session_milliseconds",
                SessionBound.milliseconds(CONTRACT) - Heartbeat.intervalMilliseconds(CONTRACT)));
        assertEquals(heartbeatsIn(quiet(CONTRACT)) - 1, heartbeatsIn(quiet(shorter)),
                "a shorter bound did not end a stream one interval earlier");
        assertFalse(SessionBound.isReached(NOW,
                        NOW + SessionBound.milliseconds(shorter) - 1, shorter),
                "a session ended before the bound it publishes");
        assertTrue(SessionBound.isReached(NOW, NOW + SessionBound.milliseconds(shorter), shorter),
                "a session did not end at the bound it publishes");
    }

    @Test
    @DisplayName("the bound this side publishes is resumable inside the client's own retry policy")
    void theboundIsResumableInsideTheClientsOwnPolicy() {
        assertTrue(SessionBound.isResumable(CONTRACT),
                "a session ending on schedule is not resumable, which ends work rather than"
                        + " pausing it");
        assertEquals(Heartbeat.timeoutMilliseconds(CONTRACT)
                        * CONTRACT.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS),
                SessionBound.resumableWindowMilliseconds(CONTRACT),
                "the window is not what the client will keep trying for");
        final byte[] violating = bytes(REPOSITORY.resolve(
                "core/src/test/resources/fixtures/agent-contract/session-not-resumable.toml"));
        assertInstanceOf(AgentContract.Refused.class,
                AgentContract.load(violating, AgentContract.digestOf(violating)),
                "a contract whose session bound outlives the client's retry policy was accepted");
    }

    @Test
    @DisplayName("streams are written from this bundle's own bounded pool, not a shared scheduler")
    void streamsAreWrittenFromThisBundlesOwnPool() {
        try (java.util.concurrent.ExecutorService pool = StreamExecutor.bounded(CONTRACT)) {
            assertEquals(CONTRACT.value(ContractLimit.MAXIMUM_CONCURRENT_EVENT_STREAMS),
                    ((java.util.concurrent.ThreadPoolExecutor) pool).getMaximumPoolSize(),
                    "the pool is not bounded by the number of streams this instance admits");
        }
        try (java.util.concurrent.ExecutorService read = StreamExecutor.bounded()) {
            assertTrue(((java.util.concurrent.ThreadPoolExecutor) read).getMaximumPoolSize()
                            >= StreamExecutor.WITHOUT_A_CONTRACT,
                    "a build reading its own contract wrote streams from nothing at all");
        }
        assertFalse(sources().anyMatch(source -> read(source).contains("commons.scheduler")),
                "a stream is held on the platform's shared scheduler, which every other feature on"
                        + " the instance is also using");
    }

    @Test
    @DisplayName("every deployment row says what its ingress must do with a stream, or is unproved")
    void everydeploymentRowSaysWhatItsIngressMustDo() {
        final String deployment = read(REPOSITORY.resolve("docs/DEPLOYMENT.md"));
        read(REPOSITORY.resolve("support/deployments.toml")).lines()
                .filter(line -> line.startsWith("id = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .forEach(row -> assertTrue(deployment.contains(row),
                        row + " has no row in the streaming table, so an operator with a shorter"
                                + " idle timeout than this bound has not been told"));
        assertTrue(deployment.contains("without buffering"),
                "the one thing a row cannot claim until it has been observed is not stated: a"
                        + " buffered stream delivers nothing at all until it ends");
        assertTrue(deployment.contains("declared and unproved"),
                "a row's streaming support is claimed rather than declared");
    }

    private String quiet(AgentContract contract) throws RepositoryException {
        final StringWriter writer = new StringWriter();
        new StreamWriter(following(identifierOf("nothing-waiting.json")), contract)
                .serve(writer, session(), new AdvancingTicker(), "");
        return writer.toString();
    }

    private static long heartbeatsIn(String wire) {
        return wire.lines().filter(line -> line.startsWith(EventEncoder.FIELD_SEPARATOR)).count();
    }

    private static java.util.stream.Stream<Path> sources() {
        try {
            return Files.walk(REPOSITORY.resolve("core/src/main/java"))
                    .filter(path -> path.toString().endsWith(".java"));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the main sources are not readable", unreadable);
        }
    }

    /** Whether a stream's writer was closed or merely stopped being written to. */
    private enum Closing {
        /** It was closed. */
        CLOSED,
        /** It was not. */
        STILL_OPEN
    }

    /** A writer that remembers being closed, which is what a clean ending looks like from here. */
    private static final class ClosingWriter extends Writer {

        private final StringWriter held = new StringWriter();

        private Closing closing = Closing.STILL_OPEN;

        @Override
        public void write(char[] buffer, int from, int length) {
            held.write(buffer, from, length);
        }

        @Override
        public void flush() {
            held.flush();
        }

        @Override
        public void close() {
            closing = Closing.CLOSED;
        }

        private String written() {
            return held.toString();
        }

        private Closing closing() {
            return closing;
        }
    }

    /**
     * A clock a suite moves rather than waits for.
     *
     * <p>Every pause is time passing at once, so a stream that ends at its bound ends here in the
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
        LedgerAdmission.prepare(session, caller());
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

    private StreamSession following(String operation) throws RepositoryException {
        return assertInstanceOf(StreamSession.Held.class,
                StreamSession.of(session(), new StreamSession.Asked(SUBSCRIPTION, operation,
                        EventStoreGeneration.FIRST, caller()), CONTRACT),
                "the stream was not opened").session();
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

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static AgentContract contractWith(Map<String, Long> overrides) {
        final StringBuilder rewritten = new StringBuilder();
        read(REPOSITORY.resolve("support/agent-contract.toml")).lines().forEach(line -> {
            final String name = line.contains("=") ? line.substring(0, line.indexOf('=')).strip()
                    : "";
            rewritten.append(overrides.containsKey(name) ? name + " = " + overrides.get(name)
                            : line)
                    .append('\n');
        });
        final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        return assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load(document, AgentContract.digestOf(document)),
                "the rewritten contract is not one this build reads").contract();
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
