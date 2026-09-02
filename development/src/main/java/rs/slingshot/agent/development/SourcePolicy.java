// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.SimpleName;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The rules about this repository's source that no off-the-shelf analyser has.
 *
 * <p>Everything here is decided by parsing rather than by matching text, so a file that names a
 * forbidden construct inside a comment or a string literal passes. That is not a nicety: a source
 * policy that matched text would refuse the document explaining the rule, and a rule nobody can
 * explain is a rule somebody eventually deletes.</p>
 *
 * <p>The second-declaration rule is the one this repository exists to have. A bound the agent
 * contract declares is read from it by name; a constant named after one, or a literal equal to one,
 * anywhere outside the package that owns the contract, is a second thing that can disagree with the
 * first quietly, for as long as nobody compares them.</p>
 *
 * <p>It has two forms and they catch different mistakes. The named form compares a constant's name
 * against the contract's own keys and carries no threshold, because a constant named after a bound
 * is a second declaration whatever its value. The value form compares numbers, and it is the one
 * that has to be careful: the contract declares enough bounds that value equality alone is nearly
 * meaningless, so it examines a number only where it could be stating a bound at all. See
 * {@link #statesABound}.</p>
 */
public final class SourcePolicy {

    private static final String POLICY_FILE = "policy/source-policy.toml";

    private static final String ABBREVIATION_FILE = "policy/abbreviated-identifiers.txt";

    private static final String EXCLUDED_ROWS = "excluded_directory";

    private static final String REVIEW_ROWS = "review_question";

    private static final String DICTATED_ROWS = "dictated_name";

    private static final String REFUSED_ROWS = "refused_symbol";

    /** The rows naming the words that make a constant's name a statement that it is a bound. */
    private static final String BOUNDING_ROWS = "bounding_word";

    /** The rules this checker decides. Everything else is a review question. */
    public static final List<String> RULES = List.of("file-length", "abbreviated-name",
            "single-character-name", "magic-number", "second-declaration");

    /** How a Java name is split into the words it spells. */
    private static final Pattern WORD_BOUNDARY = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_");

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    /** How Java spells a hexadecimal literal. */
    private static final String HEXADECIMAL_PREFIX = "0x";

    /** The base a hexadecimal literal is written in. */
    private static final int HEXADECIMAL_RADIX = 16;

    /** Where a source file sits, which decides which rules apply to it. */
    public enum Tree {
        /** Product and tooling code this repository ships or runs. */
        MAIN,
        /** Code that proves the rest, where an expected value is data rather than a quantity. */
        TEST
    }

    private final long maximumFileLines;
    private final long smallestMeaningfulLiteral;
    private final long secondDeclarationMinimumValue;
    private final String contractPackage;
    private final List<String> abbreviations;
    private final List<String> dictatedNames;
    private final List<String> excludedDirectories;
    private final List<String> reviewQuestions;
    private final SequencedMap<String, Long> contractBounds;

    private final List<String> refusedSymbols;

    private final List<String> boundingWords;

    private SourcePolicy(long maximumFileLines, long smallestMeaningfulLiteral,
                         long secondDeclarationMinimumValue, String contractPackage,
                         List<String> abbreviations, List<String> dictatedNames,
                         List<String> excludedDirectories,
                         List<String> reviewQuestions, SequencedMap<String, Long> contractBounds,
                         List<String> refusedSymbols, List<String> boundingWords) {
        this.boundingWords = boundingWords;
        this.maximumFileLines = maximumFileLines;
        this.smallestMeaningfulLiteral = smallestMeaningfulLiteral;
        this.secondDeclarationMinimumValue = secondDeclarationMinimumValue;
        this.contractPackage = contractPackage;
        this.abbreviations = abbreviations;
        this.dictatedNames = dictatedNames;
        this.excludedDirectories = excludedDirectories;
        this.reviewQuestions = reviewQuestions;
        this.contractBounds = contractBounds;
        this.refusedSymbols = refusedSymbols;
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(SourcePolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the source policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("source-policy")
                .text("format")
                .number("limits.maximum_code_file_lines")
                .number("limits.smallest_meaningful_literal")
                .number("limits.second_declaration_minimum_value")
                .rows(BOUNDING_ROWS, row -> row.text("word").text("reason"))
                .text("contract.owning_package")
                .text("contract.document")
                .rows(EXCLUDED_ROWS, row -> row.text("path").text("reason"))
                .rows(DICTATED_ROWS, row -> row.text("name").text("dictated_by")
                        .text("reason"))
                .rows(REVIEW_ROWS, row -> row.text("question").text("reason"))
                .rows(REFUSED_ROWS, row -> row.text("symbol").text("reason"))
                .text("handlers.package")
                .text("handlers.reason")
                .rows(HANDLER_ROWS, row -> row.text("symbol").text("reason"))
                .text("logging.writer")
                .text("logging.package")
                .text("logging.reason")
                .rows(LOGGER_ROWS, row -> row.text("symbol").text("reason"))
                .rows(LOG_FORM_ROWS, row -> row.text("form").text("reason"))
                .text("clocks.wall_clock")
                .text("clocks.monotonic")
                .text("clocks.seam")
                .text("clocks.reason")
                .rows(DURATION_ROWS, row -> row.text("word").text("reason"))
                .build();
    }

    /** Where the rows naming what a duration is called sit. */
    public static final String DURATION_ROWS = "duration_word";

    /** Where the rows naming the logging forms nothing outside the writer may use sit. */
    public static final String LOGGER_ROWS = "refused_logger";

    /** Where the rows naming the message forms the writer refuses sit. */
    public static final String LOG_FORM_ROWS = "refused_log_form";

    /** Where the rows naming what a handler may not reach sit. */
    private static final String HANDLER_ROWS = "refused_in_handlers";

    /**
     * Whether anything under the handler package reaches for something it was not given.
     *
     * <p>Read from the document each time rather than carried on the policy, because this is a
     * question about a directory rather than about a file, and the policy is what a file is held
     * to.</p>
     *
     * <p>Comments are removed before the scan. Naming a refused form in a comment is explaining why
     * it is refused, and a rule that could not tell that from reaching for one would be a rule that
     * stopped people writing the explanation down.</p>
     *
     * @param root the repository root
     * @return one finding per appearance, naming the file and the symbol
     */
    public static PolicyReport handlerFindings(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(POLICY_FILE), shape());
        if (outcome instanceof PolicyDocument.Refused) {
            return PolicyReport.of(List.of(PolicyFinding.inFile(POLICY_FILE, "source-policy",
                    "the policy document itself was refused")));
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final Path handlers = root.resolve("core/src/main/java")
                .resolve(document.text("handlers.package"));
        return handlerFindingsIn(handlers, root,
                document.rows(HANDLER_ROWS).stream().map(row -> row.text("symbol")).toList());
    }

    /**
     * Whether anything under one directory reaches for something a handler was not given.
     *
     * @param handlers the directory the handlers sit in
     * @param root what a finding's path is named relative to
     * @param refused every form that would obtain what a handler was not given
     * @return one finding per appearance, naming the file and the symbol
     */
    public static PolicyReport handlerFindingsIn(Path handlers, Path root,
                                                 List<String> refused) {
        if (!java.nio.file.Files.isDirectory(handlers)) {
            return PolicyReport.of(List.of());
        }
        final List<PolicyFinding> findings = new java.util.ArrayList<>();
        RepositoryTree.filesUnder(handlers, ".java").forEach(source -> refused.stream()
                .filter(symbol -> withoutComments(RepositoryTree.text(source)).contains(symbol))
                .map(symbol -> PolicyFinding.inFile(root.relativize(source).toString(),
                        "handler-reaches-outside-its-context", symbol))
                .forEach(findings::add));
        return PolicyReport.of(findings);
    }

    /**
     * Every form a handler may not reach for, as the policy declares them.
     *
     * @param root the repository root
     * @return the symbols, in the policy's own order
     */
    public static List<String> refusedInHandlers(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(POLICY_FILE), shape());
        return outcome instanceof final PolicyDocument.Loaded loaded
                ? loaded.document().rows(HANDLER_ROWS).stream().map(row -> row.text("symbol"))
                        .toList()
                : List.of();
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .lines()
                .map(line -> line.indexOf("//") >= 0 ? line.substring(0, line.indexOf("//")) : line)
                .reduce("", (all, line) -> all + line + "\n");
    }

    /**
     * Reads the policy this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readPolicy(root.resolve(POLICY_FILE), root);
    }

    /**
     * Reads a policy document from wherever it sits.
     *
     * @param policy the policy document
     * @param root the repository the policy's own paths are relative to
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome readPolicy(Path policy, Path root) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(policy, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final Optional<PolicyDocument> unexplained = document.rows(EXCLUDED_ROWS).stream()
                .filter(row -> row.text("reason").isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused("an excluded directory records no reason");
        }
        return new Loaded(new SourcePolicy(
                document.number("limits.maximum_code_file_lines"),
                document.number("limits.smallest_meaningful_literal"),
                document.number("limits.second_declaration_minimum_value"),
                document.text("contract.owning_package"),
                abbreviations(root),
                document.rows(DICTATED_ROWS).stream().map(row -> row.text("name")).toList(),
                document.rows(EXCLUDED_ROWS).stream().map(row -> row.text("path")).toList(),
                document.rows(REVIEW_ROWS).stream().map(row -> row.text("question")).toList(),
                contractBounds(root.resolve(document.text("contract.document"))),
                document.rows(REFUSED_ROWS).stream().map(row -> row.text("symbol")).toList(),
                document.rows(BOUNDING_ROWS).stream().map(row -> row.text("word")).toList()));
    }

    /**
     * Every symbol this repository refuses, and where it appears.
     *
     * <p>A rule of this kind exists because the thing it refuses is attractive. The scan is over
     * the whole repository rather than over one module, because the reason a symbol is refused does
     * not stop applying in a module nobody thought of.</p>
     *
     * @param root the repository root
     * @return one finding per appearance, naming the symbol and the file
     */
    public PolicyReport refusedSymbols(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> excludedDirectories.stream()
                        .noneMatch(excluded -> source.toString().contains(excluded)))
                .forEach(source -> findings.addAll(
                        refusedSymbolsIn(source, root.relativize(source).toString()).findings()));
        return PolicyReport.of(findings);
    }

    /**
     * Every refused symbol one file carries, whatever directory it is in.
     *
     * <p>Separate from the whole-repository scan so that a suite can prove the rule against a
     * fixture, which lives in exactly the directory the scan does not enter.</p>
     *
     * @param source the file
     * @param named how the finding names it
     * @return one finding per appearance
     */
    public PolicyReport refusedSymbolsIn(Path source, String named) {
        return PolicyReport.of(refusedSymbols.stream()
                .filter(symbol -> RepositoryTree.text(source).contains(symbol))
                .map(symbol -> PolicyFinding.inFile(named, "refused-symbol", symbol))
                .toList());
    }

    /**
     * Every symbol this policy refuses.
     *
     * @return the symbols, in the policy's own order
     */
    public List<String> refusedSymbolsDeclared() {
        return Collections.unmodifiableList(refusedSymbols);
    }

    private static List<String> abbreviations(Path root) {
        return RepositoryTree.text(root.resolve(ABBREVIATION_FILE)).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && line.charAt(0) != '#')
                .toList();
    }

    private static SequencedMap<String, Long> contractBounds(Path contract) {
        final SequencedMap<String, Long> bounds = new LinkedHashMap<>();
        try {
            final TomlParseResult parsed = Toml.parse(contract);
            if (!parsed.errors().isEmpty()) {
                throw new IllegalStateException(contract + " does not parse: " + parsed.errors());
            }
            parsed.dottedKeySet().forEach(key ->
                    Optional.ofNullable(parsed.getLong(key))
                            .ifPresent(value -> bounds.put(key.substring(key.indexOf('.') + 1), value)));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return bounds;
    }

    /**
     * The shortened forms a declared name may not use.
     *
     * @return the closed list, in the file's own order
     */
    public List<String> abbreviations() {
        return Collections.unmodifiableList(abbreviations);
    }

    /**
     * The questions this checker deliberately does not answer.
     *
     * @return the review checklist, in the policy's own order
     */
    public List<String> reviewQuestions() {
        return Collections.unmodifiableList(reviewQuestions);
    }

    /**
     * Every bound the agent contract declares, by its own name.
     *
     * @return the bounds, in the contract's own order
     */
    public SequencedMap<String, Long> contractBounds() {
        return new LinkedHashMap<>(contractBounds);
    }

    /**
     * Holds every repository-owned Java source to every rule.
     *
     * @param root the repository root
     * @return one finding per rule each source breaks, ordered deterministically
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> excludedDirectories.stream()
                        .noneMatch(excluded -> root.relativize(source).toString().contains(excluded)))
                .forEach(source -> {
                    final String named = root.relativize(source).toString();
                    findings.addAll(inFile(named, source, treeOf(root.relativize(source))));
                    findings.addAll(refusedSymbolsIn(source, named).findings());
                });
        return PolicyReport.of(findings);
    }

    /**
     * Holds one Java source to every rule that applies to where it sits.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @param tree where the file sits
     * @return one finding per rule it breaks
     */
    public List<PolicyFinding> inFile(String name, Path source, Tree tree) {
        final String text = RepositoryTree.text(source);
        final List<PolicyFinding> findings = new ArrayList<>(fileLengthFindings(name, text));
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(text);
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + parsed.getProblems());
        }
        final CompilationUnit unit = parsed.getResult().orElseThrow();
        findings.addAll(nameFindings(name, unit));
        if (tree == Tree.MAIN) {
            findings.addAll(literalFindings(name, unit, declaredPackage(unit)));
        }
        return Collections.unmodifiableList(findings);
    }

    private List<PolicyFinding> fileLengthFindings(String name, String text) {
        final long lines = text.lines().count();
        if (lines <= maximumFileLines) {
            return List.of();
        }
        return List.of(PolicyFinding.inFile(name, "file-length",
                lines + " physical lines, above the ceiling of " + maximumFileLines));
    }

    private List<PolicyFinding> nameFindings(String name, CompilationUnit unit) {
        final List<PolicyFinding> findings = new ArrayList<>();
        declaredNames(unit).stream()
                .filter(declared -> !dictatedNames.contains(declared.getIdentifier()))
                .forEach(declared -> findings.addAll(nameFindingsFor(name, declared)));
        return findings;
    }

    /**
     * Whether one declared name spells its words in full.
     *
     * @param file what to call the file in a finding
     * @param declared the name as it was declared
     * @return one finding where the name is a single character or spells a word short
     */
    private List<PolicyFinding> nameFindingsFor(String file, SimpleName declared) {
        final String spelled = declared.getIdentifier();
        if (spelled.length() == 1) {
            return List.of(finding(file, declared, "single-character-name", spelled));
        }
        return words(spelled).stream()
                .filter(abbreviations::contains)
                .findFirst()
                .map(word -> finding(file, declared, "abbreviated-name",
                        spelled + " spells " + word + " short"))
                .stream()
                .toList();
    }

    private List<PolicyFinding> literalFindings(String name, CompilationUnit unit,
                                                String declaredPackage) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final boolean ownsContract = declaredPackage.equals(contractPackage);
        unit.findAll(Node.class).stream()
                .filter(SourcePolicy::isNumericLiteral)
                .forEach(literal -> numericValue(literal).ifPresent(value -> {
                    final boolean named = isAlreadyData(literal);
                    if (!ownsContract && value >= secondDeclarationMinimumValue
                            && statesABound(literal)) {
                        contractBounds.entrySet().stream()
                                .filter(bound -> bound.getValue().equals(value))
                                // Two bounds can hold one value, and which of them a finding names
                                // would otherwise depend on the order a map was built in - so a
                                // report over one tree would differ between two runs of it.
                                .min(java.util.Map.Entry.comparingByKey())
                                .map(bound -> finding(name, literal, "second-declaration",
                                        value + " is the contract's own " + bound.getKey()))
                                .ifPresent(findings::add);
                    }
                    if (!named && value >= smallestMeaningfulLiteral) {
                        findings.add(finding(name, literal, "magic-number", String.valueOf(value)));
                    }
                }));
        if (!ownsContract) {
            findings.addAll(constantNameFindings(name, unit));
        }
        return findings;
    }

    private List<PolicyFinding> constantNameFindings(String name, CompilationUnit unit) {
        return unit.findAll(FieldDeclaration.class).stream()
                .filter(FieldDeclaration::isStatic)
                .filter(FieldDeclaration::isFinal)
                .flatMap(field -> field.getVariables().stream())
                .flatMap(variable -> contractBounds.keySet().stream()
                        .filter(bound -> bound.equals(normalised(variable.getNameAsString())))
                        .map(bound -> finding(name, variable, "second-declaration",
                                variable.getNameAsString() + " is named after the contract's "
                                        + bound)))
                .toList();
    }

    /**
     * Whether the checker restates any question the policy records as a reader's.
     *
     * @return one finding per review question that names a rule this checker decides
     */
    public PolicyReport reviewChecklist() {
        final List<PolicyFinding> findings = new ArrayList<>();
        reviewQuestions.stream()
                .flatMap(question -> RULES.stream()
                        .filter(rule -> question.toLowerCase(Locale.ROOT).contains(rule))
                        .map(rule -> PolicyFinding.inFile(POLICY_FILE, "review-checklist",
                                "the question \"" + question + "\" restates the rule " + rule)))
                .forEach(findings::add);
        if (reviewQuestions.isEmpty()) {
            findings.add(PolicyFinding.inFile(POLICY_FILE, "review-checklist",
                    "the policy records no question the checker leaves to a reader"));
        }
        return PolicyReport.of(findings);
    }

    private static Tree treeOf(Path relative) {
        for (final Path segment : relative) {
            if ("test".equals(segment.toString())) {
                return Tree.TEST;
            }
        }
        return Tree.MAIN;
    }

    private static String declaredPackage(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(declaration -> declaration.getName().asString())
                .orElse("");
    }

    private static List<SimpleName> declaredNames(CompilationUnit unit) {
        final List<SimpleName> names = new ArrayList<>();
        unit.findAll(ClassOrInterfaceDeclaration.class).forEach(node -> names.add(node.getName()));
        unit.findAll(RecordDeclaration.class).forEach(node -> {
            names.add(node.getName());
            node.getParameters().forEach(component -> names.add(component.getName()));
        });
        unit.findAll(EnumDeclaration.class).forEach(node -> names.add(node.getName()));
        unit.findAll(EnumConstantDeclaration.class).forEach(node -> names.add(node.getName()));
        unit.findAll(AnnotationDeclaration.class).forEach(node -> names.add(node.getName()));
        unit.findAll(MethodDeclaration.class).forEach(node -> names.add(node.getName()));
        unit.findAll(Parameter.class).forEach(node -> names.add(node.getName()));
        unit.findAll(VariableDeclarator.class).forEach(node -> names.add(node.getName()));
        return names;
    }

    private static List<String> words(String identifier) {
        return List.of(WORD_BOUNDARY.split(identifier)).stream()
                .map(word -> word.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    private static String normalised(String identifier) {
        return String.join("_", words(identifier));
    }

    private static boolean isNumericLiteral(Node node) {
        return node instanceof IntegerLiteralExpr || node instanceof LongLiteralExpr
                || node instanceof DoubleLiteralExpr;
    }

    private static Optional<Long> numericValue(Node literal) {
        final String text = literal.toString().replace("_", "")
                .replace("L", "").replace("l", "");
        try {
            final boolean hexadecimal = text.startsWith("0x") || text.startsWith("0X");
            return Optional.of(hexadecimal
                    ? Long.parseLong(text.substring(HEXADECIMAL_PREFIX.length()), HEXADECIMAL_RADIX)
                    : Long.parseLong(text));
        } catch (final NumberFormatException notAWholeNumber) {
            return Optional.empty();
        }
    }

    /**
     * Whether a literal is already data with a name, data laid out as data, or a position rather
     * than a quantity: the initialiser of a named constant, an entry in an array literal, or an
     * argument to an annotation.
     */
    /**
     * Whether a literal is one that could be restating a bound at all.
     *
     * <p>The value form of the second-declaration rule compares a number against every bound the
     * contract declares, and the contract declares a hundred and forty-four of them. Between the
     * two client contracts, every ordinary power of two from four thousand to a hundred and thirty
     * thousand is now a bound, and so is a thousand. That makes bare value equality nearly
     * meaningless on its own: a read buffer, a milliseconds-in-a-second conversion, and a bound are
     * all the same number, and a rule that called all three a second declaration would report the
     * wrong thing about the two that are not — which is the very failure this policy's own
     * threshold was introduced to avoid.</p>
     *
     * <p>So a number is examined where it could be stating a bound: written bare into an expression,
     * where a reader has nothing but the digits to go on, or held in a constant whose name says it
     * is a bound. A constant that says it is something else — a conversion, a buffer, a count — is
     * taken at its word. What that gives up is a bound hidden in a deliberately misleading name,
     * and what it keeps is every finding that was worth having: the named form still catches a
     * constant named after a bound, and this form still catches {@code QUERY_CEILING = 8192}.</p>
     *
     * @param literal the literal to judge
     * @return whether it is bare, or held in a constant whose name states a bound
     */
    private boolean statesABound(Node literal) {
        return !isAlreadyData(literal) || namesABound(literal);
    }

    private boolean namesABound(Node literal) {
        return ancestorOf(literal, VariableDeclarator.class)
                .map(variable -> words(variable.getNameAsString()))
                .stream()
                .flatMap(List::stream)
                .anyMatch(boundingWords::contains);
    }

    private static boolean isAlreadyData(Node literal) {
        return ancestorOf(literal, FieldDeclaration.class)
                .filter(field -> field.isStatic() && field.isFinal())
                .isPresent()
                || ancestorOf(literal, ArrayInitializerExpr.class).isPresent()
                || ancestorOf(literal, EnumConstantDeclaration.class).isPresent();
    }

    /**
     * The nearest enclosing node of a kind, found by walking the parents rather than by a varargs
     * search whose generic array the compiler cannot check.
     *
     * @param node the node to start from
     * @param kind the kind of ancestor to look for
     * @param <T> the ancestor's type
     * @return the nearest ancestor of that kind, or nothing where the node has none
     */
    private static <T extends Node> Optional<T> ancestorOf(Node node, Class<T> kind) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            final Node candidate = parent.get();
            if (kind.isInstance(candidate)) {
                return Optional.of(kind.cast(candidate));
            }
            parent = candidate.getParentNode();
        }
        return Optional.empty();
    }

    private static PolicyFinding finding(String file, Node node, String rule, String symbol) {
        return new PolicyFinding(file,
                node.getBegin().map(position -> position.line).orElse(PolicyFinding.NO_LINE),
                rule, symbol);
    }

}
