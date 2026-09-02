// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import java.util.function.Function;

/**
 * One operation, read from every store at once.
 *
 * <p>Assembled from a single view of the stores rather than from four separate reads, and that is
 * not an optimisation. Four reads taken a moment apart can show an event the snapshot has not
 * accounted for — a page saying the operation succeeded above a list whose last line is a failure
 * — and somebody reading that has no way to tell which half is stale.</p>
 */
public final class OperationDetailDataSource implements ConsoleDataSource.Rows {

    private final Function<String, Assembly> assembly;
    private final String operationIdentifier;

    /**
     * Holds one source over whatever assembles the stores.
     *
     * @param operationIdentifier which operation this page is about
     * @param assembly what reads every store under one view, asked only after the authority said
     *     yes
     */
    public OperationDetailDataSource(String operationIdentifier,
                                     Function<String, Assembly> assembly) {
        this.operationIdentifier = operationIdentifier;
        this.assembly = assembly;
    }

    /** What the stores said when they were asked about one operation. */
    public sealed interface Assembly permits Found, Absent, Unavailable {
    }

    /**
     * They hold it.
     *
     * @param detail everything they hold
     */
    public record Found(OperationDetail detail) implements Assembly {
    }

    /**
     * They do not.
     *
     * <p>An answer rather than a failure. An identifier nothing is at is very often an identifier
     * whose operation the sweep has already collected, which is the retention doing what somebody
     * configured it to do.</p>
     */
    public record Absent() implements Assembly {
    }

    /**
     * They could not be asked.
     *
     * @param detail what went wrong, carrying nothing a viewer may not see
     */
    public record Unavailable(String detail) implements Assembly {
    }

    @Override
    public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
        final Assembly held = assembly.apply(operationIdentifier);
        if (held instanceof final Unavailable unavailable) {
            return new ConsoleDataSource.Unreadable(unavailable.detail());
        }
        if (held instanceof Absent) {
            return new ConsoleDataSource.Rendered(new ConsolePage<OperationDetail>(List.of(), 0,
                    new ConsolePage.Counted(0)));
        }
        return new ConsoleDataSource.Rendered(new ConsolePage<>(List.of(((Found) held).detail()), 0,
                new ConsolePage.Counted(1)));
    }
}
