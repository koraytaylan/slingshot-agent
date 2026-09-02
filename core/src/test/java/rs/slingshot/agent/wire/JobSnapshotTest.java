// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What is currently true about a job, and what may follow it.
 *
 * <p>The vocabulary is asserted to be the event document's own types rather than two sets that
 * happen to be spelled the same: a client reconciling a stream with a snapshot compares these
 * values, and two enumerations with equal spellings are two values that are never equal.</p>
 */
final class JobSnapshotTest {

    private static final Path REPOSITORY = JobEventTest.repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/job-snapshot");

    private static final Path SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/job/snapshot.json");

    private static final AgentContract CONTRACT = JobEventTest.contract();

    private static final EventStoreGeneration SERVING = JobEventTest.generation(1);

    @Test
    @DisplayName("a snapshot at every kind is read, with the event kind's own finality")
    void everyKindIsReadWithTheEventKindsFinality() {
        List.of("accepted", "started", "progress", "succeeded", "failed").forEach(kind ->
                assertEquals(JobEventKind.named(kind).orElseThrow().finality(),
                        held(kind + ".json").kind().finality(),
                        "a snapshot and an event disagree about whether " + kind + " ends a job"));
    }

    @Test
    @DisplayName("a snapshot behind one already seen is refused, and so is one from another store")
    void thetwoStalenessRefusalsAreDistinct() {
        final JobSnapshot seen = held("progress.json");
        final JobSnapshot.Rejected behind = assertInstanceOf(JobSnapshot.Rejected.class,
                held("behind.json").following(seen), "a snapshot went backwards and was accepted");
        assertEquals(JobSnapshot.Succession.BEHIND_WHAT_WAS_SEEN, behind.succession());
        assertTrue(behind.detail().contains("1") && behind.detail().contains("4"),
                behind.detail());
        assertInstanceOf(JobSnapshot.Follows.class, held("ahead.json").following(seen),
                "a snapshot ahead of what was seen was refused");
        assertEquals(JobSnapshot.Succession.ANOTHER_OPERATION,
                assertInstanceOf(JobSnapshot.Rejected.class,
                        held("another-operation.json").following(seen)).succession());
    }

    @Test
    @DisplayName("a snapshot from another incarnation of the store is refused as its own thing")
    void aForeignGenerationIsItsOwnRefusal() {
        assertEquals(JobEvent.Refusal.FOREIGN_GENERATION,
                assertInstanceOf(JobSnapshot.Refused.class,
                        JobSnapshot.read(document("another-generation.json"), SERVING, CONTRACT),
                        "a snapshot from another store was read").refusal());
        final JobSnapshot.Rejected foreign = assertInstanceOf(JobSnapshot.Rejected.class,
                held("progress.json").following(assertInstanceOf(JobSnapshot.Held.class,
                        JobSnapshot.read(document("another-generation.json"),
                                JobEventTest.generation(2), CONTRACT)).snapshot()));
        assertEquals(JobSnapshot.Succession.FOREIGN_GENERATION, foreign.succession());
    }

    @Test
    @DisplayName("what has finished has finished, and saying it again is not a conflict")
    void aTerminalSnapshotIsFinal() {
        final JobSnapshot terminal = held("terminal.json");
        assertInstanceOf(JobSnapshot.Follows.class, held("terminal-repeated.json")
                        .following(terminal),
                "the same terminal snapshot twice was treated as a conflict");
        final JobSnapshot.Rejected disagreeing = assertInstanceOf(JobSnapshot.Rejected.class,
                held("terminal-disagreeing.json").following(terminal),
                "a job that had already ended ended again, differently");
        assertEquals(JobSnapshot.Succession.AFTER_A_TERMINAL_ONE, disagreeing.succession());
        assertTrue(disagreeing.detail().contains("succeeded")
                && disagreeing.detail().contains("failed"), disagreeing.detail());
    }

    @Test
    @DisplayName("the kind and the sequence are the event document's own types, not copies")
    void theVocabularyIsShared() {
        final List<Method> answers = Arrays.stream(JobSnapshot.class.getMethods())
                .filter(method -> List.of("kind", "sequence").contains(method.getName()))
                .toList();
        assertEquals(2, answers.size());
        assertEquals(List.of(EventSequence.class, JobEventKind.class), answers.stream()
                        .map(Method::getReturnType)
                        .sorted(java.util.Comparator.comparing(Class::getSimpleName))
                        .toList(),
                "a snapshot answers with types of its own rather than the event document's");
        assertEquals(JobEvent.MEMBERS, JobSnapshot.MEMBERS,
                "a snapshot restates the members rather than sharing them");
    }

    @Test
    @DisplayName("the committed schema and this model name the same members and kinds")
    void theSchemaAndTheModelAgree() {
        final DocumentValue.Mapping properties = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Mapping.class,
                        JobEventTest.document(new String(JobEventTest.read(SCHEMA),
                                        StandardCharsets.UTF_8).strip()
                                .getBytes(StandardCharsets.UTF_8)))
                        .member("properties").orElseThrow());
        assertEquals(JobSnapshot.MEMBERS.stream().sorted().toList(),
                List.copyOf(properties.members().keySet()).stream().sorted().toList());
        assertEquals(JobEventKind.spellings(),
                assertInstanceOf(DocumentValue.Sequence.class,
                        assertInstanceOf(DocumentValue.Mapping.class,
                                properties.member("kind").orElseThrow())
                                .member("enum").orElseThrow()).items().stream()
                        .map(item -> assertInstanceOf(DocumentValue.Text.class, item).value())
                        .sorted()
                        .toList());
    }

    private static JobSnapshot held(String fixture) {
        return assertInstanceOf(JobSnapshot.Held.class,
                JobSnapshot.read(document(fixture), SERVING, CONTRACT),
                fixture + " was refused").snapshot();
    }

    private static DocumentValue document(String fixture) {
        return JobEventTest.document(JobEventTest.read(FIXTURES.resolve(fixture)));
    }
}
