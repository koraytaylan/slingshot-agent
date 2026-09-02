// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.StatePath;

/**
 * Every delivery of one operation, recorded once however many times the job system delivers it.
 *
 * <p>A duplicate is the normal case rather than a defect: what is proved here is that the second
 * arrival of one delivery adds nothing, that the count is a fact the store holds rather than a walk
 * over children, and that a delivery arriving after the work has finished is recorded without
 * changing what finished.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class OutboxTest {

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("one delivery is recorded once, however many times it arrives")
    void adeliveryIsRecordedOnce() throws RepositoryException {
        final Session session = withRecord();
        assertEquals(Outbox.Recorded.FIRST_TIME, record(session, "job-one").recorded());
        final Outbox.Wrote again = record(session, "job-one");
        assertEquals(Outbox.Recorded.ALREADY_SEEN, again.recorded(),
                "a delivery this store had already seen was recorded a second time");
        assertEquals(1, again.attempts(), "a duplicate arrival was counted twice");
        assertEquals(1, Outbox.held(session, identity()).size());
        assertEquals("job-one", Outbox.held(session, identity()).getFirst().jobIdentifier());
    }

    @Test
    @DisplayName("two deliveries are two attempts, each with the node that saw it")
    void twodeliveriesAreTwoAttempts() throws RepositoryException {
        final Session session = withRecord();
        record(session, "job-one");
        assertEquals(2, record(session, "job-two").attempts());
        assertEquals(2, Outbox.attemptsFor(session, identity()));
        assertTrue(Outbox.held(session, identity()).stream()
                        .allMatch(attempt -> "a-node".equals(attempt.observedBy())),
                "an attempt was recorded without the node that saw it");
    }

    @Test
    @DisplayName("attempts are recorded to exactly the bound and refused one past it")
    void theattemptBoundHoldsAtBothSides() throws RepositoryException {
        final Session session = withRecord();
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_LOGICAL_OUTBOX_ATTEMPTS);
        long delivered = 0;
        while (delivered < bound) {
            assertEquals(Outbox.Recorded.FIRST_TIME,
                    record(session, "job-" + delivered).recorded(),
                    "a delivery inside the bound was refused at " + delivered);
            delivered = delivered + 1;
        }
        assertTrue(Outbox.exhausted(session, identity()),
                "an operation at its attempt bound is not recorded as exhausted");
        assertEquals(Outbox.Recorded.EXHAUSTED_ALREADY,
                record(session, "job-one-too-many").recorded(),
                "a delivery past the bound was recorded");
        assertEquals(bound, Outbox.attemptsFor(session, identity()),
                "a refused delivery was counted anyway");
    }

    @Test
    @DisplayName("a delivery after the work finished is recorded and the outcome stands")
    void adeliveryAfterTheEndChangesNothing() throws RepositoryException {
        final Session session = withRecord();
        final LogicalOperation accepted = assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation();
        final LogicalOperation running = assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, accepted, OperationState.RUNNING)).operation();
        final LogicalOperation finished = assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, running, OperationState.SUCCEEDED)).operation();
        assertEquals(Outbox.Recorded.FIRST_TIME, record(session, "job-after-the-end").recorded());
        assertEquals(finished.state(), assertInstanceOf(OperationStore.Held.class,
                        OperationStore.read(session, identity())).operation().state(),
                "a redelivery after the work finished changed what finished");
    }

    @Test
    @DisplayName("an attempt against an operation nothing recorded is not recorded either")
    void anattemptAgainstNothingIsNotRecorded() throws RepositoryException {
        final Session session = prepared();
        assertInstanceOf(Outbox.NoRecord.class,
                Outbox.record(session, identity(), attempt("job-one"), CONTRACT),
                "an attempt was recorded against an operation nothing had recorded");
    }

    @Test
    @DisplayName("a job identifier holds at exactly its own bound and is refused one byte past it")
    void theidentifierBoundHoldsAtBothSides() {
        final long bound = CONTRACT.value(ContractLimit.TRANSPORT_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES);
        assertInstanceOf(PhysicalAttempt.Held.class,
                PhysicalAttempt.of("j".repeat((int) bound), "a-node", NOW, CONTRACT),
                "a delivery named at exactly the platform's bound was refused");
        assertEquals(PhysicalAttempt.Refusal.IDENTIFIER_TOO_LONG,
                assertInstanceOf(PhysicalAttempt.Refused.class,
                        PhysicalAttempt.of("j".repeat((int) bound + 1), "a-node", NOW, CONTRACT))
                        .refusal());
        assertEquals(PhysicalAttempt.Refusal.IDENTIFIER_EMPTY,
                assertInstanceOf(PhysicalAttempt.Refused.class,
                        PhysicalAttempt.of("", "a-node", NOW, CONTRACT)).refusal());
        assertEquals(PhysicalAttempt.Refusal.OBSERVER_EMPTY,
                assertInstanceOf(PhysicalAttempt.Refused.class,
                        PhysicalAttempt.of("job-one", "", NOW, CONTRACT)).refusal());
    }

    @Test
    @DisplayName("two sessions recording one delivery leave one attempt between them")
    void twosessionsRecordingOneDeliveryLeaveOne() throws RepositoryException {
        final Session first = withRecord();
        final Session second = another();
        record(first, "job-raced-for");
        assertEquals(Outbox.Recorded.ALREADY_SEEN,
                assertInstanceOf(Outbox.Wrote.class,
                        Outbox.record(second, identity(), attempt("job-raced-for"), CONTRACT))
                        .recorded(),
                "a second session recorded a delivery the first had already recorded");
        assertEquals(1, Outbox.attemptsFor(first, identity()));
    }

    private Outbox.Wrote record(Session session, String job) throws RepositoryException {
        return assertInstanceOf(Outbox.Wrote.class,
                Outbox.record(session, identity(), attempt(job), CONTRACT),
                "the attempt was not recorded");
    }

    private static PhysicalAttempt attempt(String job) {
        return assertInstanceOf(PhysicalAttempt.Held.class,
                PhysicalAttempt.of(job, "a-node", NOW, CONTRACT), job + " is not an attempt")
                .attempt();
    }

    private static OperationIdentity identity() {
        final java.util.SequencedMap<String, DocumentValue> members = new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION,
                new DocumentValue.Whole(EventStoreGeneration.FIRST));
        members.put(OperationIdentity.IDENTIFIER,
                new DocumentValue.Text(digest("one operation").rendered()));
        members.put(OperationIdentity.TARGET_DIGEST,
                new DocumentValue.Text(digest("a target").rendered()));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new DocumentValue.Text("revision-2026-09-01"));
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(new DocumentValue.Mapping(members), CONTRACT),
                "the identity was refused").identity();
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

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private Session withRecord() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation.Outcome accepted = LogicalOperation.accepted(identity(),
                digest("a submission"), commandContract(), caller(), NOW, NOW, CONTRACT);
        OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class, accepted)
                .operation());
        return session;
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-submitting-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        return prepare(java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start"));
    }

    private Session another() throws RepositoryException {
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

    private static Session prepare(Session session) throws RepositoryException {
        if (!session.nodeExists(StatePath.ROOT)) {
            final Node variable = session.getRootNode().hasNode("var")
                    ? session.getRootNode().getNode("var")
                    : session.getRootNode().addNode("var", "nt:unstructured");
            variable.addNode("slingshot-agent", "nt:unstructured");
            session.save();
        }
        return session;
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
