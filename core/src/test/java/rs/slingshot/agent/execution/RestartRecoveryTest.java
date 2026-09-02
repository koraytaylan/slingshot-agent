// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

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
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.MaintenanceSweep;
import rs.slingshot.agent.store.RetentionPolicy;
import rs.slingshot.agent.store.StatePath;

/**
 * What the store says about unfinished work, and the seven things it can say.
 *
 * <p>Every fixture is one row of the matrix — state, how long ago it started, whether a lease is
 * live, whether its payloads arrived, how many deliveries it has had — and the suite asserts that
 * each gets exactly one answer and that the store is exactly as it was afterwards. The last part is
 * the one worth insisting on: recovery that quietly started something would be recovery inventing a
 * durable thing under nobody's identity.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class RestartRecoveryTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/restart-recovery");

    private static final AgentContract CONTRACT = contract();

    private static final long REQUEST_START = 1788000000000L;

    /** How large one declared payload is in these fixtures. */
    private static final long DECLARED_BYTES = 4096;

    private static final long BUDGET =
            CONTRACT.value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS)
                    + CONTRACT.value(ContractLimit.RECOVERY_UNDETERMINED_MARGIN_MILLISECONDS);

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every operation gets exactly one of the seven answers")
    void everyoperationGetsExactlyOneAnswer() throws RepositoryException {
        final Session session = stored();
        final RestartRecovery.Reconciliation found = RestartRecovery.reconcile(session,
                generation(), REQUEST_START + BUDGET, CONTRACT);
        assertEquals(fixtures().size(), found.examined(),
                "reconciliation did not look at every operation there is");
        assertEquals(fixtures().size(), found.findings().size(),
                "one operation got more than one answer, or none");
        assertEquals(Map.of(
                        "accepted-never-started", RecoveryDisposition.RESTARTABLE,
                        "started-inside-the-budget", RecoveryDisposition.STILL_RUNNING,
                        "started-past-the-budget", RecoveryDisposition.UNDETERMINED,
                        "lease-is-live", RecoveryDisposition.RUNNING_ELSEWHERE,
                        "lease-has-expired", RecoveryDisposition.UNDETERMINED,
                        "intake-never-completed", RecoveryDisposition.AWAITING_INTAKE,
                        "intake-still-arriving", RecoveryDisposition.AWAITING_INTAKE,
                        "ended-with-a-job-queued", RecoveryDisposition.FINISHED,
                        "attempts-exhausted", RecoveryDisposition.UNDETERMINED),
                dispositions(found), "the matrix does not decide what this build decides");
        assertEquals(7, RecoveryDisposition.values().length, "an answer was added or lost");
        assertTrue(RecoveryDisposition.named("started-again").isEmpty());
        assertEquals(RecoveryDisposition.FINISHED,
                RecoveryDisposition.named("finished").orElseThrow());
    }

    @Test
    @DisplayName("a started operation is undetermined past its budget and left alone inside it")
    void astartedOperationIsUndeterminedPastItsBudget() throws RepositoryException {
        final Session session = stored();
        final String started = "started-past-the-budget";
        assertEquals(RecoveryDisposition.STILL_RUNNING,
                disposition(session, started, REQUEST_START + BUDGET - 1),
                "an operation one millisecond inside its budget was given up on");
        assertEquals(RecoveryDisposition.UNDETERMINED,
                disposition(session, started, REQUEST_START + BUDGET),
                "an operation at exactly its budget was said to be still running");
        assertEquals(RecoveryDisposition.UNDETERMINED,
                disposition(session, started, REQUEST_START + BUDGET + 1));
    }

    @Test
    @DisplayName("recovery leaves everything exactly as it found it")
    void recoveryLeavesEverythingAsItFoundIt() throws RepositoryException {
        final Session session = stored();
        final String before = written(session);
        RestartRecovery.reconcile(session, generation(), REQUEST_START + BUDGET, CONTRACT);
        RestartRecovery.reconcile(session, generation(), REQUEST_START + BUDGET, CONTRACT);
        assertEquals(before, written(session),
                "reconciliation changed the store, which is how it invents work nobody asked for");
    }

    @Test
    @DisplayName("an intake past its retention is abandoned and gives back what it reserved")
    void anintakePastItsRetentionIsAbandoned() throws RepositoryException {
        final Session session = stored();
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, caller());
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, caller());
        // Two intakes are outstanding in this store, so two reservations are what it is holding.
        CapacityLedger.admit(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, caller(), 2,
                CONTRACT);
        CapacityLedger.admit(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, caller(),
                2 * DECLARED_BYTES, CONTRACT);
        final long past = REQUEST_START
                + RetentionPolicy.Kind.OPERATION_DETAIL.minimum(CONTRACT);
        assertEquals(RecoveryDisposition.AWAITING_INTAKE,
                disposition(session, "intake-never-completed", past - 1),
                "an intake still inside its retention was abandoned");
        assertEquals(RecoveryDisposition.ABANDONED,
                disposition(session, "intake-never-completed", past),
                "an intake nobody is waiting for was left outstanding forever");
        assertEquals(List.of(), RestartRecovery.reconcile(session, generation(), past, CONTRACT)
                        .with(RecoveryDisposition.ABANDONED),
                "a second pass abandoned the same intake again, releasing what it already gave"
                        + " back");
        assertEquals(0, CapacityLedger.held(session,
                AccountedQuantity.OPERATION_RESERVATION_ROWS, CONTRACT),
                "an abandoned intake kept the row it reserved");
        assertEquals(0, CapacityLedger.held(session,
                AccountedQuantity.OPERATION_RESERVATION_BYTES, CONTRACT),
                "an abandoned intake kept the bytes it reserved");
    }

    @Test
    @DisplayName("nothing here executes anything, obtains a session, or makes an identifier")
    void nothingHereExecutesOrIdentifies() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/execution/RestartRecovery.java"));
        for (final String reaching : List.of("login", "getResourceResolver", "impersonate",
                "OperationStore.create", "SubmissionAdmission", "JobEnqueue",
                "CommandJobConsumer", "Digest.of", "ExecutionFence.take")) {
            assertFalse(source.contains(reaching),
                    "recovery reaches for " + reaching + ", which is not classifying");
        }
        assertTrue(source.contains("ContractLimit.RECOVERY_RECONCILIATION_INTERVAL_MILLISECONDS"),
                "the interval is not read from the contract");
        assertTrue(source.contains("ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS"),
                "the execution budget is not read from the contract");
        assertFalse(source.contains("= 60000") || source.contains("= 25000"),
                "a value the contract declares is written here as well");
        assertEquals(CONTRACT.value(ContractLimit.RECOVERY_RECONCILIATION_INTERVAL_MILLISECONDS),
                RestartRecovery.intervalMilliseconds(CONTRACT));
    }

    @Test
    @DisplayName("the identifiers the store holds are the same ones after reconciliation")
    void theidentifierSetIsUnchanged() throws RepositoryException {
        final Session session = stored();
        final List<String> before = identifiers(session);
        RestartRecovery.reconcile(session, generation(), REQUEST_START + BUDGET, CONTRACT);
        assertEquals(before, identifiers(session),
                "reconciliation made an operation nobody submitted");
        assertEquals(fixtures().size(), before.size());
    }

    @Test
    @DisplayName("an operation that ended stays ended, and one under a live lease is untouched")
    void anendedOperationStaysEnded() throws RepositoryException {
        final Session session = stored();
        final String ended = written(session, "ended-with-a-job-queued");
        final String leased = written(session, "lease-is-live");
        assertEquals(RecoveryDisposition.FINISHED,
                disposition(session, "ended-with-a-job-queued", REQUEST_START + BUDGET));
        assertEquals(RecoveryDisposition.RUNNING_ELSEWHERE,
                disposition(session, "lease-is-live", REQUEST_START + BUDGET));
        assertEquals(ended, written(session, "ended-with-a-job-queued"),
                "an operation that ended was changed by recovery");
        assertEquals(leased, written(session, "lease-is-live"),
                "an operation another node is running was changed by recovery");
        assertTrue(session.nodeExists(operation("lease-is-live").child(MaintenanceSweep.LEASE)
                        .path()), "the lease another node holds was taken away");
    }

    private Map<String, RecoveryDisposition> dispositions(
            RestartRecovery.Reconciliation found) {
        final Map<String, RecoveryDisposition> byFixture = new java.util.LinkedHashMap<>();
        for (final RestartRecovery.Finding finding : found.findings()) {
            for (final String fixture : fixtures()) {
                if (finding.operation().path().equals(operation(fixture).path())) {
                    byFixture.put(fixture, finding.disposition());
                }
            }
        }
        return byFixture;
    }

    private RecoveryDisposition disposition(Session session, String fixture, long now)
            throws RepositoryException {
        return RestartRecovery.reconcile(session, generation(), now, CONTRACT).findings().stream()
                .filter(finding -> finding.operation().path().equals(operation(fixture).path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fixture + " got no answer at all"))
                .disposition();
    }

    private static List<String> fixtures() {
        return List.of("accepted-never-started", "started-inside-the-budget",
                "started-past-the-budget", "lease-is-live", "lease-has-expired",
                "intake-never-completed", "intake-still-arriving", "ended-with-a-job-queued",
                "attempts-exhausted");
    }

    private String written(Session session) throws RepositoryException {
        final StringBuilder held = new StringBuilder();
        for (final String fixture : fixtures()) {
            held.append(fixture).append('=').append(written(session, fixture)).append('\n');
        }
        return held.toString();
    }

    private String written(Session session, String fixture) throws RepositoryException {
        final Node record = session.getNode(operation(fixture).path());
        final StringBuilder held = new StringBuilder();
        final javax.jcr.PropertyIterator properties = record.getProperties();
        while (properties.hasNext()) {
            final javax.jcr.Property property = properties.nextProperty();
            if (!property.isMultiple()) {
                held.append(property.getName()).append('=').append(property.getString())
                        .append(' ');
            }
        }
        final javax.jcr.NodeIterator children = record.getNodes();
        while (children.hasNext()) {
            held.append(children.nextNode().getName()).append(' ');
        }
        return held.toString();
    }

    private List<String> identifiers(Session session) throws RepositoryException {
        return RestartRecovery.reconcile(session, generation(), REQUEST_START, CONTRACT)
                .findings().stream()
                .map(finding -> finding.operation().path())
                .sorted()
                .toList();
    }

    private Session stored() throws RepositoryException {
        final Session session = prepared();
        for (final String fixture : fixtures()) {
            OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                    LogicalOperation.accepted(identity(fixture),
                            Digest.of(fixture.getBytes(StandardCharsets.UTF_8)), commandContract(),
                            caller(), REQUEST_START, REQUEST_START, CONTRACT),
                    fixture + " was refused").operation());
        }
        shape(session);
        return session;
    }

    private void shape(Session session) throws RepositoryException {
        state(session, "started-inside-the-budget", OperationState.RUNNING);
        state(session, "started-past-the-budget", OperationState.RUNNING);
        state(session, "lease-is-live", OperationState.RUNNING);
        state(session, "lease-has-expired", OperationState.RUNNING);
        state(session, "ended-with-a-job-queued", OperationState.SUCCEEDED);
        startedAt(session, "started-inside-the-budget", REQUEST_START + BUDGET - 1);
        startedAt(session, "started-past-the-budget", REQUEST_START);
        startedAt(session, "lease-has-expired", REQUEST_START);
        lease(session, "lease-is-live", REQUEST_START + BUDGET + 1);
        lease(session, "lease-has-expired", REQUEST_START + 1);
        intake(session, "intake-never-completed");
        intake(session, "intake-still-arriving");
        attempts(session, "attempts-exhausted",
                CONTRACT.value(ContractLimit.MAXIMUM_LOGICAL_OUTBOX_ATTEMPTS));
        session.save();
    }

    private void state(Session session, String fixture, OperationState state)
            throws RepositoryException {
        session.getNode(operation(fixture).path())
                .setProperty(OperationStore.STATE, state.spelling());
    }

    private void attempts(Session session, String fixture, long attempts)
            throws RepositoryException {
        session.getNode(operation(fixture).path()).setProperty(OperationStore.ATTEMPTS, attempts);
    }

    private void lease(Session session, String fixture, long heldUntil)
            throws RepositoryException {
        final Node record = session.getNode(operation(fixture).path());
        final Node lease = record.hasNode(MaintenanceSweep.LEASE)
                ? record.getNode(MaintenanceSweep.LEASE)
                : record.addNode(MaintenanceSweep.LEASE, "nt:unstructured");
        lease.setProperty(MaintenanceSweep.LEASE_HELD_UNTIL, heldUntil);
    }

    private void intake(Session session, String fixture) throws RepositoryException {
        final Node record = session.getNode(operation(fixture).path());
        final Node intake = record.hasNode(RestartRecovery.INTAKE)
                ? record.getNode(RestartRecovery.INTAKE)
                : record.addNode(RestartRecovery.INTAKE, "nt:unstructured");
        intake.addNode("payload", "nt:unstructured")
                .setProperty(RestartRecovery.DECLARED_BYTES, DECLARED_BYTES);
    }

    private void startedAt(Session session, String fixture, long startedAt)
            throws RepositoryException {
        final Node record = session.getNode(operation(fixture).path());
        final Node ledger = record.hasNode(rs.slingshot.agent.store.EventLedger.NODE)
                ? record.getNode(rs.slingshot.agent.store.EventLedger.NODE)
                : record.addNode(rs.slingshot.agent.store.EventLedger.NODE, "nt:unstructured");
        final Node event = ledger.addNode("000000000000", "nt:unstructured");
        event.setProperty(rs.slingshot.agent.store.EventLedger.KIND,
                rs.slingshot.agent.wire.JobEventKind.STARTED.spelling());
        event.setProperty(rs.slingshot.agent.store.EventLedger.WRITTEN_AT, startedAt);
        event.setProperty(rs.slingshot.agent.store.EventLedger.SEQUENCE, 0);
        event.setProperty(rs.slingshot.agent.store.EventLedger.BYTES, 0);
    }

    private static OperationIdentity identity(String fixture) {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document(fixture + ".json"), CONTRACT),
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
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-recovered-caller"),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        for (final String fixture : fixtures()) {
            final String path = operation(fixture).path();
            walked(session, path.substring(0, path.lastIndexOf('/')));
        }
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
