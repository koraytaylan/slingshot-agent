// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The practices that decide whether this survives the next platform upgrade.
 *
 * <p>Each rule is a documented practice, each has a version of this code that would pass every
 * other check here and still be wrong at the next upgrade, and each is decidable by parsing.</p>
 *
 * <p>The deprecation list is checked against the platform artifact rather than believed. A row
 * naming a member the platform does not declare deprecated is refused, so the list cannot grow into
 * a place to record opinions, and a row whose deprecation has been withdrawn cannot sit there
 * describing something that stopped being true.</p>
 */
public final class AdobePracticePolicy {

    private static final String POLICY_FILE = "policy/adobe-practice.toml";

    private static final String DEPRECATED_ROWS = "deprecated_member";

    /** How a component is declared, and what a component may not hold. */
    private static final String COMPONENT_ANNOTATION = "Component";

    /** The calls that reach a service the container cannot see. */
    private static final List<String> MANUAL_LOOKUPS =
            List.of("getService", "getServiceReference", "getBundleContext");

    /** The types whose instances must be closed on every path. */
    private static final List<String> CLOSEABLE_PLATFORM_TYPES =
            List.of("ResourceResolver", "Session", "JackrabbitSession");

    /** How a resolver or a session is obtained. */
    private static final List<String> SESSION_FACTORIES = List.of("getServiceResourceResolver",
            "getResourceResolver", "loginService", "login", "adaptTo");

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final List<DeprecatedMember> deprecated;

    private AdobePracticePolicy(List<DeprecatedMember> deprecated) {
        this.deprecated = deprecated;
    }

    /**
     * One member the platform declares deprecated, and what to use instead.
     *
     * @param type the declaring type
     * @param member the member's own name
     * @param replacement what to use instead
     * @param reason why this repository refuses it
     */
    public record DeprecatedMember(String type, String member, String replacement, String reason) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(AdobePracticePolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the practice policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("adobe-practice")
                .answer("resolver.close_in_trailing_block_permitted")
                .text("resolver.reason")
                .answer("repository_access.direct_access_permitted_without_reason")
                .text("repository_access.reason")
                .answer("component.mutable_instance_state_permitted")
                .answer("component.manual_service_lookup_permitted")
                .answer("component.synchronisation_permitted")
                .text("component.reason")
                .rows(DEPRECATED_ROWS, row -> row.text("type").text("member").text("replacement")
                        .text("reason"))
                .build();
    }

    /**
     * Reads the policy this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readPolicy(root.resolve(POLICY_FILE));
    }

    /**
     * Reads a policy document from wherever it sits.
     *
     * @param policy the policy document
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome readPolicy(Path policy) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(policy, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final Optional<String> permitted = List.of("resolver.close_in_trailing_block_permitted",
                        "repository_access.direct_access_permitted_without_reason",
                        "component.mutable_instance_state_permitted",
                        "component.manual_service_lookup_permitted",
                        "component.synchronisation_permitted").stream()
                .filter(document::answer)
                .findFirst();
        if (permitted.isPresent()) {
            return new Refused("the policy permits " + permitted.get());
        }
        final List<DeprecatedMember> deprecated = document.rows(DEPRECATED_ROWS).stream()
                .map(row -> new DeprecatedMember(row.text("type"), row.text("member"),
                        row.text("replacement"), row.text("reason")))
                .toList();
        if (deprecated.stream().anyMatch(row -> row.replacement().isBlank())) {
            return new Refused("a deprecated member is refused with no replacement named");
        }
        return new Loaded(new AdobePracticePolicy(deprecated));
    }

    /**
     * Every member the policy refuses, with its replacement.
     *
     * @return the deprecated members, in the policy's own order
     */
    public List<DeprecatedMember> deprecatedMembers() {
        return Collections.unmodifiableList(deprecated);
    }

    /**
     * Holds every main-source file in a tree to the lifecycle practices.
     *
     * @param root the repository root
     * @return one finding per practice a source breaks
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .forEach(source ->
                        findings.addAll(inFile(root.relativize(source).toString(), source)));
        return PolicyReport.of(findings);
    }

    /**
     * Holds one file to the lifecycle practices.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per practice the file breaks
     */
    public List<PolicyFinding> inFile(String name, Path source) {
        final CompilationUnit unit = parse(source);
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(resolverFindings(name, unit));
        findings.addAll(componentFindings(name, unit));
        findings.addAll(deprecationFindings(name, unit));
        return List.copyOf(findings);
    }

    private static List<PolicyFinding> resolverFindings(String name, CompilationUnit unit) {
        return unit.findAll(VariableDeclarationExpr.class).stream()
                .filter(declaration -> declaration.getVariables().stream()
                        .anyMatch(variable -> CLOSEABLE_PLATFORM_TYPES.contains(
                                variable.getType().asString())))
                .filter(declaration -> declaration.getVariables().stream()
                        .anyMatch(variable -> variable.getInitializer()
                                .filter(AdobePracticePolicy::obtainsASession).isPresent()))
                .filter(declaration -> !isInsideResourceManagement(declaration))
                .map(declaration -> finding(name, declaration, "unclosed-resolver",
                        declaration.getVariables().getFirst()
                                .map(variable -> variable.getNameAsString()).orElse("<unnamed>")
                                + " is obtained outside the language's own resource management, so "
                                + "an early return above the close skips it"))
                .toList();
    }

