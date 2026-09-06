// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.HighWaterMark;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.SaveInterleaving;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionLedger;
import rs.slingshot.agent.store.SubscriptionRecord;
import rs.slingshot.agent.wire.EventSequence;
import rs.slingshot.agent.wire.JobEvent;

/**
 * How many streams this instance will hold at once, and the four endings that give a room back.
 *
 * <p>The bound is about this process rather than about the store: past it an author stops serving
 * anything at all, including the request that would tell somebody about it. And a room that leaked
 * would not be noticed until an instance had run out of them, which is why every ending is proved
 * here rather than assumed.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class StreamAdmissionTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/event-stream");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> IN_ORDER =
            List.of("accepted.json", "started.json", "succeeded.json");

    private static final String SUBSCRIPTION = "following-daemon-one";

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    private final List<org.apache.sling.api.resource.ResourceResolver> opened = new ArrayList<>();

    @AfterEach
    void closePeerResolvers() {
        opened.forEach(org.apache.sling.api.resource.ResourceResolver::close);
    }

    private static rs.slingshot.agent.identity.AgentOperationIdentifier boundOperation() {
        return event("accepted.json").identifier();
    }

    @Test
    @DisplayName("admission succeeds at exactly the caller's bound and is refused one past it")
    void admissionSucceedsAtTheBoundAndIsRefusedPastIt() throws RepositoryException {
        final Session session = prepared();
        final long share = CONTRACT.value(ContractLimit.MAXIMUM_CALLER_CONCURRENT_EVENT_STREAMS);
        for (long taken = 0; taken < share; taken = taken + 1) {
            assertInstanceOf(StreamAdmission.Admitted.class,
                    StreamAdmission.open(session, caller(), CONTRACT),
                    "room ran out at " + taken + ", short of the declared share");
        }
        final StreamAdmission.Refused refused = assertInstanceOf(StreamAdmission.Refused.class,
                StreamAdmission.open(session, caller(), CONTRACT),
                "a caller past their share was admitted");
        assertEquals(CapacityLedger.Reached.THE_CALLERS_SHARE, refused.refusal().reached());
        assertEquals(share, refused.refusal().bound());
        assertTrue(refused.refusal().rendered().contains(String.valueOf(share)),
                refused.refusal().rendered());
        assertTrue(StreamAdmission.refusalIn(refused).isPresent());
    }

    @Test
    @DisplayName("one caller at their share does not keep another caller out")
    void onecallerAtTheirShareDoesNotKeepAnotherOut() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller somebodyElse = assertInstanceOf(StatePath.Held.class,
                StatePath.caller("another-following-daemon"), "the caller was refused").caller();
        StreamAdmission.prepare(session, somebodyElse);
        for (long taken = 0;
                taken < CONTRACT.value(ContractLimit.MAXIMUM_CALLER_CONCURRENT_EVENT_STREAMS);
                taken = taken + 1) {
            assertInstanceOf(StreamAdmission.Admitted.class,
                    StreamAdmission.open(session, caller(), CONTRACT), "room ran out early");
        }
        assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, somebodyElse, CONTRACT),
                "one caller at their share kept everybody else from following anything");
    }

    @Test
    @DisplayName("each of the four endings gives the room back, and the count returns to zero")
    void eachOfTheFourEndingsGivesTheRoomBack() throws RepositoryException {
        final Session session = recorded();
        final StreamSession quiet = following(identifierOf("nothing-waiting.json"));
        ended(session, admission -> assertEquals(StreamWriter.Ending.REACHED_THE_SESSION_BOUND,
                new StreamWriter(quiet, CONTRACT, admission)
                        .serve(new StringWriter(), session, new AdvancingTicker(), ""),
                "a quiet stream ended some way other than at its own bound"));
        ended(session, admission -> assertEquals(StreamWriter.Ending.THE_CLIENT_WENT_AWAY,
                new StreamWriter(quiet, CONTRACT, admission)
                        .serve(new SeveredWriter(), session, new AdvancingTicker(), ""),
                "a client that stopped reading ended the stream some other way"));
        final StreamSession gone = following(identifierOf("accepted.json"));
        session.getNode(operation("accepted.json").path()).remove();
        session.save();
        ended(session, admission -> assertEquals(StreamWriter.Ending.NOTHING_LEFT_TO_SERVE,
                new StreamWriter(gone, CONTRACT, admission)
                        .serve(new StringWriter(), session, new AdvancingTicker(), "1:0"),
                "a stream on an operation nothing holds went on saying nothing"));
        ended(session, admission -> assertThrows(IllegalStateException.class,
                () -> new StreamWriter(quiet, CONTRACT, admission)
                        .serve(new FaultingWriter(), session, new AdvancingTicker(), ""),
                "a fault in the writing was swallowed rather than ending the stream"));
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                        CONTRACT),
                "after four endings the count is not back where it started");
    }

    @Test
    @DisplayName("a store nobody prepared counts nothing rather than admitting everybody")
    void astoreNobodyPreparedCountsNothing() throws RepositoryException {
        final Session session = session();
        walked(session, StatePath.ROOT);
        assertInstanceOf(StreamAdmission.NotCounted.class,
                StreamAdmission.open(session, caller(), CONTRACT),
                "a store nobody prepared admitted a stream");
        assertTrue(StreamAdmission.refusalIn(
                        StreamAdmission.open(session, caller(), CONTRACT)).isEmpty(),
                "a count that did not happen was reported as a bound that was reached");
    }

    @Test
    void repeatedCloseLeavesAnotherOpenStreamCounted() throws RepositoryException {
        final Session session = recorded();
        final StreamAdmission.Admitted first = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        final StreamAdmission.Admitted second = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        StreamAdmission.close(session, first, CONTRACT);
        StreamAdmission.close(session, first, CONTRACT);
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS, CONTRACT));
        assertEquals(1, CapacityLedger.heldBy(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                caller(), CONTRACT));
        StreamAdmission.close(session, second, CONTRACT);
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS, CONTRACT));
    }

    @Test
    @DisplayName("events acknowledged on the wire advance the durable subscription cursor")
    void deliveredEventsAdvanceDurableCursor() throws RepositoryException, LoginException {
        final Session session = recorded();
        final StreamSession stream = following(identifierOf("accepted.json"));
        final StreamAdmission.Admitted admission = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        assertEquals(StreamWriter.Ending.REACHED_THE_SESSION_BOUND,
                new StreamWriter(stream, CONTRACT, admission)
                        .serve(new StringWriter(), session, new AdvancingTicker(), "1:0"));
        final Session observer = second();
        assertEquals(new SubscriptionRecord.Shown(sequence(2)),
                HighWaterMark.read(observer, stream.subscription()),
                "successful event delivery did not become durable subscription progress");
        assertEquals(NOW, observer.getNode(SubscriptionRecord.pathOf(stream.subscription()).path())
                        .getProperty(SubscriptionRecord.LAST_ADVANCED_AT).getLong(),
                "cursor progress and activity were not committed together");
    }

    @Test
    @DisplayName("the initial snapshot notice advances the durable cursor")
    void initialSnapshotNoticeAdvancesDurableCursor() throws RepositoryException, LoginException {
        final Session session = recorded();
        final StreamSession stream = following(identifierOf("accepted.json"));
        final StreamAdmission.Admitted admission = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        new StreamWriter(stream, CONTRACT, admission)
                .serve(new StringWriter(), session, new AdvancingTicker(), "");
        final Session observer = second();
        assertEquals(new SubscriptionRecord.Shown(sequence(2)),
                HighWaterMark.read(observer, stream.subscription()),
                "the snapshot shown on an initial connection did not advance the cursor");
    }

    @Test
    @DisplayName("a reset notice advances the durable cursor to its replacement snapshot")
    void resetNoticeAdvancesDurableCursor() throws RepositoryException, LoginException {
        final Session session = recorded();
        final StreamSession stream = following(identifierOf("accepted.json"));
        final StreamAdmission.Admitted admission = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        new StreamWriter(stream, CONTRACT, admission)
                .serve(new StringWriter(), session, new AdvancingTicker(), "2:0");
        final Session observer = second();
        assertEquals(new SubscriptionRecord.Shown(sequence(2)),
                HighWaterMark.read(observer, stream.subscription()),
                "the replacement snapshot did not become durable progress after reset");
    }

    @Test
    @DisplayName("a disconnect after one event preserves only the event that was acknowledged")
    void disconnectAfterOneEventPreservesAcknowledgedProgress()
            throws RepositoryException, LoginException {
        final Session session = recorded();
        final StreamSession stream = following(identifierOf("accepted.json"));
        final StreamAdmission.Admitted admission = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        assertEquals(StreamWriter.Ending.THE_CLIENT_WENT_AWAY,
                new StreamWriter(stream, CONTRACT, admission)
                        .serve(new DisconnectAfterWrites(1), session, new AdvancingTicker(), "1:0"));
        final Session observer = second();
        assertEquals(new SubscriptionRecord.Shown(sequence(1)),
                HighWaterMark.read(observer, stream.subscription()),
                "a disconnect moved the cursor past the last acknowledged event");
    }

    @Test
    @DisplayName("a high-water save interrupted before commit leaves no progress")
    void highWaterSaveInterruptedBeforeCommitLeavesNoProgress()
            throws RepositoryException, LoginException {
        interruptedHighWaterSave(false);
    }

    @Test
    @DisplayName("a high-water save interrupted after commit preserves its progress")
    void highWaterSaveInterruptedAfterCommitPreservesProgress()
            throws RepositoryException, LoginException {
        interruptedHighWaterSave(true);
    }

    private void interruptedHighWaterSave(boolean committed)
            throws RepositoryException, LoginException {
        final Session session = recorded();
        final StreamSession stream = following(identifierOf("accepted.json"));
        final StreamAdmission.Admitted admission = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        assertEquals(StreamWriter.Ending.THE_STORE_STOPPED_ANSWERING,
                new StreamWriter(stream, CONTRACT, admission)
                        .serve(new StringWriter(),
                                SaveInterleaving.interruptSave(session, 1, committed),
                                new AdvancingTicker(), "1:0"));
        final Session observer = second();
        assertEquals(committed ? new SubscriptionRecord.Shown(sequence(1))
                        : SubscriptionRecord.Unread.NOTHING_SHOWN_YET,
                HighWaterMark.read(observer, stream.subscription()),
                "durable progress did not match the "
                        + (committed ? "committed" : "uncommitted") + " boundary");
    }

    @Test
    void uncertainAdmissionIsCancelledWithoutDebitingAnExistingStream() throws RepositoryException {
        final Session session = recorded();
        final StreamAdmission.Admitted existing = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT));
        final AtomicInteger saves = new AtomicInteger();
        final Session uncertain = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    try {
                        final Object returned = method.invoke(session, arguments);
                        if ("save".equals(method.getName()) && saves.incrementAndGet() == 2) {
                            throw new RepositoryException("the activation commit reply was lost");
                        }
                        return returned;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        assertThrows(RepositoryException.class,
                () -> StreamAdmission.open(uncertain, caller(), CONTRACT));
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS, CONTRACT));
        assertEquals(1, CapacityLedger.heldBy(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                caller(), CONTRACT));
        StreamAdmission.close(session, existing, CONTRACT);
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS, CONTRACT));
    }

    private void ended(Session session, java.util.function.Consumer<StreamAdmission.Admitted> ending)
            throws RepositoryException {
        final StreamAdmission.Admitted admission = assertInstanceOf(StreamAdmission.Admitted.class,
                StreamAdmission.open(session, caller(), CONTRACT),
                "there was no room to prove an ending with");
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                        CONTRACT), "an open stream is not counted");
        ending.accept(admission);
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.CONCURRENT_EVENT_STREAMS,
                        CONTRACT), "an ending did not give the room back");
        final StreamAdmission.Outcome reopened = StreamAdmission.open(session, caller(), CONTRACT);
        assertNotEquals(CapacityLedger.Reached.THE_TOTAL, StreamAdmission.refusalIn(reopened)
                .map(refused -> refused.refusal().reached()).orElse(null),
                "a room could not be taken again immediately after an ending");
        StreamAdmission.close(session, assertInstanceOf(StreamAdmission.Admitted.class, reopened), CONTRACT);
    }

    /** A client that went away, which is the second of the four endings. */
    private static final class SeveredWriter extends Writer {

        @Override
        public void write(char[] buffer, int from, int length) throws IOException {
            throw new IOException("the client is gone");
        }

        @Override
        public void flush() throws IOException {
            throw new IOException("the client is gone");
        }

        @Override
        public void close() throws IOException {
            throw new IOException("the client is gone");
        }
    }

    /** A client that accepts exactly the first event write before disconnecting. */
    private static final class DisconnectAfterWrites extends Writer {

        private final int allowed;
        private int writes;

        private DisconnectAfterWrites(int allowed) {
            this.allowed = allowed;
        }

        @Override
        public void write(char[] buffer, int from, int length) throws IOException {
            if (writes == allowed) {
                throw new IOException("the client is gone");
            }
            writes = writes + 1;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** Something that is not an ending at all, which must still end through the one path. */
    private static final class FaultingWriter extends Writer {

        @Override
        public void write(char[] buffer, int from, int length) {
            throw new IllegalStateException("something nobody planned for");
        }

        @Override
        public void flush() {
            throw new IllegalStateException("something nobody planned for");
        }

        @Override
        public void close() {
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
        session.getNode(operation("accepted.json").path()).setProperty(
                rs.slingshot.agent.execution.OperationStore.CALLER, caller().name());
        session.getNode(operation("nothing-waiting.json").path()).setProperty(
                rs.slingshot.agent.execution.OperationStore.CALLER, caller().name());
        session.save();
        GenerationStore.establish(session);
        SubscriptionLedger.prepare(session, caller());
        StreamAdmission.prepare(session, caller());
        LedgerAdmission.prepare(session, caller());
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                SubscriptionLedger.subscribe(session, caller(), SUBSCRIPTION, generation(),
                        boundOperation(), NOW,
                        CONTRACT), "the subscription was not taken");
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                SubscriptionLedger.subscribe(session, caller(), SUBSCRIPTION + "-empty", generation(),
                        event("nothing-waiting.json").identifier(), NOW, CONTRACT));
        return session;
    }

    private Session session() {
        return java.util.Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
    }

    private Session second() throws LoginException {
        opened.add(anotherResolver());
        return java.util.Objects.requireNonNull(opened.getLast().adaptTo(Session.class),
                "the peer resolver has no session");
    }

    private org.apache.sling.api.resource.ResourceResolver anotherResolver() throws LoginException {
        return java.util.Objects.requireNonNull(sling.getService(ResourceResolverFactory.class),
                "the resolver factory was not registered").getResourceResolver(java.util.Map.of());
    }

    private static EventSequence sequence(long number) {
        return assertInstanceOf(EventSequence.Held.class, EventSequence.of(number),
                "the sequence was refused").sequence();
    }

    private StatePath.Caller caller() {
        final String user = sling.resourceResolver().getUserID();
        return assertInstanceOf(StatePath.Held.class,
                StatePath.caller(user == null ? "admin" : user), "the caller was refused").caller();
    }

    private StreamSession following(String operation) throws RepositoryException {
        return assertInstanceOf(StreamSession.Held.class,
                StreamSession.of(session(), new StreamSession.Asked(
                        operation.equals(boundOperation().rendered())
                        ? SUBSCRIPTION : SUBSCRIPTION + "-empty", operation,
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
