// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import java.util.Optional;

/**
 * One page of console rows, and how much more there is where anybody can cheaply say.
 *
 * <p>The total is an explicit unknown rather than a zero where the store cannot produce one, and
 * that distinction is the whole reason this type exists. A console showing "0 of 0" when it means
 * "here are twenty and nobody counted the rest" teaches an operator that their instance is idle,
 * which is the single most misleading thing a diagnostic screen can say.</p>
 *
 * <p>The window is clamped rather than refused. A console is not a protocol: a person who asked for
 * more rows than one page carries has made no mistake worth an error message, and showing them the
 * page that does fit is what they wanted.</p>
 *
 * @param rows what this page carries, in the store's own order
 * @param offset how many rows were skipped to reach it
 * @param total how many there are altogether, where anybody can cheaply say
 * @param <Row> what kind of row this page carries
 */
public record ConsolePage<Row>(List<Row> rows, long offset, Total total) {

    /** Holds a page whose rows nothing can change afterwards. */
    public ConsolePage {
        rows = List.copyOf(rows);
    }

    /** How many rows there are altogether, or that nobody cheaply knows. */
    public sealed interface Total permits Counted, Unknown {
    }

    /**
     * Somebody counted them.
     *
     * @param count how many there are
     */
    public record Counted(long count) implements Total {
    }

    /**
     * Nobody did, and saying zero would be a lie a reader would act on.
     *
     * <p>A store that would have to walk everything to answer says this instead. It is not a
     * failure and it is not an empty result — it is the honest shape of "there are more, and
     * counting them would cost you the page you asked for".</p>
     */
    public record Unknown() implements Total {
    }

    /**
     * One page of a list somebody already holds.
     *
     * @param everything every row there is, in the store's own order
     * @param offset how many to skip, which is clamped to what there is
     * @param window how many to take, which is clamped to the bound
     * @param bound the most one page may carry, which the contract states
     * @param <Row> what kind of row this is
     * @return the page, whose total is counted because the whole list was already in hand
     */
    public static <Row> ConsolePage<Row> of(List<Row> everything, long offset, long window,
                                            long bound) {
        final long taken = Math.max(1, Math.min(window, bound));
        final long skipped = Math.max(0, Math.min(offset, everything.size()));
        return new ConsolePage<>(everything.stream().skip(skipped).limit(taken).toList(),
                skipped, new Counted(everything.size()));
    }

    /**
     * Whether this page carries nothing.
     *
     * <p>An empty page is an answer rather than a failure: an instance nobody has submitted work to
     * is an ordinary instance, and a console that treated it as an error would send its operator
     * looking for a problem that is not there.</p>
     *
     * @return whether it does
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * How many there are altogether, where anybody counted.
     *
     * @return the count, or nothing where nobody cheaply knows
     */
    public Optional<Long> countedTotal() {
        return total instanceof final Counted counted
                ? Optional.of(counted.count()) : Optional.empty();
    }
}
