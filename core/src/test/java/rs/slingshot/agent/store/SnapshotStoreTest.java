// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
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
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.EventSequence;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * That what is true now and what happened cannot disagree, and that a disagreement is found.
 *
 * <p>The property is about commits rather than about values, so it is proved from a second session
 * watching the first one write: at the instant the ledger has a new event staged and the snapshot
 * has moved with it, a reader that is not that writer sees neither half. What it sees is the pair
 * as it was, and then, one save later, the pair as it now is.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class SnapshotStoreTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/snapshot-store");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> IN_ORDER =
            List.of("accepted.json", "started.json", "progress.json", "succeeded.json");

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("after every event kind the snapshot is the fold of the ledger")
    void aftereveryKindTheSnapshotIsTheFold() throws RepositoryException {
        final Session session = prepared();
        long expected = 0;
        for (final String fixture : IN_ORDER) {
            final JobEvent event = event(fixture);
            assertInstanceOf(EventLedger.Appended.class, record(session, fixture),
                    fixture + " was not recorded");
            expected = expected + 1;
            final SnapshotStore.Snapshot snapshot = assertInstanceOf(SnapshotStore.Known.class,
                    SnapshotStore.read(session, operation()),
                    fixture + " left nothing currently true").snapshot();
            assertEquals(event.kind(), snapshot.kind(), fixture + " left the snapshot behind");
            assertEquals(event.sequence(), snapshot.sequence());
            assertEquals(expected, snapshot.events());
            assertEquals(NOW, snapshot.updatedAtUnixMilliseconds());
            assertInstanceOf(SnapshotStore.Agrees.class,
                    SnapshotStore.verify(session, operation(),
                            new SnapshotStore.Says(event.kind())),
                    "the snapshot and the ledger disagree after " + fixture);
        }
    }

    @Test
    @DisplayName("at every commit the pair is the one before the append or the one after it")
    void ateveryCommitThePairIsOneOrTheOther() throws RepositoryException {
        final Session session = prepared();
        record(session, IN_ORDER.get(0));
        final List<String> seen = new java.util.ArrayList<>();
        final Session watched = observed(session, () -> seen.add(pairSeenBy(session)));
        assertInstanceOf(EventLedger.Appended.class, record(watched, IN_ORDER.get(1)));
        assertFalse(seen.isEmpty(), "the append committed nothing, so nothing was watched");
        for (final String pair : seen) {
            assertTrue(List.of("accepted/0/1", "started/1/2").contains(pair),
                    "a commit was made with a pair that is neither the one before the append nor"
                            + " the one after it: " + pair);
        }
        assertEquals("started/1/2", pairSeenBy(session),
                "the pair after the append is not the one the append should have left");
    }

    @Test
    @DisplayName("a hand-made disagreement is found and named, in either direction")
    void ahandMadeDisagreementIsFoundAndNamed() throws RepositoryException {
        final Session session = prepared();
        record(session, IN_ORDER.get(0));
        final Node snapshot = session.getNode(operation().child(SnapshotStore.NODE).path());
        snapshot.setProperty(SnapshotStore.KIND, JobEventKind.SUCCEEDED.spelling());
        snapshot.setProperty(SnapshotStore.SEQUENCE, 7);
        snapshot.setProperty(SnapshotStore.EVENTS, 9);
        session.save();
        final List<SnapshotStore.Discrepancy> found = SnapshotStore.findingsIn(
                        SnapshotStore.verify(session, operation(),
                                new SnapshotStore.Says(JobEventKind.ACCEPTED)))
                .stream().map(SnapshotStore.Finding::discrepancy).toList();
        assertEquals(List.of(SnapshotStore.Discrepancy.KIND_DIFFERS,
                SnapshotStore.Discrepancy.SEQUENCE_DIFFERS,
                SnapshotStore.Discrepancy.COUNT_DIFFERS), found,
                "a snapshot made to disagree by hand was not found out");
        snapshot.remove();
        session.save();
        final SnapshotStore.Finding absent = SnapshotStore.findingsIn(
                SnapshotStore.verify(session, operation(),
                        new SnapshotStore.Says(JobEventKind.ACCEPTED))).getFirst();
        assertEquals(SnapshotStore.Discrepancy.NO_SNAPSHOT, absent.discrepancy());
        assertEquals(operation().path(), absent.operation(),
                "a finding did not name the operation somebody has to go and look at");
    }

    @Test
    @DisplayName("a snapshot with no events behind it is a finding of its own")
    void asnapshotWithNoEventsIsAfindingOfItsOwn() throws RepositoryException {
        final Session session = prepared();
        walked(session, empty().child(SnapshotStore.NODE).path());
        final Node snapshot = session.getNode(empty().child(SnapshotStore.NODE).path());
        snapshot.setProperty(SnapshotStore.KIND, JobEventKind.ACCEPTED.spelling());
        snapshot.setProperty(SnapshotStore.SEQUENCE, EventSequence.FIRST);
        snapshot.setProperty(SnapshotStore.EVENTS, 1);
        snapshot.setProperty(SnapshotStore.UPDATED_AT, NOW);
        session.save();
        assertEquals(SnapshotStore.Discrepancy.NO_EVENTS, SnapshotStore.findingsIn(
                SnapshotStore.verify(session, empty(),
                        new SnapshotStore.Says(JobEventKind.ACCEPTED)))
                .getFirst().discrepancy());
    }

    @Test
    @DisplayName("a terminal record with no terminal event, and the same lie the other way round")
    void aterminalRecordWithNoTerminalEventIsFound() throws RepositoryException {
        final Session session = prepared();
        record(session, IN_ORDER.get(0));
        assertEquals(SnapshotStore.Discrepancy.RECORD_TERMINAL_WITHOUT_EVENT,
                SnapshotStore.findingsIn(SnapshotStore.verify(session, operation(),
                        new SnapshotStore.Says(JobEventKind.SUCCEEDED))).getFirst().discrepancy(),
                "a record that says finished over a ledger that does not was not found out");
        for (final String fixture : IN_ORDER.subList(1, IN_ORDER.size())) {
            record(session, fixture);
        }
        assertEquals(SnapshotStore.Discrepancy.EVENT_TERMINAL_WITHOUT_RECORD,
                SnapshotStore.findingsIn(SnapshotStore.verify(session, operation(),
                        new SnapshotStore.Says(JobEventKind.STARTED))).getFirst().discrepancy(),
                "a ledger that says finished under a record that does not was not found out");
        assertTrue(SnapshotStore.findingsIn(SnapshotStore.verify(session, operation(),
                SnapshotStore.NoRecord.NOTHING_HOLDS_THIS_OPERATION)).stream()
                .anyMatch(finding -> finding.discrepancy()
                        == SnapshotStore.Discrepancy.EVENT_TERMINAL_WITHOUT_RECORD),
                "an operation with no record at all was treated as one that agrees");
    }

    @Test
    @DisplayName("nothing here writes a snapshot without the event that made it true")
    void nothingHereWritesAsnapshotAlone() {
        final List<String> writers = java.util.Arrays.stream(SnapshotStore.class.getMethods())
                .filter(method -> method.getDeclaringClass() == SnapshotStore.class)
                .filter(method -> java.util.Arrays.asList(method.getParameterTypes())
                        .contains(SnapshotStore.Snapshot.class))
                .map(Method::getName)
                .toList();
        assertEquals(List.of(), writers,
                "a caller can hand this type a snapshot, which is a snapshot with no event");
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/SnapshotStore.java"));
        assertEquals(4, source.lines().filter(line -> line.contains(".setProperty(")).count(),
                "the snapshot's four properties are written somewhere other than in one place");
        assertTrue(source.contains("private static void materialise"),
                "the one writer of a snapshot is reachable without an event");
        assertEquals(1, source.split("materialise\\(", -1).length - 2,
                "the one writer of a snapshot is called from more than the ledger's own commit");
        assertTrue(source.contains("EventLedger.append(session, caller, event, canonical"),
                "the snapshot is not written through the ledger's own commit");
    }

    @Test
    @DisplayName("a cursorless reader is served what is true and nothing it has already seen")
    void acursorlessReaderIsServedWhatIsTrue() throws RepositoryException {
        final Session session = prepared();
        for (final String fixture : IN_ORDER) {
            record(session, fixture);
        }
        final SnapshotStore.Reading cursorless = SnapshotStore.current(session, operation());
        assertEquals(JobEventKind.SUCCEEDED, assertInstanceOf(SnapshotStore.Known.class,
                cursorless.current(), "a cursorless reader was told nothing is true").snapshot()
                .kind());
        assertEquals(List.of(), cursorless.after(),
                "a reader was shown an event the snapshot it was given already accounts for");
        final SnapshotStore.Reading resumed =
                SnapshotStore.since(session, operation(), sequence(1));
        assertEquals(List.of(canonicalOf("progress.json"), canonicalOf("succeeded.json")),
                resumed.after(), "a resumed reader was served the wrong events");
        assertTrue(resumed.after().stream().noneMatch(document -> document.contains("\"sequence\":1")
                        || document.contains("\"sequence\":0")),
                "a resumed reader was shown something at or below its own cursor");
        assertEquals(List.of(), SnapshotStore.current(session, empty()).after(),
                "an operation with no events served one");
        assertEquals(SnapshotStore.Unwritten.NOTHING_HAS_HAPPENED,
                SnapshotStore.current(session, empty()).current(),
                "an operation nothing has happened to was said to have something true");
    }

    private EventLedger.Outcome record(Session session, String fixture)
            throws RepositoryException {
        return SnapshotStore.record(session, caller(), event(fixture), canonical(fixture), NOW,
                CONTRACT);
    }

    private String pairSeenBy(Session session) {
        try {
            final SnapshotStore.Materialised snapshot = SnapshotStore.read(session, operation());
            final long events =
                    EventLedger.events(session, operation().child(EventLedger.NODE));
            return snapshot instanceof final SnapshotStore.Known known
                    ? known.snapshot().kind().spelling() + "/"
                            + known.snapshot().sequence().number() + "/" + events
                    : "nothing";
        } catch (final RepositoryException unreadable) {
            throw new IllegalStateException("the watching reader could not read", unreadable);
        }
    }

    /**
     * A session that lets somebody look at the store at the moment it commits.
     *
     * <p>What it looks at is the writer's own staged view, which is what any other session would be
     * given if the commit happened there and then. A mixed pair staged is a mixed pair somebody
     * could be served, whether or not this suite happens to have a second session open.</p>
     *
     * @param session the session to write under
     * @param watching what to do at each commit, before it is made
     * @return the session, with the watcher wired into its saves
     */
    private static Session observed(Session session, Runnable watching) {
        return (Session) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        watching.run();
                    }
                    return method.invoke(session, arguments);
                });
    }

    private static EventSequence sequence(long number) {
        return assertInstanceOf(EventSequence.Held.class, EventSequence.of(number),
                number + " is not a sequence").sequence();
    }

    private static JobEvent event(String fixture) {
        return assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document(fixture), generation(), CONTRACT),
                fixture + " is not an event").event();
    }

    private static rs.slingshot.agent.identity.EventStoreGeneration generation() {
        return assertInstanceOf(rs.slingshot.agent.identity.EventStoreGeneration.Held.class,
                rs.slingshot.agent.identity.EventStoreGeneration.of(
                        rs.slingshot.agent.identity.EventStoreGeneration.FIRST),
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

    private static StatePath operation() {
        final JobEvent event = event("accepted.json");
        return StatePath.operation(event.generation(), event.identifier());
    }

    private static StatePath empty() {
        final JobEvent event = event("no-events.json");
        return StatePath.operation(event.generation(), event.identifier());
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-recording-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, operation().path());
        walked(session, empty().path());
        LedgerAdmission.prepare(session, caller());
        return session;
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
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
