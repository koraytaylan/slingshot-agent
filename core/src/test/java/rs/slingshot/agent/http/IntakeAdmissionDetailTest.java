// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.WriteOutcome;

/** Admission diagnostics distinguish an exhausted bound from a failed accounting transition. */
final class IntakeAdmissionDetailTest {

    @Test
    void capacityRefusalNamesTheQuantityCountAndReachedScope() {
        Stream.of(CapacityLedger.Reached.values()).forEach(reached -> {
            final var refusal = new CapacityLedger.Refused(AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS,
                    reached, 256, 257);
            final String detail = IntakeSlotWrite.admissionRefusalIn(
                    new IntakeSlotWrite.AtCapacity(refusal)).orElseThrow();
            assertTrue(detail.startsWith("intake capacity refused: active_subscription_rows"));
            assertTrue(detail.contains("257, past 256"));
            assertTrue(detail.endsWith(reached == CapacityLedger.Reached.THE_TOTAL
                    ? "what this store may hold" : "one caller's share of what this store may hold"));
            assertFalse(detail.contains("accounting did not complete"));
        });
    }

    @Test
    void accountingFailureReportsItsClosedOutcomeWithoutClaimingCapacityExhaustion() {
        Stream.of(WriteOutcome.values()).forEach(outcome -> {
            final String detail = IntakeSlotWrite.admissionRefusalIn(
                    new IntakeSlotWrite.NotCounted(outcome)).orElseThrow();
            assertEquals("intake accounting did not complete: " + outcome, detail);
            assertFalse(detail.contains("capacity refused"));
        });
    }

    @Test
    void ordinaryDecisionCannotExposeCallerValuesAsAccountingDiagnostics() {
        final var decision = new IntakeSlotWrite.Decided(new AdmissionOutcome.Conflicting(
                "synthetic-private-member", "synthetic-private-value"));
        assertTrue(IntakeSlotWrite.admissionRefusalIn(decision).isEmpty());
    }
}
