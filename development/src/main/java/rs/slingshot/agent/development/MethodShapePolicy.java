// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * The shape a method may have, with nesting as the primary ceiling rather than a number.
 *
 * <p>Complexity in a method is almost always nesting, and nesting is almost always a refusal
 * written as an {@code else} instead of as a return. So a finding names the guard clause that would
 * remove it rather than only reporting the depth — the tool says what to do, not only what is
 * wrong.</p>
 *
 * <p>The {@code else} attached to a block whose every path already returns or throws is the same
 * defect in its most mechanical form, and is refused on its own.</p>
 */
public final class MethodShapePolicy {

    private static final String POLICY_FILE = "policy/method-shape.toml";

    private static final String CEILING_ROWS = "ceiling";

    private static final String CHOICE_ROWS = "choice_position";

    /** The two names a two-valued type is spelled with in Java. */
    private static final List<String> BOOLEAN_TYPES = List.of("boolean", "Boolean");

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final SequencedMap<String, Long> ceilings;
    private final List<String> choicePositions;

    private MethodShapePolicy(SequencedMap<String, Long> ceilings, List<String> choicePositions) {
        this.ceilings = ceilings;
        this.choicePositions = choicePositions;
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(MethodShapePolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the method-shape policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("method-shape")
                .answer("boolean.permitted_in_reported_fact")
                .answer("boolean.permitted_in_choice")
                .text("boolean.reason")
                .rows(CEILING_ROWS, row -> row.text("name").number("value").text("reason"))
                .rows(CHOICE_ROWS, row -> row.text("kind").text("reason"))
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
        if (document.answer("boolean.permitted_in_choice")) {
            return new Refused("the policy permits a boolean where a choice belongs");
        }
        final SequencedMap<String, Long> ceilings = new LinkedHashMap<>();
        document.rows(CEILING_ROWS).forEach(row -> {
            if (!row.text("reason").isBlank()) {
                ceilings.put(row.text("name"), row.number("value"));
            }
        });
        if (ceilings.size() != document.rows(CEILING_ROWS).size()) {
            return new Refused("a ceiling records no reason for the value it chose");
        }
        return new Loaded(new MethodShapePolicy(ceilings,
                document.rows(CHOICE_ROWS).stream().map(row -> row.text("kind")).toList()));
    }

    /**
     * The ceiling declared for one rule.
     *
     * @param name the ceiling's own name
     * @return the value the policy declares, which is written nowhere in this checker
     * @throws IllegalArgumentException if the policy declares no such ceiling
     */
    public long ceiling(String name) {
        final Long value = ceilings.get(name);
        if (value == null) {
            throw new IllegalArgumentException("the policy declares no ceiling named " + name);
        }
        return value;
    }

    /**
     * Every ceiling the policy declares.
     *
     * @return the ceilings, in the policy's own order
     */
    public SequencedMap<String, Long> ceilings() {
        return new LinkedHashMap<>(ceilings);
    }

    /**
     * The positions in which a two-valued value is a choice rather than a reported fact.
     *
     * @return the choice positions, in the policy's own order
     */
    public List<String> choicePositions() {
        return Collections.unmodifiableList(choicePositions);
    }

    /**
     * Holds every main-source method in a tree to every ceiling.
     *
     * @param root the repository root
     * @return one finding per method that breaks one
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
     * Holds one file's methods to every ceiling.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per method that breaks one
     */
    public List<PolicyFinding> inFile(String name, Path source) {
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(RepositoryTree.text(source));
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + parsed.getProblems());
        }
        final CompilationUnit unit = parsed.getResult().orElseThrow();
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(MethodDeclaration.class)
                .forEach(method -> findings.addAll(methodFindings(name, method)));
        unit.findAll(IfStmt.class).stream()
                .filter(MethodShapePolicy::isElseAfterExhaustiveReturn)
                .map(statement -> finding(name, statement, "returning-block-else",
                        "the block before this else returns on every path, so the else is nesting "
                                + "a guard clause would remove"))
                .forEach(findings::add);
        unit.findAll(Parameter.class).stream()
                .filter(parameter -> BOOLEAN_TYPES.contains(parameter.getType().asString()))
                .map(parameter -> finding(name, parameter, "boolean-choice",
                        parameter.getNameAsString()
                                + " is a choice a call site cannot state; a named two-valued type"
                                + " records what was chosen"))
                .forEach(findings::add);
        return Collections.unmodifiableList(findings);
    }

    private List<PolicyFinding> methodFindings(String file, MethodDeclaration method) {
        final List<PolicyFinding> findings = new ArrayList<>();
        method.getBody().ifPresent(body -> {
            final int depth = nestingDepth(body, 0);
            if (depth > ceiling("nesting-depth")) {
                findings.add(finding(file, method, "nesting-depth", method.getNameAsString()
                        + " nests " + depth + " deep, above " + ceiling("nesting-depth")
                        + "; a guard clause returning early at the first refusal removes a level"));
            }
            final long cyclomatic = cyclomaticComplexity(body);
            if (cyclomatic > ceiling("cyclomatic-complexity")) {
                findings.add(finding(file, method, "cyclomatic-complexity",
                        method.getNameAsString() + " branches " + cyclomatic + " ways"));
            }
            final long cognitive = cognitiveComplexity(body, 0);
            if (cognitive > ceiling("cognitive-complexity")) {
                findings.add(finding(file, method, "cognitive-complexity",
                        method.getNameAsString() + " costs " + cognitive + " to read"));
            }
            final long lines = body.toString().lines().count();
            if (lines > ceiling("method-length")) {
                findings.add(finding(file, method, "method-length",
                        method.getNameAsString() + " is " + lines + " lines"));
            }
        });
        if (method.getParameters().size() > ceiling("parameter-count")) {
            findings.add(finding(file, method, "parameter-count", method.getNameAsString()
                    + " takes " + method.getParameters().size() + " arguments"));
        }
        return findings;
    }

    /**
     * How deep the blocks in a method nest, counting only the statements that indent their body.
     *
     * @param node the node to measure
     * @param depth how deep the node itself already sits
     * @return the deepest nesting anywhere beneath it
     */
    public static int nestingDepth(Node node, int depth) {
        final int here = nests(node) && !isElseIfChain(node) ? depth + 1 : depth;
        return node.getChildNodes().stream()
                .mapToInt(child -> nestingDepth(child, here))
                .max()
                .orElse(here);
    }

    /**
     * Whether a conditional is the {@code else} branch of another one.
     *
     * <p>An {@code else if} chain is a list of conditions written flat, not a condition inside a
     * condition, and a reader sees it that way. Charging it as nesting would push the rule toward
     * the shape it exists to prevent — a chain rewritten as a nest.</p>
     */
    private static boolean isElseIfChain(Node node) {
        return node instanceof IfStmt
                && node.getParentNode().filter(IfStmt.class::isInstance)
                        .map(IfStmt.class::cast)
                        .flatMap(IfStmt::getElseStmt)
                        .filter(branch -> branch.equals(node))
                        .isPresent();
    }

    private static boolean nests(Node node) {
        return node instanceof IfStmt || node instanceof ForStmt || node instanceof ForEachStmt
                || node instanceof WhileStmt || node instanceof DoStmt || node instanceof SwitchStmt
                || node instanceof TryStmt || node instanceof CatchClause;
    }

    private static long cyclomaticComplexity(Node body) {
        return 1 + body.findAll(Node.class).stream().filter(MethodShapePolicy::branches).count();
    }

    private static boolean branches(Node node) {
        return node instanceof IfStmt || node instanceof ForStmt || node instanceof ForEachStmt
                || node instanceof WhileStmt || node instanceof DoStmt || node instanceof CatchClause
                || node instanceof ConditionalExpr;
    }

    /**
     * What a method costs to read: every branch counts once, and a branch inside another costs the
     * depth it sits at as well. That is the difference from the number beside it, and it is why
     * this one rises faster on exactly the code the nesting rule exists to refuse.
     */
    private static long cognitiveComplexity(Node node, int depth) {
        final boolean branches = branches(node);
        final long here = branches ? 1L + depth : 0L;
        final int deeper = nests(node) ? depth + 1 : depth;
        return here + node.getChildNodes().stream()
                .mapToLong(child -> cognitiveComplexity(child, deeper))
                .sum();
    }

    /**
     * Whether an {@code else} is attached to a block whose every path already returns or throws,
     * which is the nesting a guard clause removes in its most mechanical form.
     */
    private static boolean isElseAfterExhaustiveReturn(IfStmt statement) {
        return statement.getElseStmt().filter(branch -> !(branch instanceof IfStmt)).isPresent()
                && alwaysLeaves(statement.getThenStmt());
    }

    private static boolean alwaysLeaves(Statement statement) {
        if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) {
            return true;
        }
        return statement instanceof final BlockStmt block
                && block.getStatements().getLast()
                        .filter(MethodShapePolicy::alwaysLeaves)
                        .isPresent();
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
