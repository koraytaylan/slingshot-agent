// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The key ring where every node of a deployment can see it, and the lease that stops two of them
 * rotating at once.
 *
 * <p>That nothing here is cheaper on one node is asserted over the source rather than argued about:
 * a branch on node count is the one change that would make this pass on the tier and fail on a
 * customer's author, and it is a change somebody would make for good reasons.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class DefaultContinuationKeyAuthorityTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a deployment holding no ring says so, and one is not created by asking")
    void anAbsentRingIsNotCreatedByAsking() throws RepositoryException {
        final DefaultContinuationKeyAuthority authority = opened(prepared());
        final ContinuationKeyAuthority.Unavailable unavailable = assertInstanceOf(
                ContinuationKeyAuthority.Unavailable.class, authority.read(),
                "a ring appeared where a deployment holds none");
        assertEquals(KeyRingRefusal.Failure.ABSENT, unavailable.refusal().failure());
        assertTrue(unavailable.refusal().detail().contains(DefaultContinuationKeyAuthority.record().path()),
                unavailable.refusal().detail());
    }

    @Test
    @DisplayName("the first ring is established once, however many nodes establish it")
    void thefirstRingIsEstablishedOnce() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing established = ring(authority.establish());
        assertEquals(DefaultContinuationKeyAuthority.KEY_BYTES * 2, established.current().length(),
                "a key is not the number of bytes this build asks its secure source for");
        assertEquals(established, ring(authority.establish()),
                "a second establishment replaced the ring the first one wrote");
        assertInstanceOf(KeyRing.NothingRetained.class, established.prior(),
                "a ring that has never rotated retains a key anyway");
    }

    @Test
    @DisplayName("key material comes from the platform's secure source and never repeats")
    void keyMaterialIsSecureAndUnrepeated() throws RepositoryException {
        final DefaultContinuationKeyAuthority authority = opened(prepared());
        assertNotEquals(authority.material(), authority.material(),
                "two keys from the secure source were the same, which no source that is one does");
        final String source = read(REPOSITORY.resolve("core/src/main/java/rs/slingshot/agent/"
                + "store/DefaultContinuationKeyAuthority.java"));
        assertTrue(source.contains("SecureRandom::getInstanceStrong"),
                "the key material does not come from the platform's strong source");
        List.of("new Random", "System.currentTimeMillis", "nanoTime", "setSeed")
                .forEach(weak -> assertFalse(source.contains(weak),
                        "key material can come from " + weak + ", which is a token anybody can"
                                + " forge"));
    }

    @Test
    @DisplayName("a runtime with no secure source refuses to start rather than falling back")
    void anUnavailableSourceRefusesToStart() throws RepositoryException {
        final DefaultContinuationKeyAuthority.NotOpened refused = assertInstanceOf(
                DefaultContinuationKeyAuthority.NotOpened.class,
                DefaultContinuationKeyAuthority.open(prepared(), CONTRACT, () -> {
                    throw new java.security.NoSuchAlgorithmException("no strong source here");
                }), "an authority started without a secure source");
        assertTrue(refused.detail().contains("no strong source here"), refused.detail());
    }

    @Test
    @DisplayName("a session that has been closed answers that the ring is not readable")
    void aClosedSessionIsNotAReadableRing() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        authority.establish();
        session.logout();
        assertEquals(KeyRingRefusal.Failure.ABSENT,
                assertInstanceOf(ContinuationKeyAuthority.Unavailable.class, authority.read(),
                        "a closed session answered a ring").refusal().failure());
        assertInstanceOf(ContinuationKeyAuthority.Unavailable.class, authority.establish(),
                "a closed session established a ring");
    }

    @Test
    @DisplayName("a write against a ring that has changed does not happen")
    void aStaleWriteDoesNotHappen() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing held = ring(authority.establish());
        final ContinuationKeyAuthority.Lease lease =
                new ContinuationKeyAuthority.Lease("a node", NOW + 30000);
        final KeyRing rotated = assertInstanceOf(KeyRing.Held.class,
                held.rotated(authority.material(), NOW, CONTRACT)).ring();
        assertInstanceOf(ContinuationKeyAuthority.Written.class,
                authority.compareAndSet(held, rotated, lease, NOW),
                "a write against what was read did not happen");
        assertEquals(KeyRingRefusal.Failure.CHANGED_SINCE_IT_WAS_READ,
                assertInstanceOf(ContinuationKeyAuthority.NotWritten.class,
                        authority.compareAndSet(held, rotated, lease, NOW),
                        "a write against a ring that had changed happened anyway").refusal()
                        .failure());
        assertEquals(rotated, ring(authority.read()), "a refused write changed what is held");
    }

    @Test
    @DisplayName("a write by a node whose lease has expired does not happen")
    void aWriteWithoutTheLeaseDoesNotHappen() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing held = ring(authority.establish());
        final ContinuationKeyAuthority.Lease expired =
                new ContinuationKeyAuthority.Lease("a node", NOW);
        assertEquals(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER,
                assertInstanceOf(ContinuationKeyAuthority.NotWritten.class,
                        authority.compareAndSet(held, held, expired, NOW)).refusal().failure());
    }

    @Test
    @DisplayName("two nodes deciding to rotate at once produce one lease and one refusal")
    void oneRotationLeaseIsHeldAtATime() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        authority.establish();
        final StatePath ring = DefaultContinuationKeyAuthority.record();
        final RotationLease.Taken taken = assertInstanceOf(RotationLease.Taken.class,
                RotationLease.take(session, ring, "the first node", NOW, CONTRACT),
                "the first node could not take the lease");
        assertEquals(NOW + CONTRACT.value(
                        ContractLimit.CONTINUATION_KEY_ROTATION_LEASE_MILLISECONDS),
                taken.heldUntilUnixMilliseconds());
        final RotationLease.Refused refused = assertInstanceOf(RotationLease.Refused.class,
                RotationLease.take(session, ring, "the second node", NOW, CONTRACT),
                "two nodes held the rotation lease at once");
        assertEquals(RotationLease.Refusal.HELD_BY_ANOTHER, refused.refusal());
        assertTrue(refused.detail().contains("the first node"), refused.detail());
        assertTrue(RotationLease.holds(session, ring, "the first node", NOW));
        assertFalse(RotationLease.holds(session, ring, "the second node", NOW));
        assertFalse(RotationLease.holds(session, ring, "the first node",
                        taken.heldUntilUnixMilliseconds()),
                "a lease was still held after it expired");
    }

    @Test
    @DisplayName("a lease taken after the last one expired is the taker's")
    void anExpiredLeaseIsTakenByTheNextNode() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        authority.establish();
        final StatePath ring = DefaultContinuationKeyAuthority.record();
        final RotationLease.Taken first = assertInstanceOf(RotationLease.Taken.class,
                RotationLease.take(session, ring, "the first node", NOW, CONTRACT));
        assertInstanceOf(RotationLease.Taken.class, RotationLease.take(session, ring,
                        "the second node", first.heldUntilUnixMilliseconds(), CONTRACT),
                "a lease nobody holds any more could not be taken");
    }

    @Test
    @DisplayName("nothing here branches on node count, clustering, or which deployment it is")
    void nothingBranchesOnTheDeployment() {
        final String source = read(REPOSITORY.resolve("core/src/main/java/rs/slingshot/agent/"
                + "store/DefaultContinuationKeyAuthority.java"))
                + read(REPOSITORY.resolve("core/src/main/java/rs/slingshot/agent/store/"
                + "RotationLease.java"));
        List.of("nodeCount", "isCluster", "isSingleInstance", "standalone", "topology",
                        "clusterId", "instanceCount")
                .forEach(branch -> assertFalse(source.contains(branch),
                        "a path here branches on " + branch));
        assertTrue(source.contains("CompareAndSet.set("),
                "a write here does not go through compare-and-set");
        assertFalse(source.contains("node.setProperty(CURRENT)"),
                "a key is written without comparing against what was read");
    }

    private static KeyRing ring(ContinuationKeyAuthority.ReadOutcome outcome) {
        return assertInstanceOf(ContinuationKeyAuthority.Read.class, outcome,
                "the ring was not readable").ring();
    }

    private static DefaultContinuationKeyAuthority opened(Session session) {
        return assertInstanceOf(DefaultContinuationKeyAuthority.Opened.class,
                DefaultContinuationKeyAuthority.open(session, CONTRACT),
                "this runtime has no secure source").authority();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        if (!session.nodeExists("/var")) {
            session.getRootNode().addNode("var", "nt:unstructured");
        }
        if (!session.nodeExists(StatePath.ROOT)) {
            session.getNode("/var").addNode("slingshot-agent", "nt:unstructured");
        }
        session.save();
        return session;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
