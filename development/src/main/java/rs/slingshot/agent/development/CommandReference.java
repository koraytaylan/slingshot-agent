// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The rendered command reference, generated from the registry rather than kept beside it.
 *
 * <p>A reference maintained by hand beside a registry is a reference that is wrong within a month —
 * not because anybody is careless, but because the registry changes in a commit that has nothing to
 * do with documentation and nobody reading that commit thinks about the table. Generating it means
 * the two cannot disagree, and checking the rendering on every build means the generation cannot be
 * skipped.</p>
 *
 * <p>What this check cannot decide is whether the summaries are <em>accurate</em> and whether the
 * prose around the table is worth reading. Those are recorded as review checklist entries rather
 * than as checks that pretend to, because a check that claimed to judge prose would be believed.</p>
 */
public final class CommandReference {

    private CommandReference() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "command-reference";

    /** Where the registry declares one file per command. */
    public static final String REGISTRY_DIRECTORY = "policy/commands";

    /** Where the rendered reference is written. */
    public static final String REFERENCE_FILE = "docs/COMMANDS.md";

    /** What opens the generated region, so the prose around it is plainly not generated. */
    public static final String OPENING = "<!-- generated: command-table -->";

    /** What closes it. */
    public static final String CLOSING = "<!-- end generated: command-table -->";

    /** The reference is missing a command the registry declares. */
    public static final String MISSING_ROW = "reference-missing-a-command";

    /** The reference carries a command the registry does not declare. */
    public static final String EXTRA_ROW = "reference-carries-an-unknown-command";

    /** The reference renders a value the registry does not state. */
    public static final String VALUE_DIFFERS = "reference-value-differs";

    /** The reference has no generated region at all. */
    public static final String NO_REGION = "reference-has-no-generated-region";

    /**
     * What this check does not decide, recorded so nobody mistakes its silence for approval.
     *
     * <p>Both are questions about meaning rather than about agreement, and a check that claimed to
     * answer them would be believed by somebody who then stopped reading.</p>
     */
    public static final List<String> REVIEW_CHECKLIST = List.of(
            "whether each command's summary describes what the command actually does",
            "whether the prose around the generated table is worth reading");

    /**
     * The whole table, rendered from the registry.
     *
     * @param registry the registry directory
     * @return the rendered rows, one line each, in ascending wire-name order
     */
    public static String render(Path registry) {
        final List<String> lines = new ArrayList<>();
        lines.add("| Command | Access | Operation key | Result bytes | Fails with |");
        lines.add("|---|---|---|---|---|");
        RegistryCompleteness.rowsIn(registry).forEach((command, row) -> lines.add(
                "| `" + command + "` | " + row.access() + " | " + row.operationKey() + " | "
                        + row.resultBytes() + " | " + categoriesOf(registry, command) + " |"));
        return String.join("\n", lines);
    }

    private static String categoriesOf(Path registry, String command) {
        final String held = read(registry.resolve(command + ".toml"));
        final Matcher categories =
                Pattern.compile("(?m)^failure_categories = \\[([^\\]]*)\\]$").matcher(held);
        if (!categories.find()) {
            return "";
        }
        return Stream.of(categories.group(1).split(","))
                .map(String::strip)
                .map(name -> name.replace("\"", ""))
                .filter(name -> !name.isEmpty())
                .map(name -> "`" + name + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * The reference with its generated region replaced, leaving every other byte alone.
     *
     * @param reference the whole committed document
     * @param table the rendered table
     * @return the document with the region replaced, or the document unchanged where it has none
     */
    public static String written(String reference, String table) {
        final int opens = reference.indexOf(OPENING);
        final int closes = reference.indexOf(CLOSING);
        if (opens < 0 || closes < opens) {
            return reference;
        }
        return reference.substring(0, opens + OPENING.length()) + "\n\n" + table + "\n\n"
                + reference.substring(closes);
    }

    /**
     * Whether the committed reference is what the registry renders.
     *
     * @param root the repository root
     * @return one finding per disagreement, each naming what it is about
     */
    public static PolicyReport against(Path root) {
        return against(root.resolve(REGISTRY_DIRECTORY), root.resolve(REFERENCE_FILE));
    }

    /**
     * Whether one reference is what one registry renders.
     *
     * @param registry the registry directory
     * @param reference the rendered reference
     * @return one finding per disagreement, each naming what it is about
     */
    public static PolicyReport against(Path registry, Path reference) {
        if (!Files.isRegularFile(reference)) {
            return PolicyReport.of(List.of(PolicyFinding.inFile(REFERENCE_FILE, NO_REGION,
                    "the reference is not there at all")));
        }
        final String held = read(reference);
        if (!held.contains(OPENING) || !held.contains(CLOSING)) {
            return PolicyReport.of(List.of(PolicyFinding.inFile(REFERENCE_FILE, NO_REGION,
                    "the reference has no generated region, so nothing can tell what in it was"
                            + " written by hand")));
        }
        return PolicyReport.of(findings(registry, regionOf(held)));
    }

    private static List<PolicyFinding> findings(Path registry, String region) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<String> rendered = List.of(render(registry).split("\n"));
        final List<String> committed = Stream.of(region.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        rendered.stream()
                .filter(line -> !committed.contains(line))
                .forEach(line -> findings.add(PolicyFinding.inFile(REFERENCE_FILE,
                        rowNameOf(line).isEmpty() ? VALUE_DIFFERS : MISSING_ROW,
                        rowNameOf(line).isEmpty()
                                ? "the table's own heading is not what the registry renders"
                                : rowNameOf(line) + " is declared and the reference does not render"
                                        + " it as the registry states it")));
        committed.stream()
                .filter(line -> !rendered.contains(line))
                .filter(line -> !rowNameOf(line).isEmpty())
                .forEach(line -> findings.add(PolicyFinding.inFile(REFERENCE_FILE, EXTRA_ROW,
                        rowNameOf(line) + " is rendered and the registry declares nothing that"
                                + " renders to that line")));
        return findings;
    }

    private static String rowNameOf(String line) {
        final Matcher name = Pattern.compile("^\\| `([a-z0-9_]+)` \\|").matcher(line);
        return name.find() ? name.group(1) : "";
    }

    /**
     * What sits between the two markers.
     *
     * @param reference the whole document
     * @return the generated region, or the empty string where it has none
     */
    public static String regionOf(String reference) {
        final int opens = reference.indexOf(OPENING);
        final int closes = reference.indexOf(CLOSING);
        return opens < 0 || closes < opens
                ? "" : reference.substring(opens + OPENING.length(), closes);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
