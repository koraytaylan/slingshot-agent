// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.ServletException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.OperationState;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.StatePath;

/**
 * A payload that arrives against what its own manifest already declared.
 *
 * <p>The digest test is the one worth reading: bytes that are not the ones declared leave nothing
 * reachable and leave the slot claimable again, because a partial payload under a record that looks
 * complete is a fault every later reader of that artifact inherits.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class ArtifactIntakeServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/artifact-intake");

    private static final AgentContract CONTRACT = contract();

    /** The slot this suite's own manifest declares. */
    private static final String SLOT = "payload";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);


    @BeforeEach
    void configureOperators() {
        MockOsgi.activate(new AuthorizationGate(), MockOsgi.newBundleContext(),
                java.util.Map.of("permitted.groups", new String[] { "administrators" }));
    }

    @AfterEach
    void revokeOperators() {
        new AuthorizationGate().stopped();
    }

    @BeforeEach
    void bindStateSource() {
        new rs.slingshot.agent.repository.AgentSession().available(
                sling.getService(org.apache.sling.api.resource.ResourceResolverFactory.class));
    }

    @AfterEach
    void stopStateSource() {
        new rs.slingshot.agent.repository.AgentSession().stopped();
    }

    @Test
    @DisplayName("a submission declaring a payload is acknowledged and does not start")
    void asubmissionDeclaringApayloadDoesNotStart() throws RepositoryException, IOException,
            ServletException {
        final Session session = submitted();
        capacity(session, 1);
        assertEquals(OperationState.ACCEPTED, stored(session).state(),
                "a command started against a payload that had not arrived");
        assertEquals(1, IntakeSlotWrite.outstanding(session, operation()),
                "the store is not waiting for the payload the manifest declared");
    }

    @Test
    @DisplayName("a declared payload is taken and reads back byte for byte")
    void adeclaredPayloadIsTakenAndReadsBack() throws RepositoryException, IOException,
            ServletException {
        final Session session = submitted();
        assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), SLOT).getStatus());
        capacity(session, 0);
        assertEquals(0, IntakeSlotWrite.outstanding(session, operation()),
                "the store is still waiting for a payload it has");
        try (InputStream held = ArtifactStore.open(session, operation(), slot(SLOT))
                .orElseThrow()) {
            assertArrayEquals(payload(), held.readAllBytes(),
                    "what came back is not what was sent");
        }
    }

    @Test
    @DisplayName("bytes that are not the ones declared leave nothing behind and the slot open")
    void bytesThatAreNotTheOnesDeclaredLeaveNothing() throws RepositoryException, IOException,
            ServletException {
        final Session session = submitted();
        final byte[] otherBytes = new byte[payload().length];
        java.util.Arrays.fill(otherBytes, (byte) 'x');
        assertEquals(ArtifactIntakeServlet.REFUSED, upload(otherBytes, SLOT).getStatus(),
                "bytes that are not the ones declared were taken");
        assertTrue(ArtifactStore.read(session, operation(), slot(SLOT)).isEmpty(),
                "a refused payload left something reachable");
        assertEquals(1, IntakeSlotWrite.outstanding(session, operation()),
                "a refused payload left the slot closed");
        capacity(session, 1);
        assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), SLOT).getStatus(),
                "the slot could not be filled after a refusal");
    }

    @Test
    @DisplayName("a body longer or shorter than declared is refused before it is all read")
    void abodyOfTheWrongLengthIsRefused() throws RepositoryException, IOException,
            ServletException {
        submitted();
        final byte[] longer = new byte[payload().length + 1];
        System.arraycopy(payload(), 0, longer, 0, payload().length);
        assertEquals(ArtifactIntakeServlet.REFUSED, upload(longer, SLOT).getStatus(),
                "a body longer than declared was taken");
        assertEquals(ArtifactIntakeServlet.REFUSED,
                upload(java.util.Arrays.copyOf(payload(), payload().length - 1), SLOT).getStatus(),
                "a body shorter than declared was taken");
    }

    @Test
    @DisplayName("a slot nothing is waiting for and an operation nobody holds are one answer")
    void aslotNothingWaitsForAnswersAsAnUnknownOperation() throws RepositoryException, IOException,
            ServletException {
        submitted();
        assertEquals(ArtifactIntakeServlet.NOT_WAITED_FOR,
                upload(payload(), "a-slot-nobody-declared").getStatus(),
                "a payload for a slot nothing is waiting for was taken");
    }

    @Test
    @DisplayName("a slot that already holds its payload is refused, and nothing is charged twice")
    void aslotThatAlreadyHoldsItsPayloadIsRefused() throws RepositoryException, IOException,
            ServletException {
        final Session session = submitted();
        assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), SLOT).getStatus());
        final long bytes = CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES, CONTRACT);
        assertEquals(ArtifactIntakeServlet.ALREADY_COMPLETE,
                upload(payload(), SLOT).getStatus(),
                "a slot that already holds its payload took another");
        assertEquals(bytes, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_BYTES,
                        CONTRACT),
                "a retried upload was charged a second time");
    }

    @Test
    @DisplayName("nothing is taken for an operation that has already ended")
    void nothingIsTakenForAnOperationThatEnded() throws RepositoryException, IOException,
            ServletException {
        final Session session = submitted();
        final Node record = session.getNode(operation().path());
        record.setProperty(OperationStore.STATE, OperationState.SUCCEEDED.spelling());
        session.save();
        assertEquals(ArtifactIntakeServlet.ALREADY_COMPLETE, upload(payload(), SLOT).getStatus(),
                "a payload was taken for an operation nothing is waiting on");
    }

    @Test
    @DisplayName("a request naming no operation, no slot, or nobody at all is refused")
    void arequestNamingNothingIsRefused() throws RepositoryException, IOException,
            ServletException {
        submitted();
        assertEquals(ArtifactIntakeServlet.REFUSED, uploading(payload(),
                java.util.Map.of(ArtifactIntakeServlet.SLOT_QUERY_MEMBER, SLOT)).getStatus(),
                "a payload naming no operation was taken");
        assertEquals(ArtifactIntakeServlet.REFUSED, uploading(payload(), java.util.Map.of(
                        ArtifactIntakeServlet.OPERATION_QUERY_MEMBER, identifier())).getStatus(),
                "a payload naming no slot was taken");
        assertEquals(ArtifactIntakeServlet.REFUSED, uploading(payload(), java.util.Map.of(
                        ArtifactIntakeServlet.OPERATION_QUERY_MEMBER, "not-an-identifier",
                        ArtifactIntakeServlet.SLOT_QUERY_MEMBER, SLOT)).getStatus(),
                "a payload naming an operation this build cannot read was taken");
        final SlingContext anonymous =
                new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(anonymous.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/octet-stream");
        request.setContent(payload());
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(ArtifactIntakeServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new ArtifactIntakeServlet().service(request, response);
        assertEquals(AuthenticationGate.STATUS, response.getStatus(),
                "a payload from nobody in particular was taken");
    }

    @Test
    @DisplayName("every intake refusal has an answer of its own, and there is no seventh")
    void everyintakeRefusalHasAnAnswer() {
        assertEquals(6, IntakeSlotWrite.IntakeRefusal.values().length,
                "an intake refusal was added or lost");
        for (final IntakeSlotWrite.IntakeRefusal refusal
                : IntakeSlotWrite.IntakeRefusal.values()) {
            final int status = ArtifactIntakeServlet.statusFor(refusal);
            assertTrue(status >= ArtifactIntakeServlet.REFUSED, refusal + " is answered with "
                    + status);
        }
        assertEquals(ArtifactIntakeServlet.NOT_WAITED_FOR, ArtifactIntakeServlet.statusFor(
                        IntakeSlotWrite.IntakeRefusal.NO_OPERATION),
                "an operation nobody holds and a slot nothing waits for are told apart");
        assertEquals(ArtifactIntakeServlet.NOT_WAITED_FOR, ArtifactIntakeServlet.statusFor(
                IntakeSlotWrite.IntakeRefusal.UNDECLARED_SLOT));
    }

    @Test
    void manifestQuotaRefusalLeavesNoAcceptedOperation() throws RepositoryException, IOException,
            ServletException {
        final Session session = prepared();
        final AccountedQuantity quantity = AccountedQuantity.OPERATION_RESERVATION_BYTES;
        final CapacityReservation pressure = assertInstanceOf(CapacityLedger.Reserved.class,
                CapacityLedger.take(session, caller(), java.util.List.of(new CapacityReservation.Charge(
                        quantity, quantity.admissibleCallerShare(CONTRACT))), CONTRACT)).reservation();
        assertEquals(SubmitServlet.AT_CAPACITY, submit(sling.resourceResolver()).getStatus());
        assertFalse(session.nodeExists(operation().path()));
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        CapacityLedger.release(session, pressure, CONTRACT);
        assertEquals(SubmitServlet.ACCEPTED, submit(sling.resourceResolver()).getStatus());
        capacity(session, 1);
    }

    @Test
    void missingPreparedCountersLeaveTheManifestUnaccepted() throws RepositoryException, IOException,
            ServletException {
        final Session session = prepared();
        final AccountedQuantity quantity = AccountedQuantity.OPERATION_RESERVATION_ROWS;
        session.getNode(CapacityLedger.callerPath(quantity, caller()).path()).remove();
        session.save();
        assertEquals(SubmitServlet.AT_CAPACITY, submit(sling.resourceResolver()).getStatus());
        assertFalse(session.nodeExists(operation().path()));
        count(session, AccountedQuantity.ARTIFACT_ROWS, 0);
        count(session, AccountedQuantity.ARTIFACT_BYTES, 0);
        CapacityLedger.prepare(session, quantity, caller());
        assertEquals(SubmitServlet.ACCEPTED, submit(sling.resourceResolver()).getStatus());
        capacity(session, 1);
    }

    @Test
    void twoSlotsAreReservedTogetherAndTransferredIndependently() throws RepositoryException, IOException,
            ServletException {
        final Session session = prepared();
        final String second = "{\"byte_count\":" + payload().length + ",\"digest\":\""
                + rs.slingshot.agent.digest.Digest.of(payload()).rendered()
                + "\",\"name\":\"second\"},";
        final String body = new String(submission(), StandardCharsets.UTF_8)
                .replace("\"artifact_bytes\": 48", "\"artifact_bytes\": 96")
                .replace("\"artifact_rows\": 1", "\"artifact_rows\": 2")
                .replace("\"slots\": [", "\"slots\": [" + second);
        assertEquals(SubmitServlet.ACCEPTED,
                submit(sling.resourceResolver(), body.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(2, IntakeSlotWrite.outstanding(session, operation()));
        count(session, AccountedQuantity.ARTIFACT_ROWS, 2);
        count(session, AccountedQuantity.ARTIFACT_BYTES, 2L * payload().length);
        count(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, 2);
        assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), SLOT).getStatus());
        count(session, AccountedQuantity.ARTIFACT_ROWS, 2);
        count(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, 1);
        count(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, payload().length);
        assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), "second").getStatus());
        count(session, AccountedQuantity.ARTIFACT_BYTES, 2L * payload().length);
        count(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, 0);
        count(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, 0);
    }

    @Test
    void interruptedAcceptanceCanBeRetriedWithoutStrandingTheManifest() throws RepositoryException,
            IOException, ServletException, LoginException {
        interruptedAcceptance(false);
    }

    @Test
    void lostAcceptanceReplyRecognisesThePublishedManifestWithoutChargingAgain() throws RepositoryException,
            IOException, ServletException, LoginException {
        interruptedAcceptance(true);
    }

    private void interruptedAcceptance(boolean committed) throws RepositoryException, IOException,
            ServletException, LoginException {
        final Session session = prepared();
        try (org.apache.sling.api.resource.ResourceResolver resolver =
                     publicationFailure(session, committed)) {
            assertEquals(javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    throughState(resolver, () -> submit(sling.resourceResolver())).getStatus());
            session.refresh(false);
            assertEquals(committed, session.nodeExists(operation().path()));
            final var subscription = assertInstanceOf(rs.slingshot.agent.store.SubscriptionRecord.Held.class,
                    rs.slingshot.agent.store.SubscriptionRecord.identifier("following-daemon-one", CONTRACT))
                    .identifier();
            assertEquals(committed, rs.slingshot.agent.store.SubscriptionLedger.read(session,
                    subscription, CONTRACT).isPresent(), "acceptance and binding must publish together");
            assertEquals(committed ? 1 : 0,
                    CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, CONTRACT));
            assertEquals(committed ? 1 : 0,
                    CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
            assertEquals(SubmitServlet.ACCEPTED, submit(sling.resourceResolver()).getStatus());
            capacity(session, 1);
        }
    }

    @Test
    void aCompetingSubscriptionClaimRollsBackOperationAndManifest()
            throws RepositoryException, IOException, ServletException, LoginException {
        final Session session = prepared();
        final var inserted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var peer = sling.resourceResolver().clone(java.util.Map.of());
                var intercepted = intercepting(session, () -> {
                    if (session.nodeExists(operation().path()) && inserted.compareAndSet(false, true)) {
                        final Session other = java.util.Objects.requireNonNull(peer.adaptTo(Session.class));
                        final var different = assertInstanceOf(
                                rs.slingshot.agent.identity.AgentOperationIdentifier.Held.class,
                                rs.slingshot.agent.identity.AgentOperationIdentifier.of(
                                "e".repeat(64), CONTRACT))
                                .identifier();
                        assertInstanceOf(rs.slingshot.agent.store.SubscriptionLedger.Subscribed.class,
                                rs.slingshot.agent.store.SubscriptionLedger.subscribe(other, caller(),
                                        "following-daemon-one", identity().generation(), different,
                                        System.currentTimeMillis(), CONTRACT));
                    }
                })) {
            assertEquals(SubmitServlet.AT_CAPACITY,
                    throughState(intercepted, () -> submit(sling.resourceResolver())).getStatus());
        }
        session.refresh(false);
        assertTrue(inserted.get(), "the test did not interleave at acceptance");
        assertFalse(session.nodeExists(operation().path()));
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.ARTIFACT_ROWS, CONTRACT));
        assertEquals(0, CapacityLedger.held(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, CONTRACT));
        assertEquals(1, CapacityLedger.held(session, AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, CONTRACT));
        assertEquals(SubmitServlet.CONFLICT, submit(sling.resourceResolver()).getStatus());
    }

    private org.apache.sling.api.resource.ResourceResolver publicationFailure(Session session,
                                                                              boolean committed)
            throws LoginException {
        final var failed = new java.util.concurrent.atomic.AtomicBoolean();
        return intercepting(session, () -> {
            if (session.nodeExists(operation().path()) && failed.compareAndSet(false, true)) {
                if (committed) {
                    session.save();
                }
                throw new RepositoryException("acceptance save interrupted");
            }
        });
    }

    private org.apache.sling.api.resource.ResourceResolver intercepting(Session session,
            rs.slingshot.agent.store.SaveInterleaving.Action before) throws LoginException {
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        final Session interrupted = (Session) java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[] {org.apache.jackrabbit.api.JackrabbitSession.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        before.run();
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        return new org.apache.sling.api.wrappers.ResourceResolverWrapper(
                sling.resourceResolver().clone(java.util.Map.of())) {
            @Override
            public <T> T adaptTo(Class<T> type) {
                return type == Session.class ? type.cast(interrupted) : super.adaptTo(type);
            }
        };
    }

    @Test
    void reservationAllocationContentionIsRetryableWithoutLosingThePromise() throws RepositoryException,
            IOException, ServletException, LoginException {
        final Session session = submitted();
        try (org.apache.sling.api.resource.ResourceResolver resolver = intercepting(session, () -> {
            throw new javax.jcr.InvalidItemStateException("capacity allocation contended");
        })) {
            assertEquals(ArtifactIntakeServlet.TEMPORARILY_UNAVAILABLE,
                    throughState(resolver, () -> upload(payload(), SLOT)).getStatus());
            capacity(session, 1);
            assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), SLOT).getStatus());
            capacity(session, 0);
        }
    }

    @Test
    void uploadCommitContentionIsRetryableWithoutConsumingItsPromise() throws RepositoryException,
            IOException, ServletException, LoginException {
        final Session session = submitted();
        try (org.apache.sling.api.resource.ResourceResolver resolver = intercepting(session, () -> {
            if (session.nodeExists(slot(SLOT).under(operation()).path())) {
                throw new javax.jcr.InvalidItemStateException("artifact publication contended");
            }
        })) {
            assertEquals(ArtifactIntakeServlet.TEMPORARILY_UNAVAILABLE,
                    throughState(resolver, () -> upload(payload(), SLOT)).getStatus());
            capacity(session, 1);
            assertEquals(ArtifactIntakeServlet.TAKEN, upload(payload(), SLOT).getStatus());
            capacity(session, 0);
        }
    }

    @FunctionalInterface
    private interface Answer {
        MockSlingHttpServletResponse run() throws IOException, ServletException;
    }

    private MockSlingHttpServletResponse throughState(
            org.apache.sling.api.resource.ResourceResolver resolver, Answer answer)
            throws IOException, ServletException {
        final var factory = (org.apache.sling.api.resource.ResourceResolverFactory)
                java.lang.reflect.Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                        new Class<?>[] { org.apache.sling.api.resource.ResourceResolverFactory.class },
                        (proxy, method, arguments) -> {
                            assertEquals("getServiceResourceResolver", method.getName());
                            return resolver;
                        });
        new rs.slingshot.agent.repository.AgentSession().available(factory);
        try {
            return answer.run();
        } finally {
            bindStateSource();
        }
    }

    private void capacity(Session session, long outstanding) throws RepositoryException {
        for (final AccountedQuantity quantity : java.util.List.of(AccountedQuantity.ARTIFACT_ROWS,
                AccountedQuantity.ARTIFACT_BYTES, AccountedQuantity.OPERATION_RESERVATION_ROWS,
                AccountedQuantity.OPERATION_RESERVATION_BYTES)) {
            final boolean reservation = quantity == AccountedQuantity.OPERATION_RESERVATION_ROWS
                    || quantity == AccountedQuantity.OPERATION_RESERVATION_BYTES;
            final boolean bytes = quantity == AccountedQuantity.ARTIFACT_BYTES
                    || quantity == AccountedQuantity.OPERATION_RESERVATION_BYTES;
            final long expected = (reservation ? outstanding : 1) * (bytes ? payload().length : 1);
            count(session, quantity, expected);
        }
    }

    private void count(Session session, AccountedQuantity quantity, long expected)
            throws RepositoryException {
        assertEquals(expected, CapacityLedger.held(session, quantity, CONTRACT), quantity.spelling());
        assertEquals(expected, CapacityLedger.heldBy(session, quantity, caller(), CONTRACT),
                quantity.spelling());
    }

    private MockSlingHttpServletResponse uploading(byte[] body,
                                                   java.util.Map<String, Object> asked)
            throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/octet-stream");
        request.setContent(body);
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(ArtifactIntakeServlet.route().path());
        request.setParameterMap(asked);
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new ArtifactIntakeServlet().service(request, response);
        return response;
    }

    private MockSlingHttpServletResponse upload(byte[] body, String slot)
            throws IOException, ServletException {
        return upload(body, slot, sling.resourceResolver());
    }

    private MockSlingHttpServletResponse upload(byte[] body, String slot,
            org.apache.sling.api.resource.ResourceResolver resolver) throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(resolver);
        request.setMethod("POST");
        request.setContentType("application/octet-stream");
        request.setContent(body);
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(ArtifactIntakeServlet.route().path());
        request.setParameterMap(java.util.Map.of(
                ArtifactIntakeServlet.OPERATION_QUERY_MEMBER, identifier(),
                ArtifactIntakeServlet.SLOT_QUERY_MEMBER, slot));
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new ArtifactIntakeServlet().service(request, response);
        return response;
    }

    private Session submitted() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final MockSlingHttpServletResponse response = submit(sling.resourceResolver());
        assertEquals(SubmitServlet.ACCEPTED, response.getStatus(), response.getOutputAsString());
        return session;
    }

    private MockSlingHttpServletResponse submit(org.apache.sling.api.resource.ResourceResolver resolver)
            throws IOException, ServletException {
        return submit(resolver, submission());
    }

    private MockSlingHttpServletResponse submit(org.apache.sling.api.resource.ResourceResolver resolver,
                                                byte[] body) throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(resolver);
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(body);
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(SubmitServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new SubmitServlet(new NothingRuns()).service(request, response);
        return response;
    }

    /** A build that serves the command these fixtures name and never has to run it. */
    private static final class NothingRuns implements SubmitServlet.Commands {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean serves(String wireName) {
            return true;
        }

        @Override
        public rs.slingshot.agent.execution.ExecutionOutcome.Completion run(
                rs.slingshot.agent.execution.LogicalOperation operation,
                DocumentValue.Mapping submission, Session session) {
            throw new IllegalStateException("a command ran while a payload was still arriving");
        }
    }

    private rs.slingshot.agent.execution.LogicalOperation stored(Session session)
            throws RepositoryException {
        return assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity()), "nothing holds the operation").operation();
    }

    private static byte[] submission() {
        final String written = new String(bytes(FIXTURES.resolve("declaring-a-payload.json")),
                StandardCharsets.UTF_8);
        return written.replaceAll("\"request_start_unix_milliseconds\": [0-9]+",
                        "\"request_start_unix_milliseconds\": " + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] payload() {
        return bytes(FIXTURES.resolve("payload.txt"));
    }

    private static ArtifactSlot slot(String named) {
        return assertInstanceOf(ArtifactSlot.Held.class, ArtifactSlot.of(named),
                named + " is not a slot").slot();
    }

    private static OperationIdentity identity() {
        final DocumentValue.Mapping submission = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                        BoundedDocumentReader.read(bytes(
                                        FIXTURES.resolve("declaring-a-payload.json")),
                                BoundedDocumentReader.Bounds.from(CONTRACT)),
                        "the submission is not a document").value());
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(submission.member("operation").orElseThrow(), CONTRACT),
                "the operation identity was refused").identity();
    }

    private static String identifier() {
        return identity().identifier().rendered();
    }

    private static StatePath operation() {
        return OperationStore.pathOf(identity());
    }

    private StatePath.Caller caller() {
        final String user = sling.resourceResolver().getUserID();
        return assertInstanceOf(StatePath.Held.class,
                StatePath.caller(user == null ? "admin" : user), "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        LedgerAdmission.prepare(session, caller());
        rs.slingshot.agent.store.SubscriptionLedger.prepare(session, caller());
        ArtifactStore.prepare(session, caller());
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_ROWS, caller());
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_RESERVATION_BYTES, caller());
        CapacityLedger.prepare(session, AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS, caller());
        final String path = operation().path();
        walked(session, path.substring(0, path.lastIndexOf('/')));
        permitted(session);
        return session;
    }

    private static void permitted(Session session) throws RepositoryException {
        final org.apache.jackrabbit.api.security.user.UserManager users =
                ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager();
        final org.apache.jackrabbit.api.security.user.Authorizable existing =
                users.getAuthorizable("administrators");
        final org.apache.jackrabbit.api.security.user.Group group = existing == null
                ? users.createGroup("administrators")
                : (org.apache.jackrabbit.api.security.user.Group) existing;
        group.addMember(java.util.Objects.requireNonNull(
                users.getAuthorizable(session.getUserID()),
                "this repository has no authorizable for the user its own session is"));
        session.save();
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

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
