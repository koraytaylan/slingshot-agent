// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The two primitives everything durable here is built from, and the counting that is built from
 * them rather than from a counter.
 *
 * <p>Contention is proved with a session that fails a commit as often as the suite says, because a
 * suite that waited for two threads to collide would be a suite that passes when they happen not to
 * — and the retry bound is exactly the sort of number that is right until somebody changes it.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ConditionalWriteTest {

    private static final Path REPOSITORY = repositoryRoot();

    // A real Oak repository rather than a mock: what these primitives are about is what a
    // repository does when two writers meet - a session that refreshes, a commit that conflicts, a
    // claim that loses - and a mock answering those from a map would prove the suite rather than
    // the code.
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    /** The property a compare-and-set suite writes. */
    private static final String COUNT = "count";

    @Test
    @DisplayName("a free path is claimed and a held one is already held, and neither raises")
    void aClaimHasTwoOutcomes() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("held-by-somebody");
        assertEquals(WriteOutcome.CLAIMED,
                ClaimByCreation.claim(session, path, "nt:unstructured", node -> { }));
        assertEquals(WriteOutcome.ALREADY_HELD,
                ClaimByCreation.claim(session, path, "nt:unstructured", node -> { }),
                "a path somebody already holds was claimed a second time");
        assertTrue(session.nodeExists(path.path()));
    }

    @Test
    @DisplayName("two writers that both found the path free leave one record between them")
    void twoWritersLeaveOneRecord() throws RepositoryException {
        final Session first = session();
        final StatePath path = path("raced-for");
        final Session second = anotherSession();
        // The second writer prepares its claim while the path is free and commits after the first.
        // The repository accepts both, because what a claim writes is derived from the identifier
        // it is claiming and the two writers wrote the same thing - which is why a claim says "this
        // node created this record" rather than "no other node did", and why which worker may
        // execute is decided by a lease instead.
        second.getNode(StatePath.ROOT).addNode("raced-for", "nt:unstructured");
        assertEquals(WriteOutcome.CLAIMED,
                ClaimByCreation.claim(first, path, "nt:unstructured", node -> set(node, COUNT, 1)));
        second.save();
        first.refresh(false);
        assertTrue(first.nodeExists(path.path()), "the race left no record at all");
        assertEquals(1, first.getNode(path.path()).getProperty(COUNT).getLong(),
                "the record the two writers left is not the one either of them wrote");
    }

    @Test
    @DisplayName("a claim the repository refuses as already there is already held, not an error")
    void aClaimTheRepositoryRefusesIsAlreadyHeld() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("refused-as-existing");
        assertEquals(WriteOutcome.ALREADY_HELD,
                ClaimByCreation.claim(refusing(session), path, "nt:unstructured", node -> { }),
                "a repository saying the node is there was reported as a failure");
    }

    @Test
    @DisplayName("what a claim writes is committed with the claim or not at all")
    void whatAClaimWritesArrivesWithIt() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("carries-what-it-was-given");
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> set(node, COUNT, 7));
        assertEquals(7, session.getNode(path.path()).getProperty(COUNT).getLong());
    }

    @Test
    @DisplayName("a write against the expected value happens and one against a changed value"
            + " does not")
    void aCompareAndSetWritesOnlyAgainstWhatWasRead() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("counted");
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> set(node, COUNT, 1));
        assertEquals(WriteOutcome.WRITTEN, CompareAndSet.set(session, path, COUNT, 1, 2));
        assertEquals(2, session.getNode(path.path()).getProperty(COUNT).getLong());
        assertEquals(WriteOutcome.VALUE_CHANGED, CompareAndSet.set(session, path, COUNT, 1, 9),
                "a write against a value that had changed happened anyway");
        assertEquals(2, session.getNode(path.path()).getProperty(COUNT).getLong(),
                "a refused write changed the value");
    }

    @Test
    @DisplayName("contention is retried to exactly the declared bound and then reported as itself")
    void contentionIsRetriedToTheBound() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("contended");
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> set(node, COUNT, 1));
        final AtomicInteger failures = new AtomicInteger(CompareAndSet.ATTEMPTS - 1);
        assertEquals(WriteOutcome.WRITTEN,
                CompareAndSet.set(losing(session, failures), path, COUNT, 1, 2),
                "a writer that lost one race fewer than the bound gave up anyway");
        final AtomicInteger always = new AtomicInteger(CompareAndSet.ATTEMPTS);
        assertEquals(WriteOutcome.CONTENDED,
                CompareAndSet.set(losing(session, always), path, COUNT, 2, 3),
                "a writer that lost every race reported something other than contention");
        assertEquals(2, session.getNode(path.path()).getProperty(COUNT).getLong(),
                "a writer that gave up left a value behind");
    }

    @Test
    @DisplayName("a count is the sum of its shards, and every advance lands on one of them")
    void aCountIsTheSumOfItsShards() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("capacity");
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
        assertEquals(0, ShardedCount.total(session, path, ShardedCount.SHARDS));
        final List<String> writers = List.of("first", "second", "third", "fourth", "fifth");
        for (final String writer : writers) {
            assertEquals(WriteOutcome.WRITTEN,
                    ShardedCount.advance(session, path, writer, 1, ShardedCount.SHARDS));
        }
        assertEquals(writers.size(), ShardedCount.total(session, path, ShardedCount.SHARDS),
                "the total is not the number of advances");
        assertEquals(ShardedCount.SHARDS - 1, ShardedCount.inFlightMargin(ShardedCount.SHARDS),
                "the margin an admission allows for is not one advance per other shard");
        assertEquals(0, ShardedCount.inFlightMargin(1),
                "a count on one shard is not exact, though nothing else can be in flight on it");
        assertEquals(1, ShardedCount.shardsFor(ShardedCount.SHARDS),
                "a count too small to shard was spread anyway");
        assertEquals(ShardedCount.SHARDS, ShardedCount.shardsFor(Long.MAX_VALUE),
                "a count large enough to shard was not spread as far as it may be");
        assertTrue(ShardedCount.shardOf("first", ShardedCount.SHARDS)
                .startsWith(ShardedCount.SHARD_PREFIX));
    }

    @Test
    @DisplayName("one writer advancing repeatedly totals exactly what it advanced")
    void repeatedAdvancesFromOneWriterAreExact() throws RepositoryException {
        final Session session = session();
        final StatePath path = path("repeatedly-counted");
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
        int advance = 0;
        while (advance < 20) {
            ShardedCount.advance(session, path, "one writer", 1, ShardedCount.SHARDS);
            advance = advance + 1;
        }
        assertEquals(20, ShardedCount.total(session, path, ShardedCount.SHARDS));
    }

    @Test
    @DisplayName("the counter mixin appears in no built class of this bundle")
    void theCounterMixinIsInNoBuiltClass() {
        final String refused = new String(new byte[] {'m', 'i', 'x', ':', 'a', 't', 'o', 'm', 'i',
                'c', 'C', 'o', 'u', 'n', 't', 'e', 'r'}, java.nio.charset.StandardCharsets.UTF_8);
        final Path classes = REPOSITORY.resolve("core/target/classes");
        assertTrue(Files.isDirectory(classes), "the bundle has not been built");
        try (var built = Files.walk(classes)) {
            built.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(".class"))
                    .forEach(file -> assertFalse(read(file).contains(refused),
                            file.getFileName() + " carries the counter this repository refuses"));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** A session whose commit is refused because the node is there, whatever this one saw. */
    private static Session refusing(Session session) {
        final InvocationHandler handler = (proxy, method, arguments) -> {
            if ("save".equals(method.getName())) {
                throw new javax.jcr.ItemExistsException("somebody else created it first");
            }
            return invoke(session, method, arguments);
        };
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, handler);
    }

    /** A session that loses a commit as often as it is told to, and then behaves. */
    private static Session losing(Session session, AtomicInteger failures) {
        final InvocationHandler handler = (proxy, method, arguments) -> {
            if ("save".equals(method.getName()) && failures.getAndDecrement() > 0) {
                throw new InvalidItemStateException("somebody else committed first");
            }
            return invoke(session, method, arguments);
        };
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, handler);
    }

    /**
     * Calls the real session, letting the repository's own failure out rather than the reflective
     * wrapper the proxy would otherwise report.
     */
    private static Object invoke(Session session, Method method, Object... arguments)
            throws RepositoryException, IllegalAccessException {
        try {
            return method.invoke(session, arguments);
        } catch (final InvocationTargetException failed) {
            if (failed.getCause() instanceof final RepositoryException repository) {
                // The repository's own failure is what the code under test catches, and the
                // reflective wrapper is kept beside it rather than discarded.
                repository.addSuppressed(failed);
                throw repository;
            }
            throw new IllegalStateException("the session failed", failed);
        }
    }

    private static void set(Node node, String property, long value) {
        try {
            node.setProperty(property, value);
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the node could not be written", unwritable);
        }
    }

    private StatePath path(String name) throws RepositoryException {
        final Session session = session();
        if (!session.nodeExists("/var")) {
            session.getRootNode().addNode("var", "nt:unstructured");
            session.save();
        }
        if (!session.nodeExists(StatePath.ROOT)) {
            session.getNode("/var").addNode("slingshot-agent", "nt:unstructured");
            session.save();
        }
        return StatePath.deployment(name);
    }

    /**
     * A session of its own, from a second resolver: the same one twice would be one writer, and a
     * race needs two.
     */
    private Session anotherSession() {
        try {
            final org.apache.sling.api.resource.ResourceResolverFactory factory =
                    java.util.Objects.requireNonNull(sling.getService(
                            org.apache.sling.api.resource.ResourceResolverFactory.class),
                            "the context holds no resolver factory");
            return java.util.Objects.requireNonNull(
                    factory.getResourceResolver(java.util.Map.of()).adaptTo(Session.class),
                    "the second resolver has no session");
        } catch (final org.apache.sling.api.resource.LoginException refused) {
            throw new IllegalStateException("a second session could not be opened", refused);
        }
    }

    private Session session() {
        return java.util.Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
