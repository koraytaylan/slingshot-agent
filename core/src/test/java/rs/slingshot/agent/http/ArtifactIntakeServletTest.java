// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
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

    @Test
    @DisplayName("a submission declaring a payload is acknowledged and does not start")
    void asubmissionDeclaringApayloadDoesNotStart() throws RepositoryException, IOException,
            ServletException {
        final Session session = submitted();
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
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
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
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(submission());
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(SubmitServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new SubmitServlet(new NothingRuns()).service(request, response);
        assertEquals(SubmitServlet.ACCEPTED, response.getStatus(), response.getOutputAsString());
        return session;
    }

    /** A build that serves the command these fixtures name and never has to run it. */
    private static final class NothingRuns implements SubmitServlet.Commands {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean serves(String wireName) {
            return true;
        }

        @Override
        public rs.slingshot.agent.execution.ExecutionOutcome.Result run(
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
        ArtifactStore.prepare(session, caller());
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
