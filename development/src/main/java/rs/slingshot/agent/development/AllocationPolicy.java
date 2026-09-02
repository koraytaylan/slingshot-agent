// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * Where a stream is the right shape and where it is the wrong one.
 *
 * <p>The two rules look opposed and are not. Outside a declared allocation-sensitive path a stream
 * says what a transformation is and an indexed loop states an iteration order nobody needed; inside
 * one, a pipeline and a capture per unit of input is an allocation per unit of input. So the paths
 * are declared, and the rule inverts inside them — from one policy file, so the two can never
 * disagree about which side of the line a method is on.</p>
 */
public final class AllocationPolicy {

    private static final String POLICY_FILE = "policy/allocation.toml";

    private static final String SENSITIVE_ROWS = "sensitive_path";

    /** How a copy of something that is already a copy is spelled. */
    private static final List<String> IMMUTABLE_FACTORIES = List.of("List.copyOf", "Set.copyOf",
            "Map.copyOf", "List.of", "Set.of", "Map.of");

    /** How a copy is spelled where an unmodifiable view would state the same thing. */
    private static final List<String> COPY_FACTORIES = List.of("List.copyOf", "Set.copyOf", "Map.copyOf");

    private final List<SensitivePath> sensitivePaths;

    private AllocationPolicy(List<SensitivePath> sensitivePaths) {
        this.sensitivePaths = sensitivePaths;
    }

    /**
     * One path that runs per unit of input, inside which the stream rule inverts.
     *
     * @param type the type declaring the method
     * @param method the method's own name
     * @param reason why the path is sensitive
     */
    public record SensitivePath(String type, String method, String reason) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(AllocationPolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the allocation policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("allocation")
                .answer("outside_sensitive_paths.indexed_loops_permitted")
                .answer("outside_sensitive_paths.streams_permitted")
                .text("outside_sensitive_paths.reason")
                .answer("inside_sensitive_paths.indexed_loops_permitted")
                .answer("inside_sensitive_paths.streams_permitted")
                .text("inside_sensitive_paths.reason")
                .answer("copies.defensive_copy_of_immutable_permitted")
                .answer("copies.copy_where_a_view_would_do_permitted")
                .text("copies.reason")
                .rows(SENSITIVE_ROWS, row -> row.text("type").text("method").text("reason"))
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
        if (document.answer("outside_sensitive_paths.indexed_loops_permitted")
                == document.answer("inside_sensitive_paths.indexed_loops_permitted")) {
            return new Refused("the rule does not invert inside a sensitive path, so declaring one "
                    + "would change nothing");
        }
        return new Loaded(new AllocationPolicy(document.rows(SENSITIVE_ROWS).stream()
                .map(row -> new SensitivePath(row.text("type"), row.text("method"), row.text("reason")))
                .toList()));
    }

    /**
     * Every path the policy declares allocation-sensitive.
     *
     * @return the sensitive paths, in the policy's own order
     */
    public List<SensitivePath> sensitivePaths() {
        return Collections.unmodifiableList(sensitivePaths);
    }

    /**
     * Holds every main-source method in a tree to the rule that applies on its side of the line.
     *
     * @param root the repository root
     * @return one finding per method that allocates the way its side of the line refuses
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .forEach(source ->
                        findings.addAll(inFile(root.relativize(source).toString(), source)));
        findings.addAll(declaredPathFindings(root));
        return PolicyReport.of(findings);
    }

    /**
     * Holds one file's methods to the rule that applies on their side of the line.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per method that allocates the way its side of the line refuses
     */
    public List<PolicyFinding> inFile(String name, Path source) {
        final CompilationUnit unit = parse(source);
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(TypeDeclaration.class).stream()
                .map(found -> (TypeDeclaration<?>) found)
                .forEach(type -> type.getMethods()
                        .forEach(method -> findings.addAll(methodFindings(name, type, method))));
        findings.addAll(concatenationFindings(name, unit));
        findings.addAll(copyFindings(name, unit));
        return Collections.unmodifiableList(findings);
    }

