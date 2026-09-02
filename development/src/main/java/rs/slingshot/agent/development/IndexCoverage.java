// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Whether every query this product issues is answered from an index rather than by walking.
 *
 * <p>Adobe Experience Manager answers a query either from an index or by walking the repository,
 * and walking it is how one command takes an author instance down. That is not a performance
 * remark: a query with no covering index on a large author is an outage, and it is an outage that
 * only appears on a customer's content.</p>
 *
 * <p>What this checks is a claim about what a deployment row was declared to provide. It is half
 * the guarantee: the other half is the plan the instance in front of it actually returns, checked
 * before a node is examined, because a customer can remove an index and a Cloud Service
 * environment's index set is theirs to change. A disagreement between the two is a deployment
 * whose indexes are not what this build was told, and it is reported as itself.</p>
 *
 * <p>This product ships no index definition. A custom index lives outside {@code /apps}, changes
 * the shape of somebody else's repository, and is an operator's decision rather than a side effect
 * of installing an agent.</p>
 */
public final class IndexCoverage {

    /** Where the queries and the indexes that answer them are committed. */
    public static final String COVERAGE_FILE = "policy/query-index-coverage.toml";

    private static final String INDEX_ROWS = "index";

    private static final String QUERY_ROWS = "query";

    private final List<IndexRow> indexes;
    private final List<QueryRow> queries;

    private IndexCoverage(List<IndexRow> indexes, List<QueryRow> queries) {
        this.indexes = indexes;
        this.queries = queries;
    }

    /**
     * One index a deployment row already provides.
     *
     * @param name what the platform calls it
     * @param deployment which row provides it
     * @param covers the properties a query may filter on and still be answered from it
     */
    public record IndexRow(String name, String deployment, List<String> covers) {

        /** Holds a row nothing can change afterwards. */
        public IndexRow {
            covers = List.copyOf(covers);
        }

        /**
         * The properties a query may filter on and still be answered from this index.
         *
         * @return the properties
         */
        @Override
        public List<String> covers() {
            return Collections.unmodifiableList(covers);
        }
    }

    /**
     * One query this product issues.
     *
     * @param name what it is called, which is what a refusal names
     * @param roots the subtrees it is restricted to
     * @param properties the properties it filters on
     * @param issuedBy the command that issues it
     */
    public record QueryRow(String name, List<String> roots, List<String> properties,
                           String issuedBy) {

        /** Holds a row nothing can change afterwards. */
        public QueryRow {
            roots = List.copyOf(roots);
            properties = List.copyOf(properties);
        }

        /**
         * The subtrees this query is restricted to.
         *
         * @return the roots
         */
        @Override
        public List<String> roots() {
            return Collections.unmodifiableList(roots);
        }

        /**
         * The properties this query filters on, which is what an index has to cover.
         *
         * @return the properties
         */
        @Override
        public List<String> properties() {
            return Collections.unmodifiableList(properties);
        }
    }

    /** The result of reading the coverage: the coverage, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A coverage document that satisfied its shape.
     *
     * @param coverage the coverage
     */
    public record Loaded(IndexCoverage coverage) implements Outcome {
    }

    /**
     * A read that produced no coverage.
     *
     * @param detail what was refused
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The shape the coverage document is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("query-index-coverage")
                .text("coverage.reason")
                .rows(INDEX_ROWS, row -> row.text("name").text("deployment").textList("covers")
                        .text("reason"))
                .rows(QUERY_ROWS, row -> row.text("name").textList("roots").textList("properties")
                        .text("issued_by").text("reason"))
                .build();
    }

    /**
     * Reads the coverage this repository commits.
     *
     * @param root the repository root
     * @return the coverage, or the one reason there is none
     */
    public static Outcome read(Path root) {
        return readCoverage(root.resolve(COVERAGE_FILE));
    }

    /**
     * Reads a coverage document from wherever it sits.
     *
     * @param coverage the document
     * @return the coverage, or the one reason there is none
     */
    public static Outcome readCoverage(Path coverage) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(coverage, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new IndexCoverage(
                document.rows(INDEX_ROWS).stream()
                        .map(row -> new IndexRow(row.text("name"), row.text("deployment"),
                                row.textList("covers")))
                        .toList(),
                document.rows(QUERY_ROWS).stream()
                        .map(row -> new QueryRow(row.text("name"), row.textList("roots"),
                                row.textList("properties"), row.text("issued_by")))
                        .toList()));
    }

    /**
     * Every index a deployment row provides, in the document's own order.
     *
     * @return the indexes
     */
    public List<IndexRow> indexes() {
        return Collections.unmodifiableList(indexes);
    }

    /**
     * Every query this product issues, in the document's own order.
     *
     * @return the queries
     */
    public List<QueryRow> queries() {
        return Collections.unmodifiableList(queries);
    }

    /**
     * Whether every query is covered on every deployment row this product supports.
     *
     * @param deployments every row the matrix declares
     * @return one finding per query no index covers on a row, per index naming a row nobody
     *     declared, and per query nothing issues
     */
    public PolicyReport against(List<String> deployments) {
        final List<PolicyFinding> findings = new ArrayList<>();
        deployments.forEach(deployment -> queries.stream()
                .filter(query -> uncovered(query, deployment))
                .map(query -> PolicyFinding.inFile(COVERAGE_FILE, "query-no-index-covers",
                        query.name() + " filters on " + query.properties() + " and no index "
                                + deployment + " provides covers it, so that row would answer it"
                                + " by walking the repository"))
                .forEach(findings::add));
        indexes.stream()
                .filter(index -> !deployments.contains(index.deployment()))
                .map(index -> PolicyFinding.inFile(COVERAGE_FILE, "index-for-no-deployment",
                        index.name() + " is declared for " + index.deployment()
                                + ", which is not a row this product supports"))
                .forEach(findings::add);
        queries.stream()
                .filter(query -> query.issuedBy().isBlank() || query.roots().isEmpty())
                .map(query -> PolicyFinding.inFile(COVERAGE_FILE, "query-with-no-issuer-or-root",
                        query.name() + " names no issuing command or no subtree, and a query with"
                                + " no root starts at the top of somebody's repository"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    private boolean uncovered(QueryRow query, String deployment) {
        return !query.properties().isEmpty() && indexes.stream()
                .filter(index -> index.deployment().equals(deployment))
                .noneMatch(index -> index.covers().containsAll(query.properties()));
    }

    /**
     * Whether anything this product ships carries an index definition.
     *
     * <p>A custom index lives outside {@code /apps} and changes the shape of somebody else's
     * repository. Shipping one would make installing this agent an operator decision they never
     * made.</p>
     *
     * @param packages the produced content packages
     * @return one finding per index definition found in one
     */
    public static PolicyReport againstShippedPackages(List<Path> packages) {
        final List<PolicyFinding> findings = new ArrayList<>();
        packages.stream()
                .filter(java.nio.file.Files::isRegularFile)
                .forEach(archive -> BuiltArtifact.at(archive).entryNames().stream()
                        .filter(entry -> entry.contains("oak:index"))
                        .map(entry -> PolicyFinding.inFile(String.valueOf(archive.getFileName()),
                                "shipped-index-definition", entry))
                        .forEach(findings::add));
        return PolicyReport.of(findings);
    }

    /**
     * The one reason there is no coverage, where there is none.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where there is coverage
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
