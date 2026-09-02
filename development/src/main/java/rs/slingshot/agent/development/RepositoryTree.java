// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the repository being checked is.
 *
 * <p>Every check reads the repository it is checking rather than the directory it happened to be
 * started in, and the build declares which that is. A check that walked up from its working
 * directory would read a different tree depending on how it was invoked, which is the one thing a
 * repository policy must never do.</p>
 */
public final class RepositoryTree {

    /** The property the build sets to the root of the reactor being checked. */
    public static final String ROOT_PROPERTY = "slingshot.repository.root";

    private RepositoryTree() {
    }

    /**
     * The root of the repository this run is checking.
     *
     * @return the absolute, normalised repository root
     * @throws IllegalStateException if the build did not declare it, or declared a directory that
     *     is not a reactor, because a check that guessed would be checking something else
     */
    public static Path locate() {
        final String declared = System.getProperty(ROOT_PROPERTY);
        if (declared == null || declared.isBlank()) {
            throw new IllegalStateException("the repository root is not declared in "
                    + ROOT_PROPERTY + "; run this through the build");
        }
        final Path root = Path.of(declared).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalStateException(root + " is not a reactor: it holds no aggregator");
        }
        return root;
    }

    /**
     * The text a repository-owned file carries.
     *
     * @param file the file to read
     * @return its text, read as this repository's own encoding
     * @throws UncheckedIOException if the file cannot be read, because a check that silently read
     *     nothing would report that nothing was wrong
     */
    public static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Every file under a tree that no repository rule ignores.
     *
     * @param tree the tree to walk
     * @param suffix the file suffix to keep
     * @return the matching files, sorted, with build output, planning prose, and fixtures left out
     */
    public static List<Path> filesUnder(Path tree, String suffix) {
        if (!Files.isDirectory(tree)) {
            return List.of();
        }
        final List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes ignored) {
                    return IGNORED_DIRECTORIES.contains(String.valueOf(directory.getFileName()))
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && file.toString().endsWith(suffix)) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ignored) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return found.stream().sorted().toList();
    }

    /**
     * Whether a path relative to the tree being read is one no repository rule applies to: build
     * output, version-control state, planning prose, or a fixture written to be refused.
     *
     * @param relative the path, relative to the tree being read
     * @return {@code true} where no rule reads it
     */
    public static boolean isIgnored(Path relative) {
        for (final Path segment : relative) {
            final String name = segment.toString();
            if (IGNORED_DIRECTORIES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** Directories no repository rule reads, whatever they hold. */
    private static final List<String> IGNORED_DIRECTORIES =
            List.of("target", ".git", ".makina", ".dependency-cache", "docs", "fixtures");
}
