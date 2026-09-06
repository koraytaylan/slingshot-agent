// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import rs.slingshot.agent.console.ConsoleAuthority;
import rs.slingshot.agent.console.ConsoleDataSource;

/** Lifecycle changes reach both entry points, including requests constructed before a revocation. */
final class OperatorConfigurationTest {

    private final AuthorizationGate gate = new AuthorizationGate();
    private final BundleContext context = MockOsgi.newBundleContext();

    @AfterEach
    void stop() {
        MockOsgi.deactivate(gate, context);
        MockOsgi.shutdown(context);
    }

    @Test
    void lifecycleReplacesTheEntireSetAndRevokesAlreadyConstructedRequests() {
        MockOsgi.activate(gate, context, Map.of());
        assertEquals(List.of("administrators"), SubmitServlet.permittedGroups());
        final List<String> before = AuthorizationGate.permittedGroups();
        final AuthorizationGate.Groups administrator = group -> "administrators".equals(group)
                ? AuthorizationGate.Standing.A_MEMBER : AuthorizationGate.Standing.NOT_A_MEMBER;
        final ConsoleDataSource source = new ConsoleDataSource(request ->
                new ConsoleDataSource.Unreadable("authorized read"));
        final var request = new ConsoleDataSource.Request(administrator, 0, 1, 1);
        assertInstanceOf(ConsoleDataSource.Unreadable.class, source.answer(request));
        final String[] replacement = { "operators" };
        assertTrue(MockOsgi.modified(gate, context, Map.of("permitted.groups", replacement)));
        replacement[0] = "administrators";
        assertEquals(List.of("operators"), SubmitServlet.permittedGroups());
        assertEquals(List.of("administrators"), before);
        assertThrows(UnsupportedOperationException.class,
                () -> AuthorizationGate.permittedGroups().add("administrators"));
        assertInstanceOf(ConsoleDataSource.Denied.class, source.answer(request));
        assertEquals(ConsoleAuthority.Visibility.HIDDEN, ConsoleAuthority.visibility(administrator));
        assertEquals(ConsoleAuthority.Visibility.SHOWN,
                ConsoleAuthority.visibility(group -> AuthorizationGate.Standing.A_MEMBER));
        MockOsgi.modified(gate, context, Map.of("permitted.groups", new String[0]));
        assertEquals(List.of(), SubmitServlet.permittedGroups());
        assertInstanceOf(ConsoleDataSource.Denied.class, source.answer(request));
        MockOsgi.deactivate(gate, context);
        assertEquals(List.of(), AuthorizationGate.permittedGroups());
    }

    @Test
    void unknownGroupsAndNonmembersRemainDistinctAfterConfiguration() {
        MockOsgi.activate(gate, context, Map.of("permitted.groups", new String[] { "operators" }));
        assertEquals(AuthorizationGate.Refusal.NO_SUCH_GROUP,
                refusal(AuthorizationGate.Standing.NO_SUCH_GROUP));
        assertEquals(AuthorizationGate.Refusal.NOT_PERMITTED,
                refusal(AuthorizationGate.Standing.NOT_A_MEMBER));
        MockOsgi.deactivate(gate, context);
        assertEquals(AuthorizationGate.Refusal.NO_GROUP_IS_PERMITTED,
                refusal(AuthorizationGate.Standing.A_MEMBER));
    }

    private static AuthorizationGate.Refusal refusal(AuthorizationGate.Standing standing) {
        return assertInstanceOf(AuthorizationGate.Refused.class,
                AuthorizationGate.of(new AuthorizationGate.Request("submit",
                        AuthorizationGate.permittedGroups(), group -> standing,
                        AuthorizationGate.Ownership.NOT_ABOUT_AN_OPERATION))).refusal();
    }
}
