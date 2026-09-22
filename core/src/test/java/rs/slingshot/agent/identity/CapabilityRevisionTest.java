// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A revision number is the behaviour this build declares, and nothing before the first is one.
 */
final class CapabilityRevisionTest {

    @Test
    @DisplayName("a number before the first revision is refused, and one that is a revision compares"
            + " as its number")
    void anumberBeforeTheFirstIsNotARevision() {
        final CapabilityRevision.Refused refused = assertInstanceOf(CapabilityRevision.Refused.class,
                CapabilityRevision.of(0), "zero was held as a revision");
        assertEquals(CapabilityRevision.Refusal.BEFORE_THE_FIRST, refused.refusal());
        assertTrue(refused.detail().contains(Long.toString(CapabilityRevision.FIRST)),
                refused.detail());
        final CapabilityRevision revision = assertInstanceOf(CapabilityRevision.Held.class,
                CapabilityRevision.of(CapabilityRevision.CURRENT)).revision();
        final CapabilityRevision again = assertInstanceOf(CapabilityRevision.Held.class,
                CapabilityRevision.of(CapabilityRevision.CURRENT)).revision();
        assertEquals(revision, again);
        assertEquals(revision.hashCode(), again.hashCode());
        assertEquals(Long.toString(CapabilityRevision.CURRENT), revision.toString());
        assertEquals(CapabilityRevision.CURRENT, revision.number());
        assertNotEquals(revision, "not a revision");
    }
}
