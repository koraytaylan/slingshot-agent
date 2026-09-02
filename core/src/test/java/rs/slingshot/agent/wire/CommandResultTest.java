// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.json.DocumentValue;

/**
 * An answer, a failure, and the line between them.
 *
 * <p>The inline bound is proved at both sides on answers this suite builds, because what matters is
 * what happens at the boundary rather than what a file of exactly a mebibyte looks like committed.
 * Crossing it is a switch rather than a refusal: the larger answer exists, and refusing it would be
 * this side deciding that something it already produced does not count.</p>
 */
final class CommandResultTest {

    private static final Path REPOSITORY = JobEventTest.repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/command-result");

    private static final AgentContract CONTRACT = JobEventTest.contract();

    private static final long INLINE_BOUND =
            CONTRACT.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES);

    @Test
    @DisplayName("an answer at exactly the inline bound stays inline, and one byte past it does not")
    void theInlineBoundSwitchesRatherThanRefuses() {
        final byte[] atTheBound = answerOf(INLINE_BOUND);
        final ResultDelivery inline = CommandResult.of(atTheBound, CONTRACT).delivery();
        assertInstanceOf(ResultDelivery.Inline.class, inline,
                "an answer of exactly the inline bound was published as an artifact");
        assertEquals("inline", inline.spelling());
        final byte[] onePast = answerOf(INLINE_BOUND + 1);
        final ResultDelivery.Artifact artifact = assertInstanceOf(ResultDelivery.Artifact.class,
                CommandResult.of(onePast, CONTRACT).delivery(),
                "an answer past the inline bound was refused rather than published");
        assertEquals(onePast.length, artifact.byteCount());
        assertEquals(Digest.of(onePast).rendered(), artifact.digest().rendered(),
                "the artifact reference does not say what the answer will digest to");
        assertEquals("artifact", artifact.spelling());
    }

    @Test
    @DisplayName("a delivery carrying both an answer and a reference, or neither, is refused")
    void bothAndNeitherAreRefused() {
        assertEquals(CommandResult.Refusal.DELIVERY_REFUSED, refusal("both.json").refusal());
        assertTrue(refusal("both.json").detail().contains("BOTH"),
                refusal("both.json").detail());
        assertTrue(refusal("neither.json").detail().contains("NEITHER"),
                refusal("neither.json").detail());
        assertTrue(refusal("delivery-nobody-declared.json").detail().contains("UNKNOWN_DELIVERY"),
                refusal("delivery-nobody-declared.json").detail());
        assertTrue(refusal("artifact-digest-upper-case.json").detail().contains("NOT_A_DIGEST"),
                refusal("artifact-digest-upper-case.json").detail());
    }

    @Test
    @DisplayName("a result carrying a category and a failure carrying an answer are both refused")
    void aResultAndAFailureAreTwoDocuments() {
        assertEquals(CommandResult.Refusal.CARRIES_A_FAILURE,
                refusal("result-carrying-a-category.json").refusal());
        assertEquals(CommandFailure.Refusal.CARRIES_A_RESULT,
                failureRefusal("failure-carrying-a-result.json").refusal());
    }

    @Test
    @DisplayName("a document that is a delivery is read as the delivery it is")
    void bothDeliveriesAreRead() {
        assertEquals("{\"matches\":[]}", assertInstanceOf(ResultDelivery.Inline.class,
                result("inline.json").delivery()).rendered());
        assertEquals(4194304L, assertInstanceOf(ResultDelivery.Artifact.class,
                result("artifact.json").delivery()).byteCount());
    }

    @Test
    @DisplayName("every category states one of the three effects, and unknown is one of them")
    void everyCategoryStatesItsEffect() {
        Arrays.stream(CommandFailure.Category.values()).forEach(category ->
                assertTrue(List.of(CommandFailure.Effect.NONE, CommandFailure.Effect.APPLIED,
                                CommandFailure.Effect.UNKNOWN).contains(category.effect()),
                        category.spelling() + " states no effect this build knows"));
        assertEquals(CommandFailure.Effect.UNKNOWN,
                CommandFailure.Category.BUDGET_SPENT.effect());
        assertEquals(CommandFailure.Effect.APPLIED,
                CommandFailure.Category.APPLIED_THEN_FAILED.effect());
        assertEquals(CommandFailure.Effect.NONE, CommandFailure.Category.NOT_FOUND.effect());
        assertEquals(3, CommandFailure.Effect.values().length, "a fourth effect appeared");
    }

    @Test
    @DisplayName("a failure is read with the effect its category states, and disagreement is refused")
    void aFailureCarriesItsCategorysOwnEffect() {
        assertEquals(CommandFailure.Effect.UNKNOWN, failure("failure.json").effect());
        assertEquals(CommandFailure.Effect.NONE,
                failure("failure-without-an-effect.json").effect());
        assertEquals(CommandFailure.Refusal.EFFECT_DISAGREES,
                failureRefusal("failure-with-a-disagreeing-effect.json").refusal());
        assertEquals(CommandFailure.Refusal.UNKNOWN_CATEGORY,
                failureRefusal("failure-nobody-declared.json").refusal());
        assertEquals(CommandFailure.Refusal.CATEGORY_ABSENT,
                failureRefusal("failure-without-a-category.json").refusal());
    }

    @Test
    @DisplayName("both committed schemas and both typed models agree in both directions")
    void theSchemasAndTheModelsAgree() {
        final DocumentValue.Mapping failure = schema("failure.json");
        final DocumentValue.Mapping properties = assertInstanceOf(DocumentValue.Mapping.class,
                failure.member("properties").orElseThrow());
        assertEquals(CommandFailure.MEMBERS.stream().sorted().toList(),
                List.copyOf(properties.members().keySet()).stream().sorted().toList());
        assertEquals(CommandFailure.Category.spellings(), enumeration(properties, "failure_category"));
        assertEquals(Arrays.stream(CommandFailure.Effect.values())
                        .map(CommandFailure.Effect::spelling).sorted().toList(),
                enumeration(properties, "effect"));
        assertEquals(ResultDelivery.MEMBERS.stream().sorted().toList(),
                deliveryMembers().stream().sorted().toList(),
                "the result schema and the delivery model disagree about what may be carried");
    }

    private static List<String> enumeration(DocumentValue.Mapping properties, String member) {
        return assertInstanceOf(DocumentValue.Sequence.class,
                assertInstanceOf(DocumentValue.Mapping.class,
                        properties.member(member).orElseThrow()).member("enum").orElseThrow())
                .items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Text.class, item).value())
                .sorted()
                .toList();
    }

    private static List<String> deliveryMembers() {
        return assertInstanceOf(DocumentValue.Sequence.class,
                schema("result.json").member("oneOf").orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Mapping.class, item))
                .flatMap(shape -> assertInstanceOf(DocumentValue.Mapping.class,
                        shape.member("properties").orElseThrow()).members().keySet().stream())
                .distinct()
                .toList();
    }

    private static DocumentValue.Mapping schema(String name) {
        return assertInstanceOf(DocumentValue.Mapping.class,
                JobEventTest.document(new String(JobEventTest.read(
                                REPOSITORY.resolve("schemas/agent-protocol/job").resolve(name)),
                        StandardCharsets.UTF_8).strip().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] answerOf(long length) {
        return ("\"" + "x".repeat((int) length - 2) + "\"").getBytes(StandardCharsets.UTF_8);
    }

    private static CommandResult result(String fixture) {
        return assertInstanceOf(CommandResult.Held.class,
                CommandResult.read(document(fixture), CONTRACT),
                fixture + " was refused").result();
    }

    private static CommandResult.Refused refusal(String fixture) {
        return assertInstanceOf(CommandResult.Refused.class,
                CommandResult.read(document(fixture), CONTRACT),
                fixture + " was read as a result");
    }

    private static CommandFailure failure(String fixture) {
        return assertInstanceOf(CommandFailure.Held.class, CommandFailure.read(document(fixture)),
                fixture + " was refused").failure();
    }

    private static CommandFailure.Refused failureRefusal(String fixture) {
        return assertInstanceOf(CommandFailure.Refused.class,
                CommandFailure.read(document(fixture)), fixture + " was read as a failure");
    }

    private static DocumentValue document(String fixture) {
        return JobEventTest.document(JobEventTest.read(FIXTURES.resolve(fixture)));
    }
}
