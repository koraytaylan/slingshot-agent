// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a handler can obtain what it was not given, decided by parsing rather than by review.
 *
 * <p>The agent's own bookkeeping runs as a service user; everything a handler touches is decided by
 * the caller's own repository access. That is the difference between an agent and a privilege
 * escalation, and it holds only if obtaining a session is unavailable rather than discouraged — so
 * every form that would obtain one is refused here, and each is proved on a fixture that reaches
 * for it.</p>
 *
 * <p>The comment-only fixture is the other half. Naming a refused form while explaining why it is
 * refused is not reaching for one, and a rule that could not tell those apart would be a rule that
 * stopped people writing the explanation down.</p>
 */
final class SessionAcquisitionTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/session-acquisition");

    @Test
    @DisplayName("nothing in this repository's own handler package reaches outside its context")
    void nothinginTheHandlerPackageReachesOutsideItsContext() {
        assertEquals("", SourcePolicy.handlerFindings(REPOSITORY).render(),
                "something under the framework's own package obtains what it was not given");
    }

    @Test
    @DisplayName("every form that would obtain a session is refused, and each is proved on one")
    void everyformThatWouldObtainAsessionIsRefused() {
        final List<String> refused = SourcePolicy.refusedInHandlers(REPOSITORY);
        assertEquals(6, refused.size(),
                "a way of obtaining what a handler was not given was added or lost: " + refused);
        final String report = SourcePolicy.handlerFindingsIn(FIXTURES.resolve("reaching"),
                FIXTURES, refused).render();
        refused.forEach(symbol -> assertTrue(report.contains(symbol),
                symbol + " is refused and nothing caught a fixture reaching for it: " + report));
        assertEquals(refused.size(), report.lines().count(),
                "the fixtures and the refusals do not correspond one to one: " + report);
    }

    @Test
    @DisplayName("naming a refused form while explaining it is not reaching for one")
    void namingArefusedFormInAcommentIsNotReachingForOne() {
        assertEquals("", SourcePolicy.handlerFindingsIn(FIXTURES.resolve("explaining"), FIXTURES,
                        SourcePolicy.refusedInHandlers(REPOSITORY)).render(),
                "a handler explaining what it may not do was refused for saying so");
    }

    @Test
    @DisplayName("a directory with no handlers in it yet is not a finding")
    void adirectoryWithNoHandlersIsNotAfinding() {
        assertEquals("", SourcePolicy.handlerFindingsIn(FIXTURES.resolve("nothing-here"), FIXTURES,
                        SourcePolicy.refusedInHandlers(REPOSITORY)).render(),
                "a package that does not exist yet was reported as reaching for something");
    }
}
