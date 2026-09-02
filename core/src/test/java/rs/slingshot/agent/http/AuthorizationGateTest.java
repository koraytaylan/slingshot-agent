// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * Who may use this agent, and the three ways a configuration can mean nobody.
 *
 * <p>The three are separated because an operator does something different about each. No group
 * permitted is a configuration nobody has made; a group that does not exist is a configuration
 * somebody made and misspelled; and a caller in none of them is the configuration working. An agent
 * that answered all three the same way would leave the middle one — the one where somebody believes
 * they have granted access and has not — impossible to find.</p>
 */
final class AuthorizationGateTest {

    private static final Path REPOSITORY = repositoryRoot();

    /** The group a fresh install permits and no other. */
    private static final String SHIPPED = "administrators";

    /** A group an operator might add, to prove widening works without anything else changing. */
    private static final String WIDENED = "slingshot-operators";

    @Test
    @DisplayName("every route in the committed table has a requirement, and every requirement a route")
    void everyrouteHasArequirement() {
        final List<String> routes = table().routes().values().stream()
                .map(AgentRoute::name)
                .sorted()
                .toList();
        assertEquals(routes, RouteAuthority.table().keySet().stream().sorted().toList(),
                "a route has no requirement, or a requirement names a route the table does not");
        assertEquals(RouteAuthority.A_MEMBER_OF_A_PERMITTED_GROUP,
                RouteAuthority.forRoute("submit").orElseThrow(),
                "starting work requires something other than being permitted to");
        assertEquals(RouteAuthority.ANY_AUTHENTICATED_CALLER,
                RouteAuthority.forRoute("capabilities").orElseThrow());
        assertTrue(RouteAuthority.forRoute("a-route-nobody-declared").isEmpty());
        assertEquals(AuthorizationGate.Refusal.NO_REQUIREMENT_DECLARED,
                AuthorizationGate.refusalIn(AuthorizationGate.of(
                        new AuthorizationGate.Request("a-route-nobody-declared", List.of(SHIPPED),
                                group -> AuthorizationGate.Standing.A_MEMBER,
                                AuthorizationGate.Ownership.NOT_ABOUT_AN_OPERATION)))
                        .orElseThrow().refusal(),
                "a route with no requirement was served anyway");
    }

    @Test
    @DisplayName("a member submits, somebody outside every permitted group does not")
    void amemberSubmitsAndAnOutsiderDoesNot() {
        assertInstanceOf(AuthorizationGate.Admitted.class, AuthorizationGate.of(
                submitting(List.of(SHIPPED), group -> AuthorizationGate.Standing.A_MEMBER)),
                "a member of the permitted group could not start work");
        assertEquals(AuthorizationGate.Refusal.NOT_PERMITTED, AuthorizationGate.refusalIn(
                AuthorizationGate.of(submitting(List.of(SHIPPED),
                        group -> AuthorizationGate.Standing.NOT_A_MEMBER))).orElseThrow()
                .refusal(), "somebody outside every permitted group started work");
    }

    @Test
    @DisplayName("no group permitted and a group that does not exist are two different answers")
    void thetwoWaysAconfigurationMeansNobodyAreDistinct() {
        final AuthorizationGate.Refused none = AuthorizationGate.refusalIn(AuthorizationGate.of(
                submitting(List.of(), group -> AuthorizationGate.Standing.A_MEMBER)))
                .orElseThrow();
        assertEquals(AuthorizationGate.Refusal.NO_GROUP_IS_PERMITTED, none.refusal());
        final AuthorizationGate.Refused unknown = AuthorizationGate.refusalIn(AuthorizationGate.of(
                submitting(List.of("a-group-nobody-created"),
                        group -> AuthorizationGate.Standing.NO_SUCH_GROUP))).orElseThrow();
        assertEquals(AuthorizationGate.Refusal.NO_SUCH_GROUP, unknown.refusal());
        assertTrue(unknown.detail().contains("a-group-nobody-created"), unknown.detail());
        assertEquals(4, AuthorizationGate.Refusal.values().length,
                "a refusal was added or lost");
    }

