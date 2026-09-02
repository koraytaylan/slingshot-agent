// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The analysers this build runs, and the correspondence that makes a later scan find nothing.
 *
 * <p>Three things are decided here. That every category a scan would raise has a build-time
 * counterpart, so nothing is left for a server to notice. That every analyser fails at its first
 * finding over main and test sources alike, so a finding is fixed on the day it is written. And
 * that no rule can be switched off in source: Java has no annotation that states a reason and stops
 * applying when the situation it was written for ends, so every suppression form is refused
 * outright rather than configured.</p>
 */
public final class StaticAnalysis {

    private static final String POLICY_FILE = "policy/static-analysis.toml";

    private static final String ANALYSER_ROWS = "analyser";

    private static final String CATEGORY_ROWS = "category";

    private static final String OVERLAPPING_ROWS = "overlapping_rule";

    private static final String SUPPRESSION_ROWS = "suppression_form";

    private static final String EXCLUDED_ROWS = "excluded_rule";

    /** How an exclusion is spelled in the source-pattern analyser's own rule set. */
    private static final Pattern RULE_SET_EXCLUSION = Pattern.compile("<exclude name=\"([^\"]+)\"/>");

    /** The bug-pattern analyser's filter, which carries no filter at all. */
    private static final Pattern EMPTY_FILTER = Pattern.compile("<FindBugsFilter\\s*/>");

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private final List<AnalyserRow> analysers;
    private final List<CategoryRow> categories;
    private final List<OverlappingRule> overlapping;
    private final List<String> suppressionForms;
    /** The rules the source-pattern rule set does not run, which is the set checked both ways. */
    private final List<String> excludedRules;
    private final String exclusionFile;

    private StaticAnalysis(List<AnalyserRow> analysers, List<CategoryRow> categories,
                           List<OverlappingRule> overlapping, List<String> suppressionForms,
                           List<String> excludedRules, String exclusionFile) {
        this.analysers = analysers;
        this.categories = categories;
        this.overlapping = overlapping;
        this.suppressionForms = suppressionForms;
        this.excludedRules = excludedRules;
        this.exclusionFile = exclusionFile;
    }

    /** Whether an analyser fails the build at its first finding or produces a report. */
    public enum OnFinding {
        /** The build fails, on the machine that wrote the code, on the day it was written. */
        FAILS_BUILD,
        /** A report somebody reads later, which is a finding nobody fixes today. */
        PRODUCES_REPORT
    }

    /** Whether an analyser reads test sources as well as main ones. */
    public enum TestSources {
        /** Test sources are read, so a finding planted in one is found. */
        READ,
        /** Test sources are not read. */
        NOT_READ
    }

    /**
     * One analyser this build runs.
     *
     * @param identifier what the analyser is called
     * @param artifact the build plugin that runs it
     * @param configuration the rule set or filter it reads
     * @param onFinding whether it fails the build rather than producing a report
     * @param testSources whether it reads test sources as well as main ones
     */
    public record AnalyserRow(String identifier, String artifact, String configuration,
                              OnFinding onFinding, TestSources testSources) {

        /**
         * Whether the analyser fails the build at its first finding.
         *
         * @return whether a finding fails the build rather than producing a report
         */
        public boolean failsAtFirstFinding() {
            return onFinding == OnFinding.FAILS_BUILD;
        }

        /**
         * Whether the analyser reads test sources.
         *
         * @return whether test sources are read as well as main ones
         */
        public boolean coversTestSources() {
            return testSources == TestSources.READ;
        }
    }

    /**
     * One category a scan would raise, and the analyser that covers it here.
     *
     * @param name the category
     * @param coveredBy the analyser that decides it at build time
     * @param reason why that analyser covers it
     */
    public record CategoryRow(String name, String coveredBy, String reason) {
    }

