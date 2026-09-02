// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the agent's own identity may reach, checked against the script that creates it.
 *
 * <p>The correspondence runs both ways because either direction failing alone would leave the list
 * describing something other than the instance: a grant declared and not created is a permission
 * nobody has, and a grant created and not declared is one nobody reviewed.</p>
 */
final class RepositoryAccessTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/repository-access");

    @Test
    @DisplayName("the declared grants and the ones the script creates are the same set")
    void theDeclaredAndCreatedGrantsAgree() {
        assertEquals("", policy().againstTheScript(REPOSITORY).render());
    }

    @Test
    @DisplayName("every subservice the policy declares is one the mapping names, and no other")
    void theMappingNamesExactlyTheDeclaredSubservices() {
        assertEquals("", policy().againstTheMapping(REPOSITORY).render());
        assertEquals(List.of("state", "maintenance"), policy().subservices());
    }

    @Test
    @DisplayName("every grant sits inside the agent's own tree and records why it is needed")
    void everyGrantIsInsideTheAgentsOwnTreeAndReasoned() {
        policy().grants().forEach(grant -> {
            assertTrue(grant.path().startsWith("/var/slingshot-agent"),
                    grant.path() + " is granted to the agent's own identity outside its own tree");
            assertTrue(!grant.reason().isBlank(), grant.path() + " records no reason");
            assertTrue(!grant.privileges().isEmpty(), grant.path() + " grants nothing at all");
        });
    }

    @Test
    @DisplayName("what this product ships as a permitted group is what the policy says it ships")
    void theshippedPermittedGroupsAreTheDeclaredOnes() {
        assertEquals("", policy().againstTheShippedConfiguration(REPOSITORY).render());
        assertEquals(List.of("administrators"), policy().permittedGroups().shipped(),
                "this product ships naming somebody other than an administrator, or naming"
                        + " everybody, which is not a default at all");
    }

    @Test
    @DisplayName("a grant outside the agent's tree and one with no reason refuse the whole policy")
    void bothWaysOfWideningTheGrantAreRefused() {
        assertInstanceOf(RepositoryAccess.Refused.class,
                RepositoryAccess.readPolicy(FIXTURES.resolve("grant-outside-the-tree.toml")),
                "a grant outside the agent's own tree was accepted");
        assertInstanceOf(RepositoryAccess.Refused.class,
                RepositoryAccess.readPolicy(FIXTURES.resolve("grant-with-no-reason.toml")),
                "a grant with no reason was accepted");
    }

    @Test
    @DisplayName("a mapping naming a subservice nobody declared is refused naming it")
    void anUnknownSubserviceIsRefused() {
        assertRule(policyAt("subservice-nobody-maps.toml").againstTheMapping(REPOSITORY),
                "repository-access", "reporting is declared and nothing maps it");
    }

    @Test
    @DisplayName("nothing anywhere in this repository acts as somebody else")
    void nothingAnywhereImpersonates() {
        assertEquals("", RepositoryAccess.impersonation(REPOSITORY).render());
    }

    @Test
    @DisplayName("the three paths the agent's identity must be refused are recorded")
    void theRefusedPathsAreRecorded() {
        assertEquals(List.of("/content", "/apps", "/home"), policy().refusedPaths());
        assertEquals("slingshot-agent-state", policy().serviceUser());
    }

    private static RepositoryAccess policy() {
        return assertInstanceOf(RepositoryAccess.Loaded.class, RepositoryAccess.read(REPOSITORY),
                "the access policy was refused").policy();
    }

    private static RepositoryAccess policyAt(String fixture) {
        return assertInstanceOf(RepositoryAccess.Loaded.class,
                RepositoryAccess.readPolicy(FIXTURES.resolve(fixture)),
                fixture + " was refused").policy();
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + report.render());
    }
}
