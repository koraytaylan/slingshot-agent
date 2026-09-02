// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.wire.ErrorCode;

/**
 * One row per category, no default, and no hint on a refusal that trying again cannot fix.
 *
 * <p>The last of those is the one with teeth. A hint on a refusal that will never succeed is an
 * instruction to waste an author's request budget, and the mapping is refused outright rather than
 * read with the hint ignored — because a document nobody can act on is better than one that reads
 * as though somebody had.</p>
 */
final class StatusMappingTest {

    private static final AgentContract CONTRACT = contract();

    private static final StatusMapping MAPPING = mapping();

    @Test
    @DisplayName("every category the protocol declares has exactly one row, and every row a category")
    void everycategoryHasArow() {
        assertEquals(ErrorCode.spellings().stream().sorted().toList(),
                MAPPING.categories().stream().sorted().toList(),
                "a category has no row, or a row names a category the protocol does not declare");
        for (final ErrorCode code : ErrorCode.values()) {
            assertTrue(MAPPING.forCode(code).isPresent(), code + " has no row");
        }
        assertTrue(MAPPING.forCategory("a_category_nobody_declared").isEmpty(),
                "a category nobody declared was rendered rather than refused");
    }

    @Test
    @DisplayName("no row that cannot be retried carries a hint, across the whole mapping")
    void norowThatCannotBeRetriedCarriesAhint() {
        for (final StatusMapping.Row row : MAPPING.rows()) {
            assertFalse(!row.isWorthTryingAgain() && row.carriesAhint(),
                    row.category() + " cannot be retried and carries a hint");
            assertTrue(row.status() >= LOWEST_REFUSAL && row.status() < HIGHEST_STATUS,
                    row.category() + " is answered with " + row.status());
            assertFalse(row.reason().isBlank(), row.category() + " states no reason");
        }
    }

    /** The lowest status this mapping ever answers with, which is the first refusal. */
    private static final int LOWEST_REFUSAL = 400;

    /** One past the highest status anything answers with. */
    private static final int HIGHEST_STATUS = 600;

    @Test
    @DisplayName("a mapping carrying a hint on a refusal that cannot be retried is refused")
    void amappingCarryingAnImpossibleHintIsRefused() {
        final StatusMapping.Refused refused = assertInstanceOf(StatusMapping.Refused.class,
                StatusMapping.read("""
                        [[row]]
                        category = "forbidden"
                        status = 403
                        retryable = false
                        hint = true
                        reason = "a hint on a refusal nobody can retry"
                        """),
                "a mapping asking a client to wait for something that never changes was read");
        assertEquals(StatusMapping.Failure.A_HINT_THAT_CANNOT_HELP, refused.failure());
        assertTrue(refused.detail().contains("forbidden"), refused.detail());
    }

    @Test
    @DisplayName("a row missing anything every row states is refused rather than defaulted")
    void arowMissingSomethingIsRefused() {
        assertEquals(StatusMapping.Failure.INCOMPLETE, assertInstanceOf(StatusMapping.Refused.class,
                StatusMapping.read("""
                        [[row]]
                        category = "forbidden"
                        status = 403
                        """)).failure());
        assertEquals(StatusMapping.Failure.UNPARSABLE, assertInstanceOf(StatusMapping.Refused.class,
                StatusMapping.read("this is not a mapping")).failure());
        assertEquals(StatusMapping.Failure.UNPARSABLE, assertInstanceOf(StatusMapping.Refused.class,
                StatusMapping.read("# a document with no rows at all")).failure());
    }

    @Test
    @DisplayName("a hint is capped rather than clamped, and refused where waiting cannot help")
    void ahintIsCappedRatherThanClamped() {
        final StatusMapping.Row retryable = MAPPING.forCode(ErrorCode.CAPACITY_EXHAUSTED)
                .orElseThrow();
        final long cap = CONTRACT.value(ContractLimit.RETRY_AFTER_CAP_MILLISECONDS);
        assertEquals(cap / 1000, assertInstanceOf(RetryHint.Held.class,
                        RetryHint.of(retryable, cap, CONTRACT)).hint().seconds(),
                "a hint at exactly the cap was not sent");
        final RetryHint.Refused past = RetryHint.refusalIn(
                RetryHint.of(retryable, cap + 1, CONTRACT)).orElseThrow();
        assertEquals(RetryHint.Refusal.PAST_THE_CAP, past.refusal());
        assertTrue(past.detail().contains(String.valueOf(cap)), past.detail());
        final StatusMapping.Row never = MAPPING.forCode(ErrorCode.FORBIDDEN).orElseThrow();
        assertEquals(RetryHint.Refusal.NOT_RETRYABLE,
                RetryHint.refusalIn(RetryHint.of(never, 1000, CONTRACT)).orElseThrow().refusal(),
                "a refusal nobody can retry was given a hint");
        assertEquals(1, assertInstanceOf(RetryHint.Held.class,
                        RetryHint.of(retryable, 1, CONTRACT)).hint().seconds(),
                "a hint below a second was rendered as no wait at all");
    }

    @Test
    @DisplayName("what each category is answered with is the mapping's own decision")
    void whateachCategoryIsAnsweredWithIsTheMappings() {
        assertEquals(List.of(401, 403, 404, 405), List.of(
                        MAPPING.forCode(ErrorCode.UNAUTHENTICATED).orElseThrow().status(),
                        MAPPING.forCode(ErrorCode.FORBIDDEN).orElseThrow().status(),
                        MAPPING.forCode(ErrorCode.UNKNOWN_ROUTE).orElseThrow().status(),
                        MAPPING.forCode(ErrorCode.METHOD_NOT_PERMITTED).orElseThrow().status()),
                "the transport refusals are answered with something other than their own statuses");
        assertEquals(AuthenticationGate.STATUS,
                MAPPING.forCode(ErrorCode.UNAUTHENTICATED).orElseThrow().status(),
                "the gate and the mapping disagree about what an unauthenticated caller is told");
        assertEquals(AuthorizationGate.STATUS,
                MAPPING.forCode(ErrorCode.FORBIDDEN).orElseThrow().status(),
                "the gate and the mapping disagree about what an unpermitted caller is told");
        assertEquals(ShapeRefusal.WRONG_METHOD.status(),
                MAPPING.forCode(ErrorCode.METHOD_NOT_PERMITTED).orElseThrow().status(),
                "the shape rules and the mapping disagree about a method nobody answers");
    }

    private static StatusMapping mapping() {
        return assertInstanceOf(StatusMapping.Loaded.class, StatusMapping.load(),
                "the mapping was refused").mapping();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
