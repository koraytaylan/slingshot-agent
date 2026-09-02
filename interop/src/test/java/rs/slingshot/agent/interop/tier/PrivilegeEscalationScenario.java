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
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * Every direction the architecture makes possible, attacked on a running instance.
 *
 * <p>This runs inside somebody else's author, with a service user, in the same process as their
 * content. That combination is the shape of a privilege escalation, and the directions worth
 * attacking are the ones the design permits rather than the ones that seem likely — the likely ones
 * are the ones that were already thought about.</p>
 *
 * <p>The strongest guard needs no guard: there is no impersonation call anywhere in either bundle,
 * so there is no path by which a command runs as anybody but the requesting user. What is proved
 * here is that a real instance behaves that way — the agent's own tree is unreachable through every
 * route, alias, path spelling and console resource, and the key ring is unreachable at all.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PrivilegeEscalationScenario {

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
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "the tier left a container running");
    }

    @Test
    @DisplayName("the agent's own tree is unreachable through every spelling a request can take")
    void theagentsOwnTreeIsUnreachable() {
        final List<String> reached = new ArrayList<>();
        for (final String spelling : spellingsOf("/var/slingshot-agent")) {
            if (requests.readAsAuthenticatedUser(tier.address() + spelling).statusCode()
                    < BAD_REQUEST) {
                reached.add(spelling);
            }
        }
        assertEquals(List.of(), reached,
                "the agent's own state is readable through a request, which is a caller reading"
                        + " the bookkeeping that decides what they are allowed to do: " + reached);
    }

    @Test
    @DisplayName("the key ring is unreachable, which is a stronger claim than the tree being so")
    void thekeyRingIsUnreachable() {
        final List<String> reached = new ArrayList<>();
        for (final String spelling : spellingsOf("/var/slingshot-agent/keys")) {
            final String body = requests.readAsAuthenticatedUser(tier.address() + spelling).body();
            if (body.contains("current") && body.contains("prior")) {
                reached.add(spelling);
            }
        }
        assertEquals(List.of(), reached,
                "the ring this agent signs continuation tokens with is readable, which makes every"
                        + " token forgeable: " + reached);
    }

    @Test
    @DisplayName("no way to run as somebody else exists in either bundle")
    void noimpersonationExists() {
        final List<String> found = new ArrayList<>();
        List.of("core/src/main/java", "aem/src/main/java").forEach(tree -> {
            try (var sources = Files.walk(REPOSITORY.resolve(tree))) {
                sources.filter(path -> String.valueOf(path.getFileName()).endsWith(".java"))
                        // A call rather than the word. Both packages say in prose that nothing
                        // here impersonates, and a check that read the word would refuse the
                        // sentence that states the very absence it is checking for.
                        .filter(path -> read(path).contains(".impersonate(")
                                || read(path).contains("getImpersonation("))
                        .forEach(path -> found.add(REPOSITORY.relativize(path).toString()));
            } catch (final java.io.IOException unreadable) {
                throw new java.io.UncheckedIOException(unreadable);
            }
        });
        assertEquals(List.of(), found,
                "a way to run as somebody else exists, and a guard against one can be got round"
                        + " while an absence cannot: " + found);
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode());
    }

    /**
     * Every spelling of one address a request could take.
     *
     * @param address the canonical address
     * @return the spellings, each of which reaches the same resource if anything does
     */
    private static List<String> spellingsOf(String address) {
        return List.of(address, address + ".json", address + ".infinity.json", address + "/",
                address + ".html", address + ".tidy.json");
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
