// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What actually ran against each deployment row, and what is therefore proved about it.
 *
 * <p>A row does not become supported because the code compiled. It becomes supported when a tier
 * ran against it, and until then it is declared and unproved — which is a more useful thing to
 * publish than a claim nobody tested and a less embarrassing one than a claim somebody finds out is
 * false.</p>
 *
 * <p>Three of the properties are the deployment's rather than this build's: how the artifact
 * arrives, whether that row's own ingress has been watched passing an unbuffered event stream, and
 * whether a clustered arrangement was exercised. A row whose streaming has never been watched
 * arrive is streaming that is declared and unproved, however well it works here — because what is
 * between a client and this agent on that row is not something this repository can test.</p>
 */
public final class AcceptanceMatrix {

    /** Where the matrix sits. */
    public static final String MATRIX_FILE = "support/acceptance-matrix.toml";

    /** Where the deployments this product supports are declared. */
    public static final String DEPLOYMENTS = "support/deployments.toml";

    /** Where the scenarios are declared, one file per scenario. */
    public static final String SCENARIOS = "interop/scenarios";

    /** The rule evidence naming a deployment row that does not exist is reported under. */
    public static final String AN_UNKNOWN_ROW = "an-unknown-deployment-row";

    /** The rule evidence naming a tier the gate does not declare is reported under. */
    public static final String AN_UNKNOWN_TIER = "an-unknown-tier";

    /** The rule evidence naming a scenario that does not exist is reported under. */
    public static final String AN_UNKNOWN_SCENARIO = "an-unknown-scenario";

    /** The rule a deployment row with no entry at all is reported under. */
    public static final String A_ROW_WITH_NO_ENTRY = "a-deployment-row-with-no-entry";

    /** The rule an entry naming no deployment row is reported under. */
    public static final String AN_ENTRY_WITH_NO_ROW = "an-entry-with-no-deployment-row";

    /** The rule a release claiming an unproved row as supported is reported under. */
    public static final String CLAIMED_WITHOUT_EVIDENCE = "claimed-without-evidence";

    /** What a field the run has not recorded says, which is honest rather than absent. */
    public static final String NOT_OBSERVED = "";

    private final List<Entry> entries;

    private AcceptanceMatrix(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * One deployment row and what ran against it.
     *
     * @param deployment which row
     * @param arrival how the artifact reaches an author on it, which is the row's own property
     * @param tier which tier ran, or nothing
     * @param scenarios which scenarios ran, or none
     * @param instance which instance they ran against, or nothing
     * @param ingressStreaming whether that row's own ingress was watched passing a stream
     * @param clustering whether more than one node was exercised
     */
    public record Entry(String deployment, String arrival, String tier, List<String> scenarios,
                        String instance, Observation ingressStreaming, Observation clustering) {

        /** Holds an entry whose scenarios nothing can change afterwards. */
        public Entry {
            scenarios = List.copyOf(scenarios);
        }

        /**
         * How this row renders, which is one of exactly two things.
         *
         * <p>Proved means a tier ran against a named instance and the row's own streaming was
         * watched arrive. Everything else is declared and unproved, including a row that works
         * perfectly here — because working here is a fact about here.</p>
         *
         * @return what to publish about this row
         */
        public Standing standing() {
            return !NOT_OBSERVED.equals(tier) && !NOT_OBSERVED.equals(instance)
                    && !scenarios.isEmpty() && ingressStreaming == Observation.WATCHED
                    ? Standing.PROVED : Standing.DECLARED_AND_UNPROVED;
        }
    }

    /**
     * Whether somebody has actually watched one of a row's own properties, or has not.
     *
     * <p>Named rather than true and false because the two are not symmetrical. Watched is a thing
     * somebody did on a particular instance on a particular day; not watched is the absence of
     * that, which is exactly what makes a row unproved — and a call site passing a bare flag would
     * be recording the difference between them in a character.</p>
     */
    public enum Observation {
        /** Somebody watched it, on the instance the entry names. */
        WATCHED,
        /** Nobody has, which is what makes a row declared and unproved. */
        NOT_WATCHED
    }

    /** What a release may say about one row. */
    public enum Standing {
        /** A tier ran against it and its own ingress was watched passing a stream. */
        PROVED,
        /** Everything else, which is a more useful thing to publish than an untested claim. */
        DECLARED_AND_UNPROVED
    }

    /** The result of reading the matrix: the entries, or the one reason there are none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A matrix that satisfied its shape completely.
     *
     * @param matrix the loaded matrix
     */
    public record Loaded(AcceptanceMatrix matrix) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the matrix is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("acceptance-matrix")
                .text("matrix.recorded_by")
                .text("matrix.reason")
                .rows("row", row -> row.text("deployment").text("arrival").text("tier")
                        .textList("scenarios").text("instance").text("observed_at")
                        .answer("ingress_streaming_observed")
                        .answer("clustered_arrangement_exercised").text("reason"))
                .build();
    }

