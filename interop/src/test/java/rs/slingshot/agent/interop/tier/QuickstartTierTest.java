// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * The tier that refuses rather than skipping.
 *
 * <p>The quickstart jar is licensed to its holder. This repository never commits one, never caches
 * one, never publishes one, and never fetches one — so on any machine but its holder's, this tier
 * refuses. What matters is that it refuses <em>explicitly</em>, with what its holder should do,
 * because a suite that quietly does not run is a suite reporting success it did not earn.</p>
 */
final class QuickstartTierTest {

    private static final Path REPOSITORY = repositoryRoot();

    /** A digest no jar has, which is what a recorded one that stopped matching looks like. */
    private static final String NOT_THE_JAR =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    @DisplayName("an absent jar refuses, names where it goes, and starts nothing")
    void anAbsentJarRefusesExplicitly() {
        assertEquals(Optional.of(QuickstartTier.Refusal.JAR_ABSENT),
                QuickstartTier.refusal(REPOSITORY),
                "a quickstart jar is present in this working tree, which it must never be");
        final String todo = QuickstartTier.whatToDo(REPOSITORY, QuickstartTier.Refusal.JAR_ABSENT);
        assertTrue(todo.contains(".quickstart/aem-quickstart.jar"), todo);
        assertTrue(todo.contains("never fetches"), todo);
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "a refused tier started a container");
    }

    @Test
    @DisplayName("the three refusals are distinct and each says a different thing to do")
    void theThreeRefusalsAreDistinct() {
        final List<String> said = List.of(QuickstartTier.Refusal.values()).stream()
                .map(refusal -> QuickstartTier.whatToDo(REPOSITORY, refusal))
                .toList();
        assertEquals(3, said.size());
        assertEquals(said.size(), said.stream().distinct().count(),
                "two refusals say the same thing, so one of them tells nobody anything");
        assertTrue(said.get(2).contains("Nothing here can make that statement for them."),
                "the acknowledgement refusal does not say who has to make the statement");
    }

    @Test
    @DisplayName("a jar that is not the recorded one refuses differently from an absent one")
    void aDifferentJarRefusesDifferently(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve(".quickstart"));
        Files.writeString(directory.resolve(".quickstart/aem-quickstart.jar"), "not the jar",
                StandardCharsets.UTF_8);
        final Path values = directory.resolve("quickstart-tier.toml");
        Files.writeString(values, read(REPOSITORY.resolve("support/quickstart-tier.toml"))
                .replace("digest = \"\"", "digest = \"" + NOT_THE_JAR + "\""),
                StandardCharsets.UTF_8);
        assertEquals(Optional.of(QuickstartTier.Refusal.JAR_DIFFERS),
                QuickstartTier.refusalIn(directory, values));
    }

    @Test
    @DisplayName("a jar its holder has not acknowledged refuses differently again")
    void anUnacknowledgedJarRefusesDifferentlyAgain(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve(".quickstart"));
        final Path jar = directory.resolve(".quickstart/aem-quickstart.jar");
        Files.writeString(jar, "the jar its holder has", StandardCharsets.UTF_8);
        final Path values = directory.resolve("quickstart-tier.toml");
        Files.writeString(values, read(REPOSITORY.resolve("support/quickstart-tier.toml"))
                .replace("digest = \"\"", "digest = \"" + QuickstartTier.digestOf(jar) + "\""),
                StandardCharsets.UTF_8);
        assertEquals(Optional.of(QuickstartTier.Refusal.NOT_ACKNOWLEDGED),
                QuickstartTier.refusalIn(directory, values));
        final Path acknowledged = directory.resolve("acknowledged.toml");
        Files.writeString(acknowledged, read(values).replace("acknowledged = false",
                "acknowledged = true"), StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), QuickstartTier.refusalIn(directory, acknowledged),
                "an acknowledged jar whose digest matches was still refused");
        final InteropTier.Outcome outcome = QuickstartTier.startIn(directory, acknowledged);
        final InteropTier.Refused notBuilt = assertInstanceOf(InteropTier.Refused.class, outcome,
                "a tier came up that this commit does not build");
        assertTrue(notBuilt.detail().contains("not built in this commit"), notBuilt.detail());
        assertEquals("", QuickstartTier.notRunning().address(),
                "a tier that never started reported an address");
    }

    @Test
    @DisplayName("a jar in the working tree is one version control ignores")
    void theJarPathIsIgnored() {
        final String ignored = read(REPOSITORY.resolve(".gitignore"));
        assertTrue(ignored.contains(".quickstart/"),
                "the path the owner's own jar goes in is not ignored, so it could be committed");
        assertTrue(QuickstartTier.jarPath(REPOSITORY).startsWith(REPOSITORY),
                "the jar is expected somewhere other than this working tree");
    }

    @Test
    @DisplayName("the digest is of the jar's own bytes rather than of anything about it")
    void theDigestIsOfTheJarsOwnBytes() {
        final Path anything = REPOSITORY.resolve("support/quickstart-tier.toml");
        assertEquals(64, QuickstartTier.digestOf(anything).length(),
                "the digest is not one this repository could compare against a record");
        assertEquals(QuickstartTier.digestOf(anything), QuickstartTier.digestOf(anything),
                "the same bytes digest differently twice");
    }

    @Test
    @DisplayName("starting the tier refuses with what its holder should do, and starts nothing")
    void startingRefusesWithWhatToDo() {
        final InteropTier.Outcome outcome = QuickstartTier.start(REPOSITORY);
        final InteropTier.Refused refused = assertInstanceOf(InteropTier.Refused.class, outcome,
                "a tier came up without the licensed input it needs");
        assertEquals(InteropTier.Failure.INPUT_ABSENT, refused.failure());
        assertTrue(refused.detail().contains(".quickstart/"), refused.detail());
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "a refused tier started a container");
    }

    @Test
    @DisplayName("the licensed input is never committed, cached, published, or fetched")
    void theLicensedInputIsNeverHeldHere() {
        final String values = read(REPOSITORY.resolve("support/quickstart-tier.toml"));
        assertTrue(values.contains("never committed"), values);
        assertTrue(values.contains("never fetched"), values);
        final String command = read(REPOSITORY.resolve("scripts/interop_quickstart_tier"));
        assertTrue(!command.contains("REACHES THE NETWORK"),
                "the command that runs this tier declares that it reaches the network");
        assertTrue(!command.contains("push"),
                "the command that runs this tier publishes the image it builds");
    }

    @Test
    @DisplayName("this tier is not one the gate runs, and the inventory says so")
    void thisTierIsNotOneTheGateRuns() {
        final String inventory = read(REPOSITORY.resolve("policy/quality-gate.toml"));
        assertTrue(inventory.contains("command = \"scripts/interop_quickstart_tier\""),
                "the tier inventory does not name the command that runs this tier");
        final String gate = read(REPOSITORY.resolve("scripts/quality"));
        assertTrue(!gate.contains("interop_quickstart_tier"),
                "the gate runs a tier that needs an input most contributors cannot have");
        assertEquals("b", QuickstartTier.NAME);
    }

    @Test
    @DisplayName("a tier that never started answers nothing rather than answering wrongly")
    void aTierThatNeverStartedAnswersNothing() {
        final InteropTier.Outcome outcome = QuickstartTier.start(REPOSITORY);
        assertInstanceOf(InteropTier.Refused.class, outcome, "the tier came up");
        assertEquals(Optional.empty(), notRunning().bundleState("anything"),
                "a tier that never started reported what a platform holds");
        notRunning().stop();
        assertThrows(IllegalStateException.class,
                () -> notRunning().readAsAuthenticatedUser("/anything"),
                "a tier that never started answered a request");
        assertThrows(IllegalStateException.class, () -> notRunning().readAsNobody("/anything"),
                "a tier that never started answered a request");
    }

    /**
     * The tier as it exists on a machine with no licensed input, which is every machine but one.
     *
     * @return a tier that never started
     */
    private static QuickstartTier notRunning() {
        return QuickstartTier.notRunning();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
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
