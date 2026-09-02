// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The reactor as the build resolved it, rather than as the manifests declared it.
 *
 * <p>Every check that asks a question about this project's structure asks it here. Reading the
 * effective model rather than the declared text is what makes an edge inherited from dependency
 * management visible: a module that declares no dependency and inherits one has that dependency,
 * and a check reading the raw text would not see it.</p>
 *
 * <p>The raw model is kept beside the effective one, because two different questions need the two
 * different answers. Whether a module <em>has</em> a version is a question about the effective
 * model, which always has one; whether a module <em>declares</em> its own is a question about the
 * text it wrote, which is the one the aggregator owns.</p>
 */
public final class ReactorModel {

    /** Where every module writes the dependency set the build resolved for it. */
    private static final String RESOLVED_DEPENDENCIES = "target/resolved-dependencies.txt";

    /** Where every module writes the compile classpath the build resolved for it. */
    private static final String RESOLVED_CLASSPATH = "target/resolved-compile-classpath.txt";

    private final Path root;
    private final Model aggregator;
    private final SequencedMap<String, Model> effective;
    private final SequencedMap<String, Model> raw;

    private ReactorModel(Path root, Model aggregator, SequencedMap<String, Model> effective,
                         SequencedMap<String, Model> raw) {
        this.root = root;
        this.aggregator = aggregator;
        this.effective = effective;
        this.raw = raw;
    }

    /**
     * Reads the reactor rooted at an aggregator.
     *
     * @param root the directory holding the aggregator manifest
     * @return the reactor, with every module the aggregator lists resolved
     * @throws IllegalStateException if a manifest in the reactor does not resolve, because a check
     *     cannot report on a model the build itself could not build
     */
    public static ReactorModel at(Path root) {
        final Model aggregator = build(root.resolve("pom.xml"), Phase.EFFECTIVE);
        final SequencedMap<String, Model> effective = new LinkedHashMap<>();
        final SequencedMap<String, Model> raw = new LinkedHashMap<>();
        aggregator.getModules().forEach(module -> {
            final Path manifest = root.resolve(module).resolve("pom.xml");
            effective.put(module, build(manifest, Phase.EFFECTIVE));
            raw.put(module, build(manifest, Phase.RAW));
        });
        return new ReactorModel(root, aggregator, effective, raw);
    }

    /**
     * The directory the reactor is rooted at.
     *
     * @return the reactor root
     */
    public Path root() {
        return root;
    }

    /**
     * The aggregator's own effective model.
     *
     * @return the aggregator model
     */
    public Model aggregator() {
        return aggregator.clone();
    }

    /**
     * Every module the aggregator lists, in the order it lists them.
     *
     * @return the module directory names
     */
    public List<String> modules() {
        return List.copyOf(aggregator.getModules());
    }

    /**
     * One module's model as the build resolved it, with everything inherited already merged in.
     *
     * @param module the module directory name
     * @return the effective model
     * @throws IllegalArgumentException if the reactor holds no such module
     */
    public Model effective(String module) {
        return required(effective, module);
    }

    /**
     * One module's model as its own manifest declares it, with nothing inherited merged in.
     *
     * @param module the module directory name
     * @return the raw model
     * @throws IllegalArgumentException if the reactor holds no such module
     */
    public Model raw(String module) {
        return required(raw, module);
    }

    /**
     * Every dependency one module has, including the ones it has only through management.
     *
     * @param module the module directory name
     * @return the module's effective dependencies
     */
    public List<Dependency> dependencies(String module) {
        return List.copyOf(effective(module).getDependencies());
    }

    /**
     * The configuration one module gives a build plugin, as the build resolved it.
     *
     * @param module the module directory name
     * @param artifact the plugin's artifact identifier
     * @return the plugin's configuration, or nothing where the module configures no such plugin
     */
    public Optional<Xpp3Dom> pluginConfiguration(String module, String artifact) {
        final Model model = effective(module);
        if (model.getBuild() == null) {
            return Optional.empty();
        }
        return model.getBuild().getPlugins().stream()
                .filter(plugin -> artifact.equals(plugin.getArtifactId()))
                .map(Plugin::getConfiguration)
                .filter(Xpp3Dom.class::isInstance)
                .map(Xpp3Dom.class::cast)
                .findFirst();
    }