    private List<PolicyFinding> methodFindings(String name, TypeDeclaration<?> type,
                                               MethodDeclaration method) {
        final boolean sensitive = isSensitive(type, method);
        final List<PolicyFinding> findings = new ArrayList<>();
        method.findAll(ForStmt.class).stream()
                .filter(loop -> !sensitive)
                .map(loop -> finding(name, loop, "indexed-loop", method.getNameAsString()
                        + " states an iteration order nobody needed; a stream says what the"
                        + " transformation is"))
                .forEach(findings::add);
        method.findAll(MethodCallExpr.class).stream()
                .filter(call -> sensitive && isStreamPipeline(call))
                .map(call -> finding(name, call, "stream-in-a-sensitive-path",
                        method.getNameAsString() + " allocates a pipeline per unit of input"))
                .forEach(findings::add);
        return findings;
    }

    private static List<PolicyFinding> concatenationFindings(String name, CompilationUnit unit) {
        return unit.findAll(AssignExpr.class).stream()
                .filter(assignment -> assignment.getOperator() == AssignExpr.Operator.PLUS)
                .filter(AllocationPolicy::isInsideALoop)
                .map(assignment -> finding(name, assignment, "concatenation-in-a-loop",
                        assignment.getTarget().toString()
                                + " is rebuilt whole on every turn of the loop"))
                .toList();
    }

    private List<PolicyFinding> copyFindings(String name, CompilationUnit unit) {
        final SequencedSet<String> fields = new LinkedHashSet<>();
        unit.findAll(FieldDeclaration.class).stream()
                .filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream())
                .forEach(variable -> fields.add(variable.getNameAsString()));
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(MethodCallExpr.class).stream()
                .filter(call -> COPY_FACTORIES.contains(call.getScope()
                        .map(scope -> scope + "." + call.getNameAsString()).orElse("")))
                .forEach(call -> {
                    final Optional<Expression> argument = call.getArguments().getFirst();
                    if (argument.filter(AllocationPolicy::isAlreadyImmutable).isPresent()) {
                        findings.add(finding(name, call, "copy-of-an-immutable",
                                "the argument is already something nothing can change"));
                    }
                    if (argument.filter(Expression::isNameExpr)
                            .filter(named -> fields.contains(named.asNameExpr().getNameAsString()))
                            .isPresent() && isReturned(call)) {
                        findings.add(finding(name, call, "copy-where-a-view-would-do",
                                "an unmodifiable view of "
                                        + argument.orElseThrow().asNameExpr().getNameAsString()
                                        + " states the same thing and allocates once"));
                    }
                });
        return findings;
    }

    private List<PolicyFinding> declaredPathFindings(Path root) {
        final SequencedSet<String> declared = new LinkedHashSet<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .forEach(source -> parse(source).findAll(TypeDeclaration.class).stream()
                        .map(found -> (TypeDeclaration<?>) found)
                        .forEach(type -> type.getMethods().forEach(method ->
                                declared.add(type.getNameAsString() + "#"
                                        + method.getNameAsString()))));
        return sensitivePaths.stream()
                .filter(path -> !declared.contains(path.type() + "#" + path.method()))
                .map(path -> PolicyFinding.inFile(POLICY_FILE, "sensitive-path",
                        path.type() + "#" + path.method() + " is declared sensitive and does not exist"))
                .toList();
    }

    private boolean isSensitive(TypeDeclaration<?> type, MethodDeclaration method) {
        return sensitivePaths.stream()
                .anyMatch(path -> path.type().equals(type.getNameAsString())
                        && path.method().equals(method.getNameAsString()));
    }

    private static boolean isStreamPipeline(MethodCallExpr call) {
        return "stream".equals(call.getNameAsString()) || "chars".equals(call.getNameAsString());
    }

    private static boolean isAlreadyImmutable(Expression argument) {
        return argument.isMethodCallExpr() && IMMUTABLE_FACTORIES.contains(
                argument.asMethodCallExpr().getScope()
                        .map(scope -> scope + "." + argument.asMethodCallExpr().getNameAsString())
                        .orElse(""));
    }

    private static boolean isReturned(Node call) {
        return call.getParentNode().filter(ReturnStmt.class::isInstance).isPresent();
    }

    private static boolean isInsideALoop(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            final Node enclosing = parent.get();
            if (enclosing instanceof ForStmt || enclosing instanceof ForEachStmt
                    || enclosing instanceof WhileStmt || enclosing instanceof DoStmt) {
                return true;
            }
            parent = enclosing.getParentNode();
        }
        return false;
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

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private static PolicyFinding finding(String file, Node node, String rule, String symbol) {
        return new PolicyFinding(file,
                node.getBegin().map(position -> position.line).orElse(PolicyFinding.NO_LINE),
                rule, symbol);
    }
}
