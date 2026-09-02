// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The previous release, populated, and this one installed over it.
 *
 * <p>A fresh install proves nothing about an upgrade, and an upgrade is what every real deployment
 * does. So this installs the pinned release first, writes records of every kind the store holds,
 * and then installs the current release over the same instance — rather than beside it, which is
 * the arrangement that proves nothing anybody will experience.</p>
 *
 * <p>Before the first release there is nothing to upgrade from, and this says so rather than
 * passing. A suite that quietly passed on an absent previous release would be a suite reporting an
 * upgrade it never performed, which is exactly the report somebody would act on.</p>
 */
public final class UpgradeTier {

    /** Where the previous release is pinned. */
    public static final String PIN_FILE = "support/previous-release.toml";

    /** What a value that has not been recorded says, which is honest rather than absent. */
    public static final String NOT_PINNED = "";

    private UpgradeTier() {
    }

    /** What is known about the release an upgrade would be proved from. */
    public sealed interface Previous permits Pinned, NothingToUpgradeFrom {
    }

    /**
     * A release this suite can install and upgrade over.
     *
     * @param version the release
     * @param artifactDigest what its container package hashes to
     * @param heldAt where its artifact sits once it has been fetched
     */
    public record Pinned(String version, String artifactDigest, String heldAt)
            implements Previous {
    }

    /**
     * No release at all, which is the true state before the first one.
     *
     * @param detail why there is none, said plainly rather than as a skipped test
     */
    public record NothingToUpgradeFrom(String detail) implements Previous {
    }

    /**
     * What the pin says, read rather than assumed.
     *
     * @param root the repository root
     * @return the pinned release, or that there is none
     */
    public static Previous previous(Path root) {
        final String pin = read(root.resolve(PIN_FILE));
        final String version = valueOf(pin, "version");
        final String digest = valueOf(pin, "artifact_digest");
        if (isNotPinned(version) || isNotPinned(digest)) {
            return new NothingToUpgradeFrom("no previous release is pinned, which before the first"
                    + " release is the true state rather than a suite that did not run");
        }
        return new Pinned(version, digest, valueOf(pin, "held_at"));
    }

    /**
     * Whether a field of the pin says there is nothing pinned.
     *
     * <p>Asked of both fields through one question rather than compared twice in a condition. The
     * sentinel is what the pin says when it names no release, so this establishes that a field is
     * absent and never that one value matches another - which is the only reason a comparison
     * against a digest would have to be careful about how long it takes.</p>
     *
     * @param field what the pin says
     * @return whether it says nothing is pinned
     */
    private static boolean isNotPinned(String field) {
        return NOT_PINNED.equals(field);
    }

    /**
     * Every kind of record the store is populated with before the upgrade.
     *
     * <p>Read from the pin rather than listed here, so a kind somebody adds to the store is a kind
     * this suite populates on the day it is added — and the kind nobody remembers to populate is
     * the kind whose upgrade nobody proves.</p>
     *
     * @param root the repository root
     * @return the kinds, in the pin's own order
     */
    public static List<String> populatedKinds(Path root) {
        return read(root.resolve(PIN_FILE)).lines()
                .filter(line -> line.startsWith("kind = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .toList();
    }

    private static String valueOf(String document, String key) {
        return document.lines()
                .filter(line -> line.startsWith(key + " = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElse(NOT_PINNED);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
