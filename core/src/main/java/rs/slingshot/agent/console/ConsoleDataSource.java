// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import rs.slingshot.agent.http.AuthorizationGate;

/**
 * What every console data source is: authority first, then a read, then rows — in that order.
 *
 * <p>The order is the point, and it is structural rather than remembered. The stores this console
 * reads live where no person's session can reach them, so the reading happens as the service user;
 * which means the read cannot also be what decides whether the read should happen. If a screen
 * could reach its store before this type had answered, the service user's reach would be the access
 * control, and the service user can reach everything.</p>
 *
 * <p>A screen is handed to this rather than inheriting from it, which is what makes the order
 * unskippable: a subclass can override an inherited method, and a screen that was <em>given</em>
 * nothing but a chance to produce rows has nothing to override. There is no method here that
 * returns a store, a session, or anything a store is reachable through, and the only way a screen
 * runs at all is to have been asked by this.</p>
 */
public final class ConsoleDataSource {

    private final Rows rows;

    /**
     * Holds one source over one screen's rows.
     *
     * @param rows what produces them, asked only after the authority has said yes
     */
    public ConsoleDataSource(Rows rows) {
        this.rows = rows;
    }

    /** What one screen does, once it has been permitted to do anything. */
    @FunctionalInterface
    public interface Rows {

        /**
         * One page of rows, from a store this screen knows how to read.
         *
         * <p>Called only after the authority has answered, and there is no other way to be called.
         * A screen cannot skip that step because a screen has nothing to skip it with.</p>
         *
         * @param request who is asking and for what
         * @return the rows, or the reason the store could not be read
         */
        Answer of(Request request);
    }

    /**
     * The dictionary key a viewer who may not see a screen is shown.
     *
     * <p>A key rather than a sentence, and a different key from the one below, because "there is
     * nothing here" and "you may not see what is here" are two different things to be told and a
     * console that said them the same way would have somebody investigating an instance that is
     * working.</p>
     */
    public static final String DENIED_MESSAGE = "slingshot.agent.console.denied";

    /** The dictionary key a screen whose store could not be read is shown. */
    public static final String UNREADABLE_MESSAGE = "slingshot.agent.console.unreadable";

    /** What a data source answered. */
    public sealed interface Answer permits Rendered, Denied, Unreadable {
    }

    /**
     * Rows for a viewer who may see them.
     *
     * @param page what this page carries
     */
    public record Rendered(ConsolePage<?> page) implements Answer {
    }

    /**
     * A viewer who may not.
     *
     * <p>Distinct from an empty page on purpose: an operator shown nothing has to be able to tell
     * "there is nothing" from "you may not see it", and a console that rendered both the same way
     * would have them investigating an instance that is working.</p>
     */
    public record Denied() implements Answer {
    }

    /**
     * A store this side could not read.
     *
     * <p>Also distinct from an empty page, and for the sharper reason: an unreadable store rendered
     * as no rows is a console that says an instance is idle when its own storage is broken.</p>
     *
     * @param detail what went wrong, carrying nothing a viewer may not see
     */
    public record Unreadable(String detail) implements Answer {
    }

    /**
     * Reads one page, having first decided whether this viewer may see any of it.
     *
     * @param request who is asking and for what
     * @return the rows, or the one reason there are none
     */
    public Answer answer(Request request) {
        return ConsoleAuthority.admits(request.permitted(), request.groups())
                ? rows.of(request) : new Denied();
    }

    /**
     * One console request, as everything that decides it.
     *
     * @param permitted the groups an operator has permitted, in the order they configured them
     * @param groups where this viewer stands with respect to each of them
     * @param offset how many rows to skip
     * @param window how many to take, which is clamped to the bound
     * @param bound the most one page may carry, which the contract states
     */
    public record Request(List<String> permitted, AuthorizationGate.Groups groups, long offset,
                          long window, long bound) {

        /** Holds a request whose permitted groups nothing can change afterwards. */
        public Request {
            permitted = List.copyOf(permitted);
        }
    }
}
