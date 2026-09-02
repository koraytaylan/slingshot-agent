// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The structure this project is allowed to have.
 *
 * <p>Every rule here reads the effective build model rather than the declared text, so a property
 * inherited from the aggregator is seen exactly as the build sees it. The one exception is
 * deliberate: whether a module <em>declares</em> its own version is a question about the text it
 * wrote, and the effective model always has one.</p>
 */
public final class ProjectScaffold {

    /** The exact modules this product has, each with the packaging its row declares. */
    private static final SequencedMap<String, String> DECLARED_MODULES = declaredModules();

    /** The modules Adobe's archetype generates that this product does not have. */
    private static final List<String> PRUNED_MODULES =
            List.of("ui.frontend", "ui.content", "dispatcher", "ui.tests");

    /** The one package root every Java source in this repository sits under. */
    public static final String PACKAGE_ROOT = "rs.slingshot.agent";

    /** The one group identifier, which is the package root's own prefix. */
    public static final String GROUP_IDENTIFIER = "rs.slingshot";

    /** How a version that moves on its own is spelled, whatever case somebody wrote it in. */
    private static final Pattern MOVING_VERSION = Pattern.compile("latest", Pattern.CASE_INSENSITIVE);

    private ProjectScaffold() {
    }

    private static SequencedMap<String, String> declaredModules() {
        final SequencedMap<String, String> rows = new LinkedHashMap<>();
        rows.put("core", "jar");
        rows.put("aem", "jar");
        rows.put("ui.apps.structure", "content-package");
        rows.put("ui.apps", "content-package");
        rows.put("ui.config", "content-package");
        rows.put("all", "content-package");
        rows.put("development", "jar");
        rows.put("interop", "jar");
        return rows;
    }

    /**
     * The modules this product declares, each with the packaging its row gives it.
     *
     * @return the declared rows, in the reactor's own order
     */
    public static SequencedMap<String, String> declaredRows() {
        return new LinkedHashMap<>(DECLARED_MODULES);
    }

    /**
     * The modules the archetype generates that this product removed.
     *
     * @return the pruned module names
     */
    public static List<String> prunedModules() {
        return PRUNED_MODULES;
    }

    /**
     * Whether the reactor is exactly the modules this product declares.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module that is absent, extra, or of the wrong packaging
     */
    public static List<String> moduleSetFindings(ReactorModel reactor) {
        final List<String> findings = new ArrayList<>();
        DECLARED_MODULES.forEach((name, packaging) -> {
            if (!reactor.modules().contains(name)) {
                findings.add("module " + name + " is declared by the product and absent from the reactor");
                return;
            }
            final Model module = reactor.effective(name);
            if (!packaging.equals(module.getPackaging())) {
                findings.add("module " + name + " produces " + module.getPackaging()
                        + " where its row declares " + packaging);
            }
        });
        reactor.aggregator().getModules().stream()
                .filter(name -> !DECLARED_MODULES.containsKey(name))
                .map(name -> "module " + name
                        + " is in the reactor and in no declared row")
                .forEach(findings::add);
        return List.copyOf(findings);
    }

    /**
     * Whether a module the archetype generates and this product removed has reappeared.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per pruned module named by the aggregator, an embed, or a filter
     */
    public static List<String> prunedModuleFindings(ReactorModel reactor) {
        final List<String> findings = new ArrayList<>();
        for (final String pruned : PRUNED_MODULES) {
            reactor.aggregator().getModules().stream()
                    .filter(name -> name.equals(pruned))
                    .map(name -> "pruned module " + name + " is listed by the aggregator")
                    .forEach(findings::add);
            reactor.modules().forEach(name ->
                    reactor.pluginConfiguration(name, "filevault-package-maven-plugin")
                            .map(Xpp3Dom::toString)
                            .filter(rendered -> rendered.contains(pruned))
                            .map(rendered -> "pruned module " + pruned
                                    + " is named by the content package configuration of " + name)
                            .ifPresent(findings::add));
        }
        return List.copyOf(findings);
    }

    /**
     * Whether every module takes its coordinates from the aggregator.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module that declares its own version or group identifier
     */
    public static List<String> coordinateOwnershipFindings(ReactorModel reactor) {
        final List<String> findings = new ArrayList<>();
        reactor.modules().forEach(name -> {
            final Model raw = reactor.raw(name);
            if (raw.getVersion() != null) {
                findings.add("module " + name + " declares its own version " + raw.getVersion()
                        + " where the aggregator owns it");
            }
            if (raw.getGroupId() != null) {
                findings.add("module " + name + " declares its own group identifier " + raw.getGroupId()
                        + " where the aggregator owns it");
            }
        });
        return List.copyOf(findings);
    }

    /**
     * Whether the group identifier is the package root's own prefix.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding where the coordinate and the source tree could drift apart
     */
    public static List<String> namespaceFindings(ReactorModel reactor) {
        final String group = reactor.aggregator().getGroupId();
        final List<String> findings = new ArrayList<>();
        if (!GROUP_IDENTIFIER.equals(group)) {
            findings.add("group identifier " + group + " is not the declared " + GROUP_IDENTIFIER);
        }
        if (!PACKAGE_ROOT.equals(group) && !PACKAGE_ROOT.startsWith(group + ".")) {
            findings.add("group identifier " + group + " is not the prefix of package root " + PACKAGE_ROOT);
        }
        return List.copyOf(findings);
    }

