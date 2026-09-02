// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The coverage a module and a class must reach before a build passes.
 *
 * <p>Both measures are enforced independently and both are enforced per module and per class,
 * because a module average hides a class nobody tested behind a class somebody tested twice. A
 * shortfall names the module, the class, the measure, the floor, and the actual value, so the
 * failure says what happened rather than that something did.</p>
 *
 * <p>Nothing is excluded by default, an exclusion names one class and the reason it is excluded,
 * and a package-level exclusion is refused outright — excluding in bulk is how a floor stops
 * meaning anything without anybody deciding that it should.</p>
 */
public final class CoverageFloor {

    private static final String POLICY_FILE = "policy/coverage.toml";

    private static final String MODULE_ROWS = "measured_module";

    private static final String EXCLUSION_ROWS = "exclusion";

    /** The property the build reads the line floor from. */
    public static final String LINE_PROPERTY = "coverage.line.minimum";

    /** The property the build reads the branch floor from. */
    public static final String BRANCH_PROPERTY = "coverage.branch.minimum";

    /** What a whole percentage is out of. */
    private static final long WHOLE = 100;

    /** How a package-level exclusion is spelled, and refused. */
    private static final String EVERYTHING_UNDER = "*";

    private final long linePercent;
    private final long branchPercent;
    private final List<String> measuredModules;
    private final List<ExclusionRow> exclusions;

    private CoverageFloor(long linePercent, long branchPercent, List<String> measuredModules,
                          List<ExclusionRow> exclusions) {
        this.linePercent = linePercent;
        this.branchPercent = branchPercent;
        this.measuredModules = measuredModules;
        this.exclusions = exclusions;
    }

    /**
     * One class the floor does not apply to.
     *
     * @param className the class's own fully qualified name
     * @param reason why the floor does not apply to it
     */
    public record ExclusionRow(String className, String reason) {
    }

    /**
     * One measured value, as the coverage agent reported it.
     *
     * @param module the module the class belongs to
     * @param className the class's own name
     * @param measure {@code line} or {@code branch}
     * @param coveredPercent what the agent measured, as a whole percentage
     */
    public record Measurement(String module, String className, String measure, long coveredPercent) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(CoverageFloor policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the coverage policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("coverage")
                .number("minimums.line_percent")
                .number("minimums.branch_percent")
                .text("minimums.reason")
                .rows(MODULE_ROWS, row -> row.text("name").text("reason"))
                .rows(EXCLUSION_ROWS, row -> row.text("class").text("reason"))
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
        final List<ExclusionRow> exclusions = document.rows(EXCLUSION_ROWS).stream()
                .map(row -> new ExclusionRow(row.text("class"), row.text("reason")))
                .toList();
        final Optional<ExclusionRow> unexplained = exclusions.stream()
                .filter(row -> row.reason().isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused(unexplained.get().className() + " is excluded and records no reason");
        }
        final Optional<ExclusionRow> wholesale = exclusions.stream()
                .filter(row -> row.className().contains(EVERYTHING_UNDER))
                .findFirst();
        if (wholesale.isPresent()) {
            return new Refused(wholesale.get().className()
                    + " excludes a package rather than a class, and excluding in bulk is how a"
                    + " floor stops meaning anything");
        }
        return new Loaded(new CoverageFloor(document.number("minimums.line_percent"),
                document.number("minimums.branch_percent"),
                document.rows(MODULE_ROWS).stream().map(row -> row.text("name")).toList(),
                exclusions));
    }

    /**
     * The floor one measure has to reach, as a whole percentage.
     *
     * @param measure {@code line} or {@code branch}
     * @return the floor
     * @throws IllegalArgumentException if the policy declares no such measure
     */
    public long floor(String measure) {
        return switch (measure) {
            case "line" -> linePercent;
            case "branch" -> branchPercent;
            default -> throw new IllegalArgumentException("the policy declares no measure named "
                    + measure);
        };
    }

    /**
     * The modules the floor applies to.
     *
     * @return the measured modules, in the policy's own order
     */
    public List<String> measuredModules() {
        return Collections.unmodifiableList(measuredModules);
    }

    /**
     * Every class the floor does not apply to.
     *
     * @return the exclusions, in the policy's own order
     */
    public List<ExclusionRow> exclusions() {
        return Collections.unmodifiableList(exclusions);
    }

    /**
     * Every measurement that falls below the floor for its own measure.
     *
     * @param measurements what the coverage agent reported
     * @return one finding per shortfall, naming the module, the class, the measure, the floor, and
     *     the actual value
     */
    public PolicyReport shortfalls(List<Measurement> measurements) {
        return PolicyReport.of(measurements.stream()
                .filter(measured -> exclusions.stream()
                        .noneMatch(row -> row.className().equals(measured.className())))
                .filter(measured -> measured.coveredPercent() < floor(measured.measure()))
                .map(measured -> PolicyFinding.inFile(measured.module(), "coverage-floor",
                        measured.className() + " covers " + measured.coveredPercent()
                                + " per cent of its " + measured.measure() + "s, below the floor of "
                                + floor(measured.measure())))
                .toList());
    }

    /**
     * Whether the build enforces exactly the floor the policy declares, in one place.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module that declares its own floor, and one where the aggregator's
     *     value is not the policy's
     */
    public PolicyReport againstTheBuild(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        declaredFloor(reactor, LINE_PROPERTY, linePercent).ifPresent(findings::add);
        declaredFloor(reactor, BRANCH_PROPERTY, branchPercent).ifPresent(findings::add);
        reactor.modules().forEach(module -> List.of(LINE_PROPERTY, BRANCH_PROPERTY).stream()
                .filter(property -> reactor.raw(module).getProperties().getProperty(property) != null)
                .map(property -> PolicyFinding.inFile(module + "/pom.xml", "coverage-floor",
                        module + " declares its own " + property))
                .forEach(findings::add));
        measuredModules.stream()
                .filter(module -> !reactor.modules().contains(module))
                .map(module -> PolicyFinding.inFile(POLICY_FILE, "coverage-floor",
                        module + " is measured and no such module exists"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    private static Optional<PolicyFinding> declaredFloor(ReactorModel reactor, String property,
                                                         long percent) {
        final String declared = reactor.aggregator().getProperties().getProperty(property);
        final String expected = asRatio(percent);
        if (expected.equals(declared)) {
            return Optional.empty();
        }
        return Optional.of(PolicyFinding.inFile("pom.xml", "coverage-floor",
                property + " is " + declared + " in the build and " + expected + " in the policy"));
    }

    /**
     * One whole percentage written the way the build states a ratio.
     *
     * @param percent the whole percentage
     * @return the ratio, to two places
     */
    public static String asRatio(long percent) {
        return "0." + (percent < WHOLE ? String.valueOf(percent) : "99");
    }
}
