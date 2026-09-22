// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/**
 * The nullability contract, decided over parsed source rather than trusted.
 *
 * <p>No method accepts a null argument or returns a null value. Absence is a type — a closed
 * outcome, an empty collection, or a return-only {@code Optional} — and the checker decides that
 * rather than a reviewer noticing. Once it holds, an entire category of defect and an entire
 * category of defensive code both stop existing.</p>
 *
 * <p>Two shapes are refused that look harmless. {@code Optional} in a parameter position converts a
 * caller's simple decision into a wrapper they have to construct; {@code Optional} as a field is a
 * type that has not been designed yet. Both are decidable, so both are decided.</p>
 */
public final class NullabilityPolicy {

    private static final String POLICY_FILE = "policy/nullability.toml";

    private static final String PERMITTED_ROWS = "permitted_form";

    private static final String EXEMPT_ROWS = "exempt";

    private static final String EXTERNAL_ABSENCE_ROWS = "external_absence";

    private static final String OPTIONAL_TYPE = "Optional";

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final String notNull;
    private final String nullable;
    private final String notNullByDefault;
    private final List<String> permittedForms;
    private final List<String> exemptKinds;
    private final List<ExternalAbsence> externalAbsences;

    private NullabilityPolicy(String notNull, String nullable, String notNullByDefault,
                              List<String> permittedForms, List<String> exemptKinds,
                              List<ExternalAbsence> externalAbsences) {
        this.notNull = notNull;
        this.nullable = nullable;
        this.notNullByDefault = notNullByDefault;
        this.permittedForms = permittedForms;
        this.exemptKinds = exemptKinds;
        this.externalAbsences = externalAbsences;
    }

    /**
     * One argument position of one external interface whose own contract gives absence a meaning.
     *
     * <p>This is the one place a null is written, and it is named rather than tolerated: the file,
     * the method, and the position, each with the reason the interface cannot be called any other
     * way. A null anywhere else in the same file, in another argument of the same call, or in a
     * method of the same name elsewhere is still refused.</p>
     *
     * @param source the repository path of the one file that makes the call
     * @param method the external method's own name
     * @param argument the zero-based position that may carry the absence
     */
    public record ExternalAbsence(String source, String method, long argument) {
    }

    /** Whether a package's own declaration carries the non-null default for what it holds. */
    public enum NullnessDefault {
        /** The package declares the default, so every member in it has its nullness stated. */
        DECLARED,
        /** The package declares nothing, so every member states its own or is refused. */
        ABSENT
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(NullabilityPolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the nullability policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("nullability")
                .text("annotations.package")
                .text("annotations.not_null")
                .text("annotations.nullable")
                .text("annotations.not_null_by_default")
                .text("annotations.unmodifiable")
                .answer("runtime.importable")
                .text("runtime.reason")
                .rows(PERMITTED_ROWS, row -> row.text("form").text("reason"))
                .rows(EXEMPT_ROWS, row -> row.text("kind").text("reason"))
                .rows(EXTERNAL_ABSENCE_ROWS, row -> row.text("source").text("method")
                        .number("argument").text("reason"))
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
        if (document.answer("runtime.importable")) {
            return new Refused("the policy permits the annotation package to be imported at runtime");
        }
        return new Loaded(new NullabilityPolicy(
                document.text("annotations.not_null"),
                document.text("annotations.nullable"),
                document.text("annotations.not_null_by_default"),
                document.rows(PERMITTED_ROWS).stream().map(row -> row.text("form")).toList(),
                document.rows(EXEMPT_ROWS).stream().map(row -> row.text("kind")).toList(),
                document.rows(EXTERNAL_ABSENCE_ROWS).stream()
                        .map(row -> new ExternalAbsence(row.text("source"), row.text("method"),
                                row.number("argument")))
                        .toList()));
    }

    /**
     * The forms in which a member's nullness may be declared.
     *
     * @return the permitted forms, in the policy's own order
     */
    public List<String> permittedForms() {
        return Collections.unmodifiableList(permittedForms);
    }

    /**
     * What the rule does not apply to, because something else already decides it.
     *
     * @return the exempt kinds, in the policy's own order
     */
    public List<String> exemptKinds() {
        return Collections.unmodifiableList(exemptKinds);
    }

    /**
     * The external argument positions whose contract gives an absent value a meaning.
     *
     * @return the declared absences, in the policy's own order
     */
    public List<ExternalAbsence> externalAbsences() {
        return Collections.unmodifiableList(externalAbsences);
    }

