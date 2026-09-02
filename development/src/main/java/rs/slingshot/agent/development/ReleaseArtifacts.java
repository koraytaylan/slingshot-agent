// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a release consists of, and whether each one is what was recorded.
 *
 * <p>A release is a claim: these bytes, built from this source, doing what this documentation says.
 * What makes the first part of that claim checkable by somebody who was not there is that the
 * archives are deterministic — so they rebuild from the same source, compare, and a difference is a
 * real difference rather than an argument about build environments.</p>
 *
 * <p>Determinism is a property of how the archives are produced rather than a hope: entry order is
 * fixed, every entry carries the timestamp the source declares, and nothing environment-dependent
 * goes in. What is checked here is that the source says so, because the day somebody removes the
 * declared timestamp every rebuild differs and nobody finds out until a release.</p>
 */
public final class ReleaseArtifacts {

    /** Where the artifacts a release consists of are declared. */
    public static final String INVENTORY = "support/release-artifacts.toml";

    /** Where the build timestamp every archive entry carries is declared. */
    public static final String AGGREGATOR = "pom.xml";

    /** What the build system calls the instant every archive entry records. */
    public static final String DECLARED_TIMESTAMP = "project.build.outputTimestamp";

    /** The rule an archive built from the clock rather than the source is reported under. */
    public static final String A_TIMESTAMP_FROM_THE_CLOCK = "a-timestamp-from-the-clock";

    /** The rule an artifact with no recorded digest is reported under. */
    public static final String NOT_BUILT = "artifact-not-built";

    /** The rule a declared artifact the build did not produce is reported under. */
    public static final String NOT_PRODUCED = "artifact-not-produced";

    /** The rule an artifact whose bytes are not the recorded ones is reported under. */
    public static final String NOT_THE_RECORDED_BYTES = "not-the-recorded-bytes";

    /** What a digest that has not been recorded yet says, which is honest rather than absent. */
    public static final String NOT_RECORDED = "";

    private final List<Artifact> artifacts;

    private ReleaseArtifacts(List<Artifact> artifacts) {
        this.artifacts = artifacts;
    }

    /**
     * One artifact a release consists of.
     *
     * @param name what it is called
     * @param kind what sort of thing it is
     * @param file where the build leaves it
     * @param digest what it hashed to when it was built
     */
    public record Artifact(String name, String kind, String file, String digest) {
    }

    /** The result of reading the inventory: the artifacts, or the one reason there are none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * An inventory that satisfied its shape completely.
     *
     * @param artifacts the loaded inventory
     */
    public record Loaded(ReleaseArtifacts artifacts) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the inventory is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("release-artifacts")
                .text("build.command")
                .text("build.verify")
                .text("build.reason")
                .rows("artifact", row -> row.text("name").text("kind").text("file").text("digest")
                        .text("reason"))
                .build();
    }

    /**
     * Reads the inventory this repository commits.
     *
     * @param root the repository root
     * @return the artifacts, or the one reason there are none
     */
    public static Outcome read(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(INVENTORY), shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        return new Loaded(new ReleaseArtifacts(
                ((PolicyDocument.Loaded) outcome).document().rows("artifact").stream()
                        .map(row -> new Artifact(row.text("name"), row.text("kind"),
                                row.text("file"), row.text("digest")))
                        .toList()));
    }

    /**
     * Every artifact the inventory declares, in its own order.
     *
     * @return the artifacts
     */
    public List<Artifact> artifacts() {
        return java.util.Collections.unmodifiableList(artifacts);
    }

    /**
     * Whether the source says archives are deterministic, which is what makes a rebuild a check.
     *
     * @param root the repository root
     * @return one finding where the source does not say so
     */
    public static PolicyReport determinism(Path root) {
        return RepositoryTree.text(root.resolve(AGGREGATOR)).contains(DECLARED_TIMESTAMP)
                ? PolicyReport.of(List.of())
                : PolicyReport.of(List.of(PolicyFinding.inFile(AGGREGATOR,
                        A_TIMESTAMP_FROM_THE_CLOCK, "no build timestamp is declared, so every"
                                + " archive entry carries the instant somebody built it and every"
                                + " rebuild differs")));
    }

    /**
     * Whether every declared artifact is where it should be and is what was recorded.
     *
     * <p>Reported all at once rather than at the first, because somebody about to make a release
     * wants the whole list and not one line of it at a time.</p>
     *
     * @param root the repository root
     * @return one finding per artifact that is absent, unrecorded, or different
     */
    public PolicyReport against(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        artifacts.forEach(artifact -> {
            if (NOT_RECORDED.equals(artifact.digest())) {
                findings.add(PolicyFinding.inFile(INVENTORY, NOT_BUILT,
                        artifact.name() + " has no recorded digest, so nothing has been built"));
                return;
            }
            final Path file = root.resolve(artifact.file());
            if (!Files.isRegularFile(file)) {
                findings.add(PolicyFinding.inFile(INVENTORY, NOT_PRODUCED,
                        artifact.name() + " is declared and " + artifact.file()
                                + " is not there"));
                return;
            }
            if (!artifact.digest().equals(digestOf(file))) {
                findings.add(PolicyFinding.inFile(INVENTORY, NOT_THE_RECORDED_BYTES,
                        artifact.name() + " is not the bytes that were recorded for it"));
            }
        });
        return PolicyReport.of(findings);
    }

    /**
     * What one file hashes to, spelled the way the inventory spells a digest.
     *
     * @param file the file
     * @return the digest
     */
    public static String digestOf(Path file) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(file)));
        } catch (final java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(file + " could not be digested", failure);
        }
    }
}
