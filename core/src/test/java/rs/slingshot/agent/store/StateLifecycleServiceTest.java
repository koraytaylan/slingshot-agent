// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.apache.sling.api.resource.LoginException;

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

    @Test
    void boundButUnavailableMaintenanceIdentityLeavesServiceUnavailable() {
        final StateLifecycleService service = new StateLifecycleService();
        service.available(new rs.slingshot.agent.repository.AgentSession(subservice -> {
            throw new LoginException("not ready");
        }));
        service.activate();
        assertEquals(StateLifecycleService.Availability.UNAVAILABLE,
                StateLifecycleService.observed().availability());
        service.deactivate();
    }
}