    /**
     * A rule enabled deliberately because this repository's own doctrine decides the same thing.
     *
     * @param analyser the analyser the rule belongs to
     * @param rule the rule's own identifier
     * @param doctrine the policy that decides the same thing
     * @param reason why two independent decisions are worth having
     */
    public record OverlappingRule(String analyser, String rule, String doctrine, String reason) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(StaticAnalysis policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the static-analysis policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("static-analysis")
                .text("exclusions.file")
                .answer("exclusions.must_be_empty")
                .text("exclusions.reason")
                .rows(ANALYSER_ROWS, row -> row.text("id").text("artifact").text("configuration")
                        .answer("fails_at_first_finding").answer("covers_test_sources"))
                .rows(CATEGORY_ROWS, row -> row.text("name").text("covered_by").text("reason"))
                .rows(OVERLAPPING_ROWS, row -> row.text("analyser").text("rule").text("doctrine")
                        .text("reason"))
                .rows(SUPPRESSION_ROWS, row -> row.text("form").text("reason"))
                .rows(EXCLUDED_ROWS, row -> row.text("analyser").text("rule").text("scope")
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
        return new Loaded(new StaticAnalysis(
                document.rows(ANALYSER_ROWS).stream()
                        .map(row -> new AnalyserRow(row.text("id"), row.text("artifact"),
                                row.text("configuration"),
                                row.answer("fails_at_first_finding")
                                        ? OnFinding.FAILS_BUILD : OnFinding.PRODUCES_REPORT,
                                row.answer("covers_test_sources")
                                        ? TestSources.READ : TestSources.NOT_READ))
                        .toList(),
                document.rows(CATEGORY_ROWS).stream()
                        .map(row -> new CategoryRow(row.text("name"), row.text("covered_by"),
                                row.text("reason")))
                        .toList(),
                document.rows(OVERLAPPING_ROWS).stream()
                        .map(row -> new OverlappingRule(row.text("analyser"), row.text("rule"),
                                row.text("doctrine"), row.text("reason")))
                        .toList(),
                document.rows(SUPPRESSION_ROWS).stream().map(row -> row.text("form")).toList(),
                document.rows(EXCLUDED_ROWS).stream()
                        .filter(row -> "pmd".equals(row.text("analyser")))
                        .map(row -> row.text("rule"))
                        .toList(),
                document.text("exclusions.file")));
    }

    /**
     * Every analyser the policy declares.
     *
     * @return the analyser rows, in the policy's own order
     */
    public List<AnalyserRow> analysers() {
        return Collections.unmodifiableList(analysers);
    }

    /**
     * Every category the policy declares a covering analyser for.
     *
     * @return the category rows, in the policy's own order
     */
    public List<CategoryRow> categories() {
        return Collections.unmodifiableList(categories);
    }

    /**
     * Every rule enabled because the doctrine decides the same thing.
     *
     * @return the overlapping rules, in the policy's own order
     */
    public List<OverlappingRule> overlappingRules() {
        return Collections.unmodifiableList(overlapping);
    }

    /**
     * Every form of switching a rule off that is refused outright.
     *
     * @return the suppression forms, in the policy's own order
     */
    public List<String> suppressionForms() {
        return Collections.unmodifiableList(suppressionForms);
    }

    /**
     * Whether every declared category names an analyser that this build actually runs.
     *
     * @return one finding per category with no covering analyser, and per analyser covering nothing
     */
    public PolicyReport categoryCoverage() {
        final List<PolicyFinding> findings = new ArrayList<>();
        categories.stream()
                .filter(category -> analysers.stream()
                        .noneMatch(analyser -> analyser.identifier().equals(category.coveredBy())))
                .map(category -> PolicyFinding.inFile(POLICY_FILE, "category-coverage",
                        category.name() + " names analyser " + category.coveredBy()
                                + ", which this build does not run"))
                .forEach(findings::add);
        analysers.stream()
                .filter(analyser -> categories.stream()
                        .noneMatch(category -> category.coveredBy().equals(analyser.identifier())))
                .map(analyser -> PolicyFinding.inFile(POLICY_FILE, "category-coverage",
                        analyser.identifier() + " runs and covers no declared category"))
                .forEach(findings::add);
        overlapping.stream()
                .filter(rule -> analysers.stream()
                        .noneMatch(analyser -> analyser.identifier().equals(rule.analyser())))
                .map(rule -> PolicyFinding.inFile(POLICY_FILE, "category-coverage",
                        rule.rule() + " belongs to analyser " + rule.analyser()
                                + ", which this build does not run"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether the build runs every declared analyser the way the policy declares it.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per analyser that is absent, produces a report rather than failing, or
     *     does not read test sources
     */
    public PolicyReport configuration(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        analysers.forEach(analyser -> {
            final Optional<Xpp3Dom> configured =
                    reactor.pluginConfiguration("core", analyser.artifact());
            if (configured.isEmpty()) {
                findings.add(PolicyFinding.inFile("pom.xml", "analyser-configuration",
                        analyser.artifact() + " is declared in the policy and not in the build"));
                return;
            }
            final String rendered = configured.get().toString();
            if (analyser.failsAtFirstFinding() && rendered.contains("<failOnViolation>false<")) {
                findings.add(PolicyFinding.inFile("pom.xml", "analyser-configuration",
                        analyser.identifier() + " produces a report rather than failing the build"));
            }
            if (analyser.coversTestSources() && !readsTestSources(rendered)) {
                findings.add(PolicyFinding.inFile("pom.xml", "analyser-configuration",
                        analyser.identifier() + " does not read test sources"));
            }
        });
        return PolicyReport.of(findings);
    }

    /**
     * Whether the bug-pattern analyser's filter is empty, so a finding is fixed and not filtered.
     *
     * @param root the repository root
     * @return one finding where the filter carries anything at all
     */
    public PolicyReport exclusionFilter(Path root) {
        return exclusionFilterAt(root.resolve(exclusionFile), exclusionFile);
    }

    /**
     * Whether one bug-pattern filter is empty.
     *
     * @param file the filter to read
     * @param name what to call it in a finding
     * @return one finding where the filter carries anything at all
     */
    public PolicyReport exclusionFilterAt(Path file, String name) {
        final String filter = RepositoryTree.text(file);
        final String withoutComments = filter.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)<\\?xml.*?\\?>", "")
                .strip();
        if (EMPTY_FILTER.matcher(withoutComments).matches()) {
            return PolicyReport.empty();
        }
        return PolicyReport.of(List.of(PolicyFinding.inFile(name, "exclusion-filter",
                "the bug-pattern filter carries a filter, and a filtered finding is one nobody fixes")));
    }

    /**
     * Whether every rule the source-pattern rule set excludes is a recorded decision.
     *
     * @param root the repository root
     * @param ruleSet the repository-relative rule set to read
     * @return one finding per exclusion with no row, and per row excluding nothing
     */
    public PolicyReport ruleSetExclusions(Path root, String ruleSet) {
        final List<String> excluded = RULE_SET_EXCLUSION.matcher(RepositoryTree.text(root.resolve(ruleSet)))
                .results()
                .map(match -> match.group(1))
                .toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        excluded.stream()
                .filter(rule -> !excludedRules.contains(rule))
                .map(rule -> PolicyFinding.inFile(ruleSet, "rule-set-exclusion",
                        rule + " is excluded and no row records why"))
                .forEach(findings::add);
        excludedRules.stream()
                .filter(rule -> !excluded.contains(rule))
                .map(rule -> PolicyFinding.inFile(POLICY_FILE, "rule-set-exclusion",
                        rule + " has a row and the rule set excludes no such rule"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether any repository-owned Java switches a rule off.
     *
     * @param root the repository root
     * @return one finding per suppression annotation or comment, wherever it appears
     */
    public PolicyReport suppressions(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").forEach(source ->
                findings.addAll(suppressionsIn(root.relativize(source).toString(), source)));
        return PolicyReport.of(findings);
    }

    /**
     * Whether one Java file switches a rule off.
     *
     * @param name what to call the file in a finding
     * @param source the file to read
     * @return one finding per suppression annotation or comment it carries
     */
    public List<PolicyFinding> suppressionsIn(String name, Path source) {
        final ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(RepositoryTree.text(source));
        if (!parsed.isSuccessful()) {
            throw new IllegalStateException(source + " does not parse: " + parsed.getProblems());
        }
        final List<PolicyFinding> findings = new ArrayList<>();
        parsed.getResult().orElseThrow().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> suppressionForms.stream()
                        .anyMatch(form -> form.replace("@", "").equals(annotation.getNameAsString())))
                .map(annotation -> new PolicyFinding(name,
                        annotation.getBegin().map(position -> position.line).orElse(PolicyFinding.NO_LINE),
                        "suppression", "@" + annotation.getNameAsString()))
                .forEach(findings::add);
        parsed.getCommentsCollection().ifPresent(comments -> comments.getComments().stream()
                .flatMap(comment -> suppressionForms.stream()
                        .filter(form -> !form.startsWith("@"))
                        .filter(form -> comment.getContent().contains(form))
                        .map(form -> new PolicyFinding(name,
                                comment.getBegin().map(position -> position.line)
                                        .orElse(PolicyFinding.NO_LINE),
                                "suppression", form)))
                .forEach(findings::add));
        return List.copyOf(findings);
    }

    private static boolean readsTestSources(String configuration) {
        return configuration.contains("<includeTestSourceDirectory>true<")
                || configuration.contains("<includeTests>true<");
    }

}
