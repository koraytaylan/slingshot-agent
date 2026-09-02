// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * What the product's own documents say, and what a checker can decide about it.
 *
 * <p>Documentation rots in one direction: the code moves and the prose stays. What a checker can
 * decide it decides here — an unfinished-work marker, a planning heading, a route or a tier that
 * prose names and no committed table declares, a rule stated with no gate stage behind it, a policy
 * that exists and nothing describes. What a checker cannot decide — whether a sentence is true,
 * whether a document is complete, whether a failure message tells a reader what to do — is a closed
 * review checklist with a recorded answer, so that the difference between the two is written down
 * rather than assumed.</p>
 *
 * <p>The two halves are held apart by the rules file itself: it names every rule this class
 * enforces, compared in both directions, and a review question that restates one of them is
 * refused. A checklist that repeated the checker would be a checklist nobody reads.</p>
 */
public final class ProductDocumentation {

    /** The documents this check is about, which are the ones a reader meets first. */
    public static final List<String> DOCUMENTS = List.of("README.md", "ARCHITECTURE.md",
            "CONTRIBUTING.md", "AGENTS.md", "docs/INTEROP.md", "docs/CONSOLE.md",
            "docs/RELEASING.md");

    /**
     * Every rule a checker decides about this product's documents, spelled once and compared with
     * the rules file in both directions.
     *
     * <p>Most are decided here. The two about console pages are decided beside the console, where
     * the surface they compare against is derived — but they are named here because this is the
     * one place the vocabulary of documentation rules lives, and a rule named in the file and in no
     * vocabulary is a rule nothing enforces.</p>
     */
    public static final List<String> RULES = List.of(
            "unfinished-work", "planning-heading", "unknown-route", "undocumented-route",
            "unknown-tier-command", "undocumented-tier-command", "rule-with-no-stage",
            "unknown-stage", "unknown-policy", "undocumented-policy",
            "undocumented-console-page", "unknown-console-page",
            "unanswered-review-question");

    private static final String RULES_FILE = "policy/documentation-rules.toml";

    /** Where a change is told what it is held to, and where each rule names its gate stage. */
    private static final String CONTRIBUTING = "CONTRIBUTING.md";

    /** Where the three tiers are described, and where their commands have to be exact. */
    private static final String INTEROP = "docs/INTEROP.md";

    /** The heading the rules a change is held to sit under. */
    public static final String RULES_HEADING = "## The rules a change is held to";

    private static final Pattern MARKER =
            Pattern.compile("TODO|FIXME|TBD|XXX|coming soon|to be written|work in progress");

    private static final Pattern PLANNING_HEADING =
            Pattern.compile("^#+\\s+(Roadmap|Future|Next steps|Planned|Upcoming|Backlog)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern ROUTE = Pattern.compile("/bin/slingshot/agent/[a-z/-]+");

    private static final Pattern TIER_COMMAND = Pattern.compile("scripts/interop_[a-z_]+");

    private static final Pattern STAGE = Pattern.compile("\\(stage: ([a-z-]+)\\)");

    private static final Pattern POLICY = Pattern.compile("policy/[a-z-]+\\.toml");

    private final String reviewDocument;
    private final List<String> checkedRules;
    private final List<ReviewQuestion> review;

    private ProductDocumentation(String reviewDocument, List<String> checkedRules,
                                 List<ReviewQuestion> review) {
        this.reviewDocument = reviewDocument;
        this.checkedRules = checkedRules;
        this.review = review;
    }

    /**
     * One question the checker does not answer, and which a person answered instead.
     *
     * @param identifier the question's own identifier, which is its heading in the review
     * @param question the question itself
     */
    public record ReviewQuestion(String identifier, String question) {
    }

    /** The result of reading the rules file: the rules, or the one reason there are none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A rules file that satisfied its shape completely.
     *
     * @param rules the loaded rules
     */
    public record Loaded(ProductDocumentation rules) implements Outcome {
    }

    /**
     * A read that produced no rules.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the documentation rules are held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("documentation-rules")
                .text("review.document")
                .text("review.reason")
                .rows("checked", row -> row.text("rule").text("reason"))
                .rows("reviewed", row -> row.text("id").text("question").text("reason"))
                .build();
    }

    /**
     * Reads the rules this repository commits.
     *
     * @param root the repository root
     * @return the rules, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readRules(root.resolve(RULES_FILE));
    }

    /**
     * Reads a rules document from wherever it sits.
     *
     * @param rules the rules document
     * @return the rules, or the one reason the document was refused
     */
    public static Outcome readRules(Path rules) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(rules, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new ProductDocumentation(document.text("review.document"),
                document.rows("checked").stream().map(row -> row.text("rule")).toList(),
                document.rows("reviewed").stream()
                        .map(row -> new ReviewQuestion(row.text("id"), row.text("question")))
                        .toList()));
    }

