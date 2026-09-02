// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The authority contract every deployment implements, proved against an authority that holds its
 * ring in memory.
 *
 * <p>An in-memory authority is enough to prove the contract because the contract is about what a
 * caller may observe — a read that creates nothing, a write that happens only against what was
 * read, and a rotation that cannot strand a token. Whether a particular deployment's store is
 * linearizable is that store's own proof, and Plan 0003 owns it; what is proved here is that
 * nothing in this contract lets a deployment be excused from it.</p>
 */
final class ContinuationKeyAuthorityTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private static final ContinuationKeyAuthority.Lease LEASE =
            new ContinuationKeyAuthority.Lease("a worker", NOW + 30000);

    @Test
    @DisplayName("a deployment holding no ring is a distinct answer from one holding an empty one")
    void anAbsentRingIsNotAnEmptyOne() {
        final ContinuationKeyAuthority absent = new MemoryAuthority();
        final ContinuationKeyAuthority.Unavailable unavailable =
                assertInstanceOf(ContinuationKeyAuthority.Unavailable.class, absent.read(),
                        "a ring appeared where a deployment holds none");
        assertEquals(KeyRingRefusal.Failure.ABSENT, unavailable.refusal().failure());
        assertInstanceOf(ContinuationKeyAuthority.Unavailable.class, absent.read(),
                "reading for a ring created one");
        final ContinuationKeyAuthority holding = new MemoryAuthority(KeyRing.initial(""));
        assertEquals("", assertInstanceOf(ContinuationKeyAuthority.Read.class, holding.read())
                .ring().current(), "an empty key is not the same answer as no ring");
    }

    @Test
    @DisplayName("the key bound and the record bound each hold at the limit and one past it")
    void bothBoundsHoldAtBothSides() {
        final long key = CONTRACT.value(ContractLimit.MAXIMUM_AGENT_CONTINUATION_KEY_STATE_BYTES);
        final long record =
                CONTRACT.value(ContractLimit.MAXIMUM_CONTINUATION_KEY_AUTHORITY_RECORD_BYTES);
        assertTrue(KeyRing.initial("k".repeat((int) key)).unbounded(CONTRACT).isEmpty(),
                "a key of exactly the bound was refused");
        assertEquals(KeyRingRefusal.Failure.KEY_TOO_LONG,
                KeyRing.initial("k".repeat((int) key + 1)).unbounded(CONTRACT).orElseThrow()
                        .failure());
        final int half = (int) (record / 2);
        final KeyRing atTheRecordBound = new KeyRing("c".repeat(half),
                new KeyRing.Retained("p".repeat((int) record - half), NOW));
        assertTrue(atTheRecordBound.unbounded(CONTRACT).isEmpty(),
                "a record of exactly the bound was refused");
        final KeyRing onePast = new KeyRing("c".repeat(half),
                new KeyRing.Retained("p".repeat((int) record - half + 1), NOW));
        assertEquals(KeyRingRefusal.Failure.RECORD_TOO_LONG,
                onePast.unbounded(CONTRACT).orElseThrow().failure());
    }

    @Test
    @DisplayName("a write against a ring that has changed does not happen and changes nothing")
    void aStaleCompareAndSetWritesNothing() {
        final KeyRing held = KeyRing.initial("the first key");
        final MemoryAuthority authority = new MemoryAuthority(held);
        final KeyRing changed = KeyRing.initial("somebody else's key");
        assertInstanceOf(ContinuationKeyAuthority.Written.class,
                authority.compareAndSet(held, changed, LEASE, NOW), "a write against what was read"
                        + " did not happen");
        final ContinuationKeyAuthority.NotWritten refused = assertInstanceOf(
                ContinuationKeyAuthority.NotWritten.class,
                authority.compareAndSet(held, KeyRing.initial("a third key"), LEASE, NOW),
                "a write against a ring that had changed happened anyway");
        assertEquals(KeyRingRefusal.Failure.CHANGED_SINCE_IT_WAS_READ, refused.refusal().failure());
        assertEquals("somebody else's key",
                assertInstanceOf(ContinuationKeyAuthority.Read.class, authority.read()).ring()
                        .current(),
                "a refused write changed what was held");
    }

    @Test
    @DisplayName("a write by somebody who does not hold the lease does not happen")
    void aWriteWithoutTheLeaseDoesNotHappen() {
        final KeyRing held = KeyRing.initial("the first key");
        final MemoryAuthority authority = new MemoryAuthority(held);
        final ContinuationKeyAuthority.NotWritten refused = assertInstanceOf(
                ContinuationKeyAuthority.NotWritten.class,
                authority.compareAndSet(held, KeyRing.initial("another"), LEASE, LEASE
                        .expiresAtUnixMilliseconds()),
                "a write happened after the lease had expired");
        assertEquals(KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER, refused.refusal().failure());
    }

    @Test
    @DisplayName("validation reports the current key, then the prior one, and refuses after that")
    void validationReportsWhichKeyItWas() {
        final KeyRing rotated = rotated(KeyRing.initial("the first key"), "the second key");
        assertEquals(Optional.of(ValidatingKey.CURRENT), rotated.validating("the second key", NOW));
        assertEquals(Optional.of(ValidatingKey.PRIOR), rotated.validating("the first key", NOW));
        final long retention =
                CONTRACT.value(ContractLimit.CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS);
        assertEquals(Optional.empty(), rotated.validating("the first key", NOW + retention),
                "a key was still honoured after its retention ended");
        assertEquals(Optional.empty(), rotated.validating("a key nobody issued", NOW));
    }

    @Test
    @DisplayName("a rotation while the prior key is retained is refused, naming when it ends")
    void aRotationInsideTheRetentionIsRefused() {
        final KeyRing rotated = rotated(KeyRing.initial("the first key"), "the second key");
        final KeyRing.Refused refused = assertInstanceOf(KeyRing.Refused.class,
                rotated.rotated("the third key", NOW, CONTRACT),
                "two rotations inside one retention window stranded a token");
        assertEquals(KeyRingRefusal.Failure.PRIOR_STILL_RETAINED, refused.refusal().failure());
        assertTrue(refused.refusal().rendered().startsWith("PRIOR_STILL_RETAINED: "),
                refused.refusal().rendered());
        final long retention =
                CONTRACT.value(ContractLimit.CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS);
        assertTrue(refused.refusal().detail().contains(String.valueOf(NOW + retention)),
                refused.refusal().detail());
        assertInstanceOf(KeyRing.Held.class,
                rotated.rotated("the third key", NOW + retention, CONTRACT),
                "a rotation after the retention ended was refused");
    }

    @Test
    @DisplayName("the retention outlives the longest a token can be held plus the clock skew")
    void theRetentionOutlivesEveryTokenIssuedUnderIt() {
        final long retention =
                CONTRACT.value(ContractLimit.CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS);
        final long longestHold = CONTRACT.value(ContractLimit.RETRY_AFTER_CAP_MILLISECONDS)
                * CONTRACT.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS);
        final long skew = CONTRACT.value(ContractLimit.CLOCK_SKEW_ALLOWANCE_MILLISECONDS);
        assertTrue(retention > longestHold + skew, retention + " is not longer than the "
                + longestHold + " a client may hold a token for plus " + skew + " of skew");
    }

    @Test
    @DisplayName("nothing in this contract branches on deployment, node count, or clustering")
    void nothingBranchesOnTheDeployment() {
        final String source = List.of("ContinuationKeyAuthority.java", "KeyRing.java",
                        "ValidatingKey.java", "KeyRingRefusal.java").stream()
                .map(ContinuationKeyAuthorityTest::sourceOf)
                .reduce("", (all, held) -> all + held);
        List.of("nodeCount", "isCluster", "isSingleInstance", "standalone", "topology",
                        "instanceCount")
                .forEach(branch -> assertFalse(source.contains(branch),
                        "a path here branches on " + branch));
    }

    private static KeyRing rotated(KeyRing ring, String next) {
        return assertInstanceOf(KeyRing.Held.class, ring.rotated(next, NOW, CONTRACT),
                "the rotation was refused").ring();
    }

    /** An authority holding its ring in memory, which is what a contract can be proved against. */
    private static final class MemoryAuthority implements ContinuationKeyAuthority {

        private final AtomicReference<KeyRing> held = new AtomicReference<>();

        private MemoryAuthority() {
        }

        private MemoryAuthority(KeyRing ring) {
            held.set(ring);
        }

        @Override
        public ReadOutcome read() {
            final KeyRing ring = held.get();
            return ring == null
                    ? new Unavailable(new KeyRingRefusal(KeyRingRefusal.Failure.ABSENT,
                            "this deployment holds no ring, and one is not created implicitly"))
                    : new Read(ring);
        }

        @Override
        public WriteOutcome compareAndSet(KeyRing expected, KeyRing next, Lease lease,
                                          long nowUnixMilliseconds) {
            if (nowUnixMilliseconds >= lease.expiresAtUnixMilliseconds()) {
                return new NotWritten(new KeyRingRefusal(
                        KeyRingRefusal.Failure.NOT_THE_LEASE_HOLDER,
                        lease.holder() + " no longer holds the lease"));
            }
            if (!held.compareAndSet(expected, next)) {
                return new NotWritten(new KeyRingRefusal(
                        KeyRingRefusal.Failure.CHANGED_SINCE_IT_WAS_READ,
                        "the ring changed since it was read, so this write is not the one meant"));
            }
            return new Written(next);
        }
    }

    private static String sourceOf(String name) {
        return new String(read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/continuation").resolve(name)),
                StandardCharsets.UTF_8);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
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
