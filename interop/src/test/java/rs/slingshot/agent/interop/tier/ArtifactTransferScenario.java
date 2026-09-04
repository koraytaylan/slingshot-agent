// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Serving a result too large to answer inline, asked for on a running instance.
 *
 * <p>What a running instance adds to the unit suite is the answer a caller actually receives when
 * there is nothing to serve: this build registers no command, so no execution has published an
 * artifact here, and every answer is one of the refusals. That they are indistinguishable is the
 * property worth proving on a real runtime — a caller who could tell "somebody else's operation"
 * from "no such slot" could ask which operations exist.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ArtifactTransferScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario asks for, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/artifact";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    /** Another identifier this build reads, which no operation on a fresh instance has either. */
    private static final String ANOTHER_IDENTIFIER =
            "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809";

    /** What a request from nobody in particular is answered with. */
    private static final int UNAUTHORIZED = 401;

    /** What a slot nothing here holds is answered with. */
    private static final int NOTHING_HERE = 404;

    /** What a request this build cannot read at all is answered with. */
    private static final int REFUSED = 400;

    /** Where this agent keeps things, which no answer may disclose. */
    private static final String THE_AGENTS_OWN_TREE = "/var/slingshot-agent";

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("the route is registered and refuses a request from nobody in particular")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHORIZED, requests.readAsNobody(asking(AN_IDENTIFIER, "result"))
                        .statusCode(),
                "an artifact was served to nobody in particular");
    }

    @Test
    @DisplayName("two operations nobody here holds are answered identically, byte for byte")
    void twooperationsNobodyHoldsAreAnsweredIdentically() {
        final var one = requests.readAsAuthenticatedUser(asking(AN_IDENTIFIER, "result"));
        final var other = requests.readAsAuthenticatedUser(asking(ANOTHER_IDENTIFIER, "result"));
        assertEquals(NOTHING_HERE, one.statusCode(), one.body());
        assertEquals(one.statusCode(), other.statusCode(),
                "two operations nobody holds are told apart");
        assertEquals(one.body(), other.body(),
                "the two answers differ in their bytes, which is a caller learning which"
                        + " operations exist");
    }

    @Test
    @DisplayName("an ask this build cannot read is refused rather than guessed at")
    void anaskThisBuildCannotReadIsRefused() {
        assertEquals(REFUSED, requests.readAsAuthenticatedUser(tier.address() + ROUTE
                        + "?agent_operation_identifier=not-an-identifier&artifact_slot=result")
                        .statusCode(),
                "an ask this build cannot read was guessed at");
        assertEquals(REFUSED, requests.readAsAuthenticatedUser(tier.address() + ROUTE)
                        .statusCode(),
                "an ask naming nothing at all was guessed at");
    }

    @Test
    @DisplayName("no answer discloses where this agent keeps anything")
    void noanswerDisclosesWhereThingsAreKept() {
        for (final String asked : List.of(asking(AN_IDENTIFIER, "result"),
                asking(AN_IDENTIFIER, "a-slot-nobody-filled"),
                tier.address() + ROUTE)) {
            final var answered = requests.readAsAuthenticatedUser(asked);
            assertFalse(String.valueOf(answered.body()).contains(THE_AGENTS_OWN_TREE),
                    asked + " disclosed where this agent keeps things: " + answered.body());
            assertTrue(answered.body() == null || answered.body().isEmpty(),
                    "a refusal carried a body: " + answered.body());
        }
    }

    @Test
    @DisplayName("no other spelling of the route reaches it")
    void nootherSpellingOfTheRouteReachesIt() {
        for (final String spelling : List.of(ROUTE + ".json", ROUTE + "/", ROUTE + "/anything")) {
            final int answered = requests.readAsAuthenticatedUser(tier.address() + spelling
                    + "?agent_operation_identifier=" + AN_IDENTIFIER + "&artifact_slot=result")
                    .statusCode();
            assertTrue(answered == NOTHING_HERE || answered == METHOD_REFUSED,
                    spelling + " reached the route, and a route with spellings nobody enumerated"
                            + " is a route whose policy applies to some of the ways in: "
                            + answered);
        }
    }

    /** What a method the table does not give a route is answered with. */
    private static final int METHOD_REFUSED = 405;

    private String asking(String operation, String slot) {
        return tier.address() + ROUTE + "?agent_operation_identifier=" + operation
                + "&artifact_slot=" + slot;
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
