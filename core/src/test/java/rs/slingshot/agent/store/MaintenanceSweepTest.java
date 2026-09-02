// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import rs.slingshot.agent.execution.ExecutionFence;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.TerminalCommit;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A pass that stops where it said it would, resumes where it stopped, and takes only what is safe.
 *
 * <p>The three artifacts that are left alone are the point of the collection tests: bytes a worker
 * has just written and not yet named look exactly like garbage, and a sweep that cannot tell the
 * difference is the thing that breaks an answer. So the test drives each protection separately —
 * a live lease, an artifact younger than a lease, a slot a manifest still declares — and then
 * proves the same artifact is collected once none of them holds.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class MaintenanceSweepTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/maintenance-sweep");

    private static final AgentContract CONTRACT = contract();

    private static final List<String> OPERATIONS =
            List.of("operation-0.json", "operation-1.json", "operation-2.json");

    private static final long REQUEST_START = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a sweep with nothing to do removes nothing and ends at the start again")
    void asweepWithNothingToDoRemovesNothing() throws RepositoryException {
        final Session session = recorded();
        final SweepReport report = MaintenanceSweep.run(session, generation(), REQUEST_START,
                CONTRACT);
        assertEquals(0, report.recordsRemoved(), "a sweep removed something inside its retention");
        assertEquals(0, report.artifactsCollected());
        assertEquals(OPERATIONS.size(), report.examined(),
                "a sweep did not look at every record there is");
        assertEquals(SweepCursor.FIRST, report.to(),
                "a sweep that reached the end did not come back to the beginning");
        for (final String operation : OPERATIONS) {
            assertTrue(session.nodeExists(operation(operation).path()),
                    operation + " was removed inside its own retention");
        }
    }

    @Test
    @DisplayName("a sweep past retention removes the record and gives back exactly what it held")
    void asweepPastRetentionGivesBackWhatItHeld() throws RepositoryException {
        final Session session = recorded();
        final long held = CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT);
        assertTrue(held > 0, "the suite wrote no artifact bytes, so it releases nothing");
        final SweepReport report = MaintenanceSweep.run(session, generation(), past(), CONTRACT);
        assertEquals(OPERATIONS.size(), report.recordsRemoved(),
                "a sweep past every retention left records behind");
        assertEquals(held, report.bytesReleased(),
                "what the sweep gave back is not what the store was holding");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT),
                "the counted bytes are not the bytes the store now holds");
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.EVENT_ROWS, CONTRACT),
                "the counted events are not the events the store now holds");
        for (final String operation : OPERATIONS) {
            assertFalse(session.nodeExists(operation(operation).path()),
                    operation + " outlived its own retention");
        }
    }

    @Test
    @DisplayName("a bounded pass stops where it said and a resumed one covers exactly the rest")
    void aboundedPassResumesWithNoGapAndNoOverlap() throws RepositoryException {
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final Session session = recorded();
        final SweepReport first = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(1, first.examined(), "a bounded pass looked at more than its bound");
        assertEquals(1, first.recordsRemoved());
        assertTrue(first.to() > first.from(), "a bounded pass did not advance its cursor");
        assertEquals(first.to(), SweepCursor.read(session).bucket(),
                "where the pass stopped is not where the store says it stopped");
        final SweepReport second = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(first.to(), second.from(),
                "a resumed pass started somewhere other than where the last one stopped");
        assertEquals(1, second.recordsRemoved(), "a resumed pass covered the wrong region");
        final SweepReport third = MaintenanceSweep.run(session, generation(), past(), bounded);
        assertEquals(1, third.recordsRemoved(), "the last record was never reached");
        assertEquals(SweepCursor.FIRST, third.to(),
                "a pass that reached the end did not come back to the beginning");
        for (final String operation : OPERATIONS) {
            assertFalse(session.nodeExists(operation(operation).path()),
                    operation + " was never swept, so the passes had a gap in them");
        }
    }

    @Test
    @DisplayName("an artifact is left alone while a lease is live, while it is young, and while declared")
    void anartifactIsLeftAloneWhileAnythingHoldsIt() throws RepositoryException {
        final Session session = recorded();
        final long lease = CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        final long young = REQUEST_START + lease - 1;
        assertEquals(0, MaintenanceSweep.run(session, generation(), young, CONTRACT)
                        .artifactsCollected(),
                "an artifact younger than a lease was collected");
        ExecutionFence.take(session, identity(OPERATIONS.get(0)), "a-worker",
                REQUEST_START + lease, CONTRACT);
        assertEquals(2, MaintenanceSweep.run(session, generation(), REQUEST_START + lease,
                        CONTRACT).artifactsCollected(),
                "an artifact under a live lease was collected, or one under none was not");
        assertTrue(session.nodeExists(slotOf(OPERATIONS.get(0)).path()),
                "the artifact under a live lease is gone");
        declare(session, OPERATIONS.get(0));
        assertEquals(0, MaintenanceSweep.run(session, generation(),
                        REQUEST_START + lease + lease, CONTRACT).artifactsCollected(),
                "an artifact whose slot a manifest still declares was collected");
        session.getNode(operation(OPERATIONS.get(0)).child(MaintenanceSweep.INTAKE).path())
                .remove();
        session.save();
        assertEquals(1, MaintenanceSweep.run(session, generation(),
                        REQUEST_START + lease + lease, CONTRACT).artifactsCollected(),
                "an artifact nothing holds any more was not collected");
    }

    @Test
    @DisplayName("a referenced artifact is never collected, however the reference was written")
    void areferencedArtifactIsNeverCollected() throws RepositoryException {
        final Session session = recorded();
        final Node record = session.getNode(operation(OPERATIONS.get(0)).path());
        record.setProperty(MaintenanceSweep.RESULT_SLOT, "result");
        session.save();
        final long lease = CONTRACT.value(ContractLimit.WORKER_EXECUTION_LEASE_MILLISECONDS);
        assertEquals(OPERATIONS.size() - 1, MaintenanceSweep.run(session, generation(),
                        REQUEST_START + lease, CONTRACT).artifactsCollected(),
                "a referenced artifact was collected");
        assertTrue(session.nodeExists(slotOf(OPERATIONS.get(0)).path()),
                "the artifact an answer names is gone, so that answer is broken forever");
        assertEquals(MaintenanceSweep.RESULT_SLOT, TerminalCommit.RESULT_SLOT,
                "an answer names its slot in one property and the sweep reads another");
        assertEquals(MaintenanceSweep.LEASE_HELD_UNTIL, ExecutionFence.HELD_UNTIL,
                "a lease is written in one property and the sweep reads another");
        assertEquals(MaintenanceSweep.LEASE, ExecutionFence.NODE,
                "a lease lives at one node and the sweep looks at another");
        assertEquals(MaintenanceSweep.CALLER, OperationStore.CALLER,
                "a caller is written in one property and the sweep reads another");
    }

    @Test
    @DisplayName("two passes over one store state produce byte-identical reports")
    void twopassesOverOneStateProduceIdenticalReports() throws RepositoryException {
        final Session session = recorded();
        final String first = MaintenanceSweep.run(session, generation(), REQUEST_START, CONTRACT)
                .rendered();
        SweepCursor.advance(session, SweepCursor.read(session), SweepCursor.FIRST, REQUEST_START);
        final String second = MaintenanceSweep.run(session, generation(), REQUEST_START + 1,
                CONTRACT).rendered();
        assertEquals(first, second,
                "two passes over one store state disagree, so a difference means nothing");
        assertTrue(first.contains("examined=" + OPERATIONS.size()), first);
    }

    @Test
    @DisplayName("a store the sweep has interrupted still says the same thing everywhere")
    void aninterruptedSweepLeavesTheStoreConsistent() throws RepositoryException {
        final AgentContract bounded = contractWith("maintenance_sweep_work_bound_rows", 1L);
        final Session session = recorded();
        MaintenanceSweep.run(session, generation(), past(), bounded);
        for (final String operation : OPERATIONS) {
            if (!session.nodeExists(operation(operation).path())) {
                continue;
            }
            assertInstanceOf(SnapshotStore.Agrees.class, SnapshotStore.verify(session,
                    operation(operation), SnapshotStore.NoRecord.NOTHING_HOLDS_THIS_OPERATION),
                    operation + " does not say the same thing everywhere after an interruption");
        }
    }

    @Test
    @DisplayName("a bucket is a number, a name that is not one is not a bucket, and the two agree")
    void abucketIsAnumber() {
        assertEquals(List.of("00", "00"), SweepCursor.segments(0));
        assertEquals(List.of("ff", "ff"), SweepCursor.segments(SweepCursor.BUCKETS - 1));
        assertEquals(List.of("3a", "6a"), SweepCursor.segments(0x3a6a));
        assertTrue(SweepCursor.holds(SweepCursor.FIRST));
        assertFalse(SweepCursor.holds(SweepCursor.BUCKETS));
        assertTrue(SweepCursor.at(SweepCursor.BUCKETS, REQUEST_START).isEmpty());
        assertEquals(0x3a6a, SweepCursor.at(0x3a6a, REQUEST_START).orElseThrow().bucket());
        assertEquals(REQUEST_START,
                SweepCursor.at(0, REQUEST_START).orElseThrow().advancedAtUnixMilliseconds());
        assertEquals(SweepCursor.at(0, REQUEST_START).orElseThrow(),
                SweepCursor.at(0, REQUEST_START).orElseThrow());
        assertEquals(SweepCursor.at(0, REQUEST_START).orElseThrow().hashCode(),
                SweepCursor.at(0, REQUEST_START).orElseThrow().hashCode());
        assertTrue(SweepCursor.at(0, REQUEST_START).orElseThrow().toString().contains("0"));
    }

    private void declare(Session session, String operation) throws RepositoryException {
        final Node record = session.getNode(operation(operation).path());
        final Node intake = record.hasNode(MaintenanceSweep.INTAKE)
                ? record.getNode(MaintenanceSweep.INTAKE)
                : record.addNode(MaintenanceSweep.INTAKE, "nt:unstructured");
        intake.addNode("result", "nt:unstructured");
        session.save();
    }

    private static StatePath slotOf(String operation) {
        return operation(operation).child(ArtifactStore.NODE).child("result");
    }

    private static long past() {
        return REQUEST_START + RetentionPolicy.Kind.OPERATION_DETAIL.minimum(CONTRACT);
    }

    private Session recorded() throws RepositoryException {
        final Session session = prepared();
        for (final String operation : OPERATIONS) {
            OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                    LogicalOperation.accepted(identity(operation),
                            Digest.of(operation.getBytes(StandardCharsets.UTF_8)),
                            commandContract(), caller(), REQUEST_START, REQUEST_START, CONTRACT),
                    operation + " was refused").operation());
            final byte[] content = ("the bytes of " + operation).getBytes(StandardCharsets.UTF_8);
            assertInstanceOf(ArtifactStore.Published.class, ArtifactStore.publish(session, caller(),
                    operation(operation), new ArtifactStore.Publication(slot(), content.length,
                            new ByteArrayInputStream(content)), REQUEST_START, CONTRACT),
                    operation + "'s artifact was not published");
        }
        return session;
    }

    private static ArtifactSlot slot() {
        return assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of("result"),
                "the slot was refused").slot();
    }

    private static OperationIdentity identity(String fixture) {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document(fixture), CONTRACT),
                fixture + " is not an operation identity").identity();
    }

    private static CommandContractIdentity commandContract() {
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(document("command-contract.json"),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static StatePath operation(String fixture) {
        return OperationStore.pathOf(identity(fixture));
    }

    private static EventStoreGeneration generation() {
        return assertInstanceOf(EventStoreGeneration.Held.class,
                EventStoreGeneration.of(EventStoreGeneration.FIRST),
                "the first generation was refused").generation();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-swept-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        for (final String operation : OPERATIONS) {
            final String path = operation(operation).path();
            walked(session, path.substring(0, path.lastIndexOf('/')));
        }
        ArtifactStore.prepare(session, caller());
        LedgerAdmission.prepare(session, caller());
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

    private static AgentContract contractWith(String bound, long value) {
        final Map<String, Long> overrides = Map.of(bound, value);
        final StringBuilder rewritten = new StringBuilder();
        read(REPOSITORY.resolve("support/agent-contract.toml")).lines().forEach(line -> {
            final String name = line.contains("=") ? line.substring(0, line.indexOf('=')).strip()
                    : "";
            rewritten.append(overrides.containsKey(name) ? name + " = " + overrides.get(name)
                            : line)
                    .append('\n');
        });
        final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        return assertInstanceOf(AgentContract.Loaded.class,
                AgentContract.load(document, AgentContract.digestOf(document)),
                "the shrunken contract is not one this build reads").contract();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static String read(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
