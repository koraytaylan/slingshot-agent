// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether every query this product issues is answered from an index rather than by walking.
 *
 * <p>The refusals are proved on fixtures rather than by reading a passing repository: a check that
 * has never caught an uncovered query is a check nobody has watched work, and the thing it exists
 * to catch takes an author instance down on a customer's content rather than on ours.</p>
 */
final class IndexCoverageTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/index-coverage");

    @Test
    @DisplayName("every query this product issues is covered on every deployment row")
    void everyqueryIsCoveredOnEveryRow() {
        assertEquals("", coverage().against(deployments()).render(),
                "a query would be answered by walking somebody's repository");
        assertFalse(coverage().indexes().isEmpty(),
                "no index is declared at all, so the check has nothing to compare against");
        coverage().indexes().forEach(index -> assertTrue(deployments().contains(index.deployment()),
                index.name() + " is declared for a row this product does not support"));
    }

    @Test
    @DisplayName("a query no index covers fails, naming the query and the rows that cannot answer")
    void aqueryNoIndexCoversFails() {
        final PolicyReport report = at("query-nothing-covers").against(deployments());
        assertTrue(report.render().contains("query-no-index-covers"), report.render());
        assertTrue(report.render().contains("find_pages_by_colour"), report.render());
        deployments().forEach(row -> assertTrue(report.render().contains(row), report.render()));
    }

    @Test
    @DisplayName("an index for a row nobody supports, and a query with no root, both fail")
    void bothWaysOfDeclaringSomethingUnsupportedFail() {
        final PolicyReport unsupported = at("index-for-no-deployment").against(deployments());
        assertTrue(unsupported.render().contains("index-for-no-deployment"), unsupported.render());
        final PolicyReport rootless = at("query-with-no-root").against(deployments());
        assertTrue(rootless.render().contains("query-with-no-issuer-or-root"), rootless.render());
    }

    @Test
    @DisplayName("nothing this product ships carries an index definition")
    void nothingShippedCarriesAnindexDefinition() {
        final String version = ReactorModel.at(REPOSITORY).aggregator().getVersion();
        final List<Path> shipped = List.of("ui.apps", "ui.config", "ui.apps.structure").stream()
                .map(module -> REPOSITORY.resolve(module).resolve("target")
                        .resolve("slingshot-agent-" + module + "-" + version + ".zip"))
                .toList();
        assertEquals("", IndexCoverage.againstShippedPackages(shipped).render(),
                "a content package carries an index definition, which changes the shape of"
                        + " somebody else's repository as a side effect of installing an agent");
    }

    @Test
    @DisplayName("a document that is not a coverage document at all is refused")
    void adocumentThatIsNotCoverageIsRefused() {
        assertInstanceOf(IndexCoverage.Refused.class,
                IndexCoverage.readCoverage(FIXTURES.resolve("not-a-coverage-document.toml")),
                "a document missing what a coverage document has was accepted");
        assertTrue(IndexCoverage.refusalIn(
                        IndexCoverage.readCoverage(FIXTURES.resolve("not-a-coverage-document.toml")))
                .isPresent());
    }

    private static IndexCoverage at(String fixture) {
        return assertInstanceOf(IndexCoverage.Loaded.class,
                IndexCoverage.readCoverage(FIXTURES.resolve(fixture + ".toml")),
                fixture + " is not a coverage document this checker reads").coverage();
    }

    private static IndexCoverage coverage() {
        return assertInstanceOf(IndexCoverage.Loaded.class, IndexCoverage.read(REPOSITORY),
                "the committed coverage was refused").coverage();
    }

    private static List<String> deployments() {
        return assertInstanceOf(DeploymentMatrix.Loaded.class,
                DeploymentMatrix.load(REPOSITORY.resolve("support/deployments.toml")),
                "the deployment matrix was refused").matrix().rows().stream()
                .map(DeploymentMatrix.DeploymentRow::identifier)
                .toList();
    }
}
