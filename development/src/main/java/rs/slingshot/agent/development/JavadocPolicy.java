// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.regex.Pattern;

/**
 * The half of documentation a checker can decide.
 *
 * <p>Whether prose is accurate or worth reading is a reader's judgement, recorded as a checklist.
 * Whether it exists, covers every parameter, names every declared failure, describes what is
 * returned, and does not merely restate the member's own name is decidable — and the last of those
 * is the one worth having, because a summary that spells the member's name back is the most common
 * way documentation is present and says nothing.</p>
 */
public final class JavadocPolicy {

    private static final String POLICY_FILE = "policy/javadoc.toml";

    private static final String EXCLUSION_ROWS = "exclusion";

    private static final String REVIEW_ROWS = "review_question";

    private static final String REFUSED_SUMMARY_ROWS = "refused_summary";

    /** How a Java name is split into the words it spells. */
    private static final Pattern WORD_BOUNDARY = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_");

    /** How far apart the two cases of one letter sit in the encoding this repository is written in. */
    private static final int LETTER_CASE_DISTANCE = 'a' - 'A';

    /** Words a summary may open with that add nothing to the member's own name. */
    private static final List<String> EMPTY_OPENINGS =
            List.of("the", "a", "an", "this", "returns", "return", "gets", "get", "sets", "set");

    /** Text that is a promise rather than a description. */
    private static final List<String> PLACEHOLDERS =
            List.of("todo", "fixme", "tbd", "to be written", "documentation");

    private static final String INHERIT_TAG = "{@inheritDoc}";

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final List<String> exclusions;
    private final List<String> reviewQuestions;
    private final List<String> refusedSummaries;

    private JavadocPolicy(List<String> exclusions, List<String> reviewQuestions,
                          List<String> refusedSummaries) {
        this.exclusions = exclusions;
        this.reviewQuestions = reviewQuestions;
        this.refusedSummaries = refusedSummaries;
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(JavadocPolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the documentation policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("javadoc")
                .answer("required.types")
                .answer("required.non_private_members")
                .answer("required.parameters")
                .answer("required.type_parameters")
                .answer("required.declared_failures")
                .answer("required.returns")
                .answer("required.package_documentation")
                .answer("inheritance.permitted")
                .text("inheritance.reason")
                .rows(REFUSED_SUMMARY_ROWS, row -> row.text("form").text("reason"))
                .rows("inheritance_refusal", row -> row.text("kind").text("reason"))
                .rows(EXCLUSION_ROWS, row -> row.text("path").text("reason"))
                .rows(REVIEW_ROWS, row -> row.text("question").text("reason"))
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
        final Optional<String> unrequired = List.of("required.types", "required.non_private_members",
                        "required.parameters", "required.declared_failures", "required.returns",
                        "required.package_documentation").stream()
                .filter(key -> !document.answer(key))
                .findFirst();
        if (unrequired.isPresent()) {
            return new Refused("the policy does not require " + unrequired.get());
        }
        return new Loaded(new JavadocPolicy(
                document.rows(EXCLUSION_ROWS).stream().map(row -> row.text("path")).toList(),
                document.rows(REVIEW_ROWS).stream().map(row -> row.text("question")).toList(),
                document.rows(REFUSED_SUMMARY_ROWS).stream().map(row -> row.text("form")).toList()));
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
     * The summary shapes the policy refuses outright.
     *
     * @return the refused forms, in the policy's own order
     */
    public List<String> refusedSummaries() {
        return Collections.unmodifiableList(refusedSummaries);
    }

    /**
     * Holds every documented member in a tree to the completeness rules.
     *
     * @param root the repository root
     * @return one finding per member whose documentation is absent or incomplete
     */
    public PolicyReport across(Path root) {
        final List<Path> sources = RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> exclusions.stream()
                        .noneMatch(excluded -> root.relativize(source).toString().contains(excluded)))
                .toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        sources.forEach(source ->
                findings.addAll(inFile(root.relativize(source).toString(), source)));
        findings.addAll(packageDocumentationFindings(root, sources));
        return PolicyReport.of(findings);
    }

    /**
     * Holds one file's members to the completeness rules.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per member whose documentation is absent or incomplete
     */
    public List<PolicyFinding> inFile(String name, Path source) {
        final CompilationUnit unit = parse(source);
        final List<PolicyFinding> findings = new ArrayList<>();
        unit.findAll(TypeDeclaration.class).stream()
                .filter(type -> !type.isPrivate())
                .forEach(type -> findings.addAll(documentedFindings(name, type, type.getNameAsString())));
        unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> !method.isPrivate())
                .filter(method -> !isInsideAPrivateType(method))
                .forEach(method -> findings.addAll(methodFindings(name, method)));
        unit.findAll(ConstructorDeclaration.class).stream()
                .filter(constructor -> !constructor.isPrivate())
                .filter(constructor -> !isImplicitlyPrivate(constructor))
                .filter(constructor -> !isInsideAPrivateType(constructor))
                .forEach(constructor -> findings.addAll(constructorFindings(name, constructor)));
        unit.findAll(RecordDeclaration.class).forEach(record ->
                findings.addAll(recordComponentFindings(name, record)));
        return Collections.unmodifiableList(findings);
    }

