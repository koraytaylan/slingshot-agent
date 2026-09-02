// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every fuzz target this repository declares, held to the targets and corpora that exist.
 *
 * <p>Both directions, because both go wrong. A declared target with no class is a target nothing
 * runs and nobody notices; a target class nobody declared is one the runner cannot be pointed at.
 * And a target with an empty corpus is the worst of the three, because it runs, reports nothing,
 * and looks exactly like a target that found nothing.</p>
 *
 * <p>The corpus is a regression suite rather than decoration. Every input that has ever produced a
 * finding stays in it permanently, which is what makes a fixed defect stay fixed — so a corpus
 * entry the target cannot consume is refused here rather than silently skipped at run time.</p>
 */
public final class FuzzTargetInventory {

    /** Where the tool and its targets are declared. */
    public static final String TOOL_FILE = "support/fuzzing-tool.toml";

    /** Where the classes a target names live. */
    public static final String TARGET_SOURCES =
            "development/src/test/java/rs/slingshot/agent/development/fuzz";

    /** Where each target's committed corpus sits. */
    public static final String CORPUS = "fuzz/corpus";

    /** The rule a declared target whose class does not exist is reported under. */
    public static final String TARGET_WITH_NO_CLASS = "target-with-no-class";

    /** The rule a target class nothing declares is reported under. */
    public static final String CLASS_WITH_NO_TARGET = "class-with-no-target";

    /** The rule a target whose corpus is absent or empty is reported under. */
    public static final String TARGET_WITH_NO_CORPUS = "target-with-no-corpus";

    /** The rule a corpus directory no target names is reported under. */
    public static final String CORPUS_WITH_NO_TARGET = "corpus-with-no-target";

    /** What a target class is called, so one can be found without a list. */
    private static final String TARGET_SUFFIX = "Target.java";

    /**
     * The one file that ends the same way and is not a target.
     *
     * <p>It is what a target is rather than one of them. Named here rather than found by some rule
     * about interfaces, because one exception somebody wrote down is easier to read than a rule
     * that happens to exclude it.</p>
     */
    private static final String THE_SHAPE_ITSELF = "FuzzTarget.java";

    private final List<TargetRow> targets;

    private FuzzTargetInventory(List<TargetRow> targets) {
        this.targets = targets;
    }

    /**
     * One declared target.
     *
     * @param name the name the runner is given
     * @param entry the fully qualified class the tool drives
     * @param corpus where its committed corpus sits, relative to the repository root
     */
    public record TargetRow(String name, String entry, String corpus) {
    }

    /** The result of reading the declaration: the inventory, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A declaration that satisfied its shape completely.
     *
     * @param inventory the loaded inventory
     */
    public record Loaded(FuzzTargetInventory inventory) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the declaration is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("fuzzing-tool")
                .text("tool.name")
                .text("tool.version")
                .text("tool.coordinates")
                .text("tool.file")
                .text("tool.digest")
                .text("tool.prepare")
                .text("tool.held_at")
                .text("tool.reason")
                .number("run.seed")
                .number("run.iterations")
                .text("run.reason")
                .rows("target", row -> row.text("name").text("entry").text("corpus").text("reason"))
                .build();
    }

    /**
     * Reads the declaration this repository commits.
     *
     * @param root the repository root
     * @return the inventory, or the one reason there is none
     */
    public static Outcome read(Path root) {
        return readFile(root.resolve(TOOL_FILE));
    }

    /**
     * Reads one declaration wherever it sits, so a fixture can replace it and nothing else.
     *
     * @param file the declaration
     * @return the inventory, or the one reason there is none
     */
    public static Outcome readFile(Path file) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(file, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        return new Loaded(new FuzzTargetInventory(
                ((PolicyDocument.Loaded) outcome).document().rows("target").stream()
                        .map(row -> new TargetRow(row.text("name"), row.text("entry"),
                                row.text("corpus")))
                        .toList()));
    }

    /**
     * Every declared target, in the declaration's own order.
     *
     * @return the targets
     */
    public List<TargetRow> targets() {
        return java.util.Collections.unmodifiableList(targets);
    }

    /**
     * Everything the declaration and the repository disagree about.
     *
     * @param root the repository root
     * @return one finding per disagreement, each naming what was refused
     */
    public PolicyReport against(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        targets.forEach(target -> {
            if (!Files.isRegularFile(sourceOf(root, target))) {
                findings.add(PolicyFinding.inFile(TOOL_FILE, TARGET_WITH_NO_CLASS,
                        target.name() + " names " + target.entry() + ", which does not exist"));
            }
            if (corpusOf(root, target).isEmpty()) {
                findings.add(PolicyFinding.inFile(TOOL_FILE, TARGET_WITH_NO_CORPUS,
                        target.name() + " has no committed corpus at " + target.corpus()
                                + ", so it would run, report nothing, and look exactly like a"
                                + " target that found nothing"));
            }
        });
        findings.addAll(undeclaredClasses(root));
        findings.addAll(undeclaredCorpora(root));
        return PolicyReport.of(findings);
    }

    /**
     * Every committed input one target holds.
     *
     * @param root the repository root
     * @param target the declared target
     * @return the entries, in the order the directory holds them
     */
    public static List<Path> corpusOf(Path root, TargetRow target) {
        final Path directory = root.resolve(target.corpus());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var found = Files.list(directory)) {
            return found.filter(Files::isRegularFile).sorted().toList();
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path sourceOf(Path root, TargetRow target) {
        return root.resolve(TARGET_SOURCES)
                .resolve(target.entry().substring(target.entry().lastIndexOf('.') + 1) + ".java");
    }

    private List<PolicyFinding> undeclaredClasses(Path root) {
        return RepositoryTree.filesUnder(root.resolve(TARGET_SOURCES), TARGET_SUFFIX).stream()
                .filter(file -> !THE_SHAPE_ITSELF.equals(String.valueOf(file.getFileName())))
                .map(file -> String.valueOf(file.getFileName()).replace(".java", ""))
                .filter(name -> targets.stream()
                        .noneMatch(target -> target.entry().endsWith("." + name)))
                .map(name -> PolicyFinding.inFile(TARGET_SOURCES + "/" + name + ".java",
                        CLASS_WITH_NO_TARGET, name + " is a target and the declaration does not"
                                + " name it, so the runner cannot be pointed at it"))
                .toList();
    }

    private List<PolicyFinding> undeclaredCorpora(Path root) {
        final Path corpora = root.resolve(CORPUS);
        if (!Files.isDirectory(corpora)) {
            return List.of();
        }
        try (var found = Files.list(corpora)) {
            return found.filter(Files::isDirectory)
                    .map(directory -> String.valueOf(directory.getFileName()))
                    .filter(name -> targets.stream()
                            .noneMatch(target -> target.corpus().endsWith("/" + name)))
                    .sorted()
                    .map(name -> PolicyFinding.inFile(CORPUS + "/" + name, CORPUS_WITH_NO_TARGET,
                            name + " is a corpus no target consumes, so it would rot silently"))
                    .toList();
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
