// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.health.AgentHealth;

final class HealthDataSourceTest {

    @Test
    void rendersLiveHealthResultsAfterAuthorization() {
        final AgentHealth.Result result = AgentHealth.healthy(AgentHealth.Check.CAPACITY, "ok");
        final HealthDataSource source = new HealthDataSource(() -> List.of(result));
        final ConsoleDataSource.Request request = new ConsoleDataSource.Request(
                group -> rs.slingshot.agent.http.AuthorizationGate.Standing.A_MEMBER, 0, 10, 10);
        final ConsolePage<?> page = assertInstanceOf(ConsoleDataSource.Rendered.class,
                source.of(request)).page();
        assertEquals(List.of(result), page.rows());
    }
}
