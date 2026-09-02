// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * What a reconnecting subscriber is served, and the two ways it is told it cannot be served.
 *
 * <p>The corpus of crafted cursors is the point of the last test: a cursor is two numbers, and the
 * suite tries every shape of them it can think of — a valid one, one before everything, one past
 * everything, one from another incarnation, malformed ones — against an operation that is not the
 * one whose events they are near. None of them reaches those events, because a cursor cannot name
 * an operation at all.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class EventReplayTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/event-replay");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> IN_ORDER =
            List.of("accepted.json", "started.json", "progress.json", "succeeded.json");

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a replay from a cursor serves what follows it, in order, and never its own event")
    void areplayServesWhatFollowsTheCursor() throws RepositoryException {
        final Session session = recorded();
        final ReplayOutcome.Served served = assertInstanceOf(ReplayOutcome.Served.class,
                replay(session, "at-the-second-event"), "a valid cursor was not served");
        assertEquals(List.of(canonicalOf("progress.json"), canonicalOf("succeeded.json")),
                served.events(), "a replay served something other than what follows the cursor");
        assertEquals(JobEventKind.SUCCEEDED, assertInstanceOf(SnapshotStore.Known.class,
                served.current(), "a replay carried no current state").snapshot().kind());
    }

    @Test
    @DisplayName("a cursor into events that are gone is a reset, and one past the newest is not")
    void acursorIntoGoneEventsIsAreset() throws RepositoryException {
        final Session session = recorded();
        assertInstanceOf(ReplayOutcome.Served.class, replay(session, "before-everything"));
        session.getNode(ledger().child(EventLedger.nameOf(sequenceOf(0))).path()).remove();
        session.getNode(ledger().child(EventLedger.nameOf(sequenceOf(1))).path()).remove();
        session.save();
        final ReplayOutcome.Reset reset = assertInstanceOf(ReplayOutcome.Reset.class,
                replay(session, "before-everything"),
                "a cursor into events that are gone was served as though it were merely behind");
        assertEquals(JobEventKind.SUCCEEDED, assertInstanceOf(SnapshotStore.Known.class,
                reset.current(), "a reset carried nothing to resynchronise from").snapshot()
                .kind());
        assertTrue(reset.detail().contains("2"), reset.detail());
        assertEquals(List.of(), assertInstanceOf(ReplayOutcome.Served.class,
                replay(session, "past-the-newest"),
                "a cursor past the newest event was reset rather than served nothing").events());
    }

    @Test
    @DisplayName("a cursor from another incarnation is refused rather than read as an early one")
    void acursorFromAnotherIncarnationIsRefused() throws RepositoryException {
        final Session session = recorded();
        final ReplayOutcome.Refused refused = assertInstanceOf(ReplayOutcome.Refused.class,
                replay(session, "another-generation"),
                "a cursor from another incarnation was served as an early position");
        assertEquals(ReplayOutcome.Refusal.FOREIGN_GENERATION, refused.refusal());
        assertEquals(ReplayOutcome.Refusal.NO_OPERATION, assertInstanceOf(
                ReplayOutcome.Refused.class,
                EventReplay.from(session, elsewhere(), cursor("at-the-second-event"), generation(),
                        CONTRACT), "a replay of an operation nothing holds was served").refusal());
        assertEquals(ReplayOutcome.Refusal.NO_OPERATION, assertInstanceOf(
                ReplayOutcome.Refused.class, EventReplay.current(session, elsewhere(), CONTRACT))
                .refusal());
    }

    @Test
    @DisplayName("a cursorless replay exposes nothing the snapshot it carries already accounts for")
    void acursorlessReplayExposesNothingAlreadyExposed() throws RepositoryException {
        final Session session = recorded();
        final ReplayOutcome.Served served = assertInstanceOf(ReplayOutcome.Served.class,
                EventReplay.current(session, operation(), CONTRACT));
        assertEquals(3, assertInstanceOf(SnapshotStore.Known.class, served.current()).snapshot()
                .sequence().number());
        assertEquals(List.of(), served.events(),
                "a cursorless reader was shown events its own snapshot already accounts for");
    }

    @Test
    @DisplayName("no cursor anybody can craft reaches another operation's events")
    void nocraftedCursorReachesAnotherOperation() throws RepositoryException {
        final Session session = recorded();
        walked(session, elsewhere().path());
        record(session, "another-operation.json");
        final String elsewhere = event("another-operation.json").identifier().rendered();
        for (final String crafted : List.of("at-the-second-event", "before-everything",
                "past-the-newest", "another-generation")) {
            final ReplayOutcome outcome = replay(session, crafted);
            assertFalse(outcome instanceof ReplayOutcome.Served served
                            && served.events().stream().anyMatch(held -> held.contains(elsewhere)),
                    crafted + " reached an event belonging to another operation");
        }
        assertEquals(List.of(canonicalOf("another-operation.json")),
                EventLedger.held(session, elsewhere().child(EventLedger.NODE)),
                "the other operation holds no events, so nothing above was actually kept from"
                        + " reaching one");
    }

    @Test
    @DisplayName("a cursor is written and read back, and three malformed ones are refused apart")
    void acursorIsWrittenAndReadBack() {
        assertEquals("1:1", cursor("at-the-second-event").rendered());
        assertEquals(cursor("at-the-second-event"),
                assertInstanceOf(ReplayCursor.Held.class,
                        ReplayCursor.read(cursor("at-the-second-event").rendered())).cursor());
        assertEquals(ReplayCursor.Refusal.NOT_TWO_PARTS, refusal("not-two-parts"));
        assertEquals(ReplayCursor.Refusal.NOT_TWO_PARTS, refusal("not-numbers"));
        assertEquals(ReplayCursor.Refusal.NOT_TWO_PARTS, refusal("negative-sequence"));
        assertEquals(ReplayCursor.Refusal.GENERATION_REFUSED, refusal("before-the-first-generation"));
        assertEquals(ReplayCursor.Refusal.SEQUENCE_REFUSED, ReplayCursor.refusalIn(
                ReplayCursor.of(EventStoreGeneration.FIRST, -1)).orElseThrow().refusal());
    }

    @Test
    @DisplayName("one read carries no more than a stream may hold buffered for a slow reader")
    void oneReadIsBoundedByWhatAstreamMayBuffer() throws RepositoryException {
        final Session session = recorded();
        final long two = canonicalOf("accepted.json").getBytes(StandardCharsets.UTF_8).length
                + canonicalOf("started.json").getBytes(StandardCharsets.UTF_8).length;
        final AgentContract bounded = contractWith(Map.of(
                "maximum_server_sent_event_buffer_bytes", two));
        final ReplayOutcome.Served served = assertInstanceOf(ReplayOutcome.Served.class,
                EventReplay.from(session, operation(), cursorOf(EventStoreGeneration.FIRST, 0),
                        generation(), bounded));
        assertEquals(2, served.events().size(),
                "one read carried more than a stream may hold for a reader that is not reading");
        assertEquals(List.of(canonicalOf("started.json"), canonicalOf("progress.json")),
                served.events(), "a bounded read served something other than the oldest unseen");
    }

    private ReplayOutcome replay(Session session, String crafted) throws RepositoryException {
        return EventReplay.from(session, operation(), cursor(crafted), generation(), CONTRACT);
    }

    private static ReplayCursor cursor(String named) {
        return assertInstanceOf(ReplayCursor.Held.class, ReplayCursor.read(fixture(named)),
                named + " is not a cursor").cursor();
    }

    private static ReplayCursor cursorOf(long generation, long sequence) {
        return assertInstanceOf(ReplayCursor.Held.class, ReplayCursor.of(generation, sequence),
                generation + ":" + sequence + " is not a cursor").cursor();
    }

    private static ReplayCursor.Refusal refusal(String named) {
        return ReplayCursor.refusalIn(ReplayCursor.read(fixture(named))).orElseThrow().refusal();
    }

    private static String fixture(String name) {
        final DocumentValue.Mapping cursors = assertInstanceOf(DocumentValue.Mapping.class,
                document("cursors.json"), "the cursor fixtures are not an object");
        return assertInstanceOf(DocumentValue.Text.class, cursors.member(name).orElseThrow(),
                name + " is not a fixture").value();
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

    private static JobEvent event(String fixture) {
        return assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document(fixture), generation(), CONTRACT),
                fixture + " is not an event").event();
    }

    private static rs.slingshot.agent.wire.EventSequence sequenceOf(long number) {
        return assertInstanceOf(rs.slingshot.agent.wire.EventSequence.Held.class,
                rs.slingshot.agent.wire.EventSequence.of(number),
                number + " is not a sequence").sequence();
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

    private static StatePath operation() {
        final JobEvent held = event("accepted.json");
        return StatePath.operation(held.generation(), held.identifier());
    }

    private static StatePath elsewhere() {
        final JobEvent held = event("another-operation.json");
        return StatePath.operation(held.generation(), held.identifier());
    }

    private static StatePath ledger() {
        return operation().child(EventLedger.NODE);
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-following-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, operation().path());
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
                "the shrunken contract is not one this build reads").contract();
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
