// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/**
 * One durable thing per submission: what it holds, what may happen to it, and when this side will
 * not believe the instant a client says its request began.
 *
 * <p>The transition matrix is exercised one pair at a time across every state rather than along the
 * path a command actually takes. The path is the case that works; the matrix is where the fourth
 * case nobody wrote lives.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class LogicalOperationTest {

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every permitted move is accepted from its own state and refused from every other")
    void thewholeTransitionMatrixHolds() {
        Arrays.stream(OperationState.values()).forEach(from ->
                Arrays.stream(OperationState.values()).forEach(to -> {
                    final boolean declared = OperationState.transitions().contains(List.of(from, to))
                            || from == to && from.finality() == rs.slingshot.agent.wire
                                    .JobEventKind.Finality.ENDS;
                    assertEquals(declared, from.permits(to),
                            from.spelling() + " to " + to.spelling() + " is permitted where it is"
                                    + " not declared, or refused where it is");
                }));
        assertEquals(4, OperationState.transitions().size(), "a transition was added or lost");
    }

    @Test
    @DisplayName("a terminal state accepts nothing else, and accepts itself again unchanged")
    void aterminalStateIsFinalAndIdempotent() {
        assertTrue(OperationState.SUCCEEDED.permits(OperationState.SUCCEEDED),
                "a worker that lost its answer cannot say the same thing again");
        assertFalse(OperationState.SUCCEEDED.permits(OperationState.FAILED),
                "a job that succeeded then failed");
        assertFalse(OperationState.SUCCEEDED.permits(OperationState.RUNNING),
                "a job that finished started again");
        assertEquals(rs.slingshot.agent.wire.JobEventKind.Finality.ENDS,
                OperationState.FAILED.finality());
        assertEquals(rs.slingshot.agent.wire.JobEventKind.ACCEPTED, OperationState.ACCEPTED.kind(),
                "a state and the event kind that announces it disagree");
        assertEquals(java.util.Optional.of(OperationState.RUNNING),
                OperationState.named("running"));
        assertTrue(OperationState.named("teleporting").isEmpty());
    }

    @Test
    @DisplayName("a record is created once, and a second writer reads the first's record")
    void arecordIsCreatedOnce() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation operation = accepted(NOW);
        final OperationStore.Created first = created(session, operation);
        assertEquals(WriteOutcome.CLAIMED, first.outcome());
        final OperationStore.Created second = created(session, accepted(NOW));
        assertEquals(WriteOutcome.ALREADY_HELD, second.outcome(),
                "a second submission created a second durable thing");
        assertEquals(first.operation().submissionDigest().rendered(),
                second.operation().submissionDigest().rendered());
    }

    @Test
    @DisplayName("a record holds the target, the revision, and the caller, and reads them back")
    void arecordHoldsWhatALaterPlanWillNeed() throws RepositoryException {
        final Session session = prepared();
        created(session, accepted(NOW));
        final LogicalOperation read = assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity()), "the record could not be read back")
                .operation();
        assertEquals("revision-2026-09-01", read.identity().environmentRevision());
        assertEquals(digest("a target").rendered(), read.identity().targetDigest().rendered());
        assertEquals("the-submitting-caller", read.caller().name());
        assertEquals(NOW, read.requestStartUnixMilliseconds());
        assertEquals(OperationState.ACCEPTED, read.state());
        assertEquals(0, read.attempts());
        assertEquals("query_paths", read.commandContract().wireName());
    }

    @Test
    @DisplayName("a move from the state the caller read happens, and one from another does not")
    void amoveHappensOnlyFromWhatWasRead() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = created(session, accepted(NOW)).operation();
        final LogicalOperation running = assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, accepted, OperationState.RUNNING),
                "a move from the state that was read did not happen").operation();
        assertEquals(OperationState.RUNNING, running.state());
        final OperationStore.Refused stale = assertInstanceOf(OperationStore.Refused.class,
                OperationStore.move(session, accepted, OperationState.RUNNING),
                "a move from a state the record is no longer in happened anyway");
        assertEquals(OperationStore.Refusal.NOT_THE_STATE_THAT_WAS_READ, stale.refusal());
        assertEquals(OperationState.RUNNING, assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation().state(),
                "a refused move changed the record");
        assertEquals(OperationStore.Refusal.NOT_A_PERMITTED_MOVE,
                assertInstanceOf(OperationStore.Refused.class,
                        OperationStore.move(session, running, OperationState.ACCEPTED)).refusal());
    }

    @Test
    @DisplayName("a record nobody wrote is not read, and reading for one does not create it")
    void anabsentRecordIsNotCreatedByReading() throws RepositoryException {
        final Session session = prepared();
        final OperationStore.Refused refused = assertInstanceOf(OperationStore.Refused.class,
                OperationStore.read(session, identity()), "a record appeared out of nothing");
        assertEquals(OperationStore.Refusal.NO_RECORD, refused.refusal());
        assertFalse(session.nodeExists(OperationStore.pathOf(identity()).path()),
                "reading for a record created one");
    }

    @Test
    @DisplayName("retention runs from the instant the client says its request began")
    void retentionIsAnchoredAtRequestStart() {
        final long began = NOW - 60000;
        final LogicalOperation operation = accepted(began);
        assertEquals(began + 3600000, operation.retainedUntil(3600000),
                "retention is measured from something other than the request's own start");
        assertFalse(operation.retainedUntil(3600000) == NOW + 3600000,
                "retention is measured from when the record was written, which lengthens a window"
                        + " the client is budgeting against");
    }

    @Test
    @DisplayName("a request-start instant outside this side's allowance is refused, naming both")
    void theclockAllowanceHoldsAtBothSidesAndBothDirections() {
        final long allowance = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        assertInstanceOf(LogicalOperation.Held.class, accepting(NOW - allowance),
                "a request exactly at the allowance behind was refused");
        assertInstanceOf(LogicalOperation.Held.class, accepting(NOW + allowance),
                "a request exactly at the allowance ahead was refused");
        final LogicalOperation.Refused behind = assertInstanceOf(LogicalOperation.Refused.class,
                accepting(NOW - allowance - 1), "a request from before the allowance was recorded");
        assertEquals(LogicalOperation.ClockRefusal.TOO_FAR_BEHIND, behind.refusal());
        assertTrue(behind.detail().contains(String.valueOf(NOW)), behind.detail());
        final LogicalOperation.Refused ahead = assertInstanceOf(LogicalOperation.Refused.class,
                accepting(NOW + allowance + 1), "a request from after the allowance was recorded");
        assertEquals(LogicalOperation.ClockRefusal.TOO_FAR_AHEAD, ahead.refusal());
        assertTrue(ahead.detail().contains(String.valueOf(NOW + allowance + 1)), ahead.detail());
    }

    @Test
    @DisplayName("nothing is written for a request this side will not believe")
    void arefusedInstantWritesNothing() throws RepositoryException {
        final Session session = prepared();
        final long allowance = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        assertInstanceOf(LogicalOperation.Refused.class, accepting(NOW + allowance + 1));
        assertFalse(session.nodeExists(OperationStore.pathOf(identity()).path()),
                "a refused request left a record behind");
    }

    @Test
    @DisplayName("an attempt counted against a record is counted once")
    void anattemptIsCounted() {
        assertEquals(1, accepted(NOW).attempted().attempts());
        assertEquals(2, accepted(NOW).attempted().attempted().attempts());
        assertEquals(OperationState.ACCEPTED, accepted(NOW).attempted().state(),
                "counting an attempt moved the record");
    }

    private OperationStore.Created created(Session session, LogicalOperation operation)
            throws RepositoryException {
        final Object outcome = OperationStore.create(session, operation);
        assertInstanceOf(OperationStore.Created.class, outcome, "the record was not created: "
                + outcome);
        return (OperationStore.Created) outcome;
    }

    private static LogicalOperation accepted(long requestStart) {
        return assertInstanceOf(LogicalOperation.Held.class, accepting(requestStart),
                "the submission was refused").operation();
    }

    private static LogicalOperation.Outcome accepting(long requestStart) {
        return LogicalOperation.accepted(identity(), digest("a submission"), commandContract(),
                caller(), requestStart, NOW, CONTRACT);
    }

    private static OperationIdentity identity() {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(operationDocument(), CONTRACT),
                "the operation identity was refused").identity();
    }

    private static DocumentValue operationDocument() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION, new DocumentValue.Whole(
                EventStoreGeneration.FIRST));
        members.put(OperationIdentity.IDENTIFIER,
                new DocumentValue.Text(digest("one operation").rendered()));
        members.put(OperationIdentity.TARGET_DIGEST,
                new DocumentValue.Text(digest("a target").rendered()));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new DocumentValue.Text("revision-2026-09-01"));
        return new DocumentValue.Mapping(members);
    }

    private static CommandContractIdentity commandContract() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(CommandContractIdentity.WIRE_NAME, new DocumentValue.Text("query_paths"));
        members.put(CommandContractIdentity.CONTRACT_VERSION, new DocumentValue.Text("1.0.0"));
        members.put(CommandContractIdentity.LIMITS_DIGEST,
                new DocumentValue.Text(digest("limits").rendered()));
        members.put(CommandContractIdentity.ARGUMENT_DIGEST,
                new DocumentValue.Text(digest("arguments").rendered()));
        members.put(CommandContractIdentity.RESULT_DIGEST,
                new DocumentValue.Text(digest("result").rendered()));
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(new DocumentValue.Mapping(members),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-submitting-caller"),
                "the caller was refused").caller();
    }

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        final String path = OperationStore.pathOf(identity()).path();
        final String[] segments = path.substring(1).split("/");
        javax.jcr.Node walked = session.getRootNode();
        int index = 0;
        while (index < segments.length - 1) {
            walked = walked.hasNode(segments[index])
                    ? walked.getNode(segments[index])
                    : walked.addNode(segments[index], "nt:unstructured");
            index = index + 1;
        }
        session.save();
        return session;
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