    private List<PolicyFinding> documentedFindings(String name, NodeWithJavadoc<?> member,
                                                   String declared) {
        final Optional<Javadoc> documentation = member.getJavadoc();
        if (documentation.isEmpty()) {
            return List.of(finding(name, (Node) member, "documentation-absent", declared));
        }
        final String summary = documentation.get().getDescription().toText().strip();
        if (summary.isEmpty() || isPlaceholder(summary)) {
            return List.of(finding(name, (Node) member, "documentation-placeholder", declared));
        }
        if (restatesName(summary, declared)) {
            return List.of(finding(name, (Node) member, "documentation-restates-name", declared));
        }
        return List.of();
    }

    private List<PolicyFinding> methodFindings(String name, MethodDeclaration method) {
        final String declared = method.getNameAsString();
        if (method.getJavadoc().isEmpty() && isInheritedContract(method)) {
            return List.of();
        }
        final List<PolicyFinding> findings = new ArrayList<>(documentedFindings(name, method, declared));
        if (!findings.isEmpty()) {
            return findings;
        }
        final Javadoc documentation = method.getJavadoc().orElseThrow();
        final SequencedSet<String> describedParameters = tagNames(documentation, JavadocBlockTag.Type.PARAM);
        method.getParameters().stream()
                .filter(parameter -> !describedParameters.contains(parameter.getNameAsString()))
                .map(parameter -> finding(name, parameter, "documentation-parameter",
                        declared + " does not describe " + parameter.getNameAsString()))
                .forEach(findings::add);
        method.getTypeParameters().stream()
                .filter(parameter -> !describedParameters.contains("<" + parameter.getNameAsString() + ">"))
                .map(parameter -> finding(name, method, "documentation-type-parameter",
                        declared + " does not describe " + parameter.getNameAsString()))
                .forEach(findings::add);
        if (!method.getType().isVoidType() && !hasTag(documentation, JavadocBlockTag.Type.RETURN)) {
            findings.add(finding(name, method, "documentation-return",
                    declared + " does not describe what it returns"));
        }
        method.getThrownExceptions().stream()
                .filter(thrown -> !describedFailures(documentation).contains(thrown.asString()))
                .map(thrown -> finding(name, method, "documentation-failure",
                        declared + " does not describe " + thrown.asString()))
                .forEach(findings::add);
        return findings;
    }

    private List<PolicyFinding> constructorFindings(String name, ConstructorDeclaration constructor) {
        if (constructor.getParameters().isEmpty() && constructor.getJavadoc().isEmpty()
                && isCompact(constructor)) {
            return List.of();
        }
        final List<PolicyFinding> findings =
                new ArrayList<>(documentedFindings(name, constructor, constructor.getNameAsString()));
        if (!findings.isEmpty() || isCompact(constructor)) {
            return findings;
        }
        final Javadoc documentation = constructor.getJavadoc().orElseThrow();
        final SequencedSet<String> described = tagNames(documentation, JavadocBlockTag.Type.PARAM);
        constructor.getParameters().stream()
                .filter(parameter -> !described.contains(parameter.getNameAsString()))
                .map(parameter -> finding(name, parameter, "documentation-parameter",
                        constructor.getNameAsString() + " does not describe "
                                + parameter.getNameAsString()))
                .forEach(findings::add);
        return findings;
    }

    private static List<PolicyFinding> recordComponentFindings(String name, RecordDeclaration record) {
        if (record.getJavadoc().isEmpty()) {
            return List.of();
        }
        final SequencedSet<String> described =
                tagNames(record.getJavadoc().orElseThrow(), JavadocBlockTag.Type.PARAM);
        return record.getParameters().stream()
                .filter(component -> !described.contains(component.getNameAsString()))
                .map(component -> finding(name, component, "documentation-parameter",
                        record.getNameAsString() + " does not describe "
                                + component.getNameAsString()))
                .toList();
    }

    private List<PolicyFinding> packageDocumentationFindings(Path root, List<Path> sources) {
        final SequencedSet<String> documented = new LinkedHashSet<>();
        final SequencedSet<String> declared = new LinkedHashSet<>();
        sources.forEach(source -> parse(source).getPackageDeclaration().ifPresent(declaration -> {
            declared.add(declaration.getName().asString());
            if ("package-info.java".equals(String.valueOf(source.getFileName()))) {
                documented.add(declaration.getName().asString());
            }
        }));
        return declared.stream()
                .filter(name -> !documented.contains(name))
                .map(name -> PolicyFinding.inFile(root.getFileName() + "/" + name,
                        "documentation-package", name + " has no package documentation"))
                .toList();
    }

