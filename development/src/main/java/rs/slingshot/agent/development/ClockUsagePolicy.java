// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Which clock a comparison is made on, held apart by parsing rather than by convention.
 *
 * <p>Every lease and every retention decision compares two instants, and the failure mode of a
 * skewed clock is two nodes both believing they hold one lease. The two sources exist for two
 * different purposes and neither may be used for the other's: a wall clock is read for an instant
 * that goes on the wire, because a client comparing it with its own has to be comparing the same
 * kind of thing — and it is corrected, which is exactly why nothing measures a duration by
 * subtracting two readings of it. A monotonic source measures every duration, and its value means
 * nothing on its own, which is why no instant is ever read from it.</p>
 *
 * <p>Both are read in one place, and the rule is that nowhere else derives a duration from the wall
 * clock. Naming either source in a comment is not using it, so every rule here reads source with
 * its comments removed — otherwise the paragraph explaining the rule would break it.</p>
 */
public final class ClockUsagePolicy {

    /** Where the rules are declared, beside every other source rule. */
    public static final String POLICY_FILE = "policy/source-policy.toml";

    /** The rule a duration measured on a corrected clock is reported under. */
    public static final String DURATION_ON_A_WALL_CLOCK = "duration-on-a-wall-clock";

    /** The rule an instant taken from a source whose value means nothing is reported under. */
    public static final String INSTANT_ON_A_MONOTONIC_SOURCE = "instant-on-a-monotonic-source";

    /** The rule a clock read outside the one place that reads clocks is reported under. */
    public static final String CLOCK_OUTSIDE_THE_SEAM = "clock-outside-the-seam";

    /** How an instant is spelled, which is what a monotonic reading may never be assigned to. */
    private static final List<String> INSTANT_WORDS =
            List.of("UnixMilliseconds", "unixMilliseconds", "AtUnix", "instant");

    /**
     * Where the product's own code lives, which is the only place the one-seam rule applies.
     *
     * <p>The tooling and the interoperability harness read a monotonic source to bound how long
     * they wait for a container, which is exactly what a monotonic source is for. What the rule is
     * about is the product having one place that reads a clock, so it is asked of the product.</p>
     */
    private static final List<String> PRODUCT_SOURCES =
            List.of("core/src/main/java", "aem/src/main/java");

    private final String wallClock;
    private final String monotonic;
    private final String seam;
    private final List<String> durationWords;

    private ClockUsagePolicy(String wallClock, String monotonic, String seam,
                             List<String> durationWords) {
        this.wallClock = wallClock;
        this.monotonic = monotonic;
        this.seam = seam;
        this.durationWords = durationWords;
    }

    /** The result of reading the rules: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * Rules that satisfied their shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(ClockUsagePolicy policy) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * Reads the rules this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason there is none
     */
    public static Outcome read(Path root) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(root.resolve(POLICY_FILE), SourcePolicy.shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new ClockUsagePolicy(document.text("clocks.wall_clock"),
                document.text("clocks.monotonic"), document.text("clocks.seam"),
                document.rows(SourcePolicy.DURATION_ROWS).stream()
                        .map(row -> row.text("word")).toList()));
    }

    /**
     * Every main source in this repository, held to both directions of the rule.
     *
     * @param root the repository root
     * @return one finding per source that breaks one
     */
    public PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root, ".java").stream()
                .filter(source -> !isTestSource(root.relativize(source)))
                .forEach(source -> findings.addAll(inFile(root.relativize(source).toString(),
                        source)));
        return PolicyReport.of(findings);
    }

    /**
     * One source, held to both directions.
     *
     * @param named how a finding names the file
     * @param file where to read it from
     * @return one finding per rule it breaks
     */
    public List<PolicyFinding> inFile(String named, Path file) {
        final String path = named.replace('\\', '/');
        final boolean product = PRODUCT_SOURCES.stream().anyMatch(path::startsWith);
        final boolean inTheSeam = path.contains(seam);
        final List<PolicyFinding> findings = new ArrayList<>();
        String declaring = "";
        for (final String line : withoutComments(RepositoryTree.text(file)).split("\n", -1)) {
            if (isDeclaration(line)) {
                declaring = line;
            }
            if (line.contains(wallClock) && carriesADurationWord(line, declaring)) {
                findings.add(PolicyFinding.inFile(named, DURATION_ON_A_WALL_CLOCK, line.trim()));
            }
            if (line.contains(monotonic) && carriesAnInstantWord(line, declaring)) {
                findings.add(PolicyFinding.inFile(named, INSTANT_ON_A_MONOTONIC_SOURCE,
                        line.trim()));
            }
            if (product && !inTheSeam && line.contains(monotonic)) {
                findings.add(PolicyFinding.inFile(named, CLOCK_OUTSIDE_THE_SEAM, line.trim()));
            }
        }
        return findings;
    }

    /**
     * Whether a line reads as the beginning of something with a name.
     *
     * <p>Read so that a source reading a clock is judged by what it is filling in rather than by
     * what happens to be on that one line: a method named for an instant that returns a monotonic
     * reading is wrong on the line above the reading, which is where the name is.</p>
     *
     * @param line one line of source
     * @return whether it declares something
     */
    private static boolean isDeclaration(String line) {
        return (line.contains("public ") || line.contains("private ") || line.contains("final "))
                && (line.contains("(") || line.contains("="));
    }

    private boolean carriesADurationWord(String line, String declaring) {
        return durationWords.stream()
                .anyMatch(word -> line.contains(word) || declaring.contains(word));
    }

    private static boolean carriesAnInstantWord(String line, String declaring) {
        return INSTANT_WORDS.stream()
                .anyMatch(word -> line.contains(word) || declaring.contains(word));
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .lines()
                .map(line -> line.indexOf("//") >= 0 ? line.substring(0, line.indexOf("//")) : line)
                .reduce("", (all, line) -> all + line + "\n");
    }

    private static boolean isTestSource(Path relative) {
        for (final Path segment : relative) {
            if ("test".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
