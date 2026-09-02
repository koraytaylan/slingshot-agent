// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * Where a reconnecting subscriber picks up, and what it is told when it cannot.
 *
 * <p>The distinction the whole thing turns on: a cursor into events that are gone is a reset
 * carrying what to resynchronise from, and a cursor past the newest event is nothing at all. A
 * subscriber that could not tell those apart would either resynchronise forever or jump silently.
 * </p>
 */
@ExtendWith(SlingContextExtension.class)
final class StreamResumptionTest {

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
    @DisplayName("a live cursor serves what follows it and never its own event")
    void alivecursorServesWhatFollowsIt() throws RepositoryException {
        recorded();
        final StreamResumption.Serving serving = assertInstanceOf(StreamResumption.Serving.class,
                resumed("1:0"), "a live cursor was not served");
        assertEquals(List.of(canonicalOf("started.json"), canonicalOf("succeeded.json")),
                serving.events(), "a resumption served something other than what follows a cursor");
        assertEquals(2, assertInstanceOf(SnapshotStore.Known.class, serving.current())
                .snapshot().sequence().number());
    }

    @Test
    @DisplayName("a swept cursor is a reset and a cursor past the newest is nothing at all")
    void asweptCursorIsAresetAndOnePastTheNewestIsNothing() throws RepositoryException {
        final Session session = recorded();
        session.getNode(operation("accepted.json").child(EventLedger.NODE)
                .child(EventLedger.nameOf(event("accepted.json").sequence())).path()).remove();
        session.getNode(operation("accepted.json").child(EventLedger.NODE)
                .child(EventLedger.nameOf(event("started.json").sequence())).path()).remove();
        session.save();
        final StreamResumption.Resetting reset = assertInstanceOf(
                StreamResumption.Resetting.class, resumed("1:0"),
                "a cursor into events that are gone was served as though it were merely behind");
        assertInstanceOf(SnapshotStore.Known.class, reset.current(),
                "a reset carried nothing to resynchronise from");
        assertEquals(List.of(), assertInstanceOf(StreamResumption.Serving.class,
                        resumed("1:9"),
                        "a cursor past the newest event was reset rather than served nothing")
                .events());
    }

    @Test
    @DisplayName("a cursor from another incarnation is a reset naming the one this store serves")
    void acursorFromAnotherIncarnationIsAreset() throws RepositoryException {
        recorded();
        final StreamResumption.Resetting reset = assertInstanceOf(
                StreamResumption.Resetting.class, resumed("7:1"),
                "a cursor from another incarnation was read as an early position");
        final String notice = ResetNotice.bytes(following(identifierOf("accepted.json")),
                reset.current()).orElseThrow();
        assertTrue(ResetNotice.isAreset(notice), notice);
        assertTrue(notice.contains("\"agent_event_store_generation\":1"),
                "the reset does not name the incarnation this store serves: " + notice);
        assertTrue(notice.contains("\"sequence\":2"),
                "the reset does not carry what to resynchronise from: " + notice);
    }

    @Test
    @DisplayName("a cursorless reconnection exposes nothing its own snapshot already accounts for")
    void acursorlessReconnectionExposesNothingAlreadyExposed() throws RepositoryException {
        recorded();
        final StreamResumption.Serving serving = assertInstanceOf(StreamResumption.Serving.class,
                resumed(""), "a cursorless reconnection was not served");
        assertEquals(List.of(), serving.events(),
                "a cursorless reader was shown events its own snapshot already accounts for");
        assertEquals(2, assertInstanceOf(SnapshotStore.Known.class, serving.current())
                .snapshot().sequence().number());
    }

    @Test
    @DisplayName("an identifier this build does not read is no cursor rather than an early one")
    void anidentifierThisBuildDoesNotReadIsNoCursor() {
        assertTrue(StreamResumption.cursorIn("").isEmpty());
        assertTrue(StreamResumption.cursorIn("   ").isEmpty());
        assertTrue(StreamResumption.cursorIn("not-a-cursor").isEmpty());
        assertEquals("1:1", StreamResumption.cursorIn("1:1").orElseThrow().rendered());
    }

    @Test
    @DisplayName("a stream on an operation this store no longer holds ends rather than waiting")
    void astreamOnAgoneOperationEndsRatherThanWaiting() throws RepositoryException {
        final Session session = recorded();
        final StreamSession following = following(identifierOf("accepted.json"));
        session.getNode(operation("accepted.json").path()).remove();
        session.save();
        assertEquals(StreamRefusalOutcome.REFUSED, refusalOf(
                StreamResumption.from(session, following, "1:0", CONTRACT)),
                "a cursor into an operation nothing holds was served");
        final StringWriter writer = new StringWriter();
        new StreamWriter(following, CONTRACT).serve(writer, session, new AdvancingTicker(), "1:0");
        assertEquals(Heartbeat.bytes(), writer.toString(),
                "a stream on an operation nothing holds went on saying nothing: "
                        + writer.toString());
    }

    /** What one resumption produced, where a suite only needs to say which of the three. */
    private enum StreamRefusalOutcome {
        /** It served events. */
        SERVING,
        /** It reset. */
        RESETTING,
        /** There was nothing to serve at all. */
        REFUSED
    }

    private static StreamRefusalOutcome refusalOf(StreamResumption.Outcome outcome) {
        if (outcome instanceof StreamResumption.Serving) {
            return StreamRefusalOutcome.SERVING;
        }
        return outcome instanceof StreamResumption.Resetting
                ? StreamRefusalOutcome.RESETTING
                : StreamRefusalOutcome.REFUSED;
    }

    private StreamResumption.Outcome resumed(String cursor) throws RepositoryException {
        return StreamResumption.from(session(), following(identifierOf("accepted.json")), cursor,
                CONTRACT);
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

    private static String canonicalOf(String fixture) {
        return new String(canonical(fixture), StandardCharsets.UTF_8);
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

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