    /**
     * The dependency set the build actually resolved for one module, written by the build itself.
     *
     * @param module the module directory name
     * @return one row per resolved artifact, in the build's own sorted order
     * @throws IllegalStateException if the module has not been built, because a resolved set is
     *     evidence a build produced rather than a claim a manifest made
     */
    public List<ResolvedArtifact> resolvedDependencies(String module) {
        final Path evidence = root.resolve(module).resolve(RESOLVED_DEPENDENCIES);
        return readLines(evidence, module).stream()
                .map(String::strip)
                .map(ResolvedArtifact::parse)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * The compile classpath the build actually resolved for one module.
     *
     * @param module the module directory name
     * @return the classpath entries, in the build's own order
     * @throws IllegalStateException if the module has not been built
     */
    public List<Path> compileClasspath(String module) {
        final Path evidence = root.resolve(module).resolve(RESOLVED_CLASSPATH);
        return readLines(evidence, module).stream()
                .flatMap(line -> Arrays.stream(line.split(File.pathSeparator)))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .map(Path::of)
                .toList();
    }

    /**
     * One artifact the build resolved, as the build wrote it down.
     *
     * @param group the artifact's group identifier
     * @param artifact the artifact identifier
     * @param type the artifact's type
     * @param version the exact resolved version
     * @param scope the scope the artifact was resolved at
     */
    public record ResolvedArtifact(String group, String artifact, String type, String version,
                                   String scope) {

        /** Where the group identifier sits in a resolved row. */
        private static final int GROUP_FIELD = 0;

        /** Where the artifact identifier sits in a resolved row. */
        private static final int ARTIFACT_FIELD = 1;

        /** Where the artifact's type sits in a resolved row. */
        private static final int TYPE_FIELD = 2;

        /** Where the resolved version sits in a resolved row. */
        private static final int VERSION_FIELD = 3;

        /** Where the resolved scope sits in a resolved row. */
        private static final int SCOPE_FIELD = 4;

        /** How many fields a resolved row carries before anything the build appends to it. */
        private static final int COORDINATE_FIELDS = 5;

        /**
         * Reads one row of the build's own dependency listing.
         *
         * @param row the row, as the build wrote it
         * @return the artifact, or nothing where the row is a heading rather than an artifact
         */
        public static Optional<ResolvedArtifact> parse(String row) {
            final String[] parts = row.split(":");
            if (parts.length < COORDINATE_FIELDS) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedArtifact(parts[GROUP_FIELD], parts[ARTIFACT_FIELD],
                    parts[TYPE_FIELD], parts[VERSION_FIELD],
                    parts[SCOPE_FIELD].split(" ")[0]));
        }

        /**
         * The artifact's coordinate without its version or scope.
         *
         * @return the group and artifact identifiers, joined
         */
        public String coordinate() {
            return group + ":" + artifact;
        }
    }

    private static List<String> readLines(Path evidence, String module) {
        if (!Files.isRegularFile(evidence)) {
            throw new IllegalStateException("module " + module + " has not been built: " + evidence
                    + " is absent; run the reactor build before reading what it resolved");
        }
        try {
            return Files.readAllLines(evidence);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Model required(SequencedMap<String, Model> models, String module) {
        final Model model = models.get(module);
        if (model == null) {
            throw new IllegalArgumentException("the reactor holds no module named " + module);
        }
        return model;
    }

    private enum Phase { EFFECTIVE, RAW }

    private static Model build(Path manifest, Phase phase) {
        final ModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
        final DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(manifest.toFile());
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(phase == Phase.RAW);
        request.setSystemProperties(System.getProperties());
        try {
            final ModelBuildingResult result = builder.build(request);
            return phase == Phase.RAW ? result.getRawModel() : result.getEffectiveModel();
        } catch (final ModelBuildingException failure) {
            throw new IllegalStateException("the build model at " + manifest + " does not resolve",
                    failure);
        }
    }
}
