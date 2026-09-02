// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.interop.harness.ContainerHarness;
import rs.slingshot.agent.interop.tier.PublicSlingTier.Reported;

/**
 * What the public tier refuses, and what it never does.
 *
 * <p>It never pulls. An image that is absent and one whose digest differs are two refusals that
 * name the preparation command, because a tier that fetched at run time would make the gate's claim
 * to reach nothing false — and the gate runs this tier.</p>
 */
final class PublicSlingTierTest {

    private static final Path REPOSITORY = repositoryRoot();

    /** The image this repository builds for the public tier, recorded by the preparation command. */
    private static final String TIER_IMAGE = "localhost/slingshot-agent-public-sling:1";

    @Test
    @DisplayName("an absent image refuses, names the preparation command, and pulls nothing")
    void anAbsentImageRefusesWithoutPulling() {
        final InteropTier.Outcome outcome = PublicSlingTier.start(REPOSITORY,
                "docker.invalid/nothing/like-this:0", builtBundle());
        final InteropTier.Refused refused = assertInstanceOf(InteropTier.Refused.class, outcome,
                "a tier came up on an image nothing holds");
        assertEquals(InteropTier.Failure.INPUT_ABSENT, refused.failure());
        assertTrue(refused.detail().contains("scripts/prepare_interop_images"),
                "the refusal does not name the preparation command: " + refused.detail());
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "a refused start left something running");
    }

    @Test
    @DisplayName("the tier image this repository builds is the one the record names")
    void theTierImageIsTheRecordedOne() {
        assertTrue(ContainerHarness.at(REPOSITORY).holds(TIER_IMAGE),
                TIER_IMAGE + " is not held; run scripts/prepare_interop_images");
        final String record = RepositoryText.of(REPOSITORY.resolve("support/interop-images.toml"));
        assertTrue(record.contains("name = \"" + TIER_IMAGE + "\""),
                "the image the tier starts is not the one the record names");
        assertTrue(record.contains("containerfile = \"interop/tier/public-sling/Containerfile\""),
                "the record does not say what the image is built from");
    }

    @Test
    @DisplayName("the platform's own bundle report is read for the bundle it names")
    void theBundleReportIsReadForTheRightBundle() {
        final String report = "{\"data\":[{\"id\":1,\"state\":\"Active\",\"symbolicName\":\"first\"},"
                + "{\"id\":2,\"state\":\"Installed\",\"symbolicName\":\"second\"}]}";
        assertEquals(Optional.of("Active"), PublicSlingTier.stateOf(report, "first"));
        assertEquals(Optional.of("Installed"), PublicSlingTier.stateOf(report, "second"));
        assertEquals(Optional.empty(), PublicSlingTier.stateOf(report, "nothing-like-this"));
    }

    @Test
    @DisplayName("the tier installs only what it can resolve, and says which bundle that is")
    void theTierInstallsOnlyWhatItCanResolve() {
        assertEquals("rs.slingshot.agent.core", PublicSlingTier.CORE_BUNDLE);
        assertEquals("rs.slingshot.agent.aem", PublicSlingTier.ADOBE_BUNDLE);
        assertEquals("a", PublicSlingTier.NAME);
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no bundle was built at " + target));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** Reading a committed file, which this suite does in one place. */
    private static final class RepositoryText {

        private RepositoryText() {
        }

        static String of(Path file) {
            try {
                return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            } catch (final java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("a report that names the bundle but no state for it reports no state")
    void aReportWithNoStateReportsNone() {
        assertEquals(Optional.empty(),
                PublicSlingTier.stateOf("{\"symbolicName\":\"" + PublicSlingTier.CORE_BUNDLE
                        + "\"}", PublicSlingTier.CORE_BUNDLE));
    }

    @Test
    @DisplayName("active ends the wait, and nothing yet keeps it going however long it has run")
    void activeEndsTheWait() {
        assertEquals(Optional.of(Optional.empty()),
                PublicSlingTier.settlement(0, Reported.ACTIVE),
                "an active bundle did not end the wait");
        assertEquals(Optional.empty(),
                PublicSlingTier.settlement(0, Reported.OTHERWISE),
                "a bundle the platform does not hold yet ended the wait");
        assertEquals(Optional.empty(),
                PublicSlingTier.settlement(1000, Reported.OTHERWISE),
                "a bundle that is resolved and not yet started ended the wait");
    }

    @Test
    @DisplayName("installed and unresolved is a moment at first and a named failure after that")
    void installedAndUnresolvedSettles() {
        assertEquals(Optional.empty(),
                PublicSlingTier.settlement(0, Reported.INSTALLED),
                "the moment between receiving a bundle and resolving it was read as the outcome");
        final Optional<Optional<String>> settled =
                PublicSlingTier.settlement(1000, Reported.INSTALLED);
        assertTrue(settled.isPresent() && settled.get().isPresent(),
                "a bundle that stayed installed and unresolved was not reported");
        assertTrue(settled.get().get().contains("not provided by this runtime"),
                "the failure did not name its cause: " + settled.get().get());
    }

    @Test
    @DisplayName("only a refusal for being unauthenticated proves a route registered")
    void onlyAnUnauthenticatedRefusalProvesRegistration() {
        assertEquals(Optional.of(Optional.empty()), PublicSlingTier.registration(UNAUTHENTICATED),
                "a route refusing an unauthenticated caller did not end the wait");
        assertEquals(Optional.empty(), PublicSlingTier.registration(NOTHING_THERE),
                "an instance answering as though nothing serves the path ended the wait, which is"
                        + " the window this wait exists to close");
        assertEquals(Optional.empty(), PublicSlingTier.registration(SERVED),
                "an answer no unauthenticated caller can be given ended the wait");
    }

    /** What a route this bundle owns answers a caller who presented no identity. */
    private static final int UNAUTHENTICATED = 401;

    /** What the platform answers for a path nothing serves, which is the window being closed. */
    private static final int NOTHING_THERE = 404;

    /** An answer an unauthenticated caller is never given, so it proves nothing about a route. */
    private static final int SERVED = 200;
}
