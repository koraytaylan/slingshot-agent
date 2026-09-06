// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StateLifecycleServiceTest {

    @Test
    void activationRefusesUntilTheMaintenanceIdentityIsBound() {
        final StateLifecycleService service = new StateLifecycleService();
        service.activate();
        assertEquals(StateLifecycleService.Availability.UNAVAILABLE,
                StateLifecycleService.observed().availability());
        service.deactivate();
        assertEquals("state lifecycle has stopped", StateLifecycleService.observed().detail());
    }
}
