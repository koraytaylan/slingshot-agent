// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * Four writes that are one fact, and therefore one commit.
 *
 * <p>Atomicity is proved from what is staged at every commit the ending makes: a state that says
 * finished is only ever staged together with the answer, the terminal event, and the snapshot that
 * agree with it. A run of the same suite against an implementation that wrote them one after
 * another would find a commit where the state had moved and the ledger had not.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class TerminalCommitTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/terminal-commit");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("the state, the answer, the terminal event, and the snapshot arrive together")
    void everythingThatSaysItEndedArrivesTogether() throws RepositoryException {
        final Session session = running();
        final TerminalCommit.Committed committed = assertInstanceOf(TerminalCommit.Committed.class,
                commit(session, inline(), OperationState.SUCCEEDED), "the operation did not end");
        assertEquals(OperationState.SUCCEEDED, committed.operation().state());
        assertEquals(JobEventKind.SUCCEEDED, committed.event().kind());
        assertEquals(1, committed.event().sequence().number(),
                "the terminal event was not written at the sequence after the last one");
        assertEquals(new ExecutionOutcome.Inline(inlineDocument()),
                TerminalCommit.answerIn(session, operation()).orElseThrow(),
                "the answer a reader gets is not the one that was committed");
        assertInstanceOf(SnapshotStore.Agrees.class, SnapshotStore.verify(session, operation(),
                new SnapshotStore.Says(committed.operation().state().kind())),
                "the snapshot and the ledger do not say what the record says");
        assertEquals(2, EventLedger.events(session, operation().child(EventLedger.NODE)));
    }

    @Test
    @DisplayName("no commit anywhere in the ending stages a state without everything that says so")
    void nocommitStagesAstateAlone() throws RepositoryException {
        final Session session = running();
        final List<String> staged = new java.util.ArrayList<>();
        final Session watched = observed(session, () -> staged.add(stagedPair(session)));
        assertInstanceOf(TerminalCommit.Committed.class,
                commit(watched, inline(), OperationState.SUCCEEDED));
        assertFalse(staged.isEmpty(), "the ending committed nothing, so nothing was watched");
        for (final String pair : staged) {
            assertTrue(List.of("running/started/1/nothing", "succeeded/succeeded/2/inline")
                            .contains(pair),
                    "a commit staged a state without everything that says so: " + pair);
        }
    }

    @Test
    @DisplayName("a published answer names bytes the store has committed, and nothing else")
    void apublishedAnswerNamesCommittedBytes() throws RepositoryException {
        final Session session = running();
        final byte[] content = bytes(FIXTURES.resolve("published-result.txt"));
        final ExecutionOutcome.Published uncommitted = new ExecutionOutcome.Published(
                slot(), content.length, Digest.of(content));
        assertEquals(TerminalCommit.Refusal.ARTIFACT_NOT_COMMITTED, TerminalCommit.refusalIn(
                commit(session, uncommitted, OperationState.SUCCEEDED)).orElseThrow().refusal(),
                "an answer naming bytes nothing holds was written down");
        assertEquals(OperationState.RUNNING, stored(session).state(),
                "a refused ending moved the record anyway");
        assertEquals(1, EventLedger.events(session, operation().child(EventLedger.NODE)),
                "a refused ending wrote a terminal event");
        ArtifactStore.prepare(session, caller());
        assertInstanceOf(ArtifactStore.Published.class, ArtifactStore.publish(session, caller(),
                operation(), new ArtifactStore.Publication(slot(), content.length,
                        new ByteArrayInputStream(content)), NOW, CONTRACT));
        assertEquals(TerminalCommit.Refusal.ARTIFACT_NOT_COMMITTED, TerminalCommit.refusalIn(
                commit(session, new ExecutionOutcome.Published(slot(), content.length - 1,
                        Digest.of(content)), OperationState.SUCCEEDED)).orElseThrow().refusal(),
                "an answer naming a different number of bytes than the store holds was accepted");
        assertInstanceOf(TerminalCommit.Committed.class,
                commit(session, uncommitted, OperationState.SUCCEEDED),
                "an answer naming exactly what the store committed was refused");
        assertEquals(uncommitted, TerminalCommit.answerIn(session, operation()).orElseThrow());
    }

    @Test
    @DisplayName("an unreferenced artifact leaves the operation still executable")
    void anunreferencedArtifactLeavesTheOperationExecutable() throws RepositoryException {
        final Session session = running();
        final byte[] content = bytes(FIXTURES.resolve("published-result.txt"));
        ArtifactStore.prepare(session, caller());
        ArtifactStore.publish(session, caller(), operation(),
                new ArtifactStore.Publication(slot(), content.length,
                        new ByteArrayInputStream(content)), NOW, CONTRACT);
        assertEquals(OperationState.RUNNING, stored(session).state(),
                "committing the bytes ended the operation, which is not what commits it");
        assertTrue(TerminalCommit.answerIn(session, operation()).isEmpty(),
                "an operation with no ending has an answer");
        assertInstanceOf(TerminalCommit.Committed.class,
                commit(session, new ExecutionOutcome.Published(slot(), content.length,
                        Digest.of(content)), OperationState.SUCCEEDED),
                "an operation interrupted after its artifact could not be finished afterwards");
    }

    @Test
    @DisplayName("a store with no room for the terminal event ends nothing")
    void astoreWithNoRoomEndsNothing() throws RepositoryException {
        final AgentContract full = contractWith(
                "maximum_current_generation_event_rows", 1L,
                "maximum_caller_current_generation_event_rows", 1L);
        final Session session = running(full);
        assertEquals(1, rs.slingshot.agent.store.CapacityLedger.held(session,
                rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS, full),
                "the first event was not counted");
        assertEquals(1, rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS
                .admissibleTotal(full), "the shrunken bound is not what the suite thinks");
        final rs.slingshot.agent.store.CapacityLedger.Refused refused = assertInstanceOf(
                TerminalCommit.AtCapacity.class,
                TerminalCommit.commit(session, caller(), stored(session),
                        assertInstanceOf(ExecutionOutcome.Held.class, ExecutionOutcome.of(
                                OperationState.SUCCEEDED, inline(), NOW, full)).outcome(), full),
                "an operation ended in a store with no room for the event that says so")
                .refusal();
        assertEquals(rs.slingshot.agent.store.AccountedQuantity.EVENT_ROWS, refused.quantity());
        assertEquals(OperationState.RUNNING, stored(session).state(),
                "an ending with no room for its event moved the record anyway");
        assertTrue(TerminalCommit.answerIn(session, operation()).isEmpty(),
                "an ending with no room for its event wrote an answer");
    }

    @Test
    @DisplayName("nothing here writes an artifact byte")
    void nothingHereWritesAnArtifactByte() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/execution/TerminalCommit.java"));
        for (final String writing : List.of("createBinary", "ArtifactStore.publish",
                "InputStream", "setProperty(ArtifactStore.CONTENT")) {
            assertFalse(source.contains(writing),
                    "the ending reaches for " + writing + ", which writes artifact bytes");
        }
        assertTrue(source.contains("ArtifactStore.read"),
                "the ending does not check what the store committed");
        assertTrue(source.contains("SnapshotStore.record"),
                "the state is written somewhere other than inside the event's own commit");
        assertFalse(source.contains("OperationStore.move"),
                "the state is moved by a path that does not carry the event with it");
    }

    @Test
    @DisplayName("ending twice as the same thing changes nothing, and as something else refuses")
    void endingTwiceAsTheSameThingChangesNothing() throws RepositoryException {
        final Session session = running();
        final LogicalOperation read = stored(session);
        assertInstanceOf(TerminalCommit.Committed.class,
                commit(session, inline(), OperationState.SUCCEEDED));
        final long events = EventLedger.events(session, operation().child(EventLedger.NODE));
        assertInstanceOf(TerminalCommit.Unchanged.class,
                TerminalCommit.commit(session, caller(), read, outcome(inline(),
                        OperationState.SUCCEEDED), CONTRACT),
                "ending twice as exactly the same thing was refused");
        assertEquals(events, EventLedger.events(session, operation().child(EventLedger.NODE)),
                "ending twice wrote a second terminal event");
        final TerminalCommit.Refused refused = TerminalCommit.refusalIn(
                TerminalCommit.commit(session, caller(), read,
                        outcome(ExecutionOutcome.Nothing.NOTHING_TO_RETURN, OperationState.FAILED),
                        CONTRACT)).orElseThrow();
        assertEquals(TerminalCommit.Refusal.DIFFERENT_OUTCOME, refused.refusal());
        assertTrue(refused.detail().contains("succeeded") && refused.detail().contains("failed"),
                refused.detail());
        assertEquals(OperationState.SUCCEEDED, stored(session).state(),
                "a refused second ending changed what the first one wrote");
    }

    @Test
    @DisplayName("a worker whose record moved under it cannot write its outcome over another's")
    void aworkerWhoseRecordMovedCannotWriteOverAnother() throws RepositoryException {
        final Session session = running();
        final LogicalOperation read = stored(session);
        final java.util.concurrent.atomic.AtomicBoolean moved =
                new java.util.concurrent.atomic.AtomicBoolean();
        final Session watched = observed(session, () -> {
            if (moved.compareAndSet(false, true)) {
                moveTheRecord(session);
            }
        });
        final TerminalCommit.Refused refused = TerminalCommit.refusalIn(
                TerminalCommit.commit(watched, caller(), read,
                        outcome(inline(), OperationState.SUCCEEDED), CONTRACT)).orElseThrow();
        assertEquals(TerminalCommit.Refusal.NOT_THE_STATE_THAT_WAS_READ, refused.refusal());
        assertEquals(OperationState.FAILED, stored(session).state(),
                "the stale worker wrote its outcome over the one that was already there");
        assertEquals(1, EventLedger.events(session, operation().child(EventLedger.NODE)),
                "the stale worker wrote a terminal event for an outcome nothing holds");
        assertTrue(TerminalCommit.answerIn(session, operation()).isEmpty(),
                "the stale worker wrote its answer beside somebody else's state");
    }

    @Test
    @DisplayName("an outcome that is not an ending, and one too large to carry, are refused")
    void anoutcomeThatIsNotAnEndingIsRefused() {
        assertEquals(ExecutionOutcome.Refusal.NOT_TERMINAL, ExecutionOutcome.refusalIn(
                ExecutionOutcome.of(OperationState.RUNNING,
                        ExecutionOutcome.Nothing.NOTHING_TO_RETURN, NOW, CONTRACT)).orElseThrow()
                .refusal());
        final String large = "x".repeat((int) CONTRACT.value(rs.slingshot.agent.contract
                .ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES) + 1);
        assertEquals(ExecutionOutcome.Refusal.RESULT_TOO_LARGE, ExecutionOutcome.refusalIn(
                ExecutionOutcome.of(OperationState.SUCCEEDED,
                        new ExecutionOutcome.Inline(large), NOW, CONTRACT)).orElseThrow()
                .refusal());
        assertEquals(ExecutionOutcome.Kind.NOTHING, outcome(
                ExecutionOutcome.Nothing.NOTHING_TO_RETURN, OperationState.FAILED).kind());
        assertEquals(0, outcome(ExecutionOutcome.Nothing.NOTHING_TO_RETURN,
                OperationState.FAILED).inlineBytes());
        assertTrue(ExecutionOutcome.Kind.named("teleported").isEmpty());
    }

    private void moveTheRecord(Session session) {
        try {
            final Node record = session.getNode(operation().path());
            record.setProperty(OperationStore.STATE, OperationState.FAILED.spelling());
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the record could not be moved", unwritable);
        }
    }

    private String stagedPair(Session session) {
        try {
            final Node record = session.getNode(operation().path());
            final SnapshotStore.Materialised snapshot =
                    SnapshotStore.read(session, operation());
            return record.getProperty(OperationStore.STATE).getString() + "/"
                    + (snapshot instanceof final SnapshotStore.Known known
                            ? known.snapshot().kind().spelling() : "nothing") + "/"
                    + EventLedger.events(session, operation().child(EventLedger.NODE)) + "/"
                    + (record.hasProperty(TerminalCommit.RESULT_KIND)
                            ? record.getProperty(TerminalCommit.RESULT_KIND).getString()
                            : "nothing");
        } catch (final RepositoryException unreadable) {
            throw new IllegalStateException("the staged state could not be read", unreadable);
        }
    }

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

    private TerminalCommit.Outcome commit(Session session, ExecutionOutcome.Result result,
                                          OperationState state) throws RepositoryException {
        return TerminalCommit.commit(session, caller(), stored(session), outcome(result, state),
                CONTRACT);
    }

    private static ExecutionOutcome outcome(ExecutionOutcome.Result result, OperationState state) {
        return assertInstanceOf(ExecutionOutcome.Held.class,
                ExecutionOutcome.of(state, result, NOW, CONTRACT),
                "the outcome was refused").outcome();
    }

    private static ExecutionOutcome.Inline inline() {
        return new ExecutionOutcome.Inline(inlineDocument());
    }

    private static String inlineDocument() {
        return new String(assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(document("inline-result.json")),
                "the inline answer has no canonical form").bytes(), StandardCharsets.UTF_8);
    }

    private static ArtifactSlot slot() {
        return assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of("result"),
                "the slot was refused").slot();
    }

    private LogicalOperation stored(Session session) throws RepositoryException {
        return assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity()), "nothing holds the operation")
                .operation();
    }

    private Session running() throws RepositoryException {
        return running(CONTRACT);
    }

    /**
     * An operation that has started, with its first event counted under the contract given.
     *
     * <p>The contract has to be the same one the later commit uses: a sharded count spreads itself
     * across a number of shards the bound decides, so a count written under one bound and read
     * under another is read from the wrong shards.</p>
     *
     * @param contract the contract this operation's whole life is lived under
     * @return the session
     * @throws RepositoryException if the repository fails
     */
    private Session running(AgentContract contract) throws RepositoryException {
        final Session session = prepared();
        GenerationStore.establish(session);
        final LogicalOperation accepted = assertInstanceOf(LogicalOperation.Held.class,
                LogicalOperation.accepted(identity(), digest("a submission"), commandContract(),
                        caller(), NOW, NOW, CONTRACT)).operation();
        OperationStore.create(session, accepted);
        assertInstanceOf(EventLedger.Appended.class, SnapshotStore.record(session, caller(),
                startedEvent(), startedCanonical(), NOW, contract),
                "the started event was not recorded");
        assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, stored(session), OperationState.RUNNING),
                "the operation would not start");
        return session;
    }

    private static rs.slingshot.agent.wire.JobEvent startedEvent() {
        return assertInstanceOf(rs.slingshot.agent.wire.JobEvent.Held.class,
                rs.slingshot.agent.wire.JobEvent.read(startedDocument(),
                        identity().generation(), CONTRACT),
                "the started event is not one this build reads").event();
    }

    private static byte[] startedCanonical() {
        return assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(startedDocument()),
                "the started event has no canonical form").bytes();
    }

    private static DocumentValue startedDocument() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(rs.slingshot.agent.wire.JobEvent.GENERATION,
                new DocumentValue.Whole(identity().generation().number()));
        members.put(rs.slingshot.agent.wire.JobEvent.IDENTIFIER,
                new DocumentValue.Text(identity().identifier().rendered()));
        members.put(rs.slingshot.agent.wire.JobEvent.KIND, new DocumentValue.Text("started"));
        members.put(rs.slingshot.agent.wire.JobEvent.SEQUENCE, new DocumentValue.Whole(0));
        return new DocumentValue.Mapping(members);
    }

    private static OperationIdentity identity() {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document("operation.json"), CONTRACT),
                "the operation identity was refused").identity();
    }

    private static CommandContractIdentity commandContract() {
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(document("command-contract.json"),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static StatePath operation() {
        return OperationStore.pathOf(identity());
    }

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-ending-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        final String path = operation().path();
        Node walked = session.getRootNode();
        for (final String segment : path.substring(1, path.lastIndexOf('/')).split("/")) {
            walked = walked.hasNode(segment) ? walked.getNode(segment)
                    : walked.addNode(segment, "nt:unstructured");
        }
        session.save();
        LedgerAdmission.prepare(session, caller());
        return session;
    }

    private static AgentContract contractWith(String bound, long value, String share,
                                              long shareValue) {
        final java.util.Map<String, Long> overrides = java.util.Map.of(bound, value, share,
                shareValue);
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
