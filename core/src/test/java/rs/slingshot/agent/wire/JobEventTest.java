// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Five kinds, one run of sequences, and one incarnation of the store.
 *
 * <p>An unknown kind is refused rather than read as something near it, because both ways of
 * guessing are wrong: read as progress, a terminal kind waits forever; read as terminal, a progress
 * kind reports an outcome that has not happened.</p>
 */
final class JobEventTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/job-event");

    private static final Path SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/job/event.json");

    private static final AgentContract CONTRACT = contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    private static final EventStoreGeneration SERVING = generation(1);

    @Test
    @DisplayName("each of the five kinds is read, and says for itself whether anything follows")
    void everyKindIsReadWithItsOwnFinality() {
        assertEquals(JobEventKind.Finality.CONTINUES, held("accepted.json").kind().finality());
        assertEquals(JobEventKind.Finality.CONTINUES, held("started.json").kind().finality());
        assertEquals(JobEventKind.Finality.CONTINUES, held("progress.json").kind().finality());
        assertEquals(JobEventKind.Finality.ENDS, held("succeeded.json").kind().finality());
        assertEquals(JobEventKind.Finality.ENDS, held("failed.json").kind().finality());
        assertEquals(5, JobEventKind.values().length, "a sixth kind appeared");
    }

    @Test
    @DisplayName("a kind this build does not know is refused rather than read as something near it")
    void anUnknownKindIsRefused() {
        assertEquals(JobEvent.Refusal.UNKNOWN_KIND, refusal("unknown-kind.json").refusal());
        assertEquals(JobEvent.Refusal.MEMBER_ABSENT, refusal("absent-kind.json").refusal());
        assertEquals(JobEvent.Refusal.MEMBER_UNKNOWN, refusal("a-fifth-member.json").refusal());
        assertEquals(JobEvent.Refusal.NOT_A_DOCUMENT, refusal("not-an-object.json").refusal());
    }

    @Test
    @DisplayName("a sequence starts at zero, and a repeat and a decrease are two refusals")
    void thesequenceOnlyEverMovesForward() {
        assertEquals(0, held("sequence-zero.json").sequence().number());
        final EventSequence first = held("sequence-zero.json").sequence();
        final EventSequence second = held("sequence-one.json").sequence();
        assertInstanceOf(EventSequence.Held.class, second.after(first),
                "the next sequence was refused");
        assertEquals(EventSequence.Refusal.REPEATED,
                assertInstanceOf(EventSequence.Refused.class, first.after(first)).refusal());
        final EventSequence.Refused backwards =
                assertInstanceOf(EventSequence.Refused.class, first.after(second));
        assertEquals(EventSequence.Refusal.WENT_BACKWARDS, backwards.refusal());
        assertTrue(backwards.detail().contains("0") && backwards.detail().contains("1"),
                backwards.detail());
        assertEquals(JobEvent.Refusal.OUT_OF_RANGE, refusal("sequence-below-zero.json").refusal());
    }

    @Test
    @DisplayName("an event naming another incarnation of the store is refused naming both")
    void aForeignGenerationIsRefusedNamingBoth() {
        final JobEvent.Refused refused = refusal("another-generation.json");
        assertEquals(JobEvent.Refusal.FOREIGN_GENERATION, refused.refusal());
        assertTrue(refused.detail().contains("2") && refused.detail().contains("1"),
                refused.detail());
        assertEquals(JobEvent.Refusal.OUT_OF_RANGE, refusal("generation-zero.json").refusal());
    }

    @Test
    @DisplayName("an identifier of the wrong shape and a sequence that is not a number are refused")
    void everyOtherShapeIsRefused() {
        assertEquals(JobEvent.Refusal.IDENTIFIER_REFUSED,
                refusal("identifier-not-the-shape.json").refusal());
        assertEquals(JobEvent.Refusal.WRONG_KIND_OF_VALUE,
                refusal("sequence-that-is-not-a-number.json").refusal());
    }

    @Test
    @DisplayName("the per-operation event and byte budgets each hold at the limit and one past it")
    void bothBudgetsHoldAtBothSides() {
        final JobEvent.Budget budget = JobEvent.Budget.from(CONTRACT);
        assertTrue(budget.admits(budget.events() - 1, 0, 1).isEmpty(),
                "an event that fits inside the count was refused");
        assertEquals(JobEvent.Refusal.TOO_MANY_EVENTS,
                budget.admits(budget.events(), 0, 1).orElseThrow().refusal());
        assertTrue(budget.admits(0, budget.bytes() - 1, 1).isEmpty(),
                "an event that fits inside the bytes was refused");
        assertEquals(JobEvent.Refusal.TOO_MANY_EVENT_BYTES,
                budget.admits(0, budget.bytes(), 1).orElseThrow().refusal());
        assertEquals(CONTRACT.value(
                        rs.slingshot.agent.contract.ContractLimit.MAXIMUM_OPERATION_EVENT_ROWS),
                budget.events());
        assertEquals(CONTRACT.value(
                        rs.slingshot.agent.contract.ContractLimit.MAXIMUM_OPERATION_EVENT_BYTES),
                budget.bytes());
    }

    @Test
    @DisplayName("the committed schema's kinds and members are this model's, in both directions")
    void theSchemaAndTheModelAgree() {
        final DocumentValue.Mapping properties = assertInstanceOf(DocumentValue.Mapping.class,
                schema().member("properties").orElseThrow());
        assertEquals(JobEvent.MEMBERS.stream().sorted().toList(),
                List.copyOf(properties.members().keySet()).stream().sorted().toList());
        final DocumentValue.Mapping kind = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member("kind").orElseThrow());
        assertEquals(JobEventKind.spellings(),
                assertInstanceOf(DocumentValue.Sequence.class, kind.member("enum").orElseThrow())
                        .items().stream()
                        .map(item -> assertInstanceOf(DocumentValue.Text.class, item).value())
                        .sorted()
                        .toList(),
                "this build and the committed schema disagree about the kinds that exist");
        assertEquals(List.of("accepted", "failed", "progress", "started", "succeeded"),
                Arrays.stream(JobEventKind.values()).map(JobEventKind::spelling).sorted().toList());
    }

    private static DocumentValue.Mapping schema() {
        return assertInstanceOf(DocumentValue.Mapping.class,
                document(new String(read(SCHEMA), StandardCharsets.UTF_8).strip()
                        .getBytes(StandardCharsets.UTF_8)));
    }

    static JobEvent held(String fixture) {
        return assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document(read(FIXTURES.resolve(fixture))), SERVING, CONTRACT),
                fixture + " was refused").event();
    }

    private static JobEvent.Refused refusal(String fixture) {
        return assertInstanceOf(JobEvent.Refused.class,
                JobEvent.read(document(read(FIXTURES.resolve(fixture))), SERVING, CONTRACT),
                fixture + " was read as an event");
    }

    static EventStoreGeneration generation(long number) {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(number),
                number + " is not a generation").generation();
    }

    static DocumentValue document(byte[] bytes) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes, DOCUMENT_BOUNDS),
                "the fixture is not a document this reader accepts").value();
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
