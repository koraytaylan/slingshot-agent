// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The two ways a session is obtained, and the absence of a third.
 *
 * <p>The absence is the point. A command executes inside the request that submitted it, so the
 * caller's session is the one the request already arrived with — and because nothing here ever
 * needs to act as somebody who is not currently asking, nothing here impersonates, holds a
 * credential, or stores a token. That is a guarantee about the architecture rather than about the
 * care taken inside one method.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class AgentSessionTest {

    private static final Path REPOSITORY = repositoryRoot();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    @DisplayName("the caller's session is the one the request already arrived with")
    void theCallersSessionIsTheRequestsOwn() {
        // Nothing is closed here on purpose: this session belongs to the request, the platform
        // opened it for whoever authenticated, and the platform closes it when the request ends.
        // Closing somebody else's session is how a request stops being able to answer itself.
        assertEquals(sling.request().getResourceResolver(),
                AgentSession.forCaller(sling.request()),
                "the caller's session was obtained rather than taken from the request");
        assertEquals(sling.request().getResourceResolver().getUserID(),
                AgentSession.forCaller(sling.request()).getUserID(),
                "the caller's session carries a different user from the request's own");
    }

    @Test
    @DisplayName("a subservice nobody mapped is refused before the platform is asked")
    void anUnmappedSubserviceIsRefusedFirst() {
        final AgentSession.Outcome<String> unknown =
                agentSession().withAgentState("something-nobody-mapped", resolver -> "ran");
        assertEquals(AgentSession.Failure.UNMAPPED_SUBSERVICE,
                assertInstanceOf(AgentSession.Refused.class, unknown,
                        "an unmapped subservice was granted a session").failure());
    }

    @Test
    @DisplayName("the session never leaves the method, so it is closed on every path")
    void theSessionNeverLeavesTheMethod() {
        List.of(AgentSession.STATE_SUBSERVICE, AgentSession.MAINTENANCE_SUBSERVICE)
                .forEach(subservice -> {
                    final AgentSession.Outcome<String> outcome = agentSession()
                            .withAgentState(subservice, resolver -> String.valueOf(resolver.getUserID()));
                    if (outcome instanceof final AgentSession.Completed<String> completed) {
                        assertTrue(!completed.result().isBlank(),
                                subservice + " ran under a session belonging to nobody at all");
                        return;
                    }
                    assertEquals(AgentSession.Failure.REFUSED_BY_THE_PLATFORM,
                            assertInstanceOf(AgentSession.Refused.class, outcome,
                                    subservice + " neither ran nor refused").failure(),
                            subservice + " was refused for a reason other than the platform's");
                });
        assertTrue(Arrays.stream(AgentSession.class.getDeclaredMethods())
                        .filter(method -> method.getName().startsWith("with"))
                        .noneMatch(method ->
                                ResourceResolver.class.equals(method.getReturnType())),
                "a session is handed back, so somebody has to remember to close it");
    }

    @Test
    @DisplayName("a platform that refuses the login is reported as the platform refusing it")
    void aPlatformRefusalIsReportedAsOne() {
        final AgentSession refusing = new AgentSession(subservice -> {
            throw new LoginException("no mapping");
        });
        final AgentSession.Outcome<String> outcome =
                refusing.withAgentState(AgentSession.STATE_SUBSERVICE, resolver -> "ran");
        final AgentSession.Refused<?> refused = assertInstanceOf(AgentSession.Refused.class, outcome,
                "a platform that refused the login produced a session anyway");
        assertEquals(AgentSession.Failure.REFUSED_BY_THE_PLATFORM, refused.failure());
        assertTrue(refused.detail().contains("no mapping"), refused.detail());
    }

    @Test
    void scopedStateClosesOnlyItsOwnResolverOnEveryExit() throws LoginException,
            javax.jcr.RepositoryException, IOException {
        for (final String exit : List.of("completed", "repository", "transport")) {
            final var closed = new java.util.concurrent.atomic.AtomicInteger();
            final ResourceResolver resolver = new org.apache.sling.api.wrappers.ResourceResolverWrapper(
                    sling.resourceResolver().clone(java.util.Map.of())) {
                @Override
                public void close() {
                    closed.incrementAndGet();
                    super.close();
                }
            };
            final AgentSession source = new AgentSession(subservice -> {
                assertEquals(AgentSession.STATE_SUBSERVICE, subservice);
                return resolver;
            });
            final AgentSession.StateWork work = state -> {
                assertTrue(state.isLive());
                if ("repository".equals(exit)) {
                    throw new javax.jcr.RepositoryException("state failure");
                }
                if ("transport".equals(exit)) {
                    throw new IOException("transport failure");
                }
            };
            switch (exit) {
                case "completed" -> assertEquals(AgentSession.Completion.COMPLETED, source.withState(work));
                case "repository" -> org.junit.jupiter.api.Assertions.assertThrows(
                        javax.jcr.RepositoryException.class, () -> source.withState(work));
                case "transport" -> org.junit.jupiter.api.Assertions.assertThrows(
                        IOException.class, () -> source.withState(work));
                default -> throw new AssertionError(exit);
            }
            assertEquals(1, closed.get(), exit);
            assertTrue(sling.resourceResolver().isLive(), "the original caller's resolver was closed");
        }
    }

    @Test
    void inactiveStateAccessRefusesWithoutExecutingWork() throws javax.jcr.RepositoryException, IOException {
        final AgentSession component = new AgentSession();
        component.stopped();
        final AgentSession.StateWork refused = state -> {
            throw new AssertionError("inactive state access ran work");
        };
        assertEquals(AgentSession.Completion.UNAVAILABLE, AgentSession.current().withState(refused));
        component.available(sling.getService(ResourceResolverFactory.class));
        try {
            assertEquals(AgentSession.Completion.COMPLETED,
                    AgentSession.current().withState(state -> assertTrue(state.isLive())));
        } finally {
            component.stopped();
        }
        assertEquals(AgentSession.Completion.UNAVAILABLE, AgentSession.current().withState(refused));
    }

    private AgentSession agentSession() {
        return AgentSession.usingPlatform(sling.getService(ResourceResolverFactory.class));
    }

    @Test
    @DisplayName("nothing in this bundle acts as somebody else")
    void nothingImpersonates() {
        // Built from its parts so that this assertion is not itself the thing it is looking for.
        final String acting = "impersonate" + "(";
        final List<String> impersonating = sourcesUnder(REPOSITORY.resolve("core/src/main")).stream()
                .filter(source -> read(source).contains(acting))
                .map(source -> REPOSITORY.relativize(source).toString())
                .toList();
        assertEquals(List.of(), impersonating,
                "something acts as somebody who is not currently asking");
    }

    @Test
    @DisplayName("the agent's own tree is the one path its identity is granted")
    void theAgentsOwnTreeIsTheOnlyGrant() {
        assertEquals("/var/slingshot-agent", AgentSession.AGENT_TREE);
        final String policy = read(REPOSITORY.resolve("policy/repository-access.toml"));
        List.of("/content", "/apps", "/home").forEach(refused ->
                assertTrue(policy.contains("path = \"" + refused + "\""),
                        refused + " is not recorded as a path the agent's identity is refused"));
        assertTrue(!policy.contains("privileges = [\"jcr:all\"]"),
                "the agent's identity is granted everything somewhere");
    }

    private static List<Path> sourcesUnder(Path tree) {
        try (var walk = Files.walk(tree)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/fixtures/"))
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
