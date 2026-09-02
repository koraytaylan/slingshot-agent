// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether anything may be published yet, and everything that is missing if not.
 *
 * <p>Publishing is a claim to a namespace somebody verified, made under an identity somebody holds
 * a key for, from a repository somebody owns. None of the three is inferable from a directory name,
 * so publication is refused while any field is absent — and refused with every absent field named
 * at once, because somebody about to release wants the list rather than one line of it per
 * attempt.</p>
 */
final class PublicationAuthorityTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** The target that needs a verified namespace, which is the strictest of the two. */
    private static final String MAVEN_CENTRAL = "maven-central";

    /** The target an operator installs from, which needs less and is not blocked by the other. */
    private static final String RELEASE_ASSET = "release-asset";

    @Test
    @DisplayName("publication is refused while an owner has supplied nothing")
    void publicationIsRefusedWhileNothingIsSupplied() {
        assertTrue(!PublicationAuthority.of(REPOSITORY, MAVEN_CENTRAL).findings().isEmpty(),
                "publication to the registry was permitted with no metadata at all, which is a"
                        + " claim to a namespace this build made on its own");
    }

    @Test
    @DisplayName("every absent field is named at once rather than one per attempt")
    void everyabsentFieldIsNamedAtOnce() {
        final List<PolicyFinding> findings =
                PublicationAuthority.of(REPOSITORY, MAVEN_CENTRAL).findings();
        assertTrue(findings.stream()
                        .filter(finding -> PublicationAuthority.AN_ABSENT_FIELD
                                .equals(finding.rule()))
                        .count() > 1,
                "one absent field was reported, and a boundary that reports one thing at a time is"
                        + " a boundary somebody works around by guessing: " + findings);
        assertTrue(findings.stream()
                        .anyMatch(finding -> finding.symbol().contains("signing.identity")),
                "the signing identity is not among what the registry requires, and a registry that"
                        + " accepted an unsigned artifact would be accepting a claim nobody made");
    }

    @Test
    @DisplayName("a namespace nobody verified is its own refusal, not just an absent field")
    void anunverifiedNamespaceIsItsOwnRefusal() {
        assertTrue(PublicationAuthority.of(REPOSITORY, MAVEN_CENTRAL).findings().stream()
                        .anyMatch(finding -> PublicationAuthority.AN_UNVERIFIED_NAMESPACE
                                .equals(finding.rule())),
                "an unverified namespace was reported as a missing field, and holding a domain is"
                        + " a fact about the world while a completed verification is a fact about"
                        + " a registry");
    }

    @Test
    @DisplayName("the target that needs less is not blocked by what the other one needs")
    void onetargetIsNotBlockedByAnothersRequirements() {
        final List<PolicyFinding> asset =
                PublicationAuthority.of(REPOSITORY, RELEASE_ASSET).findings();
        assertTrue(asset.stream()
                        .noneMatch(finding -> PublicationAuthority.AN_UNVERIFIED_NAMESPACE
                                .equals(finding.rule())),
                "the target that requires no namespace verification was refused for the namespace"
                        + " not being verified: " + asset);
    }

    @Test
    @DisplayName("the automation says what it may act on rather than acting wherever it runs")
    void theautomationSaysWhatItMayActOn() {
        assertTrue(PublicationAuthority.of(REPOSITORY, RELEASE_ASSET).findings().stream()
                        .anyMatch(finding -> PublicationAuthority.NO_AUTOMATION_AUTHORITY
                                .equals(finding.rule())),
                "the automation may act wherever it happens to be running, which is a property of"
                        + " the runner rather than a decision anybody made");
    }

    @Test
    @DisplayName("what an owner supplies is read from the metadata rather than listed elsewhere")
    void whatAnOwnerSuppliesIsReadFromTheMetadata() {
        final List<String> fields = PublicationAuthority.suppliedFields(REPOSITORY);
        assertTrue(fields.contains("repository.url") && fields.contains("signing.identity"),
                "the fields an owner supplies are not the ones the targets require: " + fields);
        assertEquals(fields.stream().distinct().sorted().toList(), fields,
                "a field is asked for twice, which reads as two things to supply");
    }
}
