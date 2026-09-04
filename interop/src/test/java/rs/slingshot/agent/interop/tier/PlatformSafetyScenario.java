// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What is true of every command that changes the platform, on a running instance.
 *
 * <p>Thirty commands reach past every guard the content plans relied on: none of what they touch is
 * protected by repository access control, and the most useful-looking half of it does not work at
 * all on the environment this agent is meant to run on. This proves what they have in common.</p>
 *
 * <p>Every row is enumerated from the registry rather than from a list here, so the plan that adds
 * a thirty-first platform control is covered without editing this. Selection is by what a row
 * declares, which is also what the client reads — a suite that selected by package would stop
 * selecting the day somebody moved a class.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PlatformSafetyScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a submission this build will not act on is answered with. */
    private static final int REFUSED = 400;

    /** The category a command that changes the platform declares. */
    private static final String PLATFORM_OUTCOME = "platform_control_outcome_unknown";

    /** The category a command that changes the caller's own repository declares. */
    private static final String REPOSITORY_OUTCOME = "mutation_outcome_unknown";

    /** Where each command's capability is declared, which is what a deployment may refuse. */
    private static final String CAPABILITIES = "policy/control-capabilities.toml";

    /** Where each deployment says what it does and does not provide. */
    private static final String DEPLOYMENTS = "support/deployments.toml";

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
    @DisplayName("every platform control declares that and not a repository commit as well")
    void everycontrolDeclaresOneKindOfChange() {
        final List<String> confused = new ArrayList<>();
        for (final Path row : controls()) {
            if (read(row).contains(REPOSITORY_OUTCOME)) {
                confused.add(String.valueOf(row.getFileName()));
            }
        }
        assertEquals(List.of(), confused, "a command declares that it changes the platform and"
                + " commits to the caller's repository as well, and a command that does both is"
                + " two commands — the one-commit wrapper would demand a write nobody asked for");
        assertTrue(!controls().isEmpty(),
                "no command declares that it changes the platform, so this suite proves nothing");
    }

    @Test
    @DisplayName("every platform control is one some deployment can refuse before it runs")
    void everycontrolIsOneADeploymentCanRefuse() {
        final String capabilities = read(REPOSITORY.resolve(CAPABILITIES));
        final List<String> ungated = controls().stream()
                .map(PlatformSafetyScenario::commandOf)
                .filter(command -> !capabilities.contains("command = \"" + command + "\""))
                .toList();
        assertEquals(List.of(), ungated, "a command that changes the platform is gated by no"
                + " capability, so it would run on the environment that discards the change and"
                + " report that it worked");
    }

    @Test
    @DisplayName("the deployment this agent is built for refuses the two controls it cannot keep")
    void thebuiltForDeploymentRefusesWhatItCannotKeep() {
        final String matrix = read(REPOSITORY.resolve(DEPLOYMENTS));
        final int cloud = matrix.indexOf("id = \"aem-cloud-service\"");
        final int next = matrix.indexOf("id = \"aem-6-5-lts\"");
        assertTrue(cloud >= 0 && next > cloud, "the deployment matrix lost one of its two rows");
        final String row = matrix.substring(cloud, next);
        for (final String capability : List.of("configuration_change", "bundle_lifecycle")) {
            final int at = row.indexOf("capability = \"" + capability + "\"");
            assertTrue(at >= 0, capability + " is no longer declared by the built-for deployment");
            assertTrue(row.indexOf("provided = false", at) >= 0
                            && row.indexOf("provided = false", at) < at + 200,
                    capability + " is now provided by a deployment whose configuration and bundle"
                            + " lifecycle are decided by the deployed image — a change written"
                            + " there is accepted, reported as done, and gone by the next release");
        }
    }

    @Test
    @DisplayName("every platform control requires an operation key")
    void everycontrolRequiresAKey() {
        final List<String> keyless = controls().stream()
                .filter(row -> !read(row).contains("operation_key = \"required\""))
                .map(row -> String.valueOf(row.getFileName()))
                .toList();
        assertEquals(List.of(), keyless, "a command that changes something does not require an"
                + " operation key, so a resent submission would have a second effect");
    }

    @Test
    @DisplayName("every platform control answers within one result bound")
    void everycontrolStatesItsBound() {
        final List<String> unbounded = controls().stream()
                .filter(row -> !read(row).contains("result_bytes = "))
                .map(row -> String.valueOf(row.getFileName()))
                .toList();
        assertEquals(List.of(), unbounded, "a command that changes something states no bound on"
                + " what it may answer, so an oversized result would be discovered while holding"
                + " it");
    }

    @Test
    @DisplayName("no platform control discloses a credential-shaped value in its own declaration")
    void nocontrolDeclaresACredentialShapedMember() {
        for (final Path row : controls()) {
            final String declared = read(row);
            assertTrue(!declared.contains("password") && !declared.contains("transport_uri")
                            && !declared.contains("credential"),
                    row.getFileName() + " names a credential-shaped member in its own registry"
                            + " row, which is the one place nobody would think to audit");
        }
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity");
    }

    @Test
    @DisplayName("a submission naming a control and nothing else is refused before it runs")
    void asubmissionWithNoArgumentIsRefused() {
        for (final Path row : controls()) {
            final String name = String.valueOf(row.getFileName());
            final String command = name.substring(0, name.length() - ".toml".length());
            assertEquals(REFUSED, requests.postAsAuthenticatedUser(tier.address() + SUBMIT,
                            "{\"command_wire_name\":\"" + command + "\"}", "application/json")
                    .statusCode(),
                    command + " accepted a submission carrying nothing but a name, and a command"
                            + " that changes something must refuse before it changes anything");
        }
    }

    private static List<Path> controls() {
        return registryRows().stream()
                .filter(row -> read(row).contains(PLATFORM_OUTCOME))
                .toList();
    }

    private static String commandOf(Path row) {
        final String name = String.valueOf(row.getFileName());
        return name.substring(0, name.length() - ".toml".length());
    }

    private static List<Path> registryRows() {
        try (Stream<Path> held = Files.list(REPOSITORY.resolve("policy/commands"))) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
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
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
