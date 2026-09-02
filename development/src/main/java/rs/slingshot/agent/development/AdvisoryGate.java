// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The advisory snapshot, authenticated before anything is checked against it.
 *
 * <p>There is deliberately no timestamp and no freshness claim. A snapshot's author chooses those
 * values, so neither authenticates anything, and a gate reporting freshness would be reporting
 * something it cannot check. What this decides is whether the bytes here are the bytes an owner
 * reviewed — and that is a question with an answer.</p>
 *
 * <p>Three refusals, kept apart because they are three different things to do next. Nothing has
 * been fetched; something was fetched and is not here; something is here and is not what was
 * pinned. None of the three checks a single artifact, because checking against a snapshot nobody
 * authenticated is checking against whatever happens to be on the machine.</p>
 */
public final class AdvisoryGate {

    /** Where the snapshot is pinned. */
    public static final String PIN_FILE = "compatibility/advisory-database.toml";

    /** The rule a snapshot nobody has fetched is reported under. */
    public static final String NOT_FETCHED = "advisory-snapshot-not-fetched";

    /** The rule a snapshot that is not where it should be is reported under. */
    public static final String NOT_PRESENT = "advisory-snapshot-not-present";

    /** The rule a snapshot whose bytes are not the pinned ones is reported under. */
    public static final String NOT_THE_PINNED_SNAPSHOT = "not-the-pinned-snapshot";

    /** The rule a snapshot no owner reviewed is reported under. */
    public static final String NO_OWNER_REVIEW = "advisory-snapshot-has-no-owner-review";

    /** The rule a resolved artifact nothing checked is reported under. */
    public static final String AN_ARTIFACT_NOBODY_CHECKED = "an-artifact-nobody-checked";

    /** What a value that has not been recorded yet says, which is honest rather than absent. */
    public static final String NOT_RECORDED = "";

    private final String commit;
    private final String contentDigest;
    private final String heldAt;
    private final String checkout;
    private final String reviewHeldAt;
    private final List<String> checkedScopes;

    private AdvisoryGate(String commit, String contentDigest, String heldAt, String checkout,
                         String reviewHeldAt, List<String> checkedScopes) {
        this.commit = commit;
        this.contentDigest = contentDigest;
        this.heldAt = heldAt;
        this.checkout = checkout;
        this.reviewHeldAt = reviewHeldAt;
        this.checkedScopes = checkedScopes;
    }

    /** The result of reading the pin: the gate, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A pin that satisfied its shape completely.
     *
     * @param gate the loaded gate
     */
    public record Loaded(AdvisoryGate gate) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the pin is held to.
     *
     * <p>There is no key for a date, which is the enforcement rather than a convention: a field
     * that cannot exist cannot be trusted, and a document adding one is refused as an unknown key
     * rather than believed.</p>
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("advisory-database")
                .text("snapshot.origin")
                .text("snapshot.commit")
                .text("snapshot.content_digest")
                .text("snapshot.held_at")
                .text("snapshot.checkout")
                .text("snapshot.reason")
                .text("review.record")
                .text("review.held_at")
                .text("review.reason")
                .rows("checked_scope", row -> row.text("scope").text("reason"))
                .build();
    }

    /**
     * Reads the pin this repository commits.
     *
     * @param root the repository root
     * @return the gate, or the one reason there is none
     */
    public static Outcome read(Path root) {
        return readFile(root.resolve(PIN_FILE));
    }

    /**
     * Reads one pin wherever it sits, so a fixture can replace it and nothing else.
     *
     * @param file the pin
     * @return the gate, or the one reason there is none
     */
    public static Outcome readFile(Path file) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(file, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new AdvisoryGate(document.text("snapshot.commit"),
                document.text("snapshot.content_digest"), document.text("snapshot.held_at"),
                document.text("snapshot.checkout"), document.text("review.held_at"),
                document.rows("checked_scope").stream().map(row -> row.text("scope")).toList()));
    }

    /**
     * Every scope this gate checks, which includes the two somebody would leave out.
     *
     * @return the scopes, in the pin's own order
     */
    public List<String> checkedScopes() {
        return java.util.Collections.unmodifiableList(checkedScopes);
    }

    /**
     * Whether the snapshot here is the one that was pinned and reviewed.
     *
     * <p>Answered before anything is checked against it. A gate that checked artifacts against a
     * snapshot nobody authenticated would be checking against whatever happened to be on the
     * machine, which on somebody's laptop is frequently a different one.</p>
     *
     * @param root the repository root
     * @return one finding per reason the snapshot cannot be used, and nothing where it can
     */
    public PolicyReport authentication(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        if (NOT_RECORDED.equals(commit) || NOT_RECORDED.equals(contentDigest)) {
            findings.add(PolicyFinding.inFile(PIN_FILE, NOT_FETCHED,
                    "no snapshot has been fetched, so nothing has been reviewed and nothing can be"
                            + " checked. Run " + checkout + "."));
            return PolicyReport.of(findings);
        }
        if (!Files.isDirectory(root.resolve(heldAt))) {
            findings.add(PolicyFinding.inFile(PIN_FILE, NOT_PRESENT,
                    heldAt + " is not here. Run " + checkout + "."));
            return PolicyReport.of(findings);
        }
        if (!contentDigest.equals(digestOf(root.resolve(heldAt)))) {
            findings.add(PolicyFinding.inFile(PIN_FILE, NOT_THE_PINNED_SNAPSHOT,
                    heldAt + " holds bytes other than the ones pinned, so what is here is not what"
                            + " anybody reviewed. Run " + checkout + "."));
            return PolicyReport.of(findings);
        }
        findings.addAll(reviewFindings(root));
        return PolicyReport.of(findings);
    }

    /**
     * Whether an owner reviewed exactly these bytes.
     *
     * @param root the repository root
     * @return one finding where they did not
     */
    private List<PolicyFinding> reviewFindings(Path root) {
        final Path review = root.resolve(reviewHeldAt);
        if (!Files.isRegularFile(review)
                || !RepositoryTree.text(review).contains(contentDigest)) {
            return List.of(PolicyFinding.inFile(PIN_FILE, NO_OWNER_REVIEW,
                    "no owner review names " + contentDigest + ", and a review of an advisory set"
                            + " that has moved since is a review of something else"));
        }
        return List.of();
    }

    /**
     * The digest of a whole tree, computed the way the fetch command computes it.
     *
     * @param tree where it sits
     * @return the digest, spelled the way the pin spells one
     */
    public static String digestOf(Path tree) {
        final java.security.MessageDigest over = digest();
        try (var files = Files.walk(tree)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> over.update(fileDigest(file)));
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
        return "sha256:" + rendered(over.digest());
    }

    private static byte[] fileDigest(Path file) {
        try {
            return digest().digest(Files.readAllBytes(file));
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static java.security.MessageDigest digest() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (final java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException("this runtime has no SHA-256", absent);
        }
    }

    private static String rendered(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