    /**
     * Holds every main-source Java file in a tree to the contract.
     *
     * @param root the repository root
     * @return one finding per member whose nullness is undeclared, nullable, or a null value
     */
    public PolicyReport across(Path root) {
        final List<Path> sources = RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .toList();
        final SequencedSet<String> defaulted = packagesDeclaringTheDefault(sources);
        final List<PolicyFinding> findings = new ArrayList<>();
        sources.forEach(source ->
                findings.addAll(inFile(root.relativize(source).toString(), source, defaulted)));
        return PolicyReport.of(findings);
    }

    /**
     * The packages whose own declaration carries the non-null default.
     *
     * <p>A package annotation lives in {@code package-info.java} and nowhere else, so a file's own
     * package declaration never carries it. Reading the declaration file is the only way to know
     * whether the members in that package have their nullness declared for them.</p>
     */
    private SequencedSet<String> packagesDeclaringTheDefault(List<Path> sources) {
        final SequencedSet<String> defaulted = new LinkedHashSet<>();
        sources.stream()
                .filter(source -> "package-info.java".equals(String.valueOf(source.getFileName())))
                .forEach(source -> parse(source).getPackageDeclaration()
                        .filter(declaration -> carries(declaration, notNullByDefault))
                        .ifPresent(declaration -> defaulted.add(declaration.getName().asString())));
        return defaulted;
    }

    /**
     * Holds one Java file to the contract.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per part of the contract it breaks
     */
    public List<PolicyFinding> inFile(String name, Path source) {
        return inFile(name, source, new LinkedHashSet<>());
    }

    /**
     * Holds one Java file to the contract, knowing which packages declare the default.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @param packagesDeclaringTheDefault the packages whose own declaration carries the default
     * @return one finding per part of the contract it breaks
     */
    public List<PolicyFinding> inFile(String name, Path source,
                                      SequencedSet<String> packagesDeclaringTheDefault) {
        final CompilationUnit unit = parse(source);
        final NullnessDefault declared = unit.getPackageDeclaration()
                .filter(declaration -> carries(declaration, notNullByDefault)
                        || packagesDeclaringTheDefault.contains(declaration.getName().asString()))
                .map(declaration -> NullnessDefault.DECLARED)
                .orElse(NullnessDefault.ABSENT);
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(memberFindings(name, unit, declared));
        findings.addAll(optionalFindings(name, unit));
        findings.addAll(nullValueFindings(name, unit, externalAbsences));
        return Collections.unmodifiableList(findings);
    }

