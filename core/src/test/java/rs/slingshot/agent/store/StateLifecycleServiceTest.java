// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.sling.api.resource.LoginException;
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

    @Test
    void snapshotAndRunValuesRemainObservable() {
        final StateLifecycleService.Snapshot snapshot = new StateLifecycleService.Snapshot(
                StateLifecycleService.Availability.READY, 4, "ok");
        assertEquals(StateLifecycleService.Availability.READY, snapshot.availability());
        assertEquals(4, snapshot.generation());
        assertEquals("ok", snapshot.detail());
        final StateLifecycleService.Run run = new StateLifecycleService.Run(null, "detail");
        assertEquals("detail", run.detail());
    }
}
