// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * What a type in this repository may be called, and what shape it may have.
 *
 * <p>The naming rule is the one with an argument behind it. A type called {@code SomethingImpl}
 * says that somebody needed a second name and reached for the first suffix available; it pairs
 * one-to-one with an interface that then had no reason to exist. So the suffix is refused
 * everywhere, and an interface this repository provides exactly one implementation of has that
 * implementation named {@code Default} followed by the interface's own name — while an interface
 * with several forbids any of them being called {@code Default}, because a default among equals is
 * a decision nobody made.</p>
 */
public final class ApiShapePolicy {

    private static final String POLICY_FILE = "policy/api-shape.toml";

    private static final String EXEMPTION_ROWS = "exemption";

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final String forbiddenSuffix;
    private final String soleImplementationPrefix;
    private final List<ExemptionRow> exemptions;

    private ApiShapePolicy(String forbiddenSuffix, String soleImplementationPrefix,
                           List<ExemptionRow> exemptions) {
        this.forbiddenSuffix = forbiddenSuffix;
        this.soleImplementationPrefix = soleImplementationPrefix;
        this.exemptions = exemptions;
    }

    /**
     * A shape rule that does not apply to one kind of declaration.
     *
     * @param rule the rule the exemption belongs to
     * @param kind what the exemption covers
     * @param reason why the rule does not apply there
     */
    public record ExemptionRow(String rule, String kind, String reason) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(ApiShapePolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the shape policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("api-shape")
                .text("naming.forbidden_suffix")
                .text("naming.sole_implementation_prefix")
                .answer("visibility.public_fields_permitted")
                .answer("immutability.non_final_fields_permitted")
                .rows(EXEMPTION_ROWS, row -> row.text("rule").text("kind").text("reason"))
                .build();
    }

    /**
     * Reads the policy this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(root.resolve(POLICY_FILE), shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        if (document.answer("visibility.public_fields_permitted")) {
            return new Refused("the policy permits a field anybody can reach");
        }
        if (document.answer("immutability.non_final_fields_permitted")) {
            return new Refused("the policy permits a field anybody can change");
        }
        final List<ExemptionRow> exemptions = document.rows(EXEMPTION_ROWS).stream()
                .map(row -> new ExemptionRow(row.text("rule"), row.text("kind"), row.text("reason")))
                .toList();
        if (exemptions.stream().anyMatch(row -> row.reason().isBlank())) {
            return new Refused("an exemption records no reason");
        }
        return new Loaded(new ApiShapePolicy(document.text("naming.forbidden_suffix"),
                document.text("naming.sole_implementation_prefix"), exemptions));
    }

    /**
     * Every exemption the policy records.
     *
     * @return the exemption rows, in the policy's own order
     */
    public List<ExemptionRow> exemptions() {
        return Collections.unmodifiableList(exemptions);
    }

    /**
     * Holds every main-source type in a tree to the naming and shape rules.
     *
     * @param root the repository root
     * @return one finding per type or field that breaks one
     */
    public PolicyReport across(Path root) {
        final List<Path> sources = RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        sources.forEach(source ->
                findings.addAll(inFile(root.relativize(source).toString(), source)));
        findings.addAll(implementationNamingFindings(root, sources));
        return PolicyReport.of(findings);
    }

    /**
     * Holds one file's types to the naming and shape rules.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per type or field that breaks one
     */
    public List<PolicyFinding> inFile(String name, Path source) {
        final CompilationUnit unit = parse(source);
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(TypeDeclaration.class).forEach(type -> {
            if (type.getNameAsString().endsWith(forbiddenSuffix)) {
                findings.add(finding(name, type, "impl-suffix", type.getNameAsString()));
            }
            if (isPublic(type) && !isImplicitlyClosed(type) && !isSealed(type) && !isFinal(type)
                    && !isInterface(type)) {
                findings.add(finding(name, type, "extension-point", type.getNameAsString()));
            }
        });
        unit.findAll(FieldDeclaration.class).forEach(field -> {
            final String declared = field.getVariables().getFirst()
                    .map(variable -> variable.getNameAsString()).orElse("<unnamed>");
            if (field.hasModifier(Modifier.Keyword.PUBLIC) && !isNamedConstant(field)) {
                findings.add(finding(name, field, "public-field", declared));
            }
            if (!field.isFinal()) {
                findings.add(finding(name, field, "non-final-field", declared));
            }
        });
        return Collections.unmodifiableList(findings);
    }