    private static CompilationUnit parse(Path source) {
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(RepositoryTree.text(source));
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + parsed.getProblems());
        }
        return parsed.getResult().orElseThrow();
    }

    private List<PolicyFinding> memberFindings(String name, CompilationUnit unit,
                                               NullnessDefault declared) {
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> !method.isPrivate())
                .forEach(method -> {
                    if (carries(method, nullable)) {
                        findings.add(finding(name, method, "nullable-return",
                                method.getNameAsString()));
                    }
                    if (declared == NullnessDefault.ABSENT && !carries(method, notNull)
                            && !isDecidedByTheLanguage(method.getType())) {
                        findings.add(finding(name, method, "undeclared-return",
                                method.getNameAsString()));
                    }
                    if (carries(method, notNull) && method.getType().isPrimitiveType()) {
                        findings.add(finding(name, method, "redundant-annotation",
                                method.getNameAsString()));
                    }
                    findings.addAll(parameterFindings(name, method, declared));
                });
        unit.findAll(ConstructorDeclaration.class).stream()
                .filter(constructor -> !constructor.isPrivate())
                .forEach(constructor ->
                        findings.addAll(parameterFindings(name, constructor, declared)));
        return findings;
    }

    private List<PolicyFinding> parameterFindings(String name, CallableDeclaration<?> callable,
                                                  NullnessDefault declared) {
        final List<PolicyFinding> findings = new ArrayList<>();
        callable.getParameters().forEach(parameter -> {
            if (carries(parameter, nullable)) {
                findings.add(finding(name, parameter, "nullable-parameter",
                        parameter.getNameAsString()));
            }
            if (carries(parameter, notNull) && parameter.getType().isPrimitiveType()) {
                findings.add(finding(name, parameter, "redundant-annotation",
                        parameter.getNameAsString()));
            }
            if (declared == NullnessDefault.ABSENT && !carries(parameter, notNull)
                    && !isDecidedByTheLanguage(parameter.getType())) {
                findings.add(finding(name, parameter, "undeclared-parameter",
                        parameter.getNameAsString()));
            }
        });
        return findings;
    }

    private static List<PolicyFinding> optionalFindings(String name, CompilationUnit unit) {
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(Parameter.class).stream()
                .filter(parameter -> isOptional(parameter.getType()))
                .map(parameter -> finding(name, parameter, "optional-parameter",
                        parameter.getNameAsString()))
                .forEach(findings::add);
        unit.findAll(FieldDeclaration.class).stream()
                .filter(field -> isOptional(field.getElementType()))
                .flatMap(field -> field.getVariables().stream())
                .map(variable -> finding(name, variable, "optional-field",
                        variable.getNameAsString()))
                .forEach(findings::add);
        return findings;
    }

    private static List<PolicyFinding> nullValueFindings(String name, CompilationUnit unit,
                                                         List<ExternalAbsence> declared) {
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(ReturnStmt.class).stream()
                .filter(statement -> statement.getExpression()
                        .filter(NullabilityPolicy::isNullValued).isPresent())
                .map(statement -> finding(name, statement, "null-return", "return null"))
                .forEach(findings::add);
        final SequencedSet<String> nullValued = nullValuedNames(unit);
        unit.findAll(MethodCallExpr.class).forEach(call -> argumentFindings(name,
                undeclaredArguments(name, call, declared), nullValued, findings));
        unit.findAll(ObjectCreationExpr.class).forEach(creation ->
                argumentFindings(name, creation.getArguments(), nullValued, findings));
        return findings;
    }

    /**
     * The arguments of one call that no declared absence covers.
     *
     * <p>Only a null literal at a declared position is passed over. A variable holding null is
     * still refused there, because the declaration is about what the call writes, not about
     * whatever may reach it.</p>
     */
    private static List<Expression> undeclaredArguments(String name, MethodCallExpr call,
                                                        List<ExternalAbsence> declared) {
        final List<Expression> arguments = call.getArguments();
        return java.util.stream.IntStream.range(0, arguments.size())
                .filter(position -> !(arguments.get(position) instanceof NullLiteralExpr)
                        || declared.stream().noneMatch(absence -> absence.source().equals(name)
                                && absence.method().equals(call.getNameAsString())
                                && absence.argument() == position))
                .mapToObj(arguments::get)
                .toList();
    }

    private static void argumentFindings(String name, List<Expression> arguments,
                                         SequencedSet<String> nullValued,
                                         List<PolicyFinding> findings) {
        arguments.forEach(argument -> {
            if (isNullValued(argument)) {
                findings.add(finding(name, argument, "null-argument", "null"));
                return;
            }
            if (argument.isNameExpr() && nullValued.contains(argument.asNameExpr().getNameAsString())) {
                findings.add(finding(name, argument, "null-argument",
                        argument.asNameExpr().getNameAsString()));
            }
        });
    }

    /**
     * The local names a method assigns a null value to, so a null reaching an argument position
     * through a variable is caught rather than only the token.
     */
    private static SequencedSet<String> nullValuedNames(CompilationUnit unit) {
        final SequencedSet<String> names = new LinkedHashSet<>();
        unit.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getInitializer()
                        .filter(NullabilityPolicy::isNullValued).isPresent())
                .forEach(variable -> names.add(variable.getNameAsString()));
        unit.findAll(AssignExpr.class).stream()
                .filter(assignment -> isNullValued(assignment.getValue()))
                .filter(assignment -> assignment.getTarget().isNameExpr())
                .forEach(assignment ->
                        names.add(assignment.getTarget().asNameExpr().getNameAsString()));
        return names;
    }

    private static boolean isNullValued(Expression expression) {
        return expression instanceof NullLiteralExpr
                || expression.isCastExpr() && expression.asCastExpr().getExpression()
                        instanceof NullLiteralExpr;
    }

    private static boolean isOptional(Type type) {
        return type.isClassOrInterfaceType()
                && OPTIONAL_TYPE.equals(type.asClassOrInterfaceType().getNameAsString());
    }

    /**
     * Whether the language already decides a type's nullness: a primitive cannot be null, and a
     * {@code void} return carries no value at all.
     */
    private static boolean isDecidedByTheLanguage(Type type) {
        return type.isPrimitiveType() || type.isVoidType() || type.isVarType();
    }

    private static boolean carries(NodeWithAnnotations<?> node, String annotation) {
        return node.getAnnotations().stream()
                .anyMatch(present -> present.getNameAsString().equals(annotation)
                        || present.getNameAsString().endsWith("." + annotation));
    }

    private static boolean isTestSource(Path relative) {
        for (final Path segment : relative) {
            if ("test".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static PolicyFinding finding(String file, Node node, String rule, String symbol) {
        return new PolicyFinding(file,
                node.getBegin().map(position -> position.line).orElse(PolicyFinding.NO_LINE),
                rule, symbol);
    }
}
