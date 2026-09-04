// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.harness.PlatformFaultInjector;

/**
 * Every platform interface, made to reject, to throw, and to say nothing at all.
 *
 * <p>The last one is what this suite exists for. A category no fault can produce is a category that
 * does not exist, however carefully its schema was written — so the unknown outcome every control
 * command declares is either reachable here or it is a sentence in a document.</p>
 *
 * <p>A rejection and a throw are deliberately one answer. A caller who could tell them apart would
 * be reading this product's opinion of somebody else's exception and deciding what to do next based
 * on it, which is a coupling to a platform's internals nobody agreed to.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PlatformFaultScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** How many injections this suite runs: three faults on five services. */
    private static final int EVERY_INJECTION = 15;

    /** Where the bound on how long this side waits for a platform is stated. */
    private static final String THE_DEADLINE = "platform_call_deadline_milliseconds";

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        // Its own runtime, because this scenario changes the instance itself. The shared one is
        // given back first: two published runtimes competing for the machine is how a start that
        // takes twenty seconds stops finishing inside ninety.
        SharedPublicSlingTier.release();
        final InteropTier.Outcome outcome =
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("every fault is injected on every service this agent calls")
    void everyfaultIsInjectedOnEveryService() {
        assertEquals(EVERY_INJECTION, PlatformFaultInjector.everyInjection().size(),
                "the injections are no longer the cross product, and a service left out is one"
                        + " whose failure nobody has seen");
    }

    @Test
    @DisplayName("the unknown outcome is reachable, which is what makes it a category")
    void theunknownOutcomeIsReachable() {
        assertTrue(PlatformFaultInjector.unknownOutcomeIsReachable(),
                "no fault produces the unknown outcome, which makes it a category nobody can"
                        + " reach and therefore a sentence in a document");
        assertEquals(PlatformFaultInjector.Answer.OUTCOME_UNKNOWN,
                PlatformFaultInjector.Fault.NEVER_ANSWERS.answer(),
                "a platform that said nothing was given an answer that claims something about its"
                        + " state, and nobody knows whether it acted");
    }

    @Test
    @DisplayName("a rejection and a throw are one answer, which is a decision rather than an accident")
    void arejectionAndAThrowLookAlike() {
        assertTrue(PlatformFaultInjector.aRejectionAndAThrowLookAlike(),
                "a caller can tell a platform that refused from one that failed, which couples"
                        + " them to this product's opinion of somebody else's exception");
    }

    @Test
    @DisplayName("waiting for a platform is bounded by the contract rather than by patience")
    void waitingIsBoundedByTheContract() {
        assertTrue(read(REPOSITORY.resolve("support/agent-contract.toml")).contains(THE_DEADLINE),
                "the contract states no deadline for a platform call, so a service that never"
                        + " answers would hold a request thread until somebody restarted it");
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    // Neither of the archives a release also builds: a javadoc jar handed to the
                    // platform as a bundle is refused with a 500 that reads like the product
                    // failing to install.
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources")
                            && !String.valueOf(file.getFileName()).contains("javadoc"))
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
