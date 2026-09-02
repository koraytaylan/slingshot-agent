// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.http.AuthorizationGate;

/**
 * The base every console data source extends, and the paging every one of them shares.
 *
 * <p>What is proved here is an order rather than a behaviour. The stores this console reads live
 * where no person's session reaches them, so the reading happens as the service user — which means
 * the read cannot also be what decides whether the read should happen. That the order cannot be
 * reversed is a fact about the types, not about anybody remembering.</p>
 */
final class ConsoleDataSourceTest {

    private static final List<String> PERMITTED = List.of("slingshot-agent-operators");

    private static final long BOUND = 200;

    @Test
    @DisplayName("authority is decided before the store is touched, and a screen cannot reorder it")
    void theauthorityAnswersBeforeAnyStoreIsTouched() {
        final Recording screen = new Recording(List.of("one", "two"));
        final ConsoleDataSource source = new ConsoleDataSource(screen);
        assertInstanceOf(ConsoleDataSource.Denied.class,
                source.answer(request(standing(AuthorizationGate.Standing.NOT_A_MEMBER), 0, 50)),
                "a viewer who may not see the console was shown rows");
        assertEquals(List.of(), screen.reads(),
                "the store was read for a viewer who may not see it, and the service user doing"
                        + " that reading can reach everything");
        assertInstanceOf(ConsoleDataSource.Rendered.class,
                source.answer(request(standing(AuthorizationGate.Standing.A_MEMBER), 0, 50)),
                "a permitted viewer was refused");
        assertEquals(List.of("read"), screen.reads());
    }

    @Test
    @DisplayName("nothing here hands a screen anything a store is reachable through")
    void nothingHandsOutAStore() {
        final List<String> reachable = new ArrayList<>();
        for (final Method method : ConsoleDataSource.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            final String returned = method.getReturnType().getName();
            if (returned.contains("Resolver") || returned.contains("Session")
                    || returned.contains("Store")) {
                reachable.add(method.getName());
            }
        }
        assertEquals(List.of(), reachable,
                "something here hands a screen a way to reach a store, so a screen could read"
                        + " before the authority answered: " + reachable);
        assertTrue(Modifier.isFinal(ConsoleDataSource.class.getModifiers()),
                "the gate can be subclassed, and a subclass can replace the order rather than"
                        + " follow it — a screen is handed to this precisely so it has nothing to"
                        + " override");
        assertTrue(ConsoleDataSource.Rows.class.getDeclaredMethods().length == 1,
                "a screen is given more than one thing to do, and the one thing it is given is"
                        + " what makes the order unskippable");
    }

    @Test
    @DisplayName("a window at the bound is taken whole and one past it is clamped rather than refused")
    void thewindowIsClampedRatherThanRefused() {
        final List<String> everything = new ArrayList<>();
        for (int row = 0; row < BOUND + 10; row++) {
            everything.add("row" + row);
        }
        assertEquals(BOUND, ConsolePage.of(everything, 0, BOUND, BOUND).rows().size(),
                "a window at exactly the bound did not come back whole");
        assertEquals(BOUND, ConsolePage.of(everything, 0, BOUND + 1, BOUND).rows().size(),
                "a window past the bound was not clamped, and a console is not a protocol — a"
                        + " person who asked for more rows than one page carries has made no"
                        + " mistake worth an error message");
        assertEquals(1, ConsolePage.of(everything, 0, 0, BOUND).rows().size(),
                "a window of nothing was taken literally, which is a page nobody can read");
        assertEquals(everything.size(),
                ConsolePage.of(everything, everything.size() + 100, 10, BOUND).offset(),
                "an offset past the end was not clamped to the end");
    }

