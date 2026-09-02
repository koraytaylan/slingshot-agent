// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one table every route path in this product comes from.
 *
 * <p>The assertion that carries the argument is the last one: no string literal matching the agent
 * prefix exists anywhere in this bundle's sources but here. A second spelling of a path is the
 * defect this table exists to make impossible, and the sibling repository is carrying three of them
 * right now.</p>
 */
final class AgentRouteTableTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/agent-routes");

    private static final String PREFIX = "/bin/slingshot/agent";

    @Test
    @DisplayName("the committed table parses into the exact declared route set")
    void theCommittedTableParsesIntoItsRoutes() {
        final AgentRouteTable table = loaded(AgentRouteTable.load());
        assertEquals(List.of("capabilities", "submit", "operation-lookup", "physical-job-lookup",
                        "subscription-high-water", "events", "artifact-transfer", "artifact-intake"),
                table.names());
        assertEquals(PREFIX, table.prefix());
        assertEquals("/bin/slingshot/agent/capabilities", table.route("capabilities").path());
        assertEquals("GET", table.route("capabilities").method());
        assertEquals("application/json", table.route("capabilities").mediaType());
        assertFalse(table.route("capabilities").takesABody());
        assertTrue(table.route("submit").takesABody());
    }

    @Test
    @DisplayName("every route sits under the one prefix and names the plan that builds it")
    void everyRouteIsUnderThePrefixAndOwned() {
        final AgentRouteTable table = loaded(AgentRouteTable.load());
        table.routes().forEach((name, route) -> {
            assertTrue(route.path().startsWith(PREFIX), name + " is reached outside " + PREFIX);
            assertTrue(!route.owningPlan().isBlank(), name + " names no plan that builds it");
            assertTrue(!route.reason().isBlank(), name + " records no reason");
        });
        assertEquals("0001", table.route("capabilities").owningPlan());
    }

    @Test
    @DisplayName("a route outside the prefix, a duplicate, and one with no plan are refused distinctly")
    void theThreeTableFailuresAreDistinct() {
        assertEquals(AgentRouteTable.Failure.OUTSIDE_THE_PREFIX,
                refusal("outside-the-prefix.toml").failure());
        assertEquals(AgentRouteTable.Failure.DUPLICATE_ROUTE,
                refusal("duplicate-route.toml").failure());
        assertEquals(AgentRouteTable.Failure.NO_OWNING_PLAN,
                refusal("no-owning-plan.toml").failure());
        assertEquals(AgentRouteTable.Failure.UNPARSABLE, refusal("not-a-table.toml").failure());
    }

    @Test
    @DisplayName("the table embedded in this bundle is the one this repository commits")
    void theEmbeddedTableIsTheCommittedOne() {
        assertEquals(loaded(read("accepted.toml")).names(), loaded(AgentRouteTable.load()).names());
    }

    @Test
    @DisplayName("asking for a route the table does not declare is a defect rather than an absence")
    void anUndeclaredRouteIsADefect() {
        final AgentRouteTable table = loaded(AgentRouteTable.load());
        assertThrows(IllegalArgumentException.class, () -> table.route("nothing-like-this"));
    }

    @Test
    @DisplayName("no route path exists anywhere in this bundle outside the table")
    void noRoutePathExistsOutsideTheTable() {
        final List<String> carrying = sourcesUnder(REPOSITORY.resolve("core/src/main/java")).stream()
                .filter(source -> read(source).contains(PREFIX))
                .map(source -> REPOSITORY.relativize(source).toString())
                .filter(source -> !source.endsWith("AgentRouteTable.java"))
                .toList();
        assertEquals(List.of("core/src/main/java/rs/slingshot/agent/http/ArtifactServlet.java",
                        "core/src/main/java/rs/slingshot/agent/http/CapabilityServlet.java",
                        "core/src/main/java/rs/slingshot/agent/http/EventStreamServlet.java",
                        "core/src/main/java/rs/slingshot/agent/http/HighWaterServlet.java",
                        "core/src/main/java/rs/slingshot/agent/http/OperationLookupServlet.java",
                        "core/src/main/java/rs/slingshot/agent/http/PhysicalJobServlet.java",
                        "core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java"),
                carrying,
                "a route path is written somewhere other than the table and the registrations the"
                        + " container reads before any code runs");
    }

    private static AgentRouteTable.Outcome read(String fixture) {
        return AgentRouteTable.read(read(FIXTURES.resolve(fixture)));
    }

    private static AgentRouteTable loaded(AgentRouteTable.Outcome outcome) {
        return assertInstanceOf(AgentRouteTable.Loaded.class, outcome,
                "the route table was refused: " + outcome).table();
    }

    private static AgentRouteTable.Refused refusal(String fixture) {
        return assertInstanceOf(AgentRouteTable.Refused.class, read(fixture),
                fixture + " was accepted where it must be refused");
    }

    private static List<Path> sourcesUnder(Path tree) {
        try (var walk = Files.walk(tree)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
