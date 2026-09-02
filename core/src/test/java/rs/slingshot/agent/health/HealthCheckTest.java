// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What this agent publishes into the author's own operations dashboard.
 *
 * <p>The two worth proving carefully are the two that catch what only somebody else's instance can
 * break: routes the servlet resolver never registered, and a declared query whose covering index is
 * gone. Both are silent, both look like something else, and both are the operator's to fix — so
 * what each says has to name the cause rather than the symptom.</p>
 */
final class HealthCheckTest {

    private static final List<String> ROUTES =
            List.of("submit", "events", "artifact-transfer");

    /** Where the state tree sits, which the layout declares and nothing here decides. */
    private static final String ROOT = "/var/slingshot/agent";

    /** A declared interval, standing for whatever the contract states. */
    private static final long INTERVAL = 60_000;

    @Test
    @DisplayName("an unregistered route names the prefixes the resolver permits, not just the route")
    void anunregisteredRouteNamesTheCause() {
        final AgentHealth.Result refused = RouteRegistrationHealthCheck.of(ROUTES,
                List.of("submit"), List.of("/bin/wcm", "/bin/dam"));
        assertEquals(AgentHealth.Verdict.UNHEALTHY, refused.verdict());
        assertTrue(refused.detail().contains("/bin/wcm"),
                "the answer does not name what the resolver permits, so an operator reads that"
                        + " this product is broken rather than which configuration to open: "
                        + refused.detail());
        assertTrue(refused.detail().contains("events")
                        && refused.detail().contains("artifact-transfer"),
                "the answer does not name the routes that are missing: " + refused.detail());
        assertEquals(AgentHealth.Verdict.HEALTHY,
                RouteRegistrationHealthCheck.of(ROUTES, ROUTES, List.of("/bin")).verdict());
    }

    @Test
    @DisplayName("a query that would walk names the query, and one nobody could explain is unknown")
    void anuncoveredQueryNamesItself() {
        final AgentHealth.Result walking = QueryCoverageHealthCheck.of(List.of(
                new QueryCoverageHealthCheck.Plan("select * from [cq:Page]", "cqPageLucene"),
                new QueryCoverageHealthCheck.Plan("select * from [dam:Asset]",
                        QueryCoverageHealthCheck.TRAVERSES)));
        assertEquals(AgentHealth.Verdict.UNHEALTHY, walking.verdict());
        assertTrue(walking.detail().contains("dam:Asset"),
                "the answer does not name the query that would walk, which is one of the two"
                        + " things an operator types into a search: " + walking.detail());
        assertEquals(AgentHealth.Verdict.UNKNOWN,
                QueryCoverageHealthCheck.of(List.of()).verdict(),
                "a platform that would not explain anything was reported as a repository with no"
                        + " indexes, and a check that could not run is not evidence that the thing"
                        + " it checks is broken");
        assertEquals(AgentHealth.Verdict.HEALTHY, QueryCoverageHealthCheck.of(List.of(
                        new QueryCoverageHealthCheck.Plan("select * from [cq:Page]", "cqPageLucene")))
                .verdict());
    }

    @Test
    @DisplayName("a tree that is missing an entry names the entry rather than counting differences")
    void thestateTreeNamesTheFirstDifference() {
        final AgentHealth.Result missing = StateTreeHealthCheck.of(ROOT,
                StateTreeHealthCheck.Presence.PRESENT,
                List.of("slingshot-agent-service:jcr:all", "everyone:jcr:read:deny"),
                List.of("slingshot-agent-service:jcr:all"));
        assertEquals(AgentHealth.Verdict.UNHEALTHY, missing.verdict());
        assertTrue(missing.detail().contains("everyone:jcr:read:deny"),
                "the answer counts differences rather than naming the first one, which sends an"
                        + " operator to compare two lists by hand: " + missing.detail());
        final AgentHealth.Result unexpected = StateTreeHealthCheck.of(ROOT,
                StateTreeHealthCheck.Presence.PRESENT, List.of("slingshot-agent-service:jcr:all"),
                List.of("slingshot-agent-service:jcr:all", "everyone:jcr:read"));
        assertEquals(AgentHealth.Verdict.UNHEALTHY, unexpected.verdict());
        assertTrue(unexpected.detail().contains("everyone:jcr:read")
                        && unexpected.detail().contains("does not declare"),
                "an entry nobody declared was reported the same way as one that is missing, and"
                        + " the two run in opposite directions: " + unexpected.detail());
        assertEquals(AgentHealth.Verdict.UNHEALTHY, StateTreeHealthCheck.of(ROOT,
                        StateTreeHealthCheck.Presence.ABSENT, List.of(), List.of()).verdict());
        assertEquals(AgentHealth.Verdict.HEALTHY, StateTreeHealthCheck.of(ROOT,
                        StateTreeHealthCheck.Presence.PRESENT, List.of("a:jcr:all"),
                        List.of("a:jcr:all")).verdict());
    }

