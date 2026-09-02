// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The deployments this product supports, read through the one interface anything asks.
 *
 * <p>Every field is required and none is inferred. A row that omits its Java runtime is not a row
 * that runs on whatever is nearby, and a row whose runtime is below the bytecode target is a
 * refusal naming both rather than a support request six months later.</p>
 *
 * <p>Nothing here decides whether a deployment works. A row is a declaration; the interoperability
 * tier it names is the only thing that can produce evidence, and a row has no field it could
 * record that in.</p>
 */
public final class DeploymentMatrix {

    /** The interoperability tiers a row may name, and no others. */
    public static final Set<String> TIERS = Set.of("a", "b", "c");

    private static final String ROWS = "deployment";

    /** The repeated table a row declares which platform controls it provides in. */
    private static final String CONTROL_ROWS = "control";

    private final List<DeploymentRow> rows;

    private DeploymentMatrix(List<DeploymentRow> rows) {
        this.rows = rows;
    }

    /** Whether a deployment runs as more than one instance over one repository. */
    public enum Clustering {
        /** More than one instance, sharing one repository, where contention is observable. */
        CLUSTERED,
        /** One instance, where no property about contention can be exhibited at all. */
        SINGLE_INSTANCE
    }

    /** Whether a row is the one this product is built for, or a declaration beside it. */
    public enum Intent {
        /** The row this product is built for, of which there is exactly one. */
        BUILT_FOR,
        /** A row that appears in the table and carries no evidence until a tier runs against it. */
        DECLARED_ONLY
    }

    /** Whether a deployment provides one control. */
    public enum Provision {
        /** It does, and a control command may proceed there. */
        PROVIDED,
        /** It does not, and a control command is refused before it touches the platform. */
        ABSENT
    }

    /** One supported deployment, exactly as the matrix declares it.
     *
     * @param identifier the row's own name, unique across the matrix
     * @param product the product this row is a deployment of
     * @param javaRuntime the Java runtime the deployment provides
     * @param slingVersion the Apache Sling version it carries
     * @param oakVersion the Apache Jackrabbit Oak version it carries
     * @param clustering whether the deployment runs as more than one instance over one repository
     * @param contextPrefix the path prefix a route is reached under, empty where there is none
     * @param requestWindowMilliseconds how long its gateway allows a request to run
     * @param interoperabilityTier the tier that can observe this deployment
     * @param intent whether this is the row the product is built for
     * @param controls which platform controls this deployment provides, and why it refuses the
     *     ones it does not
     */
    public record DeploymentRow(String identifier, String product, long javaRuntime,
                                String slingVersion, String oakVersion, Clustering clustering,
                                String contextPrefix, long requestWindowMilliseconds,
                                String interoperabilityTier, Intent intent,
                                List<ControlRow> controls) {

        /** Holds a row whose control declarations nothing can change afterwards. */
        public DeploymentRow {
            controls = List.copyOf(controls);
        }

        /**
         * What this deployment says about one control.
         *
         * @param capability the control, spelled as the closed set spells it
         * @param provision whether this deployment provides it
         * @param reason why it does or does not, which a bare answer cannot carry
         */
        public record ControlRow(String capability, Provision provision, String reason) {
        }

        /**
         * Whether this is the row the product is built for.
         *
         * @return whether the row carries the built-for intent
         */
        public boolean builtFor() {
            return intent == Intent.BUILT_FOR;
        }
    }

    /** Why a matrix was refused. Each cause is distinct because each has a different fix. */
    public enum Failure {
        /** The document itself did not satisfy the matrix's closed key set. */
        DOCUMENT,
        /** Two rows carry the same identifier, so one of them is unreachable. */
        DUPLICATE_ROW,
        /** A row names an interoperability tier that does not exist. */
        UNKNOWN_TIER,
        /** A row provides a Java runtime below the release level this repository compiles to. */
        RUNTIME_BELOW_TARGET,
        /** The matrix does not name exactly one row this product is built for. */
        BUILT_FOR_NOT_SINGULAR
    }

    /** The result of reading a matrix: the rows, or the one reason they were refused. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A matrix every row of which satisfied every rule.
     *
     * @param matrix the loaded matrix
     */
    public record Loaded(DeploymentMatrix matrix) implements Outcome {
    }