    /**
     * Reads the matrix this repository commits.
     *
     * @param root the repository root
     * @return the entries, or the one reason there are none
     */
    public static Outcome read(Path root) {
        return readFile(root.resolve(MATRIX_FILE));
    }

    /**
     * Reads one matrix wherever it sits, so a fixture can replace it and nothing else.
     *
     * @param file the matrix
     * @return the entries, or the one reason there are none
     */
    public static Outcome readFile(Path file) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(file, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        return new Loaded(new AcceptanceMatrix(
                ((PolicyDocument.Loaded) outcome).document().rows("row").stream()
                        .map(row -> new Entry(row.text("deployment"), row.text("arrival"),
                                row.text("tier"), row.textList("scenarios"), row.text("instance"),
                                row.answer("ingress_streaming_observed")
                                        ? Observation.WATCHED : Observation.NOT_WATCHED,
                                row.answer("clustered_arrangement_exercised")
                                        ? Observation.WATCHED : Observation.NOT_WATCHED))
                        .toList()));
    }

    /**
     * Every entry the matrix holds, in its own order.
     *
     * @return the entries
     */
    public List<Entry> entries() {
        return java.util.Collections.unmodifiableList(entries);
    }

    /**
     * Everything the matrix and the rest of the repository disagree about.
     *
     * @param root the repository root
     * @return one finding per unknown reference and per row on one side only
     */
    public PolicyReport against(Path root) {
        final List<String> rows = PlatformFloor.read(root).rows().stream()
                .map(PlatformFloor.Row::identifier)
                .toList();
        final List<String> tiers = tiersIn(root);
        final List<String> scenarios = scenariosIn(root);
        final List<PolicyFinding> findings = new ArrayList<>();
        entries.forEach(entry -> {
            if (!rows.contains(entry.deployment())) {
                findings.add(PolicyFinding.inFile(MATRIX_FILE, AN_ENTRY_WITH_NO_ROW,
                        entry.deployment() + " has evidence and the deployment matrix declares no"
                                + " such row"));
            }
            if (!NOT_OBSERVED.equals(entry.tier()) && !tiers.contains(entry.tier())) {
                findings.add(PolicyFinding.inFile(MATRIX_FILE, AN_UNKNOWN_TIER,
                        entry.tier() + " ran against " + entry.deployment()
                                + " and the gate declares no such tier"));
            }
            entry.scenarios().stream()
                    .filter(scenario -> !scenarios.contains(scenario))
                    .forEach(scenario -> findings.add(PolicyFinding.inFile(MATRIX_FILE,
                            AN_UNKNOWN_SCENARIO, scenario + " is recorded against "
                                    + entry.deployment() + " and no such scenario exists")));
        });
        rows.stream()
                .filter(row -> entries.stream()
                        .noneMatch(entry -> entry.deployment().equals(row)))
                .forEach(row -> findings.add(PolicyFinding.inFile(MATRIX_FILE, A_ROW_WITH_NO_ENTRY,
                        row + " is a supported deployment row with no entry at all, so nothing"
                                + " says whether anything has been proved about it")));
        return PolicyReport.of(findings);
    }

    /**
     * Whether a release may call one row supported.
     *
     * @param deployment the row
     * @return one finding where it may not
     */
    public PolicyReport claimOf(String deployment) {
        return entries.stream()
                .filter(entry -> entry.deployment().equals(deployment))
                .filter(entry -> entry.standing() == Standing.DECLARED_AND_UNPROVED)
                .map(entry -> PolicyFinding.inFile(MATRIX_FILE, CLAIMED_WITHOUT_EVIDENCE,
                        deployment + " is claimed as supported and no tier has run against it with"
                                + " its own ingress watched passing a stream"))
                .findFirst()
                .map(finding -> PolicyReport.of(List.of(finding)))
                .orElseGet(() -> PolicyReport.of(List.of()));
    }

    private static List<String> tiersIn(Path root) {
        final List<String> tiers = new ArrayList<>();
        boolean insideTier = false;
        for (final String line : RepositoryTree.text(root.resolve("policy/quality-gate.toml"))
                .lines().toList()) {
            final String stripped = line.strip();
            if (stripped.startsWith("[[")) {
                insideTier = "[[tier]]".equals(stripped);
            } else if (insideTier && stripped.startsWith("name = ")) {
                tiers.add(stripped.substring(stripped.indexOf('"') + 1,
                        stripped.lastIndexOf('"')));
            }
        }
        return List.copyOf(tiers);
    }

    private static List<String> scenariosIn(Path root) {
        return RepositoryTree.filesUnder(root.resolve(SCENARIOS), ".toml").stream()
                .map(file -> String.valueOf(file.getFileName()).replace(".toml", ""))
                .sorted()
                .toList();
    }
}
