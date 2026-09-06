// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.EventSequence;

/**
 * A cursor that is a promise: taken once, never moved backwards, and paid for by whoever holds it.
 *
 * <p>The bounds are proved against contracts shrunk for the suite, because a store that may hold a
 * million subscription rows is a store no test fills. What is proved on the shipped contract is the
 * identifier bound, which is small enough to reach honestly.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class SubscriptionLedgerTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/subscription-ledger");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedEndingKeepsDataAndAccountingTogether(boolean committed)
            throws RepositoryException, LoginException {
        final Session session = prepared();
        final SubscriptionRecord taken = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        assertThrows(RepositoryException.class,
                () -> SubscriptionLedger.end(SaveInterleaving.interruptSave(session, 1, committed),
                        caller(), taken, CONTRACT));
        final Session observer = second();
        try {
            final long rows = observer.nodeExists(
                    SubscriptionRecord.pathOf(taken.identifier()).path()) ? 1 : 0;
            assertEquals(rows, CapacityLedger.held(observer, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                    CONTRACT));
            assertEquals(rows * taken.bytes(), CapacityLedger.heldBy(observer,
                    AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, caller(), CONTRACT));
            SubscriptionLedger.end(observer, caller(), taken, CONTRACT);
            SubscriptionLedger.end(observer, caller(), taken, CONTRACT);
            assertEquals(0, CapacityLedger.held(observer, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                    CONTRACT));
        } finally {
            observer.logout();
        }
    }

    private final List<org.apache.sling.api.resource.ResourceResolver> opened = new java.util.ArrayList<>();

    @AfterEach
    void closePeerResolvers() {
        opened.forEach(org.apache.sling.api.resource.ResourceResolver::close);
    }

    private static rs.slingshot.agent.identity.AgentOperationIdentifier boundOperation() {
        return assertInstanceOf(rs.slingshot.agent.identity.AgentOperationIdentifier.Held.class,
                rs.slingshot.agent.identity.AgentOperationIdentifier.of(
                        "c".repeat(64), CONTRACT)).identifier();
    }

    @Test
    void anExistingNameCannotMoveToAnotherCallerOrOperation() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionRecord record = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final var other = assertInstanceOf(rs.slingshot.agent.identity.AgentOperationIdentifier.Held.class,
                rs.slingshot.agent.identity.AgentOperationIdentifier.of(
                        "d".repeat(64), CONTRACT)).identifier();
        for (final var binding : List.of(new SubscriptionRecord.Binding(caller("another-caller"),
                boundOperation()), new SubscriptionRecord.Binding(caller(), other))) {
            final var refused = assertInstanceOf(SubscriptionLedger.Refused.class,
                    SubscriptionLedger.subscribe(session, binding.caller(), record.identifier().rendered(),
                            generation(), binding.operation(), NOW, CONTRACT));
            assertEquals(SubscriptionLedger.Refusal.BINDING_DIFFERS, refused.refusal());
        }
        assertEquals(record, SubscriptionLedger.read(session, record.identifier(), CONTRACT).orElseThrow());
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, CONTRACT));
        assertEquals(record.bytes(), CapacityLedger.held(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, CONTRACT));
    }

    @Test
    void aCreationLoserChecksTheWinnersBindingAndReleasesItsReservation() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionRecord record = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final StatePath.Caller other = caller("another-caller");
        SubscriptionLedger.prepare(session, other);
        final var refused = assertInstanceOf(SubscriptionLedger.Refused.class,
                SubscriptionLedger.subscribe(blindTo(session,
                                SubscriptionRecord.pathOf(record.identifier()).path()),
                        other, record.identifier().rendered(), generation(),
                        boundOperation(), NOW, CONTRACT));
        assertEquals(SubscriptionLedger.Refusal.BINDING_DIFFERS, refused.refusal());
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, CONTRACT));
        assertEquals(0, CapacityLedger.heldBy(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                other, CONTRACT));
    }

    @Test
    void legacyOrMalformedBindingsAreRefusedRatherThanAdopted() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionRecord record = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final Node node = session.getNode(SubscriptionRecord.pathOf(record.identifier()).path());
        node.getProperty(SubscriptionLedger.OPERATION).remove();
        session.save();
        assertTrue(SubscriptionLedger.read(session, record.identifier(), CONTRACT).isEmpty());
        assertEquals(SubscriptionLedger.Refusal.BINDING_DIFFERS,
                assertInstanceOf(SubscriptionLedger.Refused.class,
                        subscribe(session, "a-new-subscription", CONTRACT)).refusal());
        node.setProperty(SubscriptionLedger.OPERATION, "invalid");
        session.save();
        assertTrue(SubscriptionLedger.read(session, record.identifier(), CONTRACT).isEmpty());
        node.setProperty(SubscriptionLedger.OPERATION, boundOperation().rendered());
        node.setProperty(SubscriptionLedger.SUBSCRIBER, "");
        session.save();
        assertTrue(SubscriptionLedger.read(session, record.identifier(), CONTRACT).isEmpty());
        node.setProperty(SubscriptionLedger.SUBSCRIBER, caller().name());
        node.setProperty(SubscriptionRecord.GENERATION, 0);
        session.save();
        assertTrue(SubscriptionLedger.read(session, record.identifier(), CONTRACT).isEmpty());
    }

    @Test
    void overlappingEndsRemoveOneSubscriptionAndReleaseOnce() throws RepositoryException, LoginException {
        final Session session = prepared();
        final SubscriptionRecord taken = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final Session competing = second();
        try {
            SubscriptionLedger.end(SaveInterleaving.before(session,
                    () -> SubscriptionLedger.end(competing, caller(), taken, CONTRACT)), caller(), taken,
                    CONTRACT);
            assertFalse(competing.nodeExists(SubscriptionRecord.pathOf(taken.identifier()).path()));
            assertEquals(0, CapacityLedger.held(competing, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                    CONTRACT));
            assertEquals(0, CapacityLedger.heldBy(competing, AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES,
                    caller(), CONTRACT));
        } finally {
            competing.logout();
        }
    }

    @Test
    void exhaustedEndingContentionPreservesTheSubscription() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionRecord taken = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final var attempts = new java.util.concurrent.atomic.AtomicInteger();
        final Session contended = SaveInterleaving.beforeEverySave(session, () -> {
            attempts.incrementAndGet();
            throw new javax.jcr.InvalidItemStateException("subscription ending contended");
        });
        assertThrows(RepositoryException.class,
                () -> SubscriptionLedger.end(contended, caller(), taken, CONTRACT));
        assertEquals(CompareAndSet.ATTEMPTS, attempts.get());
        assertFalse(session.hasPendingChanges());
        assertTrue(session.nodeExists(SubscriptionRecord.pathOf(taken.identifier()).path()));
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, CONTRACT));
        assertEquals(taken.bytes(), CapacityLedger.heldBy(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, caller(), CONTRACT));
    }

    @Test
    @DisplayName("a subscription is taken once, and subscribing again resumes the same record")
    void asubscriptionIsTakenOnceAndResumed() throws RepositoryException, LoginException {
        final Session second = second();
        final Session session = prepared();
        final SubscriptionRecord taken = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT),
                "a first subscription was not taken").record();
        assertEquals(SubscriptionRecord.Unread.NOTHING_SHOWN_YET, taken.cursor());
        second.refresh(false);
        assertInstanceOf(SubscriptionLedger.Resumed.class,
                subscribe(second, "a-resumed-subscription", CONTRACT),
                "a second daemon under one name took a second subscription");
        assertEquals(1, CapacityLedger.held(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, CONTRACT),
                "resuming a subscription was charged as a second one");
        assertEquals(taken.bytes(), CapacityLedger.held(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, CONTRACT));
        second.logout();
    }

    @Test
    @DisplayName("a writer that did not see the record still leaves one row and one charge")
    void awriterThatDidNotSeeTheRecordLeavesOneRow() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionRecord taken = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final SubscriptionLedger.Outcome late = SubscriptionLedger.subscribe(
                blindTo(session, SubscriptionRecord.pathOf(taken.identifier()).path()), caller(),
                fixture("a-new-subscription"), generation(), boundOperation(), NOW, CONTRACT);
        assertInstanceOf(SubscriptionLedger.Resumed.class, late,
                "a writer that raced for one name took a second subscription");
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                CONTRACT), "the writer that lost the claim kept the row it had taken");
        assertEquals(taken.bytes(), CapacityLedger.held(session,
                AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, CONTRACT),
                "the writer that lost the claim kept the bytes it had taken");
    }

    @Test
    @DisplayName("a mark that would go backwards is refused, naming both, and nothing moves")
    void abackwardsMarkIsRefusedAndNothingMoves() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionRecord.Identifier identifier = identifier("a-new-subscription");
        assertEquals(HighWaterMark.Refusal.NO_RECORD,
                HighWaterMark.refusalIn(HighWaterMark.advance(session, identifier, sequence(0),
                        NOW)).orElseThrow().refusal(),
                "a mark moved under a subscription nothing holds");
        subscribe(session, "a-new-subscription", CONTRACT);
        assertEquals(sequence(3), assertInstanceOf(HighWaterMark.Advanced.class,
                HighWaterMark.advance(session, identifier, sequence(3), NOW),
                "a mark did not move forwards").to());
        final HighWaterMark.Refused backwards = HighWaterMark.refusalIn(
                HighWaterMark.advance(session, identifier, sequence(1), NOW + 1)).orElseThrow();
        assertEquals(HighWaterMark.Refusal.WOULD_GO_BACKWARDS, backwards.refusal());
        assertTrue(backwards.detail().contains("3") && backwards.detail().contains("1"),
                backwards.detail());
        assertEquals(new SubscriptionRecord.Shown(sequence(3)),
                HighWaterMark.read(session, identifier),
                "a refused decrease moved the mark anyway");
        assertEquals(HighWaterMark.Refusal.WOULD_GO_BACKWARDS, HighWaterMark.refusalIn(
                HighWaterMark.advance(session, identifier, sequence(3), NOW + 1)).orElseThrow()
                .refusal(), "a mark moved to where it already stood");
        assertTrue(HighWaterMark.refusalIn(new HighWaterMark.Advanced(sequence(4), NOW)).isEmpty());
    }

    @Test
    @DisplayName("an identifier is taken at exactly its bound and refused one byte past it")
    void theidentifierBoundHoldsAtBothSides() throws RepositoryException {
        final Session session = prepared();
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "at-the-bound", CONTRACT),
                "an identifier at exactly the bound was refused");
        assertEquals(SubscriptionRecord.Refusal.PAST_THE_BOUND, SubscriptionRecord.refusalIn(
                SubscriptionRecord.identifier(fixture("past-the-bound"), CONTRACT)).orElseThrow()
                .refusal());
        assertEquals(SubscriptionRecord.Refusal.EMPTY, SubscriptionRecord.refusalIn(
                SubscriptionRecord.identifier(fixture("empty"), CONTRACT)).orElseThrow()
                .refusal());
        assertEquals(SubscriptionRecord.Refusal.NOT_A_NAME, SubscriptionRecord.refusalIn(
                SubscriptionRecord.identifier(fixture("not-a-name"), CONTRACT)).orElseThrow()
                .refusal());
        assertEquals(SubscriptionLedger.Refusal.IDENTIFIER_REFUSED, SubscriptionLedger.refusalIn(
                subscribe(session, "past-the-bound", CONTRACT)).orElseThrow().refusal(),
                "an identifier past the bound was written down anyway");
    }

    @Test
    @DisplayName("the rows a generation may hold run out at exactly the number declared")
    void therowBoundHoldsAtBothSides() throws RepositoryException {
        final AgentContract rows = contractWith(Map.of(
                "maximum_current_generation_active_subscription_rows", 2L,
                "maximum_caller_current_generation_active_subscription_rows", 2L));
        final Session session = prepared();
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", rows));
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-second-subscriber", rows));
        final CapacityLedger.Refused refused = assertInstanceOf(SubscriptionLedger.AtCapacity.class,
                subscribe(session, "at-the-bound", rows),
                "a row past what the store may hold was taken").refusal();
        assertEquals(AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, refused.quantity());
        assertEquals(CapacityLedger.Reached.THE_TOTAL, refused.reached());
        assertEquals(2, refused.bound());
    }

    @Test
    @DisplayName("the bytes a generation may hold run out at exactly the number declared")
    void thebyteBoundHoldsAtBothSides() throws RepositoryException {
        final Session session = prepared();
        final long two = 2 * record("a-new-subscription").bytes();
        final AgentContract bytes = admitting(two);
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", bytes));
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-second-subscriber", bytes));
        final CapacityLedger.Refused refused = assertInstanceOf(SubscriptionLedger.AtCapacity.class,
                subscribe(session, "at-the-bound", bytes),
                "bytes past what the store may hold were taken").refusal();
        assertEquals(AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES, refused.quantity());
        assertEquals(two, refused.bound());
        assertEquals(2, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                bytes), "a refused admission left a row counted for a subscription nobody has");
    }

    @Test
    @DisplayName("a caller at their own share is refused while a second caller is admitted")
    void onesubscriberCannotHoldTheStore() throws RepositoryException {
        final AgentContract shares = contractWith(Map.of(
                "maximum_current_generation_active_subscription_rows", 4L,
                "maximum_caller_current_generation_active_subscription_rows", 2L));
        final Session session = prepared();
        SubscriptionLedger.prepare(session, caller("the-other-daemon"));
        subscribe(session, "a-new-subscription", shares);
        subscribe(session, "a-second-subscriber", shares);
        final CapacityLedger.Refused refused = assertInstanceOf(SubscriptionLedger.AtCapacity.class,
                subscribe(session, "at-the-bound", shares),
                "a caller past their own share subscribed anyway").refusal();
        assertEquals(CapacityLedger.Reached.THE_CALLERS_SHARE, refused.reached());
        assertEquals(2, refused.bound());
        assertInstanceOf(SubscriptionLedger.Subscribed.class,
                SubscriptionLedger.subscribe(session, caller("the-other-daemon"),
                        fixture("at-the-bound"), generation(), boundOperation(), NOW, shares),
                "a second caller was refused because the first one was busy");
    }

    @Test
    @DisplayName("a store that was never prepared is told so rather than told it is full")
    void astoreThatWasNeverPreparedIsToldSo() throws RepositoryException {
        final Session session = prepared();
        final SubscriptionLedger.NotCounted notCounted = assertInstanceOf(
                SubscriptionLedger.NotCounted.class,
                SubscriptionLedger.subscribe(session, caller("a-daemon-nobody-prepared"),
                        fixture("a-new-subscription"), generation(), boundOperation(), NOW, CONTRACT),
                "a store with no counters for this caller said it had room");
        assertEquals(AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                notCounted.notCounted().quantity());
        assertFalse(session.nodeExists(SubscriptionRecord
                        .pathOf(identifier("a-new-subscription")).path()),
                "a subscription nothing counted was written down anyway");
    }

    @Test
    @DisplayName("nothing here counts anything of its own")
    void nothingHereCountsAnythingItself() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/store/SubscriptionLedger.java"));
        assertTrue(source.contains("CapacityLedger.take"),
                "a subscription is admitted somewhere other than through the one authority");
        assertFalse(source.contains("ShardedCount"),
                "the ledger reaches for a counter rather than the authority");
        assertEquals(1, source.lines().filter(line -> line.contains("ContractLimit.")).count(),
                "a bound other than the retention is compared here rather than by the authority");
    }

    @Test
    @DisplayName("a foreign generation is refused, and a record nothing has moved is expired")
    void aforeignGenerationIsRefusedAndAstaleRecordExpires() throws RepositoryException {
        final Session session = prepared();
        assertEquals(SubscriptionLedger.Refusal.FOREIGN_GENERATION, SubscriptionLedger.refusalIn(
                SubscriptionLedger.subscribe(session, caller(), fixture("a-new-subscription"),
                        generationOf(9), boundOperation(), NOW, CONTRACT)).orElseThrow().refusal(),
                "a cursor into an incarnation nothing serves was written down");
        final SubscriptionRecord taken = assertInstanceOf(SubscriptionLedger.Subscribed.class,
                subscribe(session, "a-new-subscription", CONTRACT)).record();
        final long past = NOW + CONTRACT.value(rs.slingshot.agent.contract.ContractLimit
                .MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS) + 1;
        assertTrue(SubscriptionLedger.expired(taken, past, CONTRACT));
        assertFalse(SubscriptionLedger.expired(taken, NOW + 1, CONTRACT));
        assertEquals(SubscriptionLedger.Refusal.EXPIRED, SubscriptionLedger.refusalIn(
                SubscriptionLedger.subscribe(session, caller(), fixture("a-new-subscription"),
                        generation(), boundOperation(), past, CONTRACT)).orElseThrow().refusal(),
                "a record older than anything this side keeps was served");
        SubscriptionLedger.end(session, caller(), taken, CONTRACT);
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                CONTRACT), "an ended subscription kept the row it held");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES,
                CONTRACT), "an ended subscription kept the bytes it held");
    }

    /**
     * A session that cannot see one path, which is what a writer racing for it looks like.
     *
     * @param session the session to write under
     * @param hidden the path this session is told is not there
     * @return the session, with that one path hidden from it
     */
    private static Session blindTo(Session session, String hidden) {
        return (Session) java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class},
                (proxy, method, arguments) -> "nodeExists".equals(method.getName())
                        && hidden.equals(arguments[0])
                        ? Boolean.FALSE
                        : method.invoke(session, arguments));
    }

    private SubscriptionLedger.Outcome subscribe(Session session, String fixture,
                                                 AgentContract contract)
            throws RepositoryException {
        return SubscriptionLedger.subscribe(session, caller(), fixture(fixture), generation(),
                        boundOperation(), NOW,
                contract);
    }

    private static SubscriptionRecord record(String fixture) {
        return new SubscriptionRecord(identifier(fixture), generation(),
                new SubscriptionRecord.Binding(caller(), boundOperation()),
                SubscriptionRecord.Unread.NOTHING_SHOWN_YET, NOW);
    }

    private static AgentContract admitting(long admissible) {
        final AgentContract asked = withByteBound(admissible);
        final long actual =
                AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES.admissibleTotal(asked);
        return actual == admissible ? asked : withByteBound(admissible + admissible - actual);
    }

    private static AgentContract withByteBound(long bound) {
        return contractWith(Map.of(
                "maximum_current_generation_active_subscription_bytes", bound,
                "maximum_caller_current_generation_active_subscription_bytes", bound));
    }

    private static SubscriptionRecord.Identifier identifier(String fixture) {
        return assertInstanceOf(SubscriptionRecord.Held.class,
                SubscriptionRecord.identifier(fixture(fixture), CONTRACT),
                fixture + " is not an identifier").identifier();
    }

    private static String fixture(String name) {
        final DocumentValue.Mapping identifiers = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                        BoundedDocumentReader.read(bytes(FIXTURES.resolve("identifiers.json")),
                                BoundedDocumentReader.Bounds.from(CONTRACT)),
                        "the identifier fixtures are not a document").value(),
                "the identifier fixtures are not an object");
        return assertInstanceOf(DocumentValue.Text.class,
                identifiers.member(name).orElseThrow(), name + " is not a fixture").value();
    }

    private static EventSequence sequence(long number) {
        return assertInstanceOf(EventSequence.Held.class, EventSequence.of(number),
                number + " is not a sequence").sequence();
    }

    private static EventStoreGeneration generation() {
        return generationOf(EventStoreGeneration.FIRST);
    }

    private static EventStoreGeneration generationOf(long number) {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(number),
                number + " is not a generation").generation();
    }

    private static StatePath.Caller caller() {
        return caller("the-subscribing-caller");
    }

    private static StatePath.Caller caller(String name) {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller(name),
                name + " was refused as a caller").caller();
    }

    private Session second() throws LoginException {
        opened.add(anotherResolver());
        return java.util.Objects.requireNonNull(opened.getLast().adaptTo(Session.class),
                "a second session over the same repository is not available");
    }

    private org.apache.sling.api.resource.ResourceResolver anotherResolver() throws LoginException {
        return java.util.Objects.requireNonNull(sling.getService(ResourceResolverFactory.class),
                "this context registers no resolver factory").getResourceResolver(Map.of());
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        SubscriptionLedger.prepare(session, caller());
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
