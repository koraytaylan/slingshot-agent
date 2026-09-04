// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line between packaging something and publishing it.
 *
 * <p>Three states matter and the tests walk all three: nothing supplied, some of it supplied, and
 * all of it supplied with the registry's own verification recorded. In every one of them the
 * container package an operator installs still builds, which is the point — the boundary refuses a
 * publish and never a build.</p>
 */
final class PublicationBoundaryTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/publication-boundary");

    private static final String MAVEN = "maven-central";

    private static final String RELEASE_ASSET = "release-asset";

    @Test
    @DisplayName("with nothing supplied, every module is unpublished and both targets are refused")
    void nothingSuppliedRefusesEveryTarget() {
        final PublicationBoundary boundary = boundaryAt("absent.toml");
        assertWithheldNaming(boundary, MAVEN, "namespace.verified");
        assertWithheldNaming(boundary, MAVEN, "repository.url");
        assertWithheldNaming(boundary, MAVEN, "developer.identifier");
        assertWithheldNaming(boundary, RELEASE_ASSET, "repository.url");
        assertEquals("", boundaryAt("absent.toml").against(ReactorModel.at(REPOSITORY)).render(),
                "the build would publish while nothing is supplied");
    }

    @Test
    @DisplayName("this repository's owner has supplied it all, and its own metadata says so")
    void thisRepositoryMayPublish() {
        final PublicationBoundary boundary =
                assertInstanceOf(PublicationBoundary.Loaded.class, PublicationBoundary.read(REPOSITORY),
                        "the publication metadata was refused").boundary();
        // Withheld was this repository's state while an owner had supplied nothing. What the two
        // verdicts are held to now is the state an owner put it in, and the fixtures beside this
        // test are where withholding is still proved.
        assertInstanceOf(PublicationBoundary.Publishable.class, boundary.verdict(MAVEN));
        assertInstanceOf(PublicationBoundary.Publishable.class, boundary.verdict(RELEASE_ASSET));
        assertEquals("", boundary.against(ReactorModel.at(REPOSITORY)).render());
        assertTrue(boundary.namespaceShape().isEmpty(),
                "the declared group identifier does not reverse the declared domain");
    }

    @Test
    @DisplayName("partial supply is a refusal naming the absent field, not a partial publish")
    void partialSupplyIsARefusal() {
        final PublicationBoundary boundary = boundaryAt("partial.toml");
        assertWithheldNaming(boundary, MAVEN, "repository.connection");
        assertWithheldNaming(boundary, MAVEN, "developer.identifier");
        assertInstanceOf(PublicationBoundary.Publishable.class, boundary.verdict(RELEASE_ASSET),
                "the release asset target is blocked by another target's absent fields");
    }

    @Test
    @DisplayName("a complete set with no verification record is still refused for the registry")
    void aWellFormedButUnverifiedNamespaceIsRefused() {
        final PublicationBoundary boundary = boundaryAt("complete-unverified.toml");
        assertWithheldNaming(boundary, MAVEN, "namespace.verified");
        assertInstanceOf(PublicationBoundary.Publishable.class, boundary.verdict(RELEASE_ASSET));
    }

    @Test
    @DisplayName("the flag alone does not authorise a publish")
    void theFlagAloneDoesNotAuthorise() {
        assertWithheldNaming(boundaryAt("flag-set-one-field-absent.toml"), MAVEN,
                "repository.connection");
        assertWithheldNaming(boundaryAt("flag-set-no-record.toml"), MAVEN,
                "namespace.verification_reference");
    }

    @Test
    @DisplayName("a complete verified set carries exactly the owner's values and publishes")
    void aCompleteVerifiedSetPublishes() {
        final PublicationBoundary boundary = boundaryAt("complete-verified.toml");
        assertInstanceOf(PublicationBoundary.Publishable.class, boundary.verdict(MAVEN));
        assertInstanceOf(PublicationBoundary.Publishable.class, boundary.verdict(RELEASE_ASSET));
        assertEquals(List.of(MAVEN, RELEASE_ASSET),
                boundary.targets().stream()
                        .map(PublicationBoundary.TargetRow::identifier)
                        .toList());
        assertTrue(boundary.targets().stream().allMatch(target -> !target.audience().isBlank()),
                "a target records no audience");
    }

    @Test
    @DisplayName("an identifier that does not reverse the declared domain is refused")
    void anIdentifierThatDoesNotReverseTheDomainIsRefused() {
        final PublicationBoundary boundary = boundaryAt("group-does-not-reverse-domain.toml");
        assertTrue(boundary.namespaceShape().isPresent(), "a mismatched namespace was accepted");
        assertTrue(boundary.namespaceShape().orElseThrow().symbol().contains("slingshot.rs"));
        assertWithheldNaming(boundary, RELEASE_ASSET, "does not reverse");
    }

    @Test
    @DisplayName("only the registry target is gated by the namespace record")
    void onlyTheRegistryTargetIsGatedByTheRecord() {
        assertEquals(List.of(true, false),
                boundaryAt("absent.toml").targets().stream()
                        .map(PublicationBoundary.TargetRow::requiresNamespaceVerification)
                        .toList());
    }

    @Test
    @DisplayName("the installable container is produced in every state the boundary can be in")
    void theContainerIsBuiltInEveryState() {
        final String version = ReactorModel.at(REPOSITORY).aggregator().getVersion();
        final Path container = REPOSITORY.resolve("all/target/slingshot-agent-all-" + version + ".zip");
        List.of("absent.toml", "complete-unverified.toml", "complete-verified.toml")
                .forEach(state -> assertTrue(!boundaryAt(state).targets().isEmpty(),
                        state + " declares no target at all"));
        assertTrue(BuiltArtifact.at(container).holds("META-INF/vault/filter.xml"),
                "the container package the boundary never blocks was not produced");
    }

    private static void assertWithheldNaming(PublicationBoundary boundary, String target, String named) {
        final PublicationBoundary.Withheld withheld = assertInstanceOf(
                PublicationBoundary.Withheld.class, boundary.verdict(target),
                target + " was publishable where it must be refused");
        assertTrue(withheld.reasons().stream().anyMatch(reason -> reason.contains(named)),
                "no reason names " + named + ": " + withheld.reasons());
    }

    private static PublicationBoundary boundaryAt(String fixture) {
        return assertInstanceOf(PublicationBoundary.Loaded.class,
                PublicationBoundary.readMetadata(FIXTURES.resolve(fixture)),
                fixture + " was refused").boundary();
    }
}
