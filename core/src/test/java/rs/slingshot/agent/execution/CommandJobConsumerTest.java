// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;

/**
 * What a node does with one delivery, and what it does with the four kinds it drops.
 *
 * <p>Dropping rather than retrying is the property under test. A job for an operation nothing
 * holds, for an incarnation this node is not serving, for properties nobody can read, or for an
 * immediate row will not become valid by being tried again, and a queue that keeps trying is a
 * queue that never drains.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class CommandJobConsumerTest {

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("the topic and its queue values are the contract's own, not written here")
    void thequeueValuesComeFromTheContract() {
        final CommandJobTopic.QueueValues values = CommandJobTopic.QueueValues.from(CONTRACT);
        assertEquals(CONTRACT.value(ContractLimit.MAXIMUM_CONCURRENT_COMMAND_EXECUTIONS),
                values.maximumParallel());
        assertEquals(CONTRACT.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS),
                values.maximumRetries());
        assertEquals(CONTRACT.value(ContractLimit.RETRY_BASE_MILLISECONDS),
                values.retryDelayMilliseconds());
        assertEquals("rs/slingshot/agent/command", CommandJobTopic.TOPIC);
        assertEquals(5, CommandJobTopic.PROPERTIES.size(), "a job property was added or lost");
    }

    @Test
    @DisplayName("an immediate row is never enqueued, and a job naming one is dropped")
    void animmediateRowNeverReachesAQueue() throws RepositoryException {
        final Session session = withRecord();
        assertEquals(JobEnqueue.Refusal.NOT_DEFERRED,
                JobEnqueue.refusalIn(JobEnqueue.of(session, identity(),
                        CommandJobTopic.ExecutionClass.IMMEDIATE, 0)).orElseThrow().refusal());
        final Map<String, Object> job = new LinkedHashMap<>(JobEnqueue.properties(identity()));
        job.put(CommandJobTopic.EXECUTION_CLASS,
                CommandJobTopic.ExecutionClass.IMMEDIATE.spelling());
        assertEquals(CommandJobConsumer.Verdict.DROPPED_NOT_DEFERRED, consume(session, job),
                "a job naming an immediate row reached execution");
    }

    @Test
    @DisplayName("a deferred row is enqueued only once it is durable and its payloads have arrived")
    void adeferredRowIsEnqueuedAfterTheDurableWrite() throws RepositoryException {
        final Session session = prepared();
        assertEquals(JobEnqueue.Refusal.NOT_RECORDED,
                JobEnqueue.refusalIn(JobEnqueue.of(session, identity(),
                        CommandJobTopic.ExecutionClass.DEFERRED, 0)).orElseThrow().refusal(),
                "a job was sent for work nothing durable holds");
        record(session);
        assertEquals(JobEnqueue.Refusal.INTAKE_OUTSTANDING,
                JobEnqueue.refusalIn(JobEnqueue.of(session, identity(),
                        CommandJobTopic.ExecutionClass.DEFERRED, 2)).orElseThrow().refusal(),
                "a command was started against payloads that had not arrived");
        final JobEnqueue.Enqueued enqueued = assertInstanceOf(JobEnqueue.Enqueued.class,
                JobEnqueue.of(session, identity(), CommandJobTopic.ExecutionClass.DEFERRED, 0),
                "a durable deferred row was not handed to the job system");
        assertEquals(CommandJobTopic.TOPIC, enqueued.topic());
        assertEquals(List.copyOf(CommandJobTopic.PROPERTIES).stream().sorted().toList(),
                enqueued.properties().keySet().stream().sorted().toList(),
                "a job carries something other than what is needed to find the record again");
    }

    @Test
    @DisplayName("the record survives a fault taken between the durable write and the enqueue")
    void therecordSurvivesAFaultBeforeTheEnqueue() throws RepositoryException {
        final Session session = prepared();
        GenerationStore.establish(session);
        record(session);
        assertInstanceOf(OperationStore.Held.class, OperationStore.read(session, identity()),
                "a fault between the write and the enqueue left nothing to recover");
        assertEquals(0, Outbox.attemptsFor(session, identity()),
                "a job existed for work that was never handed over");
        assertInstanceOf(JobEnqueue.Enqueued.class, JobEnqueue.of(session, identity(),
                CommandJobTopic.ExecutionClass.DEFERRED, 0),
                "recovery could not hand over a record the store still holds");
    }

    @Test
    @DisplayName("an operation waits for its last declared payload and no earlier")
    void anoperationWaitsForItsLastPayload() throws RepositoryException {
        final Session session = withRecord();
        for (final long outstanding : new long[] {2, 1}) {
            assertEquals(JobEnqueue.Refusal.INTAKE_OUTSTANDING,
                    JobEnqueue.refusalIn(JobEnqueue.of(session, identity(),
                            CommandJobTopic.ExecutionClass.DEFERRED, outstanding))
                            .orElseThrow().refusal(),
                    outstanding + " payloads outstanding did not hold the operation back");
        }
        assertInstanceOf(JobEnqueue.Enqueued.class, JobEnqueue.of(session, identity(),
                CommandJobTopic.ExecutionClass.DEFERRED, 0),
                "completing the last declared slot was not what enqueued it");
    }

    @Test
    @DisplayName("no command this build ships is deferred, so the queue stays empty")
    void noshippedCommandIsDeferred() throws RepositoryException {
        assertEquals(List.of(), CommandJobTopic.DEFERRED_COMMANDS);
        final Session session = withRecord();
        for (final String wireName : List.of("query_paths", "read_content", "write_content",
                "publish_content", "read_platform_state")) {
            assertEquals(CommandJobTopic.ExecutionClass.IMMEDIATE,
                    CommandJobTopic.executionClassOf(wireName), wireName + " would reach a queue");
            assertEquals(JobEnqueue.Refusal.NOT_DEFERRED,
                    JobEnqueue.refusalIn(JobEnqueue.of(session, identity(),
                            CommandJobTopic.executionClassOf(wireName), 0)).orElseThrow()
                            .refusal());
        }
    }

    @Test
    @DisplayName("a delivery of finished work is recorded as an attempt and runs nothing")
    void adeliveryOfFinishedWorkRunsNothing() throws RepositoryException {
        final Session session = withRecord();
        final LogicalOperation accepted = assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation();
        final LogicalOperation running = assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, accepted, OperationState.RUNNING)).operation();
        assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, running, OperationState.SUCCEEDED));
        assertEquals(CommandJobConsumer.Verdict.ALREADY_FINISHED,
                CommandJobConsumer.consume(session, delivery("a-redelivery"),
                        JobEnqueue.properties(identity()), CONTRACT,
                        (operation, holder) -> {
                            throw new IllegalStateException("finished work was executed again");
                        }));
        assertEquals(1, Outbox.attemptsFor(session, identity()),
                "the redelivery was not recorded as an attempt");
    }

    @Test
    @DisplayName("a delivery past the attempt bound is dropped rather than tried once more")
    void adeliveryPastTheAttemptBoundIsDropped() throws RepositoryException {
        final Session session = withRecord();
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_LOGICAL_OUTBOX_ATTEMPTS);
        for (long attempt = 0; attempt < bound; attempt++) {
            consume(session, "delivery-" + attempt, JobEnqueue.properties(identity()));
        }
        assertEquals(CommandJobConsumer.Verdict.DROPPED_ATTEMPTS_EXHAUSTED,
                consume(session, "one-too-many", JobEnqueue.properties(identity())));
    }

    @Test
    @DisplayName("a delivery for a record this node serves is executed under a fence it holds")
    void adeliveryIsExecutedUnderAFence() throws RepositoryException {
        final Session session = withRecord();
        final List<FenceHolder> held = new java.util.ArrayList<>();
        final CommandJobConsumer.Verdict verdict = CommandJobConsumer.consume(session,
                delivery("a-delivery"), JobEnqueue.properties(identity()), CONTRACT,
                (operation, holder) -> {
                    held.add(holder);
                    return CommandJobConsumer.Verdict.EXECUTED;
                });
        assertEquals(CommandJobConsumer.Verdict.EXECUTED, verdict);
        assertEquals(1, held.size(), "execution ran without a fence, or more than once");
        assertEquals("a-node", held.getFirst().worker());
        assertEquals(1, Outbox.attemptsFor(session, identity()),
                "the delivery that was executed was not recorded as an attempt");
    }

    @Test
    @DisplayName("a delivery for work another node is running is left to that node")
    void adeliveryForHeldWorkIsLeftAlone() throws RepositoryException {
        final Session session = withRecord();
        ExecutionFence.take(session, identity(), "another-node", NOW, CONTRACT);
        assertEquals(CommandJobConsumer.Verdict.ANOTHER_WORKER_HOLDS_IT,
                consume(session, JobEnqueue.properties(identity())),
                "two nodes executed one operation");
    }

    @Test
    @DisplayName("an unknown operation, a foreign generation, and malformed properties are dropped")
    void thethreeDroppedKindsAreDistinct() throws RepositoryException {
        final Session session = prepared();
        GenerationStore.establish(session);
        assertEquals(CommandJobConsumer.Verdict.DROPPED_UNKNOWN_OPERATION,
                consume(session, JobEnqueue.properties(identity())));
        record(session);
        final Map<String, Object> foreign =
                new LinkedHashMap<>(JobEnqueue.properties(identity()));
        foreign.put(CommandJobTopic.GENERATION, 9L);
        assertEquals(CommandJobConsumer.Verdict.DROPPED_FOREIGN_GENERATION,
                consume(session, foreign));
        final Map<String, Object> malformed =
                new LinkedHashMap<>(JobEnqueue.properties(identity()));
        malformed.put(CommandJobTopic.IDENTIFIER, "not-an-identifier");
        assertEquals(CommandJobConsumer.Verdict.DROPPED_MALFORMED, consume(session, malformed));
        assertEquals(CommandJobConsumer.Verdict.DROPPED_MALFORMED,
                consume(session, Map.of(CommandJobTopic.EXECUTION_CLASS, "teleported")));
        assertEquals(CommandJobConsumer.Verdict.DROPPED_MALFORMED,
                consume(session, "", JobEnqueue.properties(identity())),
                "a delivery with no name of its own was recorded against something");
    }

    @Test
    @DisplayName("what a job carries is read back as the values it names")
    void whatAJobCarriesIsReadBack() {
        final Map<String, Object> job = JobEnqueue.properties(identity());
        assertEquals(identity().identifier().rendered(),
                CommandJobConsumer.identifierIn(job, CONTRACT).orElseThrow().rendered());
        assertEquals(EventStoreGeneration.FIRST,
                CommandJobConsumer.generationIn(job).orElseThrow().number());
        assertEquals(identity().targetDigest().rendered(),
                CommandJobConsumer.targetIn(job).orElseThrow().rendered());
        assertTrue(CommandJobConsumer.identifierIn(Map.of(), CONTRACT).isEmpty());
        assertTrue(CommandJobConsumer.generationIn(Map.of()).isEmpty());
        assertTrue(CommandJobConsumer.targetIn(Map.of()).isEmpty());
        assertEquals(java.util.Optional.of(CommandJobTopic.ExecutionClass.DEFERRED),
                CommandJobTopic.ExecutionClass.named("deferred"));
        assertTrue(CommandJobTopic.ExecutionClass.named("later, maybe").isEmpty());
    }

    private CommandJobConsumer.Verdict consume(Session session, Map<String, Object> job)
            throws RepositoryException {
        return consume(session, "a-delivery", job);
    }

    private CommandJobConsumer.Verdict consume(Session session, String jobIdentifier,
                                               Map<String, Object> job)
            throws RepositoryException {
        return CommandJobConsumer.consume(session, delivery(jobIdentifier), job, CONTRACT,
                (operation, holder) -> CommandJobConsumer.Verdict.EXECUTED);
    }

    private static CommandJobConsumer.Delivery delivery(String jobIdentifier) {
        return new CommandJobConsumer.Delivery(jobIdentifier, "a-node", NOW);
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

    private void record(Session session) throws RepositoryException {
        OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                LogicalOperation.accepted(identity(), digest("a submission"), commandContract(),
                        caller(), NOW, NOW, CONTRACT)).operation());
    }

    private Session withRecord() throws RepositoryException {
        final Session session = prepared();
        GenerationStore.establish(session);
        record(session);
        return session;
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-submitting-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
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