    /**
     * A read that produced no matrix.
     *
     * @param failure why the matrix was refused
     * @param detail what was refused, named so that somebody can fix it
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * The closed key set a deployment matrix is held to.
     *
     * @return the shape, which declares every field a row must carry and admits no other
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("deployments")
                .rows(ROWS, row -> row
                        .text("id")
                        .text("product")
                        .number("java_runtime")
                        .text("sling_version")
                        .text("oak_version")
                        .answer("clustered")
                        .text("context_prefix")
                        .number("request_window_milliseconds")
                        .text("interop_tier")
                        .answer("built_for")
                        .rows(CONTROL_ROWS, control -> control
                                .text("capability")
                                .answer("provided")
                                .text("reason")))
                .build();
    }

    /**
     * Reads the matrix and holds every row to every rule.
     *
     * @param file the matrix document
     * @return the matrix, or the one reason it was refused
     */
    public static Outcome load(Path file) {
        final PolicyDocument.Outcome document = PolicyDocument.load(file, shape());
        if (document instanceof final PolicyDocument.Refused refused) {
            return new Refused(Failure.DOCUMENT, refused.failure() + ": " + refused.detail());
        }
        return hold(((PolicyDocument.Loaded) document).document());
    }

    private static Outcome hold(PolicyDocument document) {
        final List<DeploymentRow> rows = document.rows(ROWS).stream()
                .map(DeploymentMatrix::readRow)
                .toList();
        final Set<String> seen = new LinkedHashSet<>();
        for (final DeploymentRow row : rows) {
            if (!seen.add(row.identifier())) {
                return new Refused(Failure.DUPLICATE_ROW, row.identifier());
            }
            if (!TIERS.contains(row.interoperabilityTier())) {
                return new Refused(Failure.UNKNOWN_TIER,
                        row.identifier() + " names tier " + row.interoperabilityTier());
            }
            if (row.javaRuntime() < BytecodeContract.DECLARED_RELEASE) {
                return new Refused(Failure.RUNTIME_BELOW_TARGET, row.identifier()
                        + " provides Java " + row.javaRuntime()
                        + " and this repository compiles to release "
                        + BytecodeContract.DECLARED_RELEASE);
            }
        }
        final List<DeploymentRow> builtFor = rows.stream().filter(DeploymentRow::builtFor).toList();
        if (builtFor.size() != 1) {
            return new Refused(Failure.BUILT_FOR_NOT_SINGULAR,
                    builtFor.size() + " rows are marked as the one this product is built for");
        }
        return new Loaded(new DeploymentMatrix(rows));
    }

    private static DeploymentRow readRow(PolicyDocument row) {
        return new DeploymentRow(row.text("id"), row.text("product"), row.number("java_runtime"),
                row.text("sling_version"), row.text("oak_version"),
                row.answer("clustered") ? Clustering.CLUSTERED : Clustering.SINGLE_INSTANCE,
                row.text("context_prefix"), row.number("request_window_milliseconds"),
                row.text("interop_tier"),
                row.answer("built_for") ? Intent.BUILT_FOR : Intent.DECLARED_ONLY,
                row.rows(CONTROL_ROWS).stream()
                        .map(control -> new DeploymentRow.ControlRow(control.text("capability"),
                                control.answer("provided")
                                        ? Provision.PROVIDED : Provision.ABSENT,
                                control.text("reason")))
                        .toList());
    }

    /**
     * Every declared row, in the order the document declares them.
     *
     * @return the rows
     */
    public List<DeploymentRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * Every row's identifier, in the document's own order.
     *
     * @return the identifiers
     */
    public List<String> identifiers() {
        return rows.stream().map(DeploymentRow::identifier).toList();
    }

    /**
     * The one row this product is built for.
     *
     * @return that row, which loading has already proved is exactly one
     */
    public DeploymentRow builtFor() {
        return rows.stream().filter(DeploymentRow::builtFor).findFirst().orElseThrow();
    }

    /**
     * One row by its identifier.
     *
     * @param identifier the row's own name
     * @return the row, or nothing where the matrix declares no such row
     */
    public Optional<DeploymentRow> row(String identifier) {
        return rows.stream().filter(row -> identifier.equals(row.identifier())).findFirst();
    }

    /**
     * The shortest request window any supported deployment allows.
     *
     * <p>This is the bound every other time budget in this product has to sit under: a command that
     * ran to a budget above it would answer after the gateway of some supported deployment had
     * already ended the request.</p>
     *
     * @return the smallest declared window, in milliseconds
     */
    public long smallestRequestWindowMilliseconds() {
        return rows.stream()
                .mapToLong(DeploymentRow::requestWindowMilliseconds)
                .min()
                .orElseThrow();
    }

    /**
     * Whether every declared row provides a runtime at or above the bytecode target.
     *
     * @return one finding per row that does not, naming the row and both versions
     */
    public PolicyReport againstBytecodeContract() {
        final List<PolicyFinding> findings = new ArrayList<>();
        rows.stream()
                .filter(row -> row.javaRuntime() < BytecodeContract.DECLARED_RELEASE)
                .map(row -> PolicyFinding.inFile("support/deployments.toml", "bytecode-target",
                        row.identifier() + " provides Java " + row.javaRuntime()
                                + " below release " + BytecodeContract.DECLARED_RELEASE))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }
}
