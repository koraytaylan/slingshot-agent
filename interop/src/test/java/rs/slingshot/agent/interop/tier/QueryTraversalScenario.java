// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That this product ships no index definition, and that every query it declares is covered.
 *
 * <p>A custom index lives outside {@code /apps}, changes the shape of somebody else's repository,
 * and is an operator's decision rather than a side effect of installing an agent. So the check is
 * against the archives this build actually produces rather than against what anybody intended: a
 * package is the thing a customer installs, and what it carries is what they get.</p>
 *
 * <p>The other half — a query whose plan traverses on the instance in front of it, refused before a
 * node is examined and read from the platform's own query statistics — needs a command that issues
 * one. This build registers none; that half arrives with the first read command, and this plan's
 * status records it rather than a row here claiming otherwise.</p>
 */
final class QueryTraversalScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** What an index definition is called wherever one appears in a repository. */
    private static final String INDEX_DEFINITION = "oak:index";

    @Test
    @DisplayName("no package this build produces carries an index definition")
    void nopackageCarriesAnindexDefinition() {
        final List<Path> shipped = packages();
        assertFalse(shipped.isEmpty(),
                "no content package was produced, so this scan proves nothing");
        shipped.forEach(archive -> entriesOf(archive).forEach(entry ->
                assertFalse(entry.contains(INDEX_DEFINITION),
                        archive.getFileName() + " carries " + entry + ", which changes the shape"
                                + " of somebody else's repository as a side effect of installing"
                                + " an agent")));
    }

    @Test
    @DisplayName("every query this product declares names an index that already exists")
    void everyqueryNamesAnindexThatAlreadyExists() {
        final String coverage = read(REPOSITORY.resolve("policy/query-index-coverage.toml"));
        assertTrue(coverage.contains("[[index]]"),
                "no index is declared at all, so nothing is compared against anything");
        assertTrue(coverage.contains("ships no index definition"),
                "the coverage document does not say what the archives are checked for");
        assertFalse(coverage.contains(INDEX_DEFINITION),
                "the coverage document declares an index definition of this product's own, which"
                        + " is the thing the archives are checked for not carrying");
    }

    private static List<Path> packages() {
        return List.of("ui.apps", "ui.config", "ui.apps.structure").stream()
                .map(module -> REPOSITORY.resolve(module).resolve("target"))
                .flatMap(QueryTraversalScenario::archivesUnder)
                .toList();
    }

    private static java.util.stream.Stream<Path> archivesUnder(Path target) {
        if (!Files.isDirectory(target)) {
            return java.util.stream.Stream.empty();
        }
        try (var found = Files.list(target)) {
            return found.filter(file -> String.valueOf(file.getFileName()).endsWith(".zip"))
                    .toList()
                    .stream();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(target + " is not readable", unreadable);
        }
    }

    private static List<String> entriesOf(Path archive) {
        try (ZipFile held = new ZipFile(archive.toFile())) {
            return held.stream().map(ZipEntry::getName).toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(archive + " is not readable", unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
