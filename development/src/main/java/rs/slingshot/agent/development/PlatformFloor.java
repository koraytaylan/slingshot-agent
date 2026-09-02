// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The oldest platform this build runs on, derived from the deployment rows rather than declared.
 *
 * <p>Bound to the matrix rather than written separately, so raising the floor is a change to the
 * matrix — and the matrix is already what the bytecode contract and the imported-package footprint
 * check against. A floor written down twice is two numbers that disagree quietly, for as long as
 * nobody compares them.</p>
 *
 * <p>What the floor is for is a refusal that names a version rather than a bundle that installs and
 * then fails at the first call into something the platform does not have. An operator reading
 * "requires Sling 12" knows what to do; an operator reading a missing-class failure at three in the
 * morning does not.</p>
 */
public final class PlatformFloor {

    /** Where the deployments this product supports are declared. */
    public static final String MATRIX = "support/deployments.toml";

    /** The rule a floor that is not the matrix's own oldest row is reported under. */
    public static final String NOT_THE_OLDEST_ROW = "not-the-oldest-supported-row";

    /** The rule a row that names no version of something the floor is about is reported under. */
    public static final String A_ROW_WITH_NO_VERSION = "a-row-with-no-version";

    /** The rule a bundle compiled for a runtime no row provides is reported under. */
    public static final String ABOVE_EVERY_ROWS_RUNTIME = "above-every-rows-runtime";

    private final List<Row> rows;

    private PlatformFloor(List<Row> rows) {
        this.rows = rows;
    }

    /**
     * One deployment row, as the three versions a floor is about.
     *
     * @param identifier the row's own identifier
     * @param javaRuntime the release of the Java runtime it provides
     * @param slingVersion the Sling release it provides
     * @param oakVersion the Oak release it provides
     */
    public record Row(String identifier, long javaRuntime, String slingVersion,
                      String oakVersion) {
    }

    /**
     * Reads the matrix this repository commits.
     *
     * @param root the repository root
     * @return the floor, over the rows the matrix declares
     */
    public static PlatformFloor read(Path root) {
        final List<Row> rows = new ArrayList<>();
        String identifier = "";
        long javaRuntime = 0;
        String sling = "";
        for (final String line : RepositoryTree.text(root.resolve(MATRIX)).lines().toList()) {
            final String stripped = line.strip();
            if (stripped.startsWith("id = ")) {
                identifier = quoted(stripped);
            } else if (stripped.startsWith("java_runtime = ")) {
                javaRuntime = Long.parseLong(stripped.substring(stripped.indexOf('=') + 1).strip());
            } else if (stripped.startsWith("sling_version = ")) {
                sling = quoted(stripped);
            } else if (stripped.startsWith("oak_version = ")) {
                rows.add(new Row(identifier, javaRuntime, sling, quoted(stripped)));
            }
        }
        return new PlatformFloor(List.copyOf(rows));
    }

    /**
     * Every row the matrix declares, in its own order.
     *
     * @return the rows
     */
    public List<Row> rows() {
        return java.util.Collections.unmodifiableList(rows);
    }

    /**
     * The oldest runtime any supported row provides, which is what this build may assume.
     *
     * @return the Java release
     */
    public long oldestJavaRuntime() {
        return rows.stream().mapToLong(Row::javaRuntime).min().orElse(0);
    }

    /**
     * The oldest Sling release any supported row provides.
     *
     * @return the release, compared as a number of major versions
     */
    public String oldestSlingVersion() {
        return rows.stream()
                .map(Row::slingVersion)
                .min(PlatformFloor::compareVersions)
                .orElse("");
    }

    /**
     * The oldest Oak release any supported row provides.
     *
     * @return the release
     */
    public String oldestOakVersion() {
        return rows.stream()
                .map(Row::oakVersion)
                .min(PlatformFloor::compareVersions)
                .orElse("");
    }

    /**
     * Everything the floor and the matrix disagree about.
     *
     * @param bytecodeTarget the Java release the bundles are compiled for
     * @return one finding per disagreement
     */
    public PolicyReport against(long bytecodeTarget) {
        final List<PolicyFinding> findings = new ArrayList<>();
        rows.stream()
                .filter(row -> row.javaRuntime() == 0 || row.slingVersion().isEmpty()
                        || row.oakVersion().isEmpty())
                .map(row -> PolicyFinding.inFile(MATRIX, A_ROW_WITH_NO_VERSION,
                        row.identifier() + " names no version of something the floor is about, so"
                                + " the floor over it is a guess"))
                .forEach(findings::add);
        if (bytecodeTarget > oldestJavaRuntime()) {
            findings.add(PolicyFinding.inFile(MATRIX, ABOVE_EVERY_ROWS_RUNTIME,
                    "the bundles are compiled for Java " + bytecodeTarget + " and the oldest"
                            + " supported row provides Java " + oldestJavaRuntime()
                            + ", so on that row nothing this product ships resolves at all"));
        }
        return PolicyReport.of(findings);
    }

    /**
     * Which of two releases is older, compared as numbers rather than as text.
     *
     * <p>As text, "12" is older than "6.5" and the floor would be the wrong row. As numbers, they
     * are what they are, which is the whole reason this is not a string comparison.</p>
     *
     * @param first one release
     * @param second the other
     * @return a negative number where the first is older
     */
    public static int compareVersions(String first, String second) {
        final List<Long> left = partsOf(first);
        final List<Long> right = partsOf(second);
        return java.util.stream.IntStream.range(0, Math.max(left.size(), right.size()))
                .map(part -> Long.compare(partAt(left, part), partAt(right, part)))
                .filter(difference -> difference != 0)
                .findFirst()
                .orElse(0);
    }

    /**
     * One release as its parts, so a comparison is arithmetic rather than alphabetical.
     *
     * @param release the release
     * @return its parts, most significant first
     */
    private static List<Long> partsOf(String release) {
        return java.util.Arrays.stream(release.split("\\."))
                .map(Long::parseLong)
                .toList();
    }

    /**
     * One part of a release, where a release with fewer parts has noughts after it.
     *
     * @param parts the release's parts
     * @param at which part
     * @return the part, or nought where the release has none that deep
     */
    private static long partAt(List<Long> parts, int at) {
        return at < parts.size() ? parts.get(at) : 0;
    }

    private static String quoted(String line) {
        return line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
    }
}