    /**
     * Every question the checker does not answer.
     *
     * @return the review questions, in the rules file's own order
     */
    public List<ReviewQuestion> review() {
        return Collections.unmodifiableList(review);
    }

    /**
     * Whether the rules file and this class agree about what is checked rather than reviewed.
     *
     * @return one finding per rule only one of them holds, and one per review question that
     *     restates a rule the checker already decides
     */
    public PolicyReport rulesAgree() {
        final List<PolicyFinding> findings = new ArrayList<>();
        RULES.stream()
                .filter(rule -> !checkedRules.contains(rule))
                .forEach(rule -> findings.add(PolicyFinding.inFile(RULES_FILE, "unknown-rule",
                        rule + " is decided by the checker and the rules file does not name it")));
        checkedRules.stream()
                .filter(rule -> !RULES.contains(rule))
                .forEach(rule -> findings.add(PolicyFinding.inFile(RULES_FILE, "unknown-rule",
                        rule + " is named as checked and no checker decides it")));
        review.stream()
                .filter(question -> RULES.contains(question.identifier()))
                .forEach(question -> findings.add(PolicyFinding.inFile(RULES_FILE, "unknown-rule",
                        question.identifier() + " is reviewed and the checker already decides it")));
        return PolicyReport.of(findings);
    }