    @Test
    @DisplayName("widening the configuration admits the group named and changes nothing else")
    void wideningAdmitsTheGroupNamed() {
        final AuthorizationGate.Groups inTheWidenedOne = group -> WIDENED.equals(group)
                ? AuthorizationGate.Standing.A_MEMBER
                : AuthorizationGate.Standing.NOT_A_MEMBER;
        assertEquals(AuthorizationGate.Refusal.NOT_PERMITTED, AuthorizationGate.refusalIn(
                AuthorizationGate.of(submitting(List.of(SHIPPED), inTheWidenedOne))).orElseThrow()
                .refusal(), "a group nobody permitted was admitted");
        assertInstanceOf(AuthorizationGate.Admitted.class, AuthorizationGate.of(
                submitting(List.of(SHIPPED, WIDENED), inTheWidenedOne)),
                "naming a further group did not admit that group's members");
    }

    @Test
    @DisplayName("a caller reads their own work, is refused somebody else's, and a member reads both")
    void acallerReadsTheirOwnAndAmemberReadsBoth() {
        final AuthorizationGate.Groups outside = group -> AuthorizationGate.Standing.NOT_A_MEMBER;
        assertInstanceOf(AuthorizationGate.Admitted.class, AuthorizationGate.of(
                new AuthorizationGate.Request("operation-lookup", List.of(SHIPPED), outside,
                        AuthorizationGate.Ownership.THE_CALLERS_OWN)),
                "a caller could not read their own work");
        assertEquals(AuthorizationGate.Refusal.NOT_PERMITTED, AuthorizationGate.refusalIn(
                AuthorizationGate.of(new AuthorizationGate.Request("operation-lookup",
                        List.of(SHIPPED), outside, AuthorizationGate.Ownership.SOMEBODY_ELSES)))
                .orElseThrow().refusal(), "a caller read work that is not theirs");
        final AuthorizationGate.Groups member = group -> AuthorizationGate.Standing.A_MEMBER;
        for (final AuthorizationGate.Ownership whose : AuthorizationGate.Ownership.values()) {
            assertInstanceOf(AuthorizationGate.Admitted.class, AuthorizationGate.of(
                    new AuthorizationGate.Request("operation-lookup", List.of(SHIPPED), member,
                            whose)),
                    "a member could not read work that is " + whose);
        }
        assertInstanceOf(AuthorizationGate.Admitted.class, AuthorizationGate.of(
                new AuthorizationGate.Request("capabilities", List.of(), outside,
                        AuthorizationGate.Ownership.NOT_ABOUT_AN_OPERATION)),
                "discovery required more than the platform having authenticated somebody");
    }

    @Test
    @DisplayName("what ships is administrators and nothing else, and the policy says the same")
    void whatShipsIsAdministratorsAndNothingElse() {
        final String shipped = read(REPOSITORY.resolve("ui.config/src/main/content/jcr_root/apps/"
                + "slingshot-agent/osgiconfig/config/rs.slingshot.agent.http.AuthorizationGate"
                + ".cfg.json"));
        assertTrue(shipped.contains("\"" + SHIPPED + "\""),
                "a fresh install does not permit an administrator: " + shipped);
        assertEquals(1, shipped.lines().filter(line -> line.contains("\"")
                        && !line.contains("permitted.groups")).count(),
                "the shipped configuration names more than one group: " + shipped);
        final String policy = read(REPOSITORY.resolve("policy/repository-access.toml"));
        assertTrue(policy.contains("shipped = [\"" + SHIPPED + "\"]"),
                "the access policy declares a different shipped value than what is shipped");
        assertTrue(policy.contains("configuration = \"" + AuthorizationGate.class.getName() + "\""),
                "the access policy names a different configuration than the gate's own");
    }

    private static AuthorizationGate.Request submitting(List<String> permitted,
                                                        AuthorizationGate.Groups groups) {
        return new AuthorizationGate.Request("submit", permitted, groups,
                AuthorizationGate.Ownership.NOT_ABOUT_AN_OPERATION);
    }

    private static AgentRouteTable table() {
        return assertInstanceOf(AgentRouteTable.Loaded.class, AgentRouteTable.load(),
                "the route table was refused").table();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
