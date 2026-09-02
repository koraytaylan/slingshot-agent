// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;
import rs.slingshot.agent.http.AuthorizationGate;

/**
 * Whether this person may see the console, decided the way every route decides it.
 *
 * <p>A console is a servlet, an authenticated user, and a permitted group — exactly like a route,
 * and deliberately so: a second answer to "may this person" is a second thing to keep in step with
 * the first, and the day they disagree one of them is wrong in a direction nobody planned.</p>
 *
 * <p>One thing does differ, and it is about what a person is shown rather than what they may do. A
 * tool nobody may use is better not advertised: an entry that appears under Tools and refuses when
 * clicked teaches an operator that this product is broken, whereas an entry that is not there
 * teaches them nothing at all — which is correct, because there is nothing here for them.</p>
 */
public final class ConsoleAuthority {

    /** The route whose requirement the console is held to, so the two cannot drift apart. */
    public static final String ROUTE = "submit";

    private ConsoleAuthority() {
    }

    /** What a viewer is shown, which is not the same question as what they may do. */
    public enum Visibility {
        /** The entry appears and the console answers. */
        SHOWN,
        /**
         * The entry is absent.
         *
         * <p>Absent rather than present and refusing: an entry that refuses when clicked teaches
         * an operator that this product is broken, and one that is not there teaches them nothing,
         * which is what there is to teach.</p>
         */
        HIDDEN
    }

    /**
     * Whether this person may see the console at all.
     *
     * @param permitted the groups an operator has permitted, in the order they configured them
     * @param groups where this person stands with respect to each of them
     * @return whether to show them the entry
     */
    public static Visibility visibility(List<String> permitted,
                                        AuthorizationGate.Groups groups) {
        return admits(permitted, groups) ? Visibility.SHOWN : Visibility.HIDDEN;
    }

    /**
     * Whether this person may reach a console resource.
     *
     * <p>Decided identically to the visibility, because the two must not be able to disagree: a
     * console that hides its entry and answers anyway is a console whose access control is the
     * navigation, and navigation is not access control.</p>
     *
     * @param permitted the groups an operator has permitted
     * @param groups where this person stands with respect to each of them
     * @return whether they may
     */
    public static boolean admits(List<String> permitted, AuthorizationGate.Groups groups) {
        return AuthorizationGate.of(new AuthorizationGate.Request(ROUTE, permitted, groups,
                AuthorizationGate.Ownership.NOT_ABOUT_AN_OPERATION)) instanceof AuthorizationGate.Admitted;
    }
}