    private static List<PolicyFinding> componentFindings(String name, CompilationUnit unit) {
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(TypeDeclaration.class).stream()
                .map(found -> (TypeDeclaration<?>) found)
                .filter(AdobePracticePolicy::isComponent)
                .forEach(component -> {
                    component.getFields().stream()
                            .filter(field -> !field.isStatic() && !field.isFinal())
                            .flatMap(field -> field.getVariables().stream())
                            .map(variable -> finding(name, variable, "component-mutable-state",
                                    variable.getNameAsString()
                                            + " is state every caller of this component shares"))
                            .forEach(findings::add);
                    component.findAll(SynchronizedStmt.class).stream()
                            .map(statement -> finding(name, statement, "component-synchronisation",
                                    component.getNameAsString()
                                            + " serialises every caller through one instance"))
                            .forEach(findings::add);
                    component.findAll(MethodCallExpr.class).stream()
                            .filter(call -> MANUAL_LOOKUPS.contains(call.getNameAsString()))
                            .map(call -> finding(name, call, "component-manual-lookup",
                                    call.getNameAsString()
                                            + " reaches a service the container cannot see"))
                            .forEach(findings::add);
                });
        return findings;
    }

    private List<PolicyFinding> deprecationFindings(String name, CompilationUnit unit) {
        return unit.findAll(MethodCallExpr.class).stream()
                .flatMap(call -> deprecated.stream()
                        .filter(row -> row.member().equals(call.getNameAsString()))
                        .map(row -> finding(name, call, "deprecated-member",
                                row.member() + " is deprecated; use " + row.replacement())))
                .toList();
    }

    /**
     * Holds every declared deprecation to the platform artifact itself.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per row naming a member the platform does not declare deprecated, and per
     *     row naming a type the platform does not carry at all
     */
    public PolicyReport againstThePlatform(ReactorModel reactor) {
        final List<URL> classpath = reactor.compileClasspath("aem").stream()
                .filter(Files::isRegularFile)
                .map(AdobePracticePolicy::asUrl)
                .toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        try (URLClassLoader platform = new URLClassLoader(classpath.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            deprecated.forEach(row -> platformFinding(platform, row).ifPresent(findings::add));
        } catch (final IOException failure) {
            throw new IllegalStateException("the platform artifact could not be read", failure);
        }
        return PolicyReport.of(findings);
    }

    private static Optional<PolicyFinding> platformFinding(ClassLoader platform, DeprecatedMember row) {
        final Class<?> declaring;
        try {
            declaring = Class.forName(row.type(), false, platform);
        } catch (final ClassNotFoundException absent) {
            return Optional.of(PolicyFinding.inFile(POLICY_FILE, "deprecation-list",
                    row.type() + " is refused and the platform carries no such type"));
        }
        final boolean declaresIt = declaring.isAnnotationPresent(Deprecated.class)
                || Arrays.stream(declaring.getDeclaredMethods())
                        .filter(method -> method.getName().equals(row.member()))
                        .anyMatch(method -> method.isAnnotationPresent(Deprecated.class));
        if (declaresIt) {
            return Optional.empty();
        }
        return Optional.of(PolicyFinding.inFile(POLICY_FILE, "deprecation-list",
                row.type() + "#" + row.member() + " is refused and the platform does not declare it"
                        + " deprecated"));
    }

    private static URL asUrl(Path artifact) {
        try {
            return artifact.toUri().toURL();
        } catch (final MalformedURLException failure) {
            throw new IllegalStateException(artifact + " is not a readable location", failure);
        }
    }

    private static boolean isComponent(TypeDeclaration<?> type) {
        return type.getAnnotationByName(COMPONENT_ANNOTATION).isPresent();
    }

    private static boolean obtainsASession(Expression initialiser) {
        return initialiser.isMethodCallExpr()
                && SESSION_FACTORIES.contains(initialiser.asMethodCallExpr().getNameAsString());
    }

    private static boolean isInsideResourceManagement(Node declaration) {
        return declaration.getParentNode().filter(TryStmt.class::isInstance).isPresent();
    }

    private static boolean isTestSource(Path relative) {
        for (final Path segment : relative) {
            if ("test".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static CompilationUnit parse(Path source) {
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(RepositoryTree.text(source));
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + parsed.getProblems());
        }
        return parsed.getResult().orElseThrow();
    }

    private static PolicyFinding finding(String file, Node node, String rule, String symbol) {
        return new PolicyFinding(file,
                node.getBegin().map(position -> position.line).orElse(PolicyFinding.NO_LINE),
                rule, symbol);
    }
}