    @Test
    @DisplayName("the authority check issues and validates rather than inspecting the ring")
    void theauthorityCheckPerformsRatherThanInspects() {
        final List<String> performed = new ArrayList<>();
        final AgentHealth.Result unusable = new ContinuationAuthorityHealthCheck(() -> {
            performed.add("performed");
            return new ContinuationAuthorityHealthCheck.NotValidated(
                    "the ring is there and the token it signed was not accepted back");
        }, INTERVAL).at(1_000);
        assertEquals(List.of("performed"), performed,
                "the check answered without performing anything, and a ring that is present and"
                        + " unusable is the case that matters");
        assertEquals(AgentHealth.Verdict.UNHEALTHY, unusable.verdict());
        assertEquals(AgentHealth.Verdict.HEALTHY, new ContinuationAuthorityHealthCheck(
                        () -> new ContinuationAuthorityHealthCheck.Validated("key-2"), INTERVAL)
                .at(1_000).verdict());
    }

    @Test
    @DisplayName("the authority check performs at most once per interval and says when it last ran")
    void theauthorityCheckHoldsItsAnswerForTheDeclaredInterval() {
        final List<String> performed = new ArrayList<>();
        final ContinuationAuthorityHealthCheck check = new ContinuationAuthorityHealthCheck(() -> {
            performed.add("performed");
            return new ContinuationAuthorityHealthCheck.Validated("key-" + performed.size());
        }, INTERVAL);
        final AgentHealth.Result first = check.at(1_000);
        assertTrue(first.detail().contains("1000"),
                "the answer does not say when it was performed, so a held one is indistinguishable"
                        + " from a fresh one: " + first.detail());
        assertEquals(first, check.at(1_000 + INTERVAL - 1),
                "a poll inside the declared interval was answered with fresh work, and a dashboard"
                        + " and a monitor both polling is the ordinary case rather than the"
                        + " exception");
        assertEquals(1, performed.size(),
                "the work was performed more than once inside one interval: " + performed);
        final AgentHealth.Result later = check.at(1_000 + INTERVAL);
        assertEquals(2, performed.size(),
                "the answer was held past the interval the contract declares");
        assertTrue(later.detail().contains(String.valueOf(1_000 + INTERVAL)),
                "the fresh answer still reports the earlier instant: " + later.detail());
    }

    @Test
    @DisplayName("capacity names every count against its bound, over or not")
    void capacityNamesEveryCountAgainstItsBound() {
        final AgentHealth.Result over = CapacityHealthCheck.of(List.of(
                new CapacityHealthCheck.Reading("operation_detail_rows", 41_200, 40_000),
                new CapacityHealthCheck.Reading("event_rows", 12, 1_000)));
        assertEquals(AgentHealth.Verdict.UNHEALTHY, over.verdict());
        assertTrue(over.detail().contains("41200/40000") && over.detail().contains("12/1000"),
                "the answer does not name every count against its bound, and one-away-from-three"
                        + " reads the same as well-within-every-bound: " + over.detail());
        final AgentHealth.Result within = CapacityHealthCheck.of(List.of(
                new CapacityHealthCheck.Reading("event_rows", 12, 1_000)));
        assertEquals(AgentHealth.Verdict.HEALTHY, within.verdict());
        assertTrue(within.detail().contains("12/1000"), within.detail());
        assertEquals(AgentHealth.Verdict.UNKNOWN, CapacityHealthCheck.of(List.of()).verdict(),
                "a ledger that answered nothing was reported as a ledger holding nothing");
    }

