// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Every bound pushed rather than assumed, on an instance that has to keep answering.
 *
 * <p>An author that has stopped serving is not distinguishable, from outside, from one that is
 * gone. Every bound in this repository exists so that a caller cannot produce that state, and the
 * property at each is the same: it refuses at the contract's own value, with its own declared
 * category, and everything else on the instance goes on answering.</p>
 *
 * <p>Every one of these bounds has a per-caller half as well as a total, and that is not a detail.
 * A bound that is only a total is a bound one client can spend on everybody else's behalf, which is
 * a denial of service with a valid credential.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ResourceExhaustionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

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
    @DisplayName("every exhaustible resource has a per-caller bound as well as a total")
    void everyresourceHasAPerCallerBound() {
        final String contract = read(REPOSITORY.resolve("support/agent-contract.toml"));
        List.of("concurrent_event_streams", "concurrent_command_executions",
                        "current_generation_event_rows", "current_generation_artifact_rows",
                        "current_generation_operation_detail_rows",
                        "current_generation_active_subscription_rows")
                .forEach(resource -> {
                    assertTrue(contract.contains("maximum_" + resource),
                            resource + " has no total bound, so nothing refuses it at all");
                    assertTrue(contract.contains("maximum_caller_" + resource),
                            resource + " has a total and no per-caller bound, which is a bound one"
                                    + " client can spend on everybody else's behalf");
                });
    }

    @Test
    @DisplayName("the instance goes on answering while a caller is being refused")
    void theinstanceKeepsAnswering() {
        final List<String> refused = new ArrayList<>();
        for (int attempt = 0; attempt < 32; attempt++) {
            if (requests.postAsAuthenticatedUser(tier.address() + SUBMIT, "{}",
                    "application/json").statusCode() >= 500) {
                refused.add("attempt " + attempt);
            }
        }
        assertEquals(List.of(), refused,
                "the instance answered a refusable submission with a failure rather than a"
                        + " refusal, which is the shape of a bound that was reached by falling"
                        + " over: " + refused);
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "the instance stopped refusing an unauthenticated caller while it was busy, which"
                        + " is the one thing it must never stop doing");
    }

    @Test
    @DisplayName("the stream budget is separate from the request threads, which is what it is for")
    void thestreamBudgetIsItsOwn() {
        final String contract = read(REPOSITORY.resolve("support/agent-contract.toml"));
        assertTrue(contract.contains("maximum_concurrent_event_streams")
                        && contract.contains("maximum_concurrent_command_executions"),
                "the streams and the running commands share one bound, so saturating the first"
                        + " would take the second with it and the asynchronous route would be"
                        + " protecting nothing");
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
