// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether anything may be published yet, and everything that is missing if not.
 *
 * <p>Publishing is a claim to a namespace somebody has to have verified, made under an identity
 * somebody holds a key for, from a repository somebody owns. None of the three is inferable from a
 * directory name, and no build should make any of them on its own — so publication is refused while
 * any field is absent, and the refusal names every absent field at once.</p>
 *
 * <p>All at once rather than the first, deliberately. Somebody about to release wants the list of
 * what they have to supply, not one line of it per attempt — and a boundary that reported one thing
 * at a time would be a boundary somebody works around by guessing.</p>
 */
public final class PublicationAuthority {

    /** Where the owner-supplied metadata sits. */
    public static final String METADATA = "support/publication-metadata.toml";

    /** Where what the automation may act on and as sits. */
    public static final String AUTHORITY = "support/github-automation-authority.toml";

    /** The rule a field an owner has not supplied is reported under. */
    public static final String AN_ABSENT_FIELD = "an-absent-field";

    /** The rule a namespace nobody has verified is reported under. */
    public static final String AN_UNVERIFIED_NAMESPACE = "an-unverified-namespace";

    /** The rule automation with nowhere it may act is reported under. */
    public static final String NO_AUTOMATION_AUTHORITY = "no-automation-authority";

    /** What a field an owner has not supplied says, which is honest rather than absent. */
    public static final String NOT_SUPPLIED = "";

    private PublicationAuthority() {
    }

    /**
     * Whether one target may be published to, and everything absent if not.
     *
     * @param root the repository root
     * @param target the target's identifier
     * @return one finding per absent field, and nothing where the target may be published to
     */
    public static PolicyReport of(Path root, String target) {
        final PublicationBoundary.Outcome outcome = PublicationBoundary.read(root);
        if (outcome instanceof final PublicationBoundary.Refused refused) {
            return PolicyReport.of(List.of(PolicyFinding.inFile(METADATA, AN_ABSENT_FIELD,
                    "the metadata did not read: " + refused.detail())));
        }
        final PublicationBoundary boundary = ((PublicationBoundary.Loaded) outcome).boundary();
        final List<PolicyFinding> findings = new ArrayList<>();
        boundary.targets().stream()
                .filter(row -> row.identifier().equals(target))
                .forEach(row -> findings.addAll(targetFindings(root, row)));
        findings.addAll(automationFindings(root));
        return PolicyReport.of(findings);
    }

    /**
     * Everything one target needs and does not have.
     *
     * @param root the repository root
     * @param target the target's own row
     * @return one finding per absent field
     */
    private static List<PolicyFinding> targetFindings(Path root,
                                                      PublicationBoundary.TargetRow target) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final String metadata = RepositoryTree.text(root.resolve(METADATA));
        target.requiredFields().stream()
                .filter(field -> NOT_SUPPLIED.equals(valueOf(metadata, field)))
                .forEach(field -> findings.add(PolicyFinding.inFile(METADATA, AN_ABSENT_FIELD,
                        field + " is what " + target.identifier() + " requires and nobody has supplied"
                                + " it")));
        if (target.requiresNamespaceVerification() && !namespaceIsVerified(root)) {
            findings.add(PolicyFinding.inFile(METADATA, AN_UNVERIFIED_NAMESPACE,
                    target.identifier() + " requires a verified namespace, and holding a domain is a fact"
                            + " about the world while a completed verification is a fact about a"
                            + " registry that only the owner who did it can report"));
        }
        return findings;
    }

    /**
     * Whether the automation has anywhere it may act.
     *
     * @param root the repository root
     * @return one finding where it has none
     */
    private static List<PolicyFinding> automationFindings(Path root) {
        final Path authority = root.resolve(AUTHORITY);
        if (!Files.isRegularFile(authority)) {
            return List.of(PolicyFinding.inFile(AUTHORITY, NO_AUTOMATION_AUTHORITY,
                    "nothing says what the automation may act on"));
        }
        final String held = RepositoryTree.text(authority);
        return NOT_SUPPLIED.equals(valueOf(held, "owner")) || NOT_SUPPLIED.equals(valueOf(held, "name"))
                ? List.of(PolicyFinding.inFile(AUTHORITY, NO_AUTOMATION_AUTHORITY,
                        "the repository the automation may act on is not named, and a workflow"
                                + " running against a fork with the same file would be acting"
                                + " somewhere nobody granted it"))
                : List.of();
    }

    /**
     * Every field an owner supplies, read from the metadata rather than listed here.
     *
     * @param root the repository root
     * @return the field names every target between them requires
     */
    public static List<String> suppliedFields(Path root) {
        return PublicationBoundary.read(root) instanceof final PublicationBoundary.Loaded loaded
                ? loaded.boundary().targets().stream()
                        .flatMap(target -> target.requiredFields().stream())
                        .distinct()
                        .sorted()
                        .toList()
                : List.of();
    }

    /**
     * Whether an owner has recorded that they completed the registry's own namespace verification.
     *
     * <p>Read from the metadata rather than inferred. Holding a domain is a fact about the world; a
     * completed verification is a fact about a registry that only the owner who did it can
     * report.</p>
     *
     * @param root the repository root
     * @return whether they have
     */
    private static boolean namespaceIsVerified(Path root) {
        return RepositoryTree.text(root.resolve(METADATA)).lines()
                .map(String::strip)
                .anyMatch(line -> "verified = true".equals(line));
    }

    private static String valueOf(String document, String dotted) {
        final String key = dotted.contains(".")
                ? dotted.substring(dotted.lastIndexOf('.') + 1) : dotted;
        return document.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(key + " = \""))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElse(NOT_SUPPLIED);
    }
}
