// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The Java release this repository compiles to, checked against the bytes rather than the property.
 *
 * <p>A compiler release level set in one place and a class file that actually carries that level
 * are different claims. The second is the one an author instance enforces when it loads the bundle,
 * and it is the one worth asserting — so this check opens every produced artifact and reads the
 * class-file major version out of every class in it.</p>
 *
 * <p>Two model-level rules sit beside it, because both are ways the bytes could quietly stop
 * matching: a module that declares its own release, source, or target level compiles to something
 * the aggregator did not choose, and a module that turns warnings-as-errors off compiles code
 * nobody was told about.</p>
 */
public final class BytecodeContract {

    /** The one release level this repository compiles to. */
    public static final int DECLARED_RELEASE = 21;

    /** What a Java release adds to reach its class-file major version. */
    private static final int CLASS_FILE_MAJOR_OFFSET = 44;

    /** The property the aggregator owns, and the two nothing may set at all. */
    private static final String RELEASE_PROPERTY = "maven.compiler.release";

    private static final List<String> FORBIDDEN_PROPERTIES =
            List.of("maven.compiler.source", "maven.compiler.target");

    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";

    private static final String LINT_EVERYTHING = "-Xlint:all";

    /** Compiler arguments that put a warning back to being only a warning. */
    private static final List<String> WARNING_SUPPRESSING_ARGUMENTS =
            List.of("-nowarn", "-Xlint:none");

    private BytecodeContract() {
    }

    /**
     * The class-file major version the declared release produces.
     *
     * @return the major version every class in every produced artifact must carry
     */
    public static int declaredClassFileMajorVersion() {
        return DECLARED_RELEASE + CLASS_FILE_MAJOR_OFFSET;
    }

    /**
     * Reads the class-file major version out of every class in a produced artifact.
     *
     * @param file the repository-relative path to name in a finding
     * @param artifact the produced artifact
     * @return one finding per class whose bytes do not carry the declared version
     */
    public static PolicyReport inArtifact(String file, BuiltArtifact artifact) {
        final int expected = declaredClassFileMajorVersion();
        final List<PolicyFinding> findings = new ArrayList<>();
        artifact.classFileMajorVersions().forEach((entry, major) -> {
            if (major != expected) {
                findings.add(PolicyFinding.inFile(file, "bytecode-version",
                        entry + " carries class-file version " + major + " rather than " + expected));
            }
        });
        return PolicyReport.of(findings);
    }

    /**
     * Refuses a module that decides its own bytecode target, and a source or target level anywhere.
     *
     * @param reactor the reactor to read
     * @return one finding per module that declares a level the aggregator owns
     */
    public static PolicyReport releaseDeclarations(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        forbiddenPropertyFindings(reactor.aggregator(), "pom.xml", findings);
        if (!String.valueOf(DECLARED_RELEASE)
                .equals(reactor.aggregator().getProperties().getProperty(RELEASE_PROPERTY))) {
            findings.add(PolicyFinding.inFile("pom.xml", "release-declaration",
                    RELEASE_PROPERTY + " is not " + DECLARED_RELEASE + " in the aggregator"));
        }
        reactor.modules().forEach(module -> {
            final String file = module + "/pom.xml";
            final Model raw = reactor.raw(module);
            if (raw.getProperties().getProperty(RELEASE_PROPERTY) != null) {
                findings.add(PolicyFinding.inFile(file, "release-declaration",
                        module + " declares its own " + RELEASE_PROPERTY));
            }
            forbiddenPropertyFindings(raw, file, findings);
            compilerArgumentFindings(reactor, module, file, findings);
        });
        return PolicyReport.of(findings);
    }

    /**
     * Refuses a module that compiles with warnings that are not errors, or with linting reduced.
     *
     * @param reactor the reactor to read
     * @return one finding per module that weakened either
     */
    public static PolicyReport warningsAsErrors(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        reactor.modules().forEach(module -> {
            final String file = module + "/pom.xml";
            final Optional<Xpp3Dom> configuration =
                    reactor.pluginConfiguration(module, COMPILER_PLUGIN);
            if (configuration.isEmpty()) {
                findings.add(PolicyFinding.inFile(file, "warnings-as-errors",
                        module + " inherits no compiler configuration at all"));
                return;
            }
            final Xpp3Dom compiler = configuration.get();
            if (!"true".equals(childValue(compiler, "failOnWarning"))) {
                findings.add(PolicyFinding.inFile(file, "warnings-as-errors",
                        module + " does not fail on a warning"));
            }
            if (!compilerArguments(compiler).contains(LINT_EVERYTHING)) {
                findings.add(PolicyFinding.inFile(file, "warnings-as-errors",
                        module + " does not compile with " + LINT_EVERYTHING));
            }
        });
        return PolicyReport.of(findings);
    }

    private static void compilerArgumentFindings(ReactorModel reactor, String module, String file,
                                                 List<PolicyFinding> findings) {
        reactor.pluginConfiguration(module, COMPILER_PLUGIN)
                .map(BytecodeContract::compilerArguments)
                .orElseGet(List::of)
                .stream()
                .filter(WARNING_SUPPRESSING_ARGUMENTS::contains)
                .forEach(argument -> findings.add(PolicyFinding.inFile(file, "warnings-as-errors",
                        module + " compiles with " + argument)));
    }

    private static void forbiddenPropertyFindings(Model model, String file,
                                                  List<PolicyFinding> findings) {
        FORBIDDEN_PROPERTIES.stream()
                .filter(property -> model.getProperties().getProperty(property) != null)
                .map(property -> PolicyFinding.inFile(file, "release-declaration",
                        property + " is set where only " + RELEASE_PROPERTY + " may be"))
                .forEach(findings::add);
    }

    private static List<String> compilerArguments(Xpp3Dom configuration) {
        final Xpp3Dom arguments = configuration.getChild("compilerArgs");
        if (arguments == null) {
            return List.of();
        }
        return Arrays.stream(arguments.getChildren())
                .map(Xpp3Dom::getValue)
                .filter(value -> value != null)
                .map(String::strip)
                .toList();
    }

    private static String childValue(Xpp3Dom configuration, String name) {
        final Xpp3Dom child = configuration.getChild(name);
        return child == null || child.getValue() == null ? "" : child.getValue().strip();
    }
}
