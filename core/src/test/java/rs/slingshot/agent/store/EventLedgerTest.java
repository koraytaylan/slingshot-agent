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
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.JobEvent;

/**
 * An append-only ledger: sequenced, bounded in four directions, and counted by the one authority.
 *
 * <p>Each bound is proved at exactly the limit and one past it, against a contract shrunk for the
 * suite rather than against the shipped one — a bound of sixty-eight gigabytes is a bound no test
 * reaches, and a suite that only proves the bounds it can afford to reach proves the small ones
 * twice and the large ones never.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class EventLedgerTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/event-ledger");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> IN_ORDER =
            List.of("accepted.json", "started.json", "progress.json", "succeeded.json");

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a first append at sequence zero is written where its sequence says")
    void afirstAppendIsWrittenWhereItsSequenceSays() throws RepositoryException {
        final Session session = prepared(CONTRACT);
        final EventLedger.Appended appended = assertInstanceOf(EventLedger.Appended.class,
                append(session, "accepted.json", CONTRACT), "a first event was not appended");
        assertEquals(1, appended.events());
        assertEquals(canonical("accepted.json").length, appended.bytes());
        assertEquals("000000000000", EventLedger.nameOf(appended.event().sequence()));
        assertTrue(session.nodeExists(ledger().child("000000000000").path()),
                "the event is not at the path its own sequence derives");
        assertEquals(List.of(new String(canonical("accepted.json"), StandardCharsets.UTF_8)),
                EventLedger.held(session, ledger()),
                "what the ledger holds is not the bytes that were appended");
    }

    @Test
    @DisplayName("a gap and a repeat are two refusals, each naming the sequences it saw")
    void agapAndArepeatAreTwoDifferentAnswers() throws RepositoryException {
        final Session session = prepared(CONTRACT);
        append(session, "accepted.json", CONTRACT);
        final EventLedger.Refused gap = EventLedger.refusalIn(append(session, "gap.json", CONTRACT))
                .orElseThrow();
        assertEquals(EventLedger.Refusal.SEQUENCE_GAP, gap.refusal());
        assertTrue(gap.detail().contains("5") && gap.detail().contains("1"), gap.detail());
        final EventLedger.Refused repeat =
                EventLedger.refusalIn(append(session, "repeat.json", CONTRACT)).orElseThrow();
        assertEquals(EventLedger.Refusal.SEQUENCE_REPEAT, repeat.refusal());
        assertTrue(repeat.detail().contains("0"), repeat.detail());
        assertEquals(1, EventLedger.events(session, ledger()),
                "a refused append wrote something anyway");
        assertEquals(EventLedger.Refusal.NO_OPERATION,
                EventLedger.refusalIn(append(session, "another-operation.json", CONTRACT))
                        .orElseThrow().refusal(),
                "an event was appended for an operation nothing holds");
    }

    @Test
    @DisplayName("two writers at one sequence leave one event, and the second is told so")
    void twowritersAtOneSequenceLeaveOneEvent()
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        // The second session is opened before the first one holds anything, because a session over
        // this repository has to exist before there is state for it to be behind.
        final Session second = second();
        final Session session = prepared(CONTRACT);
        assertInstanceOf(EventLedger.Appended.class, append(session, "accepted.json", CONTRACT));
        second.refresh(false);
        final EventLedger.Outcome loser = append(second, "accepted.json", CONTRACT);
        assertFalse(loser instanceof EventLedger.Appended,
                "two writers both appended what one sequence holds");
        assertEquals(EventLedger.Refusal.SEQUENCE_REPEAT,
                EventLedger.refusalIn(loser).orElseThrow().refusal());
        assertEquals(1, EventLedger.events(session, ledger()),
                "one sequence holds more than one event");
        second.logout();
    }

    private Session second() throws org.apache.sling.api.resource.LoginException {
        return java.util.Objects.requireNonNull(
                java.util.Objects.requireNonNull(sling.getService(
                                org.apache.sling.api.resource.ResourceResolverFactory.class),
                        "this context registers no resolver factory")
                        .getResourceResolver(java.util.Map.of()).adaptTo(Session.class),
                "a second session over the same repository is not available");
    }

    @Test
    @DisplayName("the events one operation may hold run out at exactly the number declared")
    void theperOperationEventCountHoldsAtBothSides() throws RepositoryException {
        assertEquals(EventLedger.Refusal.TOO_MANY_EVENTS,
                upToTheLimit(contractWith(Map.of("maximum_operation_event_rows", 3L))).refusal());
    }

    @Test
    @DisplayName("the bytes one operation's events may come to run out at the number declared")
    void theperOperationByteBoundHoldsAtBothSides() throws RepositoryException {
        assertEquals(EventLedger.Refusal.TOO_MANY_EVENT_BYTES,
                upToTheLimit(contractWith(Map.of("maximum_operation_event_bytes",
                        threeEvents()))).refusal());
    }

    @Test
    @DisplayName("the rows this generation may hold are a store with no room, not a fault")
    void theperGenerationRowBoundRefusesAsNoRoom() throws RepositoryException {
        final AgentContract rows = contractWith(Map.of(
                "maximum_current_generation_event_rows", 3L,
                "maximum_caller_current_generation_event_rows", 3L));
        final CapacityLedger.Refused refused = atCapacity(rows).refusal();
        assertEquals(AccountedQuantity.EVENT_ROWS, refused.quantity());
        assertEquals(CapacityLedger.Reached.THE_TOTAL, refused.reached());
        assertEquals(3, refused.bound(), "a count too small to shard was spread anyway");
    }

    @Test
    @DisplayName("the bytes this generation may hold are a store with no room, not a fault")
    void theperGenerationByteBoundRefusesAsNoRoom() throws RepositoryException {
        final AgentContract bytes = admitting(threeEvents());
        final CapacityLedger.Refused refused = atCapacity(bytes).refusal();
        assertEquals(AccountedQuantity.EVENT_BYTES, refused.quantity());
        assertEquals(CapacityLedger.Reached.THE_TOTAL, refused.reached());
        assertEquals(threeEvents(), refused.bound(),
                "the margin a sharded count understates by was not taken into account");
    }

    @Test
    @DisplayName("a refused admission leaves the counts where it found them")
    void arefusedAdmissionLeavesTheCountsLevel() throws RepositoryException {
        final AgentContract bytes = admitting(threeEvents());
        final Session full = atCapacitySession(bytes);
        assertInstanceOf(EventLedger.AtCapacity.class, append(full, IN_ORDER.get(3), bytes));
        assertEquals(3, CapacityLedger.held(full, AccountedQuantity.EVENT_ROWS, bytes),
                "a refused admission left a row counted for an event nothing wrote");
        assertEquals(threeEvents(), CapacityLedger.held(full, AccountedQuantity.EVENT_BYTES, bytes),
                "a refused admission left bytes counted for an event nothing wrote");
    }

    @Test
    @DisplayName("a store that could not count is a different answer from a store with no room")
    void astoreThatCouldNotCountIsNotAstoreWithNoRoom() throws RepositoryException {
        final Session session = prepared(CONTRACT);
        walked(session, ledger().path());
        final EventLedger.NotWritten notWritten = assertInstanceOf(EventLedger.NotWritten.class,
                append(unwritable(session), "accepted.json", CONTRACT),
                "a store that could not count said it had no room");
        assertEquals(WriteOutcome.CONTENDED, notWritten.outcome(),
                "a store under contention was reported as one that answered something else");
        assertEquals(0, EventLedger.events(session, ledger()),
                "an append that was not counted wrote an event anyway");
    }

    @Test
    @DisplayName("room for an event is taken from both counts, or from neither")
    void roomIsTakenFromBothCountsOrNeither() throws RepositoryException {
        final Session session = prepared(CONTRACT);
        assertInstanceOf(LedgerAdmission.NotCounted.class,
                LedgerAdmission.admit(session, caller("nobody-prepared-this"), size(), CONTRACT),
                "a store that never counted this caller said it had room");
        final LedgerAdmission.Outcome admitted =
                LedgerAdmission.admit(session, caller(), size(), CONTRACT);
        assertEquals(size(), assertInstanceOf(LedgerAdmission.Admitted.class, admitted).bytes());
        assertTrue(LedgerAdmission.refusalIn(admitted).isEmpty(),
                "room that was taken was reported as room that was refused");
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT));
        assertEquals(size(), CapacityLedger.held(session, AccountedQuantity.EVENT_BYTES, CONTRACT));
        LedgerAdmission.release(session, caller(), size(), CONTRACT);
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT),
                "what an event gave back is not what it took");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.EVENT_BYTES, CONTRACT));
    }

    @Test
    @DisplayName("nothing here counts anything or compares a bound of its own")
    void nothingHereCountsAnythingItself() {
        final String ledger = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/EventLedger.java"));
        final String admission = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/LedgerAdmission.java"));
        assertTrue(ledger.contains("LedgerAdmission.admit"),
                "the ledger admits somewhere other than through the one authority");
        assertTrue(admission.contains("CapacityLedger.admit"),
                "the admission counts somewhere other than through the one authority");
        for (final String counting : List.of("ShardedCount", "ContractLimit", "CompareAndSet")) {
            assertFalse(ledger.contains(counting),
                    "the ledger reaches for " + counting + " rather than the authority");
            assertFalse(admission.contains(counting),
                    "the admission reaches for " + counting + " rather than the authority");
        }
    }

    @Test
    @DisplayName("the counters after a series of appends are what the tree actually holds")
    void thecountersEqualTheTree() throws RepositoryException {
        final Session session = prepared(CONTRACT);
        for (final String fixture : IN_ORDER) {
            assertInstanceOf(EventLedger.Appended.class, append(session, fixture, CONTRACT),
                    fixture + " was refused inside every bound");
        }
        assertEquals(IN_ORDER.size(), EventLedger.events(session, ledger()));
        assertEquals(IN_ORDER.size(),
                CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT),
                "the counted rows are not the events the tree holds");
        assertEquals(EventLedger.bytes(session, ledger()),
                CapacityLedger.held(session, AccountedQuantity.EVENT_BYTES, CONTRACT),
                "the counted bytes are not the bytes the tree holds");
        assertEquals(IN_ORDER.stream().map(EventLedgerTest::canonical)
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList(),
                EventLedger.held(session, ledger()),
                "the ledger reads back in an order that is not the order it happened in");
    }

    private EventLedger.Refused upToTheLimit(AgentContract contract) throws RepositoryException {
        final Session session = prepared(contract);
        for (final String fixture : IN_ORDER.subList(0, 3)) {
            assertInstanceOf(EventLedger.Appended.class, append(session, fixture, contract),
                    fixture + " was refused inside the bound");
        }
        return EventLedger.refusalIn(append(session, IN_ORDER.get(3), contract))
                .orElseThrow(() -> new AssertionError("an event past the bound was appended"));
    }

    /**
     * What the first three events come to, which is what every bound in this suite is set from.
     *
     * <p>Three times one event's size would be a number that happens to be wrong: the events differ
     * by the length of the kind they name, and a bound set from the wrong one of them proves the
     * bound holds a byte early.</p>
     *
     * @return the bytes the first three fixtures come to
     */
    private static long threeEvents() {
        return IN_ORDER.subList(0, 3).stream()
                .mapToLong(fixture -> canonical(fixture).length)
                .sum();
    }

    /**
     * A contract whose event-byte bound admits exactly what is asked for.
     *
     * <p>A sharded count understates by a margin of one advance per other shard, so the number a
     * bound has to declare is the number that may be admitted plus that margin. Asking for it twice
     * is how the margin is discovered rather than assumed.</p>
     *
     * @param admissible how many bytes the suite needs the store to admit
     * @return the contract
     */
    private static AgentContract admitting(long admissible) {
        final AgentContract asked = withByteBound(admissible);
        final long actual = AccountedQuantity.EVENT_BYTES.admissibleTotal(asked);
        return actual == admissible ? asked
                : withByteBound(admissible + admissible - actual);
    }

    private static AgentContract withByteBound(long bound) {
        return contractWith(Map.of(
                "maximum_current_generation_event_bytes", bound,
                "maximum_caller_current_generation_event_bytes", bound));
    }

    private EventLedger.AtCapacity atCapacity(AgentContract contract) throws RepositoryException {
        return assertInstanceOf(EventLedger.AtCapacity.class,
                append(atCapacitySession(contract), IN_ORDER.get(3), contract),
                "an event past what the store may hold was appended");
    }

    private Session atCapacitySession(AgentContract contract) throws RepositoryException {
        final Session session = prepared(contract);
        for (final String fixture : IN_ORDER.subList(0, 3)) {
            assertInstanceOf(EventLedger.Appended.class, append(session, fixture, contract),
                    fixture + " was refused inside the bound");
        }
        return session;
    }

    private EventLedger.Outcome append(Session session, String fixture, AgentContract contract)
            throws RepositoryException {
        return EventLedger.append(session, caller(), event(fixture, contract), canonical(fixture),
                NOW, contract);
    }

    private static JobEvent event(String fixture, AgentContract contract) {
        return assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document(fixture, contract), generation(), contract),
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
                CanonicalByteWriter.write(document(fixture, CONTRACT)),
                fixture + " has no canonical form").bytes();
    }

    private static DocumentValue document(String fixture, AgentContract contract) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(contract)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath operation(AgentContract contract) {
        final JobEvent event = event("accepted.json", contract);
        return StatePath.operation(event.generation(), event.identifier());
    }

    private static StatePath ledger() {
        return EventLedger.pathOf(event("accepted.json", CONTRACT));
    }

    private static StatePath.Caller caller() {
        return caller("the-appending-caller");
    }

    private static StatePath.Caller caller(String name) {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller(name),
                name + " was refused as a caller").caller();
    }

    private static long size() {
        return canonical("accepted.json").length;
    }

    private Session prepared(AgentContract contract) throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, operation(contract).path());
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
            rewritten.append(overrides.containsKey(name)
                            ? name + " = " + overrides.get(name)
                            : line)
                    .append('\n');
        });
        final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        return assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load(document, AgentContract.digestOf(document)),
                "the shrunken contract is not one this build reads").contract();
    }

    private static Session unwritable(Session session) {
        return (Session) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        throw new javax.jcr.InvalidItemStateException("somebody else wrote first");
                    }
                    return method.invoke(session, arguments);
                });
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
