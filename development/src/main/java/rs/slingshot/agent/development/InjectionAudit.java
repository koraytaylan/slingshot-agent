// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a caller's value can reach a grammar, checked over parsed source rather than by grep.
 *
 * <p>The strongest answer this product has to query injection is that it has no query. Every search
 * walks resources through the caller's own resolver, so there is no statement for a value to break
 * out of — and this is what keeps that true rather than a thing that happened to be true when
 * somebody wrote it. The day a statement appears, it appears as a finding.</p>
 *
 * <p>Parsed rather than matched, because the two things worth telling apart look identical to a
 * text search. A statement named in a comment is somebody explaining why there are none; a string
 * concatenated into a call is somebody building one. Only a parser can see the difference, and a
 * rule that could not would be a rule that stopped people documenting it.</p>
 */
public final class InjectionAudit {

    /** Where the attack shapes are declared. */
    public static final String CORPUS_FILE = "policy/injection-corpus.toml";

    /** The rule a query statement built out of pieces is reported under. */
    public static final String QUERY_BY_CONCATENATION = "query-by-concatenation";

    /** The rule any call into a query engine at all is reported under. */
    public static final String A_QUERY_ENGINE_IS_REACHED = "a-query-engine-is-reached";

    /** The rule a name written into the repository without being escaped is reported under. */
    public static final String UNESCAPED_REPOSITORY_NAME = "unescaped-repository-name";

    /** The grammars a value may attack, which is the closed set the corpus is grouped by. */
    public static final List<String> GRAMMARS =
            List.of("query", "path", "name", "expression", "control");

    /** Every way into a query engine, from either of the two APIs a deployment offers. */
    private static final List<String> QUERY_ENGINES =
            List.of("createQuery", "getQueryManager", "findResources", "queryResources",
                    "PredicateGroup", "QueryBuilder");

    /** How a node is made, which is where an unescaped name would land. */
    private static final List<String> NAME_TAKING_CALLS = List.of("addNode", "createNode");

    /**
     * What a name-taking call may be given, other than something this repository wrote itself.
     *
     * <p>Two of the three are escapes. The third is a value type: a slot name has already been
     * through a reader that refuses anything a repository would read as more than a name, so by the
     * time it is a slot it is not a caller's string any more. That is the shape this repository
     * prefers to escaping — a value that cannot be wrong rather than one that was corrected — and
     * naming it here is what keeps the rule from refusing it.</p>
     */
    private static final List<String> ALREADY_SAFE =
            List.of("escape", "createValidName", "escapeName", "slot()", "path()", "StatePath");

    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final List<Shape> shapes;

    private InjectionAudit(List<Shape> shapes) {
        this.shapes = shapes;
    }

    /**
     * One attack shape.
     *
     * @param grammar which grammar it attacks
     * @param value the value itself
     * @param attacks what it does to that grammar
     */
    public record Shape(String grammar, String value, String attacks) {
    }