    /**
     * Whether any bundle declares an exported package by instruction rather than by annotation.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module that declares one
     */
    public static List<String> exportInstructionFindings(ReactorModel reactor) {
        final List<String> findings = new ArrayList<>();
        reactor.modules().forEach(name ->
                reactor.pluginConfiguration(name, "bnd-maven-plugin")
                        .map(Xpp3Dom::toString)
                        .filter(rendered -> rendered.contains("Export-Package"))
                        .map(ignored -> "module " + name
                                + " declares an exported package by instruction rather than by annotation")
                        .ifPresent(findings::add));
        for (final Path bnd : filesUnder(reactor.root(), ".bnd")) {
            if (read(bnd).contains("Export-Package")) {
                findings.add(reactor.root().relativize(bnd)
                        + " declares an exported package by manifest instruction rather than by annotation");
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Whether every Java source in a tree sits under the one package root.
     *
     * @param tree the tree to read
     * @return one finding per source outside the root
     */
    public static List<String> packageRootFindings(Path tree) {
        final List<String> findings = new ArrayList<>();
        for (final Path source : filesUnder(tree, ".java")) {
            final String declared = declaredPackage(source);
            if (!PACKAGE_ROOT.equals(declared) && !declared.startsWith(PACKAGE_ROOT + ".")) {
                findings.add(tree.relativize(source) + " declares package " + declared
                        + " outside the package root " + PACKAGE_ROOT);
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Whether the provenance record names exact versions and a reason for every removal.
     *
     * @param document the provenance document
     * @return one finding per range, unrecorded removal, or removal with no reason
     */
    public static List<String> provenanceFindings(Path document) {
        final TomlParseResult provenance = parse(document);
        final List<String> findings = new ArrayList<>();
        exactVersionFinding("archetype.version", provenance.getString("archetype.version"))
                .ifPresent(findings::add);
        exactVersionFinding("wrapper.maven", provenance.getString("wrapper.maven"))
                .ifPresent(findings::add);
        exactVersionFinding("wrapper.wrapper", provenance.getString("wrapper.wrapper"))
                .ifPresent(findings::add);
        final TomlArray removals = provenance.getArray("removed_module");
        final List<TomlTable> rows = removals == null
                ? List.of()
                : IntStream.range(0, removals.size()).mapToObj(removals::getTable).toList();
        final List<String> named = rows.stream()
                .map(removal -> Optional.ofNullable(removal.getString("name")).orElse("<unnamed>"))
                .toList();
        rows.forEach(removal -> {
            final String name = Optional.ofNullable(removal.getString("name")).orElse("<unnamed>");
            final String reason = removal.getString("reason");
            if (reason == null || reason.isBlank()) {
                findings.add("removed module " + name + " records no reason");
            }
            if (!PRUNED_MODULES.contains(name)) {
                findings.add("removed module " + name + " is not one the archetype generates");
            }
        });
        PRUNED_MODULES.stream()
                .filter(pruned -> !named.contains(pruned))
                .map(pruned -> "pruned module " + pruned + " is removed and unrecorded")
                .forEach(findings::add);
        return List.copyOf(findings);
    }

    private static Optional<String> exactVersionFinding(String key, String value) {
        if (value == null || value.isBlank()) {
            return Optional.of("provenance key " + key + " is absent");
        }
        final boolean range = value.indexOf('[') >= 0 || value.indexOf('(') >= 0
                || value.indexOf(',') >= 0 || value.indexOf('*') >= 0
                || MOVING_VERSION.matcher(value).find();
        if (!range) {
            return Optional.empty();
        }
        return Optional.of("provenance key " + key
                + " names a range rather than an exact version: " + value);
    }

    // --- reading the repository ------------------------------------------------------------

    private static String declaredPackage(Path source) {
        return read(source).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .map(line -> line.substring("package ".length()))
                .map(declared -> declared.replace(";", "").strip())
                .orElse("<default>");
    }

    private static List<Path> filesUnder(Path tree, String suffix) {
        if (!Files.isDirectory(tree)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> Optional.ofNullable(path.getFileName())
                            .map(Path::toString)
                            .filter(name -> name.endsWith(suffix))
                            .isPresent())
                    .filter(path -> !isBuildOutputOrFixture(tree.relativize(path)))
                    .sorted()
                    .toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Whether a path relative to the tree being read is one no repository rule applies to: build
     * output, planning prose, or a fixture written to be refused.
     */
    private static boolean isBuildOutputOrFixture(Path relative) {
        for (final Path segment : relative) {
            final String name = segment.toString();
            if ("target".equals(name) || "fixtures".equals(name) || "docs".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static TomlParseResult parse(Path document) {
        try {
            final TomlParseResult result = Toml.parse(document);
            if (!result.errors().isEmpty()) {
                throw new IllegalStateException(document + " does not parse: " + result.errors());
            }
            return result;
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