    @Test
    @DisplayName("an empty page is an answer, and a store that could not be read is not one")
    void emptinessAndFailureAreDifferent() {
        final ConsolePage<String> empty = ConsolePage.of(List.of(), 0, 50, BOUND);
        assertTrue(empty.isEmpty(),
                "a page carrying nothing did not say so");
        assertEquals(Optional.of(0L), empty.countedTotal(),
                "an instance nobody has submitted work to is an ordinary instance, and its console"
                        + " has to say nothing rather than report a problem");
        final ConsoleDataSource.Answer answer = new ConsoleDataSource(new Broken())
                .answer(request(standing(AuthorizationGate.Standing.A_MEMBER), 0, 50));
        assertInstanceOf(ConsoleDataSource.Unreadable.class, answer,
                "a store that could not be read rendered as no rows, which is a console saying an"
                        + " instance is idle when its own storage is broken");
        assertTrue(((ConsoleDataSource.Unreadable) answer).detail().contains("could not"),
                "the answer does not say what went wrong");
    }

    @Test
    @DisplayName("an uncounted total is an explicit unknown rather than a zero")
    void anuncountedTotalIsNotZero() {
        final ConsolePage<String> uncounted = new ConsolePage<>(List.of("one"), 0,
                new ConsolePage.Unknown());
        assertEquals(Optional.empty(), uncounted.countedTotal(),
                "a total nobody counted was reported as a number");
        assertTrue(!uncounted.isEmpty(),
                "a page with a row in it was reported as empty");
        assertInstanceOf(ConsolePage.Counted.class,
                ConsolePage.of(List.of("one"), 0, 50, BOUND).total(),
                "a page built from a list somebody already held did not count it, and counting a"
                        + " list you are holding costs nothing");
    }

    @Test
    @DisplayName("a viewer who may not use the console is not shown the entry at all")
    void anunpermittedViewerSeesNoEntry() {
        assertEquals(ConsoleAuthority.Visibility.HIDDEN,
                ConsoleAuthority.visibility(PERMITTED,
                        standing(AuthorizationGate.Standing.NOT_A_MEMBER)),
                "an entry that appears and refuses when clicked teaches an operator that this"
                        + " product is broken; one that is not there teaches them nothing, which"
                        + " is what there is to teach");
        assertEquals(ConsoleAuthority.Visibility.SHOWN,
                ConsoleAuthority.visibility(PERMITTED,
                        standing(AuthorizationGate.Standing.A_MEMBER)));
        assertEquals(ConsoleAuthority.Visibility.HIDDEN,
                ConsoleAuthority.visibility(List.of(),
                        standing(AuthorizationGate.Standing.A_MEMBER)),
                "a deployment where nobody is permitted showed the entry to somebody");
    }

    @Test
    @DisplayName("what a viewer is shown and what they may reach are decided by the same answer")
    void visibilityAndAccessCannotDisagree() {
        for (final AuthorizationGate.Standing standing : AuthorizationGate.Standing.values()) {
            assertEquals(ConsoleAuthority.visibility(PERMITTED, standing(standing))
                            == ConsoleAuthority.Visibility.SHOWN,
                    ConsoleAuthority.admits(PERMITTED, standing(standing)),
                    "the entry and the console disagree for a viewer who is " + standing
                            + ", which would make navigation the access control — and navigation"
                            + " is not access control");
        }
        assertEquals("submit", ConsoleAuthority.ROUTE,
                "the console is held to a different requirement from the route that starts work,"
                        + " so the two can drift apart");
    }

    /** A screen that records whether it was reached. */
    private static final class Recording implements ConsoleDataSource.Rows {

        private final List<String> everything;
        private final List<String> reads = new ArrayList<>();

        Recording(List<String> everything) {
            this.everything = List.copyOf(everything);
        }

        List<String> reads() {
            return List.copyOf(reads);
        }

        @Override
        public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
            reads.add("read");
            return new ConsoleDataSource.Rendered(ConsolePage.of(everything, request.offset(),
                    request.window(), request.bound()));
        }
    }

    /** One whose store will not answer. */
    private static final class Broken implements ConsoleDataSource.Rows {

        @Override
        public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
            return new ConsoleDataSource.Unreadable("the operation store could not be read");
        }
    }

    private static ConsoleDataSource.Request request(AuthorizationGate.Groups groups, long offset,
                                                     long window) {
        return new ConsoleDataSource.Request(PERMITTED, groups, offset, window, BOUND);
    }

    private static AuthorizationGate.Groups standing(AuthorizationGate.Standing standing) {
        return group -> standing;
    }
}
