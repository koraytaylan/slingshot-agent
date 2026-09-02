// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import java.util.function.Supplier;

/**
 * Generation, capacity and the last sweep — where an operator would otherwise never think to look.
 *
 * <p>Every number here is one somebody only wants once, and always urgently: which generation this
 * instance is on after a rotation, how much of the capacity somebody allotted is actually in use,
 * and when the sweep last ran. None of it is visible from the content, none of it is in a log, and
 * an operator who does not know it exists has no way to ask.</p>
 *
 * <p>A sweep that has never run says so rather than showing an instant of nought, which would read
 * as nineteen-seventy and send somebody looking for a clock problem.</p>
 */
public final class MaintenanceDataSource implements ConsoleDataSource.Rows {

    private final Supplier<State> state;

    /**
     * Holds one source over whatever reads the maintenance state.
     *
     * @param state where the readings come from, asked only after the authority said yes
     */
    public MaintenanceDataSource(Supplier<State> state) {
        this.state = state;
    }

    /**
     * One thing an operator can act on.
     *
     * @param name what it is
     * @param value what it says
     */
    public record Reading(String name, String value) {
    }

    /** What the stores said when they were asked how this instance is doing. */
    public sealed interface State permits Held, Unavailable {
    }

    /**
     * What they hold.
     *
     * @param generation which generation this instance is on
     * @param retainedGenerations how many earlier ones are still readable
     * @param usedCapacity how much of what was allotted is in use
     * @param allottedCapacity how much was allotted
     * @param lastSweep when the sweep last ran, or {@link #NEVER_SWEPT}
     */
    public record Held(String generation, long retainedGenerations, long usedCapacity,
                       long allottedCapacity, long lastSweep) implements State {
    }

    /**
     * They could not be asked.
     *
     * @param detail what went wrong
     */
    public record Unavailable(String detail) implements State {
    }

    /** What the last sweep says when there has not been one. */
    public static final long NEVER_SWEPT = -1;

    /** How the reading spells a sweep that has never run. */
    public static final String NEVER = "never";

    @Override
    public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
        final State held = state.get();
        if (held instanceof final Unavailable unavailable) {
            return new ConsoleDataSource.Unreadable(unavailable.detail());
        }
        return new ConsoleDataSource.Rendered(new ConsolePage<>(readingsOf((Held) held), 0,
                new ConsolePage.Counted(readingsOf((Held) held).size())));
    }

    /**
     * What one maintenance state reads as.
     *
     * @param held what the stores hold
     * @return the readings, in the order an operator would ask about them
     */
    public static List<Reading> readingsOf(Held held) {
        return List.of(
                new Reading("generation", held.generation()),
                new Reading("retained_generations", String.valueOf(held.retainedGenerations())),
                new Reading("used_capacity", String.valueOf(held.usedCapacity())),
                new Reading("allotted_capacity", String.valueOf(held.allottedCapacity())),
                new Reading("last_sweep", held.lastSweep() == NEVER_SWEPT
                        ? NEVER : String.valueOf(held.lastSweep())));
    }
}