    /**
     * Everything the product's documents and the repository's own tables disagree about.
     *
     * @param root the repository root
     * @return one finding per disagreement, each naming what was refused
     */
    public PolicyReport against(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        DOCUMENTS.forEach(document -> findings.addAll(markers(root, document)));
        findings.addAll(routes(root));
        findings.addAll(tiers(root));
        findings.addAll(rules(root));
        findings.addAll(policies(root));
        findings.addAll(answers(root));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> markers(Path root, String document) {
        final List<String> lines = linesOf(root.resolve(document));
        return IntStream.range(0, lines.size())
                .boxed()
                .flatMap(index -> Stream.of(
                                finding(document, index, lines.get(index), MARKER,
                                        "unfinished-work"),
                                finding(document, index, lines.get(index), PLANNING_HEADING,
                                        "planning-heading"))
                        .flatMap(Optional::stream))
                .toList();
    }

    private static Optional<PolicyFinding> finding(String document, int index, String line,
                                                   Pattern pattern, String rule) {
        return pattern.matcher(line).find()
                ? Optional.of(new PolicyFinding(document, index + 1, rule, line.strip()))
                : Optional.empty();
    }

    private static List<PolicyFinding> routes(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<PolicyDocument> table = routeRows(root);
        final Set<String> declared = new LinkedHashSet<>(table.stream()
                .map(row -> row.text("path"))
                .toList());
        DOCUMENTS.forEach(document -> {
            final Matcher named = ROUTE.matcher(contentOf(root.resolve(document)));
            while (named.find()) {
                if (!declared.contains(named.group())) {
                    findings.add(PolicyFinding.inFile(document, "unknown-route",
                            named.group() + " is not a route the table declares"));
                }
            }
        });
        final String prose = prose(root);
        final List<String> served = ScenarioInventory.servedFeatures(
                ScenarioInventory.Sources.of(root));
        table.stream()
                .filter(row -> served.contains(row.text("name")))
                .filter(row -> !prose.contains(row.text("path")))
                .forEach(row -> findings.add(PolicyFinding.inFile("README.md",
                        "undocumented-route", row.text("path")
                                + " is served and no product document names it")));
        return findings;
    }

    private static List<PolicyFinding> tiers(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final QualityGate.Outcome outcome = QualityGate.read(root);
        if (outcome instanceof QualityGate.Refused) {
            return findings;
        }
        final List<String> commands = ((QualityGate.Loaded) outcome).gate().tiers().stream()
                .map(QualityGate.TierRow::command)
                .toList();
        final String described = contentOf(root.resolve(INTEROP));
        commands.stream()
                .filter(command -> !described.contains(command))
                .forEach(command -> findings.add(PolicyFinding.inFile(INTEROP,
                        "undocumented-tier-command", command + " runs a tier and " + INTEROP
                                + " does not name it")));
        final Matcher named = TIER_COMMAND.matcher(described);
        while (named.find()) {
            if (!commands.contains(named.group())) {
                findings.add(PolicyFinding.inFile(INTEROP, "unknown-tier-command",
                        named.group() + " runs no tier the gate declares"));
            }
        }
        return findings;
    }

    private static List<PolicyFinding> rules(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final QualityGate.Outcome outcome = QualityGate.read(root);
        final List<String> stages = outcome instanceof QualityGate.Refused ? List.of()
                : ((QualityGate.Loaded) outcome).gate().stages().stream()
                        .map(QualityGate.StageRow::name)
                        .toList();
        statedRules(root).forEach(rule -> {
            final Matcher stage = STAGE.matcher(rule);
            if (!stage.find()) {
                findings.add(PolicyFinding.inFile(CONTRIBUTING, "rule-with-no-stage", rule));
                return;
            }
            if (!stages.contains(stage.group(1))) {
                findings.add(PolicyFinding.inFile(CONTRIBUTING, "unknown-stage",
                        stage.group(1) + " is not a stage the gate declares"));
            }
        });
        return findings;
    }

    /**
     * Every rule a change is held to, as the contributing document states them.
     *
     * @param root the repository root
     * @return one entry per stated rule, in the document's own order
     */
    public static List<String> statedRules(Path root) {
        final List<String> stated = new ArrayList<>();
        boolean inside = false;
        for (final String line : linesOf(root.resolve(CONTRIBUTING))) {
            if (line.startsWith("## ")) {
                inside = RULES_HEADING.equals(line.strip());
                continue;
            }
            if (inside && line.startsWith("- ")) {
                stated.add(line.strip());
                continue;
            }
            // A rule wraps across lines, and the stage it names may sit on any of them. Reading
            // only the line the bullet starts on would refuse a rule for being long.
            if (inside && !line.isBlank() && !stated.isEmpty()) {
                stated.set(stated.size() - 1, stated.getLast() + " " + line.strip());
            }
        }
        return stated;
    }

    private static List<PolicyFinding> policies(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final String described = contentOf(root.resolve(CONTRIBUTING));
        final Set<String> named = new LinkedHashSet<>();
        final Matcher mentions = POLICY.matcher(described);
        while (mentions.find()) {
            named.add(mentions.group());
        }
        named.stream()
                .filter(policy -> !Files.isRegularFile(root.resolve(policy)))
                .forEach(policy -> findings.add(PolicyFinding.inFile(CONTRIBUTING, "unknown-policy",
                        policy + " is described and no such policy exists")));
        committedPolicies(root).stream()
                .filter(policy -> !named.contains(policy))
                .forEach(policy -> findings.add(PolicyFinding.inFile(CONTRIBUTING,
                        "undocumented-policy", policy + " decides what this repository's code may"
                                + " look like and " + CONTRIBUTING + " does not describe it")));
        return findings;
    }

    private List<PolicyFinding> answers(Path root) {
        final String answered = contentOf(root.resolve(reviewDocument));
        return review.stream()
                .filter(question -> !answered.contains("## " + question.identifier()))
                .map(question -> PolicyFinding.inFile(reviewDocument, "unanswered-review-question",
                        question.identifier() + " is on the checklist and the review does not"
                                + " answer it"))
                .toList();
    }

    /**
     * Every policy this repository commits, as repository-relative paths.
     *
     * @param root the repository root
     * @return the policy files, sorted
     */
    public static List<String> committedPolicies(Path root) {
        if (!Files.isDirectory(root.resolve("policy"))) {
            return List.of();
        }
        try (var files = Files.list(root.resolve("policy"))) {
            return files.filter(Files::isRegularFile)
                    .map(file -> "policy/" + file.getFileName())
                    .filter(policy -> policy.endsWith(".toml"))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static List<PolicyDocument> routeRows(Path root) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(
                root.resolve(ScenarioInventory.ROUTES), ScenarioInventory.routeShape());
        return outcome instanceof PolicyDocument.Refused ? List.of()
                : ((PolicyDocument.Loaded) outcome).document().rows("route");
    }

    private static String prose(Path root) {
        return DOCUMENTS.stream()
                .map(document -> contentOf(root.resolve(document)))
                .reduce("", (all, content) -> all + content);
    }

    private static String contentOf(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static List<String> linesOf(Path file) {
        return contentOf(file).lines().toList();
    }
}
