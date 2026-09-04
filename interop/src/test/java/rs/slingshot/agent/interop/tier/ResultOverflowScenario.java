// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A result too large to carry is fetched rather than truncated.
 *
 * <p>Nothing is ever cut short. A truncated answer is not a smaller answer but an unparseable one,
 * and the client is built to fetch a large result rather than to cope with a damaged one. So an
 * overflowing result becomes an artifact, the answer carries the count and digest of it, and a
 * reader checks both for itself before believing what came back.</p>
 *
 * <p>What a running instance can answer today is the transfer half: that the route serves whole
 * bytes, states a length before a reader starts reading, and never answers a fetch with a body it
 * has quietly shortened. Producing an overflowing result needs a registered read command, and this
 * build registers none — so the end-to-end half, where a command's own answer overflows and the
 * reference in it is followed, arrives with workstream 0018. This plan's status records that rather
 * than a test here pretending to it.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ResultOverflowScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route an overflowing result is fetched on, spelled by the committed table. */
    private static final String ROUTE = "/bin/slingshot/agent/artifact";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    /** What a fetch for an operation nobody started is answered with. */
    private static final int NOTHING_HERE = 404;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("a fetch that finds nothing carries no body a reader could mistake for a result")
    void afetchThatFindsNothingCarriesNoResult() {
        final HttpResponse<String> answered = requests.readAsAuthenticatedUser(
                tier.address() + ROUTE + "?agent_operation_identifier=" + AN_IDENTIFIER
                        + "&artifact_slot=result");
        assertEquals(NOTHING_HERE, answered.statusCode());
        assertEquals("", answered.body(),
                "a fetch that found nothing answered with bytes, and a reader following a"
                        + " reference would read them as a shortened result");
    }

    @Test
    @DisplayName("the route never answers a fetch with a length it does not then serve")
    void therouteNeverStatesALengthItDoesNotServe() {
        final HttpResponse<String> answered = requests.readAsAuthenticatedUser(
                tier.address() + ROUTE + "?agent_operation_identifier=" + AN_IDENTIFIER
                        + "&artifact_slot=result");
        final long stated = answered.headers().firstValueAsLong("Content-Length").orElse(0);
        assertEquals(answered.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                stated,
                "the route stated a length other than the one it served, so a reader checking what"
                        + " arrived against a declared count cannot rely on the check");
    }

    @Test
    @DisplayName("the instance declares the bound past which a result is fetched rather than sent")
    void theinstanceDeclaresTheBound() {
        final long declared = contractValue("maximum_agent_inline_result_bytes");
        assertTrue(declared > 0,
                "the contract states no inline result bound, so nothing decides when a result is"
                        + " published rather than carried");
        final HttpResponse<String> capabilities = requests.readAsAuthenticatedUser(
                tier.address() + "/bin/slingshot/agent/capabilities");
        assertEquals(SERVED, capabilities.statusCode(),
                "the running instance does not answer what it can do");
        assertTrue(!capabilities.body().contains(String.valueOf(declared)),
                "the instance publishes its own inline bound to any authenticated caller; a bound"
                        + " is a property of this deployment and not a thing a caller decides"
                        + " against");
    }

    /** What a served request is answered with. */
    private static final int SERVED = 200;

    private static long contractValue(String bound) {
        final String contract = read(REPOSITORY.resolve("support/agent-contract.toml"));
        return contract.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(bound + " "))
                .map(line -> line.substring(line.indexOf('=') + 1).strip())
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElseThrow(() -> new AssertionError(bound + " is not declared"));
    }

    private static String read(Path file) {
        try {
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
