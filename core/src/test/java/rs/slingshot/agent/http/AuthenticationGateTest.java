// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;

/**
 * Somebody in particular, on every route, with no way to say otherwise.
 *
 * <p>The exemption test is the one that matters most and looks the least like a test: it asserts
 * over the gate's own surface that there is no argument through which a route could be let
 * through. A rule enforced by every caller remembering to call it is a rule with a hole in it the
 * day somebody adds the fourteenth route.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class AuthenticationGateTest {

    private static final Path REPOSITORY = repositoryRoot();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    @DisplayName("every route in the table requires somebody in particular")
    void everyrouteRequiresSomebodyInParticular() {
        for (final AgentRoute route : table().routes().values()) {
            assertEquals(AuthenticationGate.Refusal.NOBODY_IN_PARTICULAR,
                    AuthenticationGate.refusalIn(
                            AuthenticationGate.established(AuthenticationGate.ANONYMOUS))
                            .orElseThrow().refusal(),
                    route.name() + " would serve a request from nobody in particular");
        }
        assertEquals(AuthenticationGate.Refusal.NOBODY_IN_PARTICULAR, AuthenticationGate.refusalIn(
                AuthenticationGate.established("")).orElseThrow().refusal(),
                "a request the platform bound to nothing at all was served");
    }

    @Test
    @DisplayName("no argument anywhere could exempt a route from the gate")
    void noargumentCouldExemptAroute() {
        for (final Method method : AuthenticationGate.class.getMethods()) {
            if (method.getDeclaringClass() != AuthenticationGate.class) {
                continue;
            }
            for (final Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == boolean.class || parameter == Boolean.class,
                        method.getName() + " takes a flag, which is a way to exempt a route");
                assertFalse(parameter == AgentRoute.class,
                        method.getName() + " takes a route, which is a way to treat one"
                                + " differently from another");
            }
        }
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/http/AuthenticationGate.java"));
        for (final String exempting : List.of("@Activate", "@Designate", "@Component",
                "ObjectClassDefinition", "System.getProperty", "boolean ")) {
            assertFalse(source.contains(exempting),
                    "the gate reaches for " + exempting + ", which is a way to switch it off");
        }
    }

    @Test
    @DisplayName("the service user is refused, distinctly here and identically on the wire")
    void theserviceUserIsRefused() {
        final AuthenticationGate.Refused refused = AuthenticationGate.refusalIn(
                AuthenticationGate.established(AuthenticationGate.SERVICE_USER)).orElseThrow();
        assertEquals(AuthenticationGate.Refusal.THE_SERVICE_USER, refused.refusal());
        assertTrue(refused.detail().contains(AuthenticationGate.SERVICE_USER), refused.detail());
        assertEquals(AuthenticationGate.STATUS, AuthenticationGate.STATUS,
                "the two refusals are answered with different statuses");
        assertEquals(2, AuthenticationGate.Refusal.values().length,
                "a refusal was added or lost");
    }

    @Test
    @DisplayName("the service user this bundle knows is the one the access policy declares")
    void theserviceUserIsTheOneThePolicyDeclares() {
        final String policy = read(REPOSITORY.resolve("policy/repository-access.toml"));
        assertTrue(policy.contains("name = \"" + AuthenticationGate.SERVICE_USER + "\""),
                "this bundle recognises a service user the access policy does not declare");
        final Path mapping = REPOSITORY.resolve("ui.config/src/main/content/jcr_root/apps/"
                + "slingshot-agent/osgiconfig/config/org.apache.sling.serviceusermapping.impl"
                + ".ServiceUserMapperImpl.amended~slingshot-agent.cfg.json");
        assertTrue(read(mapping).contains(AuthenticationGate.SERVICE_USER),
                "the deployment maps a different service user than this bundle recognises");
    }

    @Test
    @DisplayName("a caller identity carries a name and nothing that proved it")
    void acallerIdentityCarriesOnlyAname() {
        assertEquals(List.of("authorizable"),
                java.util.Arrays.stream(CallerIdentity.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList(),
                "the caller identity carries something other than the name the platform decided");
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/http/CallerIdentity.java"));
        for (final String carried : List.of("getHeader", "Cookie", "getRemoteUser",
                "SimpleCredentials", "getAuthType")) {
            assertFalse(source.contains(carried),
                    "the caller identity reaches for " + carried + ", which it has no business"
                            + " carrying");
        }
        assertEquals("an-operator", new CallerIdentity("an-operator").counted().orElseThrow()
                .name());
        assertTrue(new CallerIdentity("a name with spaces").counted().isEmpty(),
                "a caller the store cannot count was counted anyway");
    }

    @Test
    @DisplayName("the platform's own answer decides who is asking, absent or not")
    void theplatformsAnswerDecides() {
        final SlingContext nobody = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
        assertEquals(AuthenticationGate.Refusal.NOBODY_IN_PARTICULAR,
                AuthenticationGate.refusalIn(AuthenticationGate.of(nobody.request()))
                        .orElseThrow().refusal(),
                "a request the platform bound to nobody was admitted");
        final String user = sling.resourceResolver().getUserID();
        assertEquals(user, assertInstanceOf(AuthenticationGate.Admitted.class,
                        AuthenticationGate.of(sling.request()),
                        "a request the platform bound to " + user + " was refused").caller()
                        .authorizable(),
                "the gate admitted somebody other than the one the platform established");
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
