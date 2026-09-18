// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exact bytes explicit uncertainty is spelled as, which the sibling client reads.
 *
 * <p>Nothing on this side decodes this shape back; the only reader of it is the daemon on the
 * other side of the wire, so nothing here would catch a change to it except a test that pins the
 * literal bytes rather than one that only checks the value equals itself.</p>
 */
final class ExecutionOutcomeTest {

    @Test
    @DisplayName("each explicit-uncertainty reason is exactly two members, and no other shape")
    void eachreasonIsExactlyTwoMembers() {
        for (final var pair : new Object[][] {
            {ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE, "result_unavailable"},
            {ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED, "effects_undetermined"},
        }) {
            final ExecutionOutcome.Uncertain uncertain = (ExecutionOutcome.Uncertain) pair[0];
            final String reason = (String) pair[1];
            final ExecutionOutcome.Inline result =
                    assertInstanceOf(ExecutionOutcome.Inline.class, uncertain.result());
            assertEquals("{\"outcome\":\"undetermined\",\"reason\":\"" + reason + "\"}",
                    result.document(),
                    uncertain + " no longer spells the shape the daemon's decoder reads");
        }
    }
}
