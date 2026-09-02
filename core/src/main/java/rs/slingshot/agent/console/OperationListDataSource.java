// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import java.util.function.Supplier;

/**
 * The list an operator lands on: what this agent has been asked to do, newest first.
 *
 * <p>Newest first because the question that brings somebody here is almost always about something
 * that just happened. An oldest-first list is correct and useless: the row they want is on the last
 * page, and they will page through a year of history to reach it.</p>
 *
 * <p>What the store cannot cheaply count, this does not pretend to. A generation holding a million
 * operations has a total nobody should pay for on the way to reading twenty rows, so the page says
 * it does not know rather than saying nought.</p>
 */
public final class OperationListDataSource implements ConsoleDataSource.Rows {

    private final Supplier<Listing> listing;

    /**
     * Holds one source over whatever produces the operations.
     *
     * @param listing where the rows come from, asked once per answer and only after the authority
     *     has said yes
     */
    public OperationListDataSource(Supplier<Listing> listing) {
        this.listing = listing;
    }

    /** What the store said when it was asked for the operations. */
    public sealed interface Listing permits Held, Unavailable {
    }

    /**
     * The operations it holds, newest first.
     *
     * @param operations what it holds
     * @param total how many there are altogether, where anybody can cheaply say
     */
    public record Held(List<OperationRow> operations, ConsolePage.Total total)
            implements Listing {

        /** Holds a listing whose rows nothing can change afterwards. */
        public Held {
            operations = List.copyOf(operations);
        }
    }

    /**
     * It could not be asked.
     *
     * @param detail what went wrong, carrying nothing a viewer may not see
     */
    public record Unavailable(String detail) implements Listing {
    }

    @Override
    public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
        final Listing held = listing.get();
        if (held instanceof final Unavailable unavailable) {
            return new ConsoleDataSource.Unreadable(unavailable.detail());
        }
        final Held operations = (Held) held;
        final long taken = Math.max(1, Math.min(request.window(), request.bound()));
        final long skipped = Math.max(0, Math.min(request.offset(), operations.operations().size()));
        return new ConsoleDataSource.Rendered(new ConsolePage<>(operations.operations().stream()
                .skip(skipped).limit(taken).toList(), skipped, operations.total()));
    }
}
