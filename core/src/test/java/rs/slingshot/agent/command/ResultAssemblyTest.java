// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.CommandFailure;

/**
 * A result that fits and a result that does not, and the difference never being a truncation.
 *
 * <p>The assertion that matters most here is the structural one: an overflowing result is proved
 * never to have been held whole. Everything else about overflow can be got right while still
 * building the whole answer first, and building it first is the failure that takes an instance
 * down.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ResultAssemblyTest {

    private static final AgentContract CONTRACT = contract();

    /** What this side's clock says, fixed so a published time is arithmetic rather than a race. */
    private static final long NOW = 1_700_000_000_000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a result at exactly the bound is carried inline")
    void aResultAtTheBoundIsInline() {
        final ByteArrayOutputStream overflow = new ByteArrayOutputStream();
        final ResultAssembly.Assembled assembled = assemble(BOUND, BOUND, overflow);
        final ResultAssembly.Inline inline = assertInstanceOf(ResultAssembly.Inline.class, assembled,
                "a result of exactly the bound was sent to the overflow");
        assertEquals(BOUND, inline.bytes().length);
        assertEquals(0, overflow.size(), "an inline result wrote to the overflow");
    }

    @Test
    @DisplayName("a result one byte past the bound becomes a reference, whole and in order")
    void aResultPastTheBoundOverflows() {
        final ByteArrayOutputStream overflow = new ByteArrayOutputStream();
        final ResultAssembly.Assembled assembled = assemble(BOUND + 1, BOUND, overflow);
        final ResultAssembly.Overflowed overflowed = assertInstanceOf(
                ResultAssembly.Overflowed.class, assembled,
                "a result one byte past the bound was carried inline");
        assertEquals(BOUND + 1, overflowed.byteCount(), "the count is not the whole result");
        assertEquals(BOUND + 1, overflow.size(), "the overflow did not receive the whole result");
        assertEquals(content(BOUND + 1).length, overflow.size());
        assertTrue(java.util.Arrays.equals(content(BOUND + 1), overflow.toByteArray()),
                "the overflow received the bytes out of order or altered");
        assertEquals(Digest.of(content(BOUND + 1)).rendered(), overflowed.digest().rendered(),
                "the digest is not the digest of what a fetcher will receive");
    }

    /** A bound small enough to write past in a test and large enough to need several writes. */
    private static final int BOUND = 4096;

    @Test
    @DisplayName("every distinct bound this build declares is proved on both of its sides")
    void everyDistinctBoundIsProvedOnBothSides() {
        final List<ContractLimit> bounds = List.of(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES,
                ContractLimit.MAXIMUM_DISCOVERY_RESULT_BYTES,
                ContractLimit.MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES,
                ContractLimit.MAXIMUM_OPERATIONAL_LISTING_RESULT_BYTES,
                ContractLimit.MAXIMUM_OPERATIONAL_INSPECTION_RESULT_BYTES,
                ContractLimit.MAXIMUM_REPLICATION_RESULT_BYTES);
        for (final ContractLimit bound : bounds) {
            final long value = CONTRACT.value(bound);
            assertInstanceOf(ResultAssembly.Inline.class,
                    assembleCounted(value, value), bound + " refused a result of exactly itself");
            assertInstanceOf(ResultAssembly.Overflowed.class,
                    assembleCounted(value + 1, value),
                    bound + " carried a result one byte past itself inline");
        }
        assertEquals(SIX, bounds.size(), "a result bound was added or lost without this suite"
                + " being told");
    }

    /** How many distinct result bounds this build declares. */
    private static final int SIX = 6;

    @Test
    @DisplayName("an overflowing result is never held whole, however far past the bound it goes")
    void anOverflowingResultIsNeverHeldWhole() {
        final CountingSink counted = new CountingSink();
        try (ResultAssembly assembly = ResultAssembly.upTo(BOUND, counted)) {
            final byte[] chunk = new byte[WRITE];
            for (int written = 0; written < BOUND * MANY; written = written + WRITE) {
                assembly.wrote(chunk, chunk.length);
            }
            final ResultAssembly.Assembled assembled = assembly.build();
            assertInstanceOf(ResultAssembly.Overflowed.class, assembled);
            assertEquals(BOUND * MANY, counted.count, "the overflow did not receive every byte");
            assertTrue(assembly.held() <= BOUND + WRITE,
                    "the assembly held " + assembly.held() + " bytes of a "
                            + (BOUND * MANY) + "-byte result, so it was building the whole answer"
                            + " before discovering it should not have been");
        }
    }

    /** How many times the bound one deliberately oversized result exceeds it. */
    private static final int MANY = 64;

    /** How much one write carries, which is what bounds how much is ever held at once. */
    private static final int WRITE = 512;

    @Test
    @DisplayName("a publication that fails answers with its category and with no result at all")
    void aFailedPublicationAnswersNothing() {
        final ResultAssembly.Overflowed overflowed = overflowed();
        final OverflowPublication.Failed atCapacity = assertInstanceOf(
                OverflowPublication.Failed.class,
                OverflowPublication.answerFor(new rs.slingshot.agent.store.ArtifactStore.AtCapacity(
                        new CapacityLedger.Refused(
                                AccountedQuantity.ARTIFACT_BYTES,
                                CapacityLedger.Reached.THE_CALLERS_SHARE, 1, 2)), overflowed),
                "a caller with no room was given a reference");
        assertEquals(CommandFailure.Category.BUDGET_SPENT, atCapacity.category());
        assertTrue(atCapacity.detail().contains("no room"), atCapacity.detail());
        final OverflowPublication.Failed refused = assertInstanceOf(
                OverflowPublication.Failed.class,
                OverflowPublication.answerFor(new rs.slingshot.agent.store.ArtifactStore.Refused(
                        rs.slingshot.agent.store.ArtifactStore.Refusal.NO_OPERATION,
                        "there is no operation"), overflowed),
                "a refused publication was given a reference");
        assertEquals(CommandFailure.Category.PLATFORM_FAILED, refused.category());
        final OverflowPublication.Failed notCounted = assertInstanceOf(
                OverflowPublication.Failed.class,
                OverflowPublication.answerFor(new ArtifactStore.NotCounted(
                        new CapacityLedger.NotCounted(AccountedQuantity.ARTIFACT_BYTES,
                                rs.slingshot.agent.store.WriteOutcome.CONTENDED)), overflowed),
                "room that could not be accounted was given a reference");
        assertEquals(CommandFailure.Category.PLATFORM_FAILED, notCounted.category());
        assertEquals(3, List.of(atCapacity, refused, notCounted).stream()
                        .map(OverflowPublication.Failed::detail).distinct().count(),
                "two different failures are told to a caller in the same words");
    }

    @Test
    @DisplayName("a command that can overflow takes its room before it runs")
    void roomIsTakenBeforeTheCommandRuns() {
        final long inline = CONTRACT.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES);
        assertEquals(OverflowPublication.Reservation.BEFORE_THE_COMMAND_RUNS,
                OverflowPublication.reservationFor(rowAnswering(inline + 1), CONTRACT),
                "a command whose answer can outgrow what an answer carries took no room before"
                        + " running, so it would read to completion and then find nowhere to put"
                        + " what it read");
        assertEquals(OverflowPublication.Reservation.NONE_IS_NEEDED,
                OverflowPublication.reservationFor(rowAnswering(inline), CONTRACT),
                "a command whose whole answer always fits reserved artifact room it cannot use");
    }

    @Test
    @DisplayName("an overflow that stops accepting bytes is a failure rather than a short result")
    void anOverflowThatStopsIsNotAShortResult() {
        final OutputStream closed = new GoneOverflow();
        try (ResultAssembly assembly = ResultAssembly.upTo(BOUND, closed)) {
            final byte[] chunk = new byte[BOUND + 1];
            assertTrue(assertThrows(assembly, chunk).getMessage().contains("stopped accepting"),
                    "an overflow that stopped accepting bytes did not say so");
        }
    }

    @Test
    @DisplayName("an overflowing result is published and answered with a reference a caller checks")
    void anOverflowIsAnsweredWithACheckableReference() throws RepositoryException {
        final Session session = prepared();
        final byte[] whole = content(BOUND + 1);
        final OverflowPublication.Published published = assertInstanceOf(
                OverflowPublication.Published.class,
                OverflowPublication.publish(session, caller(), operation(), overflowed(), whole,
                        NOW, CONTRACT),
                "an overflowing result was not published");
        assertEquals(OverflowPublication.RESULT_SLOT, published.slot());
        assertEquals(whole.length, published.delivery().byteCount(),
                "the count a caller checks against is not the count of what was written");
        assertEquals(Digest.of(whole).rendered(), published.delivery().digest().rendered(),
                "the digest a caller checks against is not the digest of what was written");
        final byte[] fetched = read(session);
        assertTrue(java.util.Arrays.equals(whole, fetched),
                "what a caller fetches is not what the command produced");
        assertEquals(published.delivery().digest().rendered(), Digest.of(fetched).rendered(),
                "the published digest does not verify against the bytes the store hands back");
    }

    @Test
    @DisplayName("a second publication into a taken slot answers no reference at all")
    void asecondPublicationAnswersNothing() throws RepositoryException {
        final Session session = prepared();
        final byte[] whole = content(BOUND + 1);
        assertInstanceOf(OverflowPublication.Published.class,
                OverflowPublication.publish(session, caller(), operation(), overflowed(), whole,
                        NOW, CONTRACT));
        final OverflowPublication.Failed second = assertInstanceOf(OverflowPublication.Failed.class,
                OverflowPublication.publish(session, caller(), operation(), overflowed(), whole,
                        NOW, CONTRACT),
                "a slot that already holds an artifact accepted a second one");
        assertEquals(CommandFailure.Category.PLATFORM_FAILED, second.category());
        assertTrue(read(session).length == whole.length,
                "a refused second publication changed what the first one wrote");
    }

    private byte[] read(Session session) throws RepositoryException {
        try (java.io.InputStream held = ArtifactStore.open(session, operation(),
                        slot()).orElseThrow(() -> new AssertionError("the artifact is not there"))) {
            return held.readAllBytes();
        } catch (final IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static rs.slingshot.agent.store.ArtifactSlot slot() {
        return assertInstanceOf(rs.slingshot.agent.store.ArtifactSlot.Held.class,
                rs.slingshot.agent.store.ArtifactSlot.of(OverflowPublication.RESULT_SLOT),
                "the result slot was refused").slot();
    }

    private Session prepared() throws RepositoryException {
        final Session session = Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, operation().path());
        ArtifactStore.prepare(session, caller());
        return session;
    }

    private static void walked(Session session, String path) throws RepositoryException {
        Node node = session.getRootNode();
        for (final String segment : path.substring(1).split("/")) {
            node = node.hasNode(segment) ? node.getNode(segment)
                    : node.addNode(segment, "nt:unstructured");
        }
        session.save();
    }

    private static StatePath operation() {
        return StatePath.operation(generation(), assertInstanceOf(
                AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        Digest.of("the overflowing operation".getBytes(StandardCharsets.UTF_8))
                                .rendered(), CONTRACT),
                "the operation's own name was refused").identifier());
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-overflowing-caller"),
                "the caller was refused").caller();
    }

    private static java.io.UncheckedIOException assertThrows(ResultAssembly assembly, byte[] chunk) {
        return org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                () -> assembly.wrote(chunk, chunk.length),
                "a result was quietly cut short where its overflow had gone");
    }

    private static RegistryRow rowAnswering(long resultBytes) {
        return new RegistryRow("a_command", "1.0.0", AccessClass.READ,
                RegistryRow.OperationKey.REFUSED, resultBytes, List.of("not_found"),
                "a".repeat(64), "b".repeat(64), "c".repeat(64), 0, ExecutionClass.IMMEDIATE);
    }

    private static ResultAssembly.Overflowed overflowed() {
        return new ResultAssembly.Overflowed(BOUND + 1L, Digest.of(content(BOUND + 1)));
    }

    private static ResultAssembly.Assembled assembleCounted(long size, long bound) {
        final CountingSink counted = new CountingSink();
        try (ResultAssembly assembly = ResultAssembly.upTo(bound, counted)) {
            final byte[] chunk = new byte[WRITE];
            long written = 0;
            while (written < size) {
                final int take = (int) Math.min(WRITE, size - written);
                assembly.wrote(chunk, take);
                written = written + take;
            }
            return assembly.build();
        }
    }

    private static ResultAssembly.Assembled assemble(int size, long bound, OutputStream overflow) {
        try (ResultAssembly assembly = ResultAssembly.upTo(bound, overflow)) {
            final byte[] whole = content(size);
            for (int at = 0; at < whole.length; at = at + WRITE) {
                final int take = Math.min(WRITE, whole.length - at);
                final byte[] chunk = new byte[take];
                System.arraycopy(whole, at, chunk, 0, take);
                assembly.wrote(chunk, take);
            }
            return assembly.build();
        }
    }

    private static byte[] content(int size) {
        final byte[] bytes = new byte[size];
        for (int at = 0; at < size; at = at + 1) {
            bytes[at] = (byte) ("slingshot".charAt(at % "slingshot".length()));
        }
        return bytes;
    }

    /** An overflow that counts what it receives without keeping any of it. */
    private static final class CountingSink extends OutputStream {

        private long count;

        @Override
        public void write(int one) {
            count = count + 1;
        }

        @Override
        public void write(byte[] chunk, int from, int length) {
            count = count + length;
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    static {
        assertTrue("slingshot".getBytes(StandardCharsets.UTF_8).length > 0);
    }

    /** An overflow that has stopped accepting bytes, named rather than anonymous. */
    private static final class GoneOverflow extends OutputStream {

        @Override
        public void write(int one) throws IOException {
            throw new IOException("this overflow is gone");
        }

        @Override
        public void write(byte[] chunk, int from, int length) throws IOException {
            throw new IOException("this overflow is gone");
        }
    }
}
