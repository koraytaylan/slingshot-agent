// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.ContinuationState;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.KeyRingRefusal;
import rs.slingshot.agent.continuation.QueryDigest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

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
    void fabricatedLeaseCannotReplaceThePersistedHoldersRing() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        assertInstanceOf(RotationLease.Taken.class, RotationLease.take(session,
                DefaultContinuationKeyAuthority.record(), "the-real-holder", NOW, CONTRACT));
        final ContinuationKeyAuthority.Lease fabricated =
                new ContinuationKeyAuthority.Lease("never-took-the-lease", NOW + 30000, "fabricated");
        final KeyRing rotated = assertInstanceOf(KeyRing.Held.class,
                original.rotated(authority.material(), NOW, CONTRACT)).ring();
        assertEquals(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER,
                assertInstanceOf(ContinuationKeyAuthority.NotWritten.class,
                        authority.compareAndSet(original, rotated, fabricated, NOW)).refusal().failure());
        assertEquals(original, ring(authority.read()));
    }

    @Test
    void currentHolderCannotDiscardTheSigningKeyBeforeItsRetention() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken taken = assertInstanceOf(RotationLease.Taken.class,
                RotationLease.take(session, DefaultContinuationKeyAuthority.record(),
                        "the-real-holder", NOW, CONTRACT));
        final ContinuationKeyAuthority.Lease lease = taken.lease();
        assertInstanceOf(ContinuationKeyAuthority.NotWritten.class,
                authority.compareAndSet(original, KeyRing.initial(authority.material()), lease, NOW));
        assertEquals(original, ring(authority.read()));
    }

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
                assertInstanceOf(RotationLease.Taken.class, RotationLease.take(session,
                        DefaultContinuationKeyAuthority.record(), "a node", NOW, CONTRACT)).lease();
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
                new ContinuationKeyAuthority.Lease("a node", NOW, "expired");
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
        assertTrue(RotationLease.holds(session, ring, taken.lease(), NOW));
        assertFalse(RotationLease.holds(session, ring,
                new ContinuationKeyAuthority.Lease("the second node",
                        taken.heldUntilUnixMilliseconds(), taken.epoch()), NOW));
        assertFalse(RotationLease.holds(session, ring, taken.lease(),
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
        assertTrue(source.contains("CompareAndSet.stamp("),
                "a write here does not go through compare-and-set");
        assertFalse(source.contains("node.setProperty(CURRENT)"),
                "a key is written without comparing against what was read");
    }

    @Test
    void renewedAndReacquiredLeasesRequireTheExactPersistedEpoch() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken first = take(session, NOW);
        final RotationLease.Taken renewed = assertInstanceOf(RotationLease.Taken.class,
                RotationLease.renew(session, DefaultContinuationKeyAuthority.record(),
                        first.lease(), NOW + 1, CONTRACT));
        assertEquals(first.epoch(), renewed.epoch());
        assertFalse(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                first.lease(), NOW));
        assertInstanceOf(RotationLease.Refused.class, RotationLease.renew(session,
                DefaultContinuationKeyAuthority.record(), first.lease(), NOW + 1, CONTRACT));
        assertInstanceOf(ContinuationKeyAuthority.NotWritten.class,
                authority.compareAndSet(original, rotated(original, "second", NOW + 1),
                        first.lease(), NOW + 1));
        assertInstanceOf(ContinuationKeyAuthority.Written.class,
                authority.compareAndSet(original, rotated(original, "second", NOW + 1),
                        renewed.lease(), NOW + 1));
        final long later = renewed.heldUntilUnixMilliseconds();
        final RotationLease.Taken reacquired = take(session, later);
        assertNotEquals(first.epoch(), reacquired.epoch());
        final ContinuationKeyAuthority.Lease stale = new ContinuationKeyAuthority.Lease(reacquired.holder(),
                reacquired.heldUntilUnixMilliseconds(), renewed.epoch());
        final KeyRing current = ring(authority.read());
        assertEquals(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER, assertInstanceOf(
                ContinuationKeyAuthority.NotWritten.class,
                authority.compareAndSet(current, current, stale, later)).refusal().failure());
        assertFalse(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(), stale, later));
        assertTrue(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                reacquired.lease(), later));
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void bothTokenGenerationsRemainVerifiableThroughoutRequiredRetention() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken lease = take(session, NOW);
        final long until = NOW + CONTRACT.value(ContractLimit.CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS);
        final QueryDigest query = assertInstanceOf(QueryDigest.Held.class,
                QueryDigest.of("query_paths", new DocumentValue.Mapping(new java.util.LinkedHashMap<>())))
                .digest();
        final EventStoreGeneration generation = assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(1)).generation();
        final ContinuationState state = new ContinuationState(generation, query.value(), query.value(),
                0, until + 1000);
        final ContinuationToken before = ContinuationToken.issue(state, original.current());
        final KeyRing next = rotated(original, authority.material(), NOW);
        assertInstanceOf(ContinuationKeyAuthority.Written.class,
                authority.compareAndSet(original, next, lease.lease(), NOW));
        final ContinuationToken after = ContinuationToken.issue(state, next.current());
        final RotationLease.Taken later = take(session, until - 1);
        final KeyRing illegal = new KeyRing("third", new KeyRing.Retained(next.current(), until * 2));
        assertEquals(KeyRingRefusal.Failure.PRIOR_STILL_RETAINED, assertInstanceOf(
                ContinuationKeyAuthority.NotWritten.class,
                authority.compareAndSet(next, illegal, later.lease(), until - 1)).refusal().failure());
        final KeyRing durable = ring(authority.read());
        List.of(before, after).forEach(token -> assertInstanceOf(ContinuationToken.Honoured.class,
                token.validate(durable, state.targetDigest(), query, generation, until - 1, CONTRACT)));
        assertInstanceOf(ContinuationKeyAuthority.Written.class,
                authority.compareAndSet(next, rotated(next, "third", until), later.lease(), until));
        final KeyRing finalRing = ring(authority.read());
        assertEquals(ContinuationToken.Refusal.INTEGRITY_INVALID, assertInstanceOf(
                ContinuationToken.Refused.class,
                before.validate(finalRing, state.targetDigest(), query, generation, until, CONTRACT))
                .refusal());
        assertInstanceOf(ContinuationToken.Honoured.class,
                after.validate(finalRing, state.targetDigest(), query, generation, until, CONTRACT));
    }

    @Test
    void takeoverDuringTheKeySaveRejectsTheOldEpoch()
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken first = take(session, NOW);
        final var factory = java.util.Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class));
        try (var resolver = factory.getResourceResolver(java.util.Map.of())) {
            final Session competing = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            final DefaultContinuationKeyAuthority overlapping = opened(SaveInterleaving.before(session,
                    () -> take(competing, first.heldUntilUnixMilliseconds())));
            assertInstanceOf(ContinuationKeyAuthority.NotWritten.class, overlapping.compareAndSet(
                    original, rotated(original, "second", NOW), first.lease(), NOW));
            assertEquals(original, ring(authority.read()));
            assertFalse(session.hasPendingChanges());
            assertFalse(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                    first.lease(), NOW));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acquisitionHasOneAtomicPersistenceBoundary(boolean committed) throws RepositoryException {
        final Session session = prepared();
        opened(session).establish();
        assertThrows(RepositoryException.class, () -> RotationLease.take(
                SaveInterleaving.interruptSave(session, 1, committed),
                DefaultContinuationKeyAuthority.record(),
                "the-holder", NOW, CONTRACT));
        assertFalse(session.hasPendingChanges());
        final var node = session.getNode(DefaultContinuationKeyAuthority.record().path());
        assertEquals(committed, node.hasProperty(RotationLease.HOLDER));
        assertEquals(committed, node.hasProperty(RotationLease.HELD_UNTIL));
        assertEquals(committed, node.hasProperty(RotationLease.EPOCH));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renewalHasOneAtomicPersistenceBoundary(boolean committed) throws RepositoryException {
        final Session session = prepared();
        opened(session).establish();
        final RotationLease.Taken first = take(session, NOW);
        assertThrows(RepositoryException.class, () -> RotationLease.renew(
                SaveInterleaving.interruptSave(session, 1, committed),
                DefaultContinuationKeyAuthority.record(),
                first.lease(), NOW + 1, CONTRACT));
        assertFalse(session.hasPendingChanges());
        final var node = session.getNode(DefaultContinuationKeyAuthority.record().path());
        assertEquals(first.holder(), node.getProperty(RotationLease.HOLDER).getString());
        assertEquals(first.epoch(), node.getProperty(RotationLease.EPOCH).getString());
        assertEquals(first.heldUntilUnixMilliseconds() + (committed ? 1 : 0),
                node.getProperty(RotationLease.HELD_UNTIL).getLong());
    }

    @Test
    void invalidRetentionAndEmptyAuthorityCannotBePersisted() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        assertThrows(IllegalArgumentException.class, () -> RotationLease.take(session,
                DefaultContinuationKeyAuthority.record(), "", NOW, CONTRACT));
        assertThrows(ArithmeticException.class, () -> take(session, Long.MAX_VALUE));
        final RotationLease.Taken first = take(session, NOW);
        final long until = NOW + CONTRACT.value(ContractLimit.CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS);
        final List<KeyRing> invalid = List.of(KeyRing.initial("second"),
                new KeyRing("second", new KeyRing.Retained(original.current(), until - 1)),
                new KeyRing("second", new KeyRing.Retained("wrong", until)),
                rotated(original, original.current(), NOW), rotated(original, "", NOW));
        invalid.forEach(next -> assertEquals(KeyRingRefusal.Failure.INVALID_TRANSITION,
                assertInstanceOf(ContinuationKeyAuthority.NotWritten.class,
                        authority.compareAndSet(original, next, first.lease(), NOW)).refusal().failure()));
        assertEquals(KeyRingRefusal.Failure.INVALID_TRANSITION, assertInstanceOf(KeyRing.Refused.class,
                original.rotated("second", Long.MAX_VALUE, CONTRACT)).refusal().failure());
        assertEquals(original, ring(authority.read()));
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void missingLegacyAndFabricatedLeaseFieldsNeverAuthorizeWrites() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        assertFalse(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                new ContinuationKeyAuthority.Lease("nobody", NOW + 1, "unknown"), NOW));
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken taken = take(session, NOW);
        final List<ContinuationKeyAuthority.Lease> invalid = List.of(
                new ContinuationKeyAuthority.Lease("", taken.heldUntilUnixMilliseconds(), taken.epoch()),
                new ContinuationKeyAuthority.Lease(taken.holder(), taken.heldUntilUnixMilliseconds(), ""),
                new ContinuationKeyAuthority.Lease(taken.holder(), taken.heldUntilUnixMilliseconds() + 1,
                        taken.epoch()));
        invalid.forEach(lease -> assertEquals(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER,
                assertInstanceOf(ContinuationKeyAuthority.NotWritten.class, authority.compareAndSet(
                        original, rotated(original, "second", NOW), lease, NOW)).refusal().failure()));
        session.getNode(DefaultContinuationKeyAuthority.record().path()).getProperty(RotationLease.EPOCH)
                .remove();
        session.save();
        assertFalse(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                taken.lease(), NOW));
        assertInstanceOf(RotationLease.Refused.class, RotationLease.take(session,
                DefaultContinuationKeyAuthority.record(), "another", NOW, CONTRACT));
        assertInstanceOf(RotationLease.Refused.class, RotationLease.renew(session,
                DefaultContinuationKeyAuthority.record(), taken.lease(), taken.heldUntilUnixMilliseconds(),
                CONTRACT));
        final RotationLease.Taken recovered = take(session, taken.heldUntilUnixMilliseconds());
        assertInstanceOf(ContinuationKeyAuthority.Written.class, authority.compareAndSet(original,
                rotated(original, "second", taken.heldUntilUnixMilliseconds()), recovered.lease(),
                taken.heldUntilUnixMilliseconds()));
    }

    @Test
    void conflictingAcquisitionAndRenewalDiscardTheirPendingWrites() throws RepositoryException {
        final Session session = prepared();
        opened(session).establish();
        final Session conflicting = SaveInterleaving.beforeEverySave(session, () -> {
            throw new javax.jcr.InvalidItemStateException("another transition committed");
        });
        assertEquals(RotationLease.Refusal.CONTENDED, assertInstanceOf(RotationLease.Refused.class,
                RotationLease.take(conflicting, DefaultContinuationKeyAuthority.record(),
                        "the-holder", NOW, CONTRACT)).refusal());
        assertFalse(session.hasPendingChanges());
        final RotationLease.Taken taken = take(session, NOW);
        assertEquals(RotationLease.Refusal.CONTENDED, assertInstanceOf(RotationLease.Refused.class,
                RotationLease.renew(conflicting, DefaultContinuationKeyAuthority.record(),
                        taken.lease(), NOW + 1, CONTRACT)).refusal());
        assertFalse(session.hasPendingChanges());
        assertTrue(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                taken.lease(), NOW));
    }

    @Test
    void competingAcquisitionsCannotMergeEvenWithTheSameHolderAndExpiry()
            throws RepositoryException, org.apache.sling.api.resource.LoginException {
        final Session session = prepared();
        opened(session).establish();
        final var factory = java.util.Objects.requireNonNull(sling.getService(
                org.apache.sling.api.resource.ResourceResolverFactory.class));
        try (var resolver = factory.getResourceResolver(java.util.Map.of())) {
            final Session competing = java.util.Objects.requireNonNull(resolver.adaptTo(Session.class));
            final var winner = new java.util.concurrent.atomic.AtomicReference<RotationLease.Taken>();
            final RotationLease.Outcome lost = RotationLease.take(SaveInterleaving.before(session,
                    () -> winner.set(take(competing, NOW))), DefaultContinuationKeyAuthority.record(),
                    "the-holder", NOW, CONTRACT);
            assertEquals(RotationLease.Refusal.CONTENDED,
                    assertInstanceOf(RotationLease.Refused.class, lost).refusal());
            assertTrue(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                    winner.get().lease(), NOW));
            assertFalse(session.hasPendingChanges());
        }
    }

    @Test
    void oversizedRingIsRefusedWithoutChangingAuthority() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken taken = take(session, NOW);
        final int bound = (int) CONTRACT.value(ContractLimit.MAXIMUM_AGENT_CONTINUATION_KEY_STATE_BYTES);
        assertEquals(KeyRingRefusal.Failure.KEY_TOO_LONG, assertInstanceOf(
                ContinuationKeyAuthority.NotWritten.class, authority.compareAndSet(original,
                        KeyRing.initial("k".repeat(bound + 1)), taken.lease(), NOW)).refusal().failure());
        assertEquals(original, ring(authority.read()));
        assertTrue(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(),
                taken.lease(), NOW));
    }

    @Test
    void aMissingPersistedHolderCannotMatchTheDiagnosticPlaceholder() throws RepositoryException {
        final Session session = prepared();
        final DefaultContinuationKeyAuthority authority = opened(session);
        final KeyRing original = ring(authority.establish());
        final RotationLease.Taken taken = take(session, NOW);
        session.getNode(DefaultContinuationKeyAuthority.record().path()).getProperty(RotationLease.HOLDER)
                .remove();
        session.save();
        final ContinuationKeyAuthority.Lease fabricated = new ContinuationKeyAuthority.Lease("nobody",
                taken.heldUntilUnixMilliseconds(), taken.epoch());
        assertEquals(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER,
                assertInstanceOf(ContinuationKeyAuthority.NotWritten.class, authority.compareAndSet(
                        original, rotated(original, "second", NOW), fabricated, NOW)).refusal().failure());
        assertEquals(original, ring(authority.read()));
    }

    @Test
    void missingExpiryIsNotAZeroExpiryLease() throws RepositoryException {
        final Session session = prepared();
        opened(session).establish();
        final RotationLease.Taken taken = take(session, NOW);
        session.getNode(DefaultContinuationKeyAuthority.record().path()).getProperty(RotationLease.HELD_UNTIL)
                .remove();
        session.save();
        final ContinuationKeyAuthority.Lease fabricated = new ContinuationKeyAuthority.Lease(
                taken.holder(), 0, taken.epoch());
        assertFalse(RotationLease.holds(session, DefaultContinuationKeyAuthority.record(), fabricated, -1));
        assertInstanceOf(RotationLease.Refused.class, RotationLease.renew(session,
                DefaultContinuationKeyAuthority.record(), fabricated, -1, CONTRACT));
        assertFalse(session.hasPendingChanges());
    }

    private static RotationLease.Taken take(Session session, long now) throws RepositoryException {
        return assertInstanceOf(RotationLease.Taken.class, RotationLease.take(session,
                DefaultContinuationKeyAuthority.record(), "the-holder", now, CONTRACT));
    }

    private static KeyRing rotated(KeyRing ring, String key, long now) {
        return assertInstanceOf(KeyRing.Held.class, ring.rotated(key, now, CONTRACT)).ring();
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