    /**
     * Holds every interface this repository declares to the implementation-naming rule.
     *
     * @param root the repository root
     * @param sources the main sources to read
     * @return one finding per sole implementation not named for its interface, and per member of a
     *     several-implementation interface that claims to be the default
     */
    public List<PolicyFinding> implementationNamingFindings(Path root, List<Path> sources) {
        final SequencedMap<String, List<String>> implementations = new LinkedHashMap<>();
        final SequencedMap<String, String> declaringFile = new LinkedHashMap<>();
        final java.util.Set<String> expectingMany = new java.util.LinkedHashSet<>();
        sources.forEach(source -> {
            final CompilationUnit unit = parse(source);
            unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(ClassOrInterfaceDeclaration::isInterface)
                    .forEach(declared -> {
                        implementations.putIfAbsent(declared.getNameAsString(), new ArrayList<>());
                        declaringFile.put(declared.getNameAsString(),
                                root.relativize(source).toString());
                        if (declared.getAnnotationByName("FunctionalInterface").isPresent()) {
                            expectingMany.add(declared.getNameAsString());
                        }
                    });
        });
        sources.forEach(source -> {
            final CompilationUnit unit = parse(source);
            unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(declared -> !declared.isInterface())
                    .forEach(declared -> declared.getImplementedTypes().forEach(implemented ->
                            implementations.computeIfPresent(implemented.getNameAsString(),
                                    (declaredInterface, members) -> {
                                        members.add(declared.getNameAsString());
                                        return members;
                                    })));
            unit.findAll(RecordDeclaration.class).forEach(declared ->
                    declared.getImplementedTypes().forEach(implemented ->
                            implementations.computeIfPresent(implemented.getNameAsString(),
                                    (declaredInterface, members) -> {
                                        members.add(declared.getNameAsString());
                                        return members;
                                    })));
            // An enum that implements an interface is an implementation of it. Counting only
            // classes and records made a closed pair of a record and an enum look like a sole
            // implementation, and the naming rule then asked for a name the pair does not want.
            unit.findAll(EnumDeclaration.class).forEach(declared ->
                    declared.getImplementedTypes().forEach(implemented ->
                            implementations.computeIfPresent(implemented.getNameAsString(),
                                    (declaredInterface, members) -> {
                                        members.add(declared.getNameAsString());
                                        return members;
                                    })));
        });
        final List<PolicyFinding> findings = new ArrayList<>();
        implementations.forEach((declaredInterface, members) -> {
            final String file = declaringFile.getOrDefault(declaredInterface, POLICY_FILE);
            if (members.size() == 1 && !expectingMany.contains(declaredInterface)) {
                final String expected = soleImplementationPrefix + declaredInterface;
                if (!members.getFirst().equals(expected)) {
                    findings.add(PolicyFinding.inFile(file, "sole-implementation-naming",
                            members.getFirst() + " is the only implementation of " + declaredInterface
                                    + " and is not named " + expected));
                }
                return;
            }
            members.stream()
                    .filter(member -> member.startsWith(soleImplementationPrefix))
                    .map(member -> PolicyFinding.inFile(file, "several-implementations-naming",
                            member + " is one of " + members.size() + " implementations of "
                                    + declaredInterface + " and claims to be the default"))
                    .forEach(findings::add);
        });
        return Collections.unmodifiableList(findings);
    }

    private static boolean isNamedConstant(FieldDeclaration field) {
        return field.isStatic() && field.isFinal();
    }

    private static boolean isPublic(TypeDeclaration<?> type) {
        return type.hasModifier(Modifier.Keyword.PUBLIC);
    }

    private static boolean isFinal(TypeDeclaration<?> type) {
        return type.hasModifier(Modifier.Keyword.FINAL);
    }

    private static boolean isSealed(TypeDeclaration<?> type) {
        return type.hasModifier(Modifier.Keyword.SEALED);
    }

    /**
     * Whether a type is an interface, whose every declared method is an extension point written
     * where the compiler checks it.
     */
    private static boolean isInterface(TypeDeclaration<?> type) {
        return type instanceof final ClassOrInterfaceDeclaration declared && declared.isInterface();
    }

    /** Whether the language already closes a type: a record and an enumeration cannot be extended. */
    private static boolean isImplicitlyClosed(TypeDeclaration<?> type) {
        // An annotation declaration is closed the same way a record and an enumeration are: the
        // language will not let anything extend one, so there is no extension point to declare.
        return type instanceof RecordDeclaration || type instanceof EnumDeclaration
                || type instanceof com.github.javaparser.ast.body.AnnotationDeclaration;
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
