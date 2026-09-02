// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Which routes carry a token, what happens when one is not good, and what an operator must know.
 *
 * <p>The three refusals are kept apart in this build's own record and are one answer on the wire.
 * That is the whole point of separating them: somebody reading this instance's log can tell an
 * expired token from a foreign one, and the caller cannot.</p>
 */
final class ForgeryProtectionTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final AgentRouteTable TABLE = table();

    @Test
    @DisplayName("state-changing routes carry a token and read routes deliberately do not")
    void statechangingRoutesCarryAtoken() {
        for (final AgentRoute route : TABLE.routes().values()) {
            assertEquals(!"GET".equals(route.method()), ForgeryProtection.requiresAtoken(route),
                    route.name() + " requires a token that its method does not call for");
            if (!ForgeryProtection.requiresAtoken(route)) {
                assertInstanceOf(ForgeryProtection.Satisfied.class,
                        ForgeryProtection.of(route, ForgeryProtection.Verdict.ABSENT),
                        route.name() + " asked a read for a token, which buys nothing");
            }
        }
        assertEquals(List.of("submit", "subscription-high-water", "artifact-intake"),
                ForgeryProtection.requiring(List.copyOf(TABLE.routes().values())),
                "the set of routes that change something is not what the table declares");
    }

    @Test
    @DisplayName("the three ways of not having a token are three here and one on the wire")
    void thethreeWaysAreThreeHereAndOneOnTheWire() {
        final AgentRoute submit = TABLE.route("submit");
        assertInstanceOf(ForgeryProtection.Satisfied.class,
                ForgeryProtection.of(submit, ForgeryProtection.Verdict.ACCEPTED));
        assertEquals(ForgeryProtection.Refusal.NO_TOKEN, ForgeryProtection.refusalIn(
                ForgeryProtection.of(submit, ForgeryProtection.Verdict.ABSENT)).orElseThrow()
                .refusal());
        assertEquals(ForgeryProtection.Refusal.ANOTHER_CALLERS_TOKEN, ForgeryProtection.refusalIn(
                ForgeryProtection.of(submit, ForgeryProtection.Verdict.FOREIGN)).orElseThrow()
                .refusal());
        assertEquals(ForgeryProtection.Refusal.AN_EXPIRED_TOKEN, ForgeryProtection.refusalIn(
                ForgeryProtection.of(submit, ForgeryProtection.Verdict.EXPIRED)).orElseThrow()
                .refusal());
        assertEquals(3, ForgeryProtection.Refusal.values().length, "a refusal was added or lost");
        assertEquals(4, ForgeryProtection.Verdict.values().length, "a verdict was added or lost");
        assertEquals(ForgeryProtection.Refusal.NO_TOKEN,
                ForgeryProtection.named("NO_TOKEN").orElseThrow());
        assertTrue(ForgeryProtection.named("A_TOKEN_FROM_THE_FUTURE").isEmpty());
    }

    @Test
    @DisplayName("no refusal says anything about the token that was not good")
    void norefusalSaysAnythingAboutTheToken() {
        final AgentRoute submit = TABLE.route("submit");
        for (final ForgeryProtection.Verdict verdict : List.of(
                ForgeryProtection.Verdict.ABSENT, ForgeryProtection.Verdict.FOREIGN,
                ForgeryProtection.Verdict.EXPIRED)) {
            final ForgeryProtection.Refused refused = ForgeryProtection.refusalIn(
                    ForgeryProtection.of(submit, verdict)).orElseThrow();
            assertFalse(refused.detail().contains(ForgeryProtection.TOKEN_HEADER),
                    "a refusal named the header the token arrives in: " + refused.detail());
        }
        assertEquals(403, ForgeryProtection.STATUS,
                "the three refusals are answered with something other than one status");
    }

    @Test
    @DisplayName("nothing here validates a token, because the key belongs to the platform")
    void nothingHereValidatesAtoken() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/http/ForgeryProtection.java"));
        for (final String validating : List.of("Mac.getInstance", "MessageDigest", "SecretKey",
                "Cipher", "getHeader")) {
            assertFalse(source.contains(validating),
                    "this type reaches for " + validating + ", which is validating a token with a"
                            + " key this bundle has no business holding");
        }
    }

    @Test
    @DisplayName("every platform configuration this depends on is named and written down")
    void everyplatformConfigurationIsNamedAndWrittenDown() {
        final String deployment = read(REPOSITORY.resolve("docs/DEPLOYMENT.md"));
        for (final String configuration : ForgeryProtection.PLATFORM_CONFIGURATIONS) {
            assertTrue(deployment.contains(configuration),
                    configuration + " is depended on and not written down for an operator");
        }
        assertEquals(3, ForgeryProtection.PLATFORM_CONFIGURATIONS.size(),
                "a platform configuration was added or lost");
        assertTrue(deployment.contains(ForgeryProtection.TOKEN_HEADER),
                "the header a client sends its token in is not written down");
        assertTrue(deployment.contains(ForgeryProtection.TOKEN_ROUTE),
                "where a client fetches a token is not written down");
        assertTrue(deployment.contains("allow.empty"),
                "the one relaxation an operator must not make is not written down");
    }

    @Test
    @DisplayName("no configuration this product ships excludes a route from either filter")
    void noshippedConfigurationExcludesAroute() {
        final Path configurations = REPOSITORY.resolve("ui.config/src/main/content/jcr_root/apps/"
                + "slingshot-agent/osgiconfig/config");
        try (var files = Files.list(configurations)) {
            files.forEach(file -> {
                final String shipped = read(file);
                for (final String excluding : List.of("filter.methods", "allow.empty",
                        "excluded.paths", "filter.excluded", "csrf.exclude")) {
                    assertFalse(shipped.contains(excluding),
                            file.getFileName() + " configures " + excluding + ", which takes this"
                                    + " product out of a protection the platform applies");
                }
            });
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the shipped configurations are not readable",
                    unreadable);
        }
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