    @Test
    @DisplayName("an unclaimed deployment row is reported as unclaimed rather than as a failure")
    void anunclaimedRowIsReportedAsItself() {
        final AgentHealth.Result unclaimed = DeploymentRowHealthCheck.of(
                new DeploymentRowHealthCheck.Matched("aem-6-5-lts",
                        DeploymentRowHealthCheck.Claim.UNCLAIMED));
        assertEquals(AgentHealth.Verdict.UNKNOWN, unclaimed.verdict(),
                "a row this build does not claim was reported as something being broken, and"
                        + " plenty of this works on a row nobody claimed");
        assertTrue(unclaimed.detail().contains("aem-6-5-lts")
                        && unclaimed.detail().contains("does not claim"),
                "the answer does not say which row or that it is unclaimed: " + unclaimed.detail());
        assertEquals(AgentHealth.Verdict.HEALTHY, DeploymentRowHealthCheck.of(
                        new DeploymentRowHealthCheck.Matched("aem-cloud-service",
                                DeploymentRowHealthCheck.Claim.CLAIMED)).verdict());
        final AgentHealth.Result unrecognised = DeploymentRowHealthCheck.of(
                new DeploymentRowHealthCheck.Unrecognised("Adobe Experience Manager", "6.4"));
        assertEquals(AgentHealth.Verdict.UNKNOWN, unrecognised.verdict());
        assertTrue(unrecognised.detail().contains("6.4"),
                "the answer does not say what it found itself running on: "
                        + unrecognised.detail());
    }

    @Test
    @DisplayName("no check reports another's cause, so a dashboard names one thing to fix")
    void nocheckReportsAnothersCause() {
        final List<AgentHealth.Result> broken = List.of(
                StateTreeHealthCheck.of(ROOT, StateTreeHealthCheck.Presence.ABSENT, List.of(),
                        List.of()),
                new ContinuationAuthorityHealthCheck(
                        () -> new ContinuationAuthorityHealthCheck.NotValidated("no ring"),
                        INTERVAL).at(1_000),
                CapacityHealthCheck.of(List.of(
                        new CapacityHealthCheck.Reading("event_rows", 2, 1))),
                DeploymentRowHealthCheck.of(new DeploymentRowHealthCheck.Matched("aem-6-5-lts",
                        DeploymentRowHealthCheck.Claim.UNCLAIMED)),
                RouteRegistrationHealthCheck.of(ROUTES, List.of(), List.of("/bin/wcm")),
                QueryCoverageHealthCheck.of(List.of(new QueryCoverageHealthCheck.Plan(
                        "select * from [cq:Page]", QueryCoverageHealthCheck.TRAVERSES))));
        assertEquals(AgentHealth.Check.names(), broken.stream().map(AgentHealth.Result::name)
                        .toList(),
                "one check answered under another's name, so a dashboard would show the wrong"
                        + " thing to fix");
        assertEquals(broken.size(), broken.stream().map(AgentHealth.Result::detail).distinct()
                        .count(),
                "two checks gave the same sentence, which means one is reporting the other's"
                        + " cause");
    }

    @Test
    @DisplayName("there are six checks rather than one aggregate, each with its own name")
    void thereAreSixChecksRatherThanOne() {
        assertEquals(6, AgentHealth.Check.values().length,
                "the set of checks changed, and one aggregate tells an operator only that"
                        + " something is wrong while each of these has a different"
                        + " somebody-to-fix-it");
        assertEquals(AgentHealth.Check.values().length,
                AgentHealth.Check.names().stream().distinct().count(),
                "two checks are named the same way on the dashboard");
        AgentHealth.Check.names().forEach(name ->
                assertTrue(AgentHealth.Check.named(name).isPresent(),
                        name + " is a name nothing answers to"));
        assertEquals(Optional.empty(), AgentHealth.Check.named("everything"),
                "an aggregate check exists, which answers the one question that helps least");
        AgentHealth.Check.names().forEach(name -> assertTrue(
                AgentHealth.Check.named(name).orElseThrow().tags()
                        .contains(AgentHealth.AGENT_TAG),
                name + " does not carry the tag every check of this agent's carries, so an"
                        + " operator cannot select them as a set"));
        assertEquals(AgentHealth.Check.values().length + 1,
                AgentHealth.Check.allTags().size(),
                "two checks are selected by the same second tag, so they no longer appear"
                        + " separately to somebody watching one concern: "
                        + AgentHealth.Check.allTags());
    }

    @Test
    @DisplayName("a check that could not run is told apart from one that found something wrong")
    void unknownIsNotUnhealthy() {
        assertEquals(AgentHealth.Verdict.UNKNOWN,
                AgentHealth.unknown(AgentHealth.Check.CAPACITY, "the ledger did not answer")
                        .verdict(),
                "a check that could not run was reported as having found a problem, which has"
                        + " operators chasing a store that is fine because the check beside it"
                        + " timed out");
        assertEquals(AgentHealth.Check.CAPACITY.spelling(),
                AgentHealth.healthy(AgentHealth.Check.CAPACITY, "within every bound").name());
        assertEquals(AgentHealth.Verdict.UNHEALTHY,
                AgentHealth.unhealthy(AgentHealth.Check.STATE_TREE, "an entry is missing")
                        .verdict());
    }
}
