// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import rs.slingshot.agent.health.AgentHealth;

/** Renders the live health checks for the authorized console. */
public final class HealthDataSource implements ConsoleDataSource.Rows {

    private final Supplier<List<AgentHealth.Result>> results;

    /**
     * Holds a source over health results.
     *
     * @param results live health results, read only after console authorization
     */
    public HealthDataSource(Supplier<List<AgentHealth.Result>> results) {
        this.results = Objects.requireNonNull(results, "results");
    }

    @Override
    public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
        final List<AgentHealth.Result> held = List.copyOf(results.get());
        return new ConsoleDataSource.Rendered(new ConsolePage<>(held, 0,
                new ConsolePage.Counted(held.size())));
    }
}