    /**
     * Whether an override's contract is genuinely the one it inherits: it declares no failure its
     * supertype could not have, so restating the documentation would only create a second place for
     * it to drift.
     */
    private static boolean isInheritedContract(MethodDeclaration method) {
        return method.getAnnotationByName("Override").isPresent()
                && method.getThrownExceptions().isEmpty();
    }

    /**
     * Whether a member sits inside a type nothing outside its own file can reach, in which case the
     * enclosing type's own documentation is the contract that applies to it.
     */
    private static boolean isInsideAPrivateType(Node member) {
        Optional<Node> parent = member.getParentNode();
        while (parent.isPresent()) {
            final Node enclosing = parent.get();
            if (enclosing instanceof final TypeDeclaration<?> type && type.isPrivate()) {
                return true;
            }
            parent = enclosing.getParentNode();
        }
        return false;
    }

    /**
     * Whether a constructor is one the language already closes: an enumeration's constructor can be
     * reached by nothing outside the enumeration, whatever modifier it carries.
     */
    private static boolean isImplicitlyPrivate(ConstructorDeclaration constructor) {
        return constructor.getParentNode().filter(EnumDeclaration.class::isInstance).isPresent();
    }

    /** Whether a constructor is a record's compact one, whose components the record documents. */
    private static boolean isCompact(ConstructorDeclaration constructor) {
        return constructor.getParameters().isEmpty()
                && constructor.getParentNode().filter(RecordDeclaration.class::isInstance).isPresent();
    }

    private static boolean isPlaceholder(String summary) {
        final String text = summary.strip();
        return PLACEHOLDERS.contains(folded(text)) || INHERIT_TAG.equals(text);
    }

    /**
     * One text folded to the form two spellings of the same words share.
     *
     * <p>The folding is over the letters a Java identifier and an English summary are spelled with
     * and no others. Nothing here transforms Unicode: a case mapping that consulted the platform's
     * own tables would answer differently under a different locale, and a rule that changed its
     * mind depending on where the build ran would not be a rule.</p>
     *
     * @param text the text to fold
     * @return the folded text
     */
    private static String folded(String text) {
        final StringBuilder lowered = new StringBuilder(text.length());
        text.chars().forEach(character -> lowered.append(loweredLetter(character)));
        return lowered.toString();
    }

    /**
     * One character lowered, for the letters a Java name and an English summary are spelled with.
     *
     * <p>The mapping is explicit rather than the platform's own, because Unicode case mapping is
     * locale-dependent: two texts a reader would call the same can map to different characters
     * depending on where the build runs.</p>
     */
    private static char loweredLetter(int character) {
        final boolean upperCaseLetter = character >= 'A' && character <= 'Z';
        return (char) (upperCaseLetter ? character + LETTER_CASE_DISTANCE : character);
    }

    /**
     * Whether a summary is the member's own name with the spaces put back.
     *
     * @param summary the summary as written
     * @param declared the member's declared name
     * @return whether the summary adds nothing to the name
     */
    public static boolean restatesName(String summary, String declared) {
        final String spelled = String.join(" ", words(declared));
        final List<String> written = new ArrayList<>(List.of(summary
                .replaceAll("[.,;:]", "").strip().split("\\s+")));
        while (!written.isEmpty() && EMPTY_OPENINGS.contains(folded(written.getFirst()))) {
            written.removeFirst();
        }
        return folded(spelled).equals(folded(String.join(" ", written)));
    }

    private static List<String> words(String identifier) {
        return List.of(WORD_BOUNDARY.split(identifier)).stream()
                .filter(word -> !word.isEmpty())
                .toList();
    }

    private static SequencedSet<String> tagNames(Javadoc documentation, JavadocBlockTag.Type type) {
        final SequencedSet<String> names = new LinkedHashSet<>();
        documentation.getBlockTags().stream()
                .filter(tag -> tag.getType() == type)
                .forEach(tag -> tag.getName().ifPresent(names::add));
        return names;
    }

    private static SequencedSet<String> describedFailures(Javadoc documentation) {
        final SequencedSet<String> names = new LinkedHashSet<>();
        documentation.getBlockTags().stream()
                .filter(tag -> tag.getType() == JavadocBlockTag.Type.THROWS)
                .forEach(tag -> tag.getName().ifPresent(names::add));
        return names;
    }

    private static boolean hasTag(Javadoc documentation, JavadocBlockTag.Type type) {
        return documentation.getBlockTags().stream().anyMatch(tag -> tag.getType() == type);
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