    /** The result of reading the corpus: the audit, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A corpus that satisfied its shape completely.
     *
     * @param audit the loaded audit
     */
    public record Loaded(InjectionAudit audit) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the corpus is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("injection-corpus")
                .text("corpus.reason")
                .rows("shape", row -> row.text("grammar").text("value").text("attacks")
                        .text("reason"))
                .build();
    }

    /**
     * Reads the corpus this repository commits.
     *
     * @param root the repository root
     * @return the audit, or the one reason there is none
     */
    public static Outcome read(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(CORPUS_FILE), shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        return new Loaded(new InjectionAudit(
                ((PolicyDocument.Loaded) outcome).document().rows("shape").stream()
                        .map(row -> new Shape(row.text("grammar"), row.text("value"),
                                row.text("attacks")))
                        .toList()));
    }

    /**
     * Every shape the corpus declares, in its own order.
     *
     * @return the shapes
     */
    public List<Shape> shapes() {
        return java.util.Collections.unmodifiableList(shapes);
    }

    /**
     * Everything the corpus and its own vocabulary disagree about.
     *
     * @return one finding per grammar with no shape, and per shape naming no grammar
     */
    public PolicyReport coverage() {
        final List<PolicyFinding> findings = new ArrayList<>();
        GRAMMARS.stream()
                .filter(grammar -> shapes.stream()
                        .noneMatch(shape -> shape.grammar().equals(grammar)))
                .map(grammar -> PolicyFinding.inFile(CORPUS_FILE, "grammar-with-no-shape",
                        grammar + " is a grammar a value can attack and nothing attacks it"))
                .forEach(findings::add);
        shapes.stream()
                .filter(shape -> !GRAMMARS.contains(shape.grammar()))
                .map(shape -> PolicyFinding.inFile(CORPUS_FILE, "shape-with-no-grammar",
                        shape.value() + " attacks " + shape.grammar()
                                + ", which is not a grammar this vocabulary holds"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Every product source, held to all three rules over its parsed form.
     *
     * @param root the repository root
     * @return one finding per source that breaks one
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        List.of("core/src/main/java", "aem/src/main/java").forEach(tree ->
                RepositoryTree.filesUnder(root.resolve(tree), ".java").forEach(source ->
                        findings.addAll(inFile(root.relativize(source).toString(), source))));
        return PolicyReport.of(findings);
    }

    /**
     * One source, held to all three rules.
     *
     * @param named how a finding names the file
     * @param file where to read it from
     * @return one finding per rule it breaks
     */
    public List<PolicyFinding> inFile(String named, Path file) {
        final List<PolicyFinding> findings = new ArrayList<>();
        parsed(file).findAll(MethodCallExpr.class).forEach(call -> {
            if (QUERY_ENGINES.contains(call.getNameAsString())) {
                findings.add(PolicyFinding.inFile(named, A_QUERY_ENGINE_IS_REACHED,
                        call.getNameAsString() + " reaches a query engine, and nothing here has a"
                                + " query for a caller's value to break out of"));
            }
            if (QUERY_ENGINES.contains(call.getNameAsString())
                    && call.getArguments().stream().anyMatch(BinaryExpr.class::isInstance)) {
                findings.add(PolicyFinding.inFile(named, QUERY_BY_CONCATENATION,
                        call.getNameAsString() + " is given a statement built out of pieces"));
            }
            // A repository call always has something to the left of it. A bare call by the same
            // name is a helper in this repository's own code, and flagging one would be flagging a
            // method for its spelling rather than for what it does.
            if (NAME_TAKING_CALLS.contains(call.getNameAsString()) && call.getScope().isPresent()
                    && !isEscaped(call)) {
                findings.add(PolicyFinding.inFile(named, UNESCAPED_REPOSITORY_NAME,
                        call.getNameAsString() + " is given a name nothing escaped, and a colon in"
                                + " one chooses a namespace rather than naming anything"));
            }
        });
        return findings;
    }

    /**
     * One source, parsed.
     *
     * @param source where it sits
     * @return its compilation unit
     */
    private static CompilationUnit parsed(Path source) {
        final ParseResult<CompilationUnit> read = PARSER.parse(RepositoryTree.text(source));
        if (!read.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + read.getProblems());
        }
        return read.getResult().orElseThrow();
    }

    /**
     * Whether a name-taking call was given something that had been made safe.
     *
     * <p>A literal counts, because a name written here is a name somebody chose rather than one a
     * caller supplied — and the rule is about a caller's value reaching a name, not about the
     * spelling of a constant.</p>
     *
     * @param call the call
     * @return whether its name is safe
     */
    private static boolean isEscaped(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return true;
        }
        final String name = call.getArgument(0).toString();
        return call.getArgument(0).isStringLiteralExpr()
                || name.matches("[A-Z][A-Z0-9_]*")
                || ALREADY_SAFE.stream().anyMatch(name::contains)
                || ALREADY_SAFE.stream().anyMatch(safe -> whereItCameFrom(call, name).contains(safe));
    }

    /**
     * What a local was assigned, so a name is judged by where it came from rather than by its own
     * spelling.
     *
     * <p>One level, deliberately. A name assigned from a validated value type is safe and reads as
     * a bare local at the call; a name assigned from a local assigned from another is a source
     * nobody can follow by eye either, and refusing it is the right answer.</p>
     *
     * @param call the name-taking call
     * @param name what the name expression is spelled as
     * @return what that name was assigned, or the empty string where it is not a local here
     */
    private static String whereItCameFrom(MethodCallExpr call, String name) {
        return enclosingMethod(call)
                .map(method -> method.findAll(VariableDeclarator.class).stream()
                        .filter(declared -> declared.getNameAsString().equals(name))
                        .map(declared -> declared.getInitializer()
                                .map(Object::toString).orElse(""))
                        .findFirst()
                        .orElse(""))
                .orElse("");
    }

    /**
     * The method one call sits in, found by walking up rather than by a varargs search.
     *
     * @param call the call
     * @return the method, or nothing where it sits outside one
     */
    private static Optional<MethodDeclaration> enclosingMethod(MethodCallExpr call) {
        Optional<Node> above = call.getParentNode();
        while (above.isPresent()) {
            if (above.get() instanceof final MethodDeclaration method) {
                return Optional.of(method);
            }
            above = above.get().getParentNode();
        }
        return Optional.empty();
    }
}
