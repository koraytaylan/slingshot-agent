// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

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
import java.util.concurrent.atomic.AtomicInteger;
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
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationState;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.TerminalCommit;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.StatePath;

/**
 * One route, one record, and an answer whose every value came out of that record.
 *
 * <p>The resend test is the one the client's whole recovery story rests on: a daemon that crashed
 * after sending and before recording compares the bytes it gets back, so two acknowledgements for
 * one submission have to be the same bytes rather than merely the same meaning.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class SubmitServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/submit-servlet");

    private static final AgentContract CONTRACT = contract();

    /** The command this suite's own build runs, which is the one the fixtures name. */
    private static final String SERVED = "query_paths";

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a first submission is acknowledged with the record's own values")
    void afirstSubmissionIsAcknowledged() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final Counting commands = new Counting();
        final var first = answering(commands, "a-submission.json");
        final String answered = first.getOutputAsString();
        assertEquals(SubmitServlet.ACCEPTED, first.getStatus(), answered);
        assertTrue(answered.contains("\"already_accepted\":false"), answered);
        assertTrue(answered.contains("\"" + SubmissionResponse.IDENTIFIER + "\":\""
                        + identifierIn("a-submission.json") + "\""), answered);
        assertTrue(answered.contains("\"" + SubmissionResponse.GENERATION + "\":1"), answered);
        assertEquals(1, commands.ran(), "the command did not run inside its own request");
        assertEquals(OperationState.SUCCEEDED, stored(session, "a-submission.json").state(),
                "the operation was not terminal before its acknowledgement was written");
        assertTrue(TerminalCommit.answerIn(session,
                        OperationStore.pathOf(stored(session, "a-submission.json").identity()))
                        .isPresent(),
                "the answer the command produced was not committed with the operation");
    }

    @Test
    @DisplayName("a resend is answered byte for byte with what the first submission produced")
    void aresendIsAnsweredByteForByte() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final Counting commands = new Counting();
        final String first = submit(commands, "a-submission.json");
        final String again = submit(commands, "a-submission.json");
        assertNotEquals(first, again,
                "a resend is answered identically except for the one member that says it is one");
        assertEquals(first.replace("\"already_accepted\":false", "\"already_accepted\":true"),
                again, "a resend was answered with something other than the record it recognised");
        assertEquals(1, commands.ran(), "a resend ran the work a second time");
        assertEquals(1, records(session), "a resend created a second record");
    }

    @Test
    @DisplayName("a submission naming other work under one identifier is a conflict, and nothing moves")
    void anothercommandUnderOneIdentifierIsAconflict()
            throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final Counting commands = new Counting();
        submit(commands, "a-submission.json");
        final String before = written(session, "a-submission.json");
        for (final String conflicting : List.of("another-command.json", "another-target.json")) {
            final var answered = answering(commands, conflicting);
            assertEquals(SubmitServlet.CONFLICT, answered.getStatus(),
                    conflicting + " was not refused as a conflict");
            assertEquals("", answered.getOutputAsString(),
                    conflicting + " was answered with something rather than refused");
        }
        assertEquals(before, written(session, "a-submission.json"),
                "a refused conflict changed the record it conflicted with");
        assertEquals(1, commands.ran(), "a refused conflict ran the work");
    }

    @Test
    @DisplayName("a command this build does not run is refused before anything is written")
    void acommandThisBuildDoesNotRunIsRefused() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final Counting commands = new Counting();
        assertEquals(SubmitServlet.REFUSED,
                answering(commands, "an-unserved-command.json").getStatus());
        assertEquals(0, commands.ran());
        assertEquals(0, records(session), "a command nothing runs left a record behind");
    }

    @Test
    @DisplayName("a key that is not the one this side derived is refused, disclosing neither")
    void akeyThatIsNotTheDerivedOneIsRefused() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(submissionBytes("a-submission.json"));
        request.setHeader(SubmitServlet.IDEMPOTENCY_KEY, "0".repeat(A_DIGEST_IN_CHARACTERS));
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(SubmitServlet.route().path());
        final MockSlingHttpServletResponse answered = new MockSlingHttpServletResponse();
        new SubmitServlet(new Counting()).service(request, answered);
        assertEquals(SubmitServlet.REFUSED, answered.getStatus());
        assertEquals("", answered.getOutputAsString(), "a refusal disclosed a key");
        assertEquals(0, records(session), "a refused key left a record behind");
    }

    /** How many characters a digest is written in. */
    private static final int A_DIGEST_IN_CHARACTERS = 64;

    @Test
    @DisplayName("two requests racing to start one operation produce exactly one execution")
    void tworequestsRacingProduceOneExecution() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final Counting commands = new Counting();
        final LogicalOperation accepted = admitted(session, "a-submission.json");
        assertInstanceOf(OperationStore.Held.class,
                OperationStore.move(session, accepted, OperationState.RUNNING),
                "the first request could not start the operation");
        final var second = answering(commands, "a-submission.json");
        final String answered = second.getOutputAsString();
        assertEquals(SubmitServlet.ACCEPTED, second.getStatus(), answered);
        assertTrue(answered.contains("\"already_accepted\":true"),
                "the request that lost the start race did not answer from the record: " + answered);
        assertEquals(0, commands.ran(),
                "the request that lost the compare-and-set ran the work anyway");
    }

    @Test
    @DisplayName("an instance already holding every execution it may refuses with a capped hint")
    void aninstanceAtItsExecutionBoundRefuses() throws RepositoryException, IOException, ServletException {
        final Session session = prepared();
        final long bound = AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS
                .admissibleTotal(CONTRACT);
        for (long taken = 0; taken < bound; taken = taken + 1) {
            CapacityLedger.admit(session, AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS,
                    caller(), 1, CONTRACT);
        }
        final var answered = answering(new Counting(), "a-submission.json");
        assertEquals(SubmitServlet.AT_CAPACITY, answered.getStatus(),
                "an instance holding every execution it may took another");
        final String hint = answered.getHeader(SubmitServlet.RETRY_AFTER);
        assertTrue(hint != null && Long.parseLong(hint) > 0, "the refusal carried no hint: " + hint);
        assertTrue(Long.parseLong(hint) * 1000
                        <= CONTRACT.value(rs.slingshot.agent.contract.ContractLimit
                                .RETRY_AFTER_CAP_MILLISECONDS),
                "the hint is longer than the cap the contract declares: " + hint);
    }

    @Test
    @DisplayName("the acknowledgement carries the nine members the client knows and no tenth")
    void theacknowledgementCarriesTheNineMembers()
            throws RepositoryException, IOException, ServletException {
        prepared();
        final String answered = submit(new Counting(), "a-submission.json");
        for (final String member : SubmissionResponse.MEMBERS) {
            assertTrue(answered.contains("\"" + member + "\""),
                    member + " is not in the acknowledgement: " + answered);
        }
        assertEquals(SubmissionResponse.MEMBERS.size(),
                answered.split("\":", -1).length - 1,
                "the acknowledgement carries a member the client does not know: " + answered);
        assertEquals(9, SubmissionResponse.MEMBERS.size(), "a member was added or lost");
    }

    @Test
    @DisplayName("a build with nothing registered runs nothing and says so before it is asked")
    void abuildWithNothingRegisteredRunsNothing() {
        assertFalse(SubmitServlet.NOTHING_REGISTERED.serves(SERVED),
                "a build with no commands said it runs one");
        assertThrows(IllegalStateException.class, () -> SubmitServlet.NOTHING_REGISTERED.run(
                        null, null, null),
                "a build with no commands ran something rather than refusing before the record");
    }

    /** A build that runs one command and counts how often it was asked to. */
    private static final class Counting implements SubmitServlet.Commands {

        private static final long serialVersionUID = 1L;

        private final AtomicInteger ran = new AtomicInteger();

        @Override
        public boolean serves(String wireName) {
            return SERVED.equals(wireName);
        }

        @Override
        public ExecutionOutcome.Result run(LogicalOperation operation,
                                           DocumentValue.Mapping submission, Session session) {
            ran.incrementAndGet();
            return new ExecutionOutcome.Inline("{\"matches\":[]}");
        }

        int ran() {
            return ran.get();
        }
    }

    /**
     * Sends one submission, on a request and a response of its own.
     *
     * <p>Its own rather than the context's, because two submissions in one suite are two requests:
     * a response reused between them accumulates both answers, and a suite comparing bytes would be
     * comparing the concatenation of what it asked for twice.</p>
     *
     * @param commands what this build runs
     * @param fixture which submission
     * @return what came back
     * @throws IOException if the answer cannot be written
     * @throws javax.servlet.ServletException if answering fails for a reason that is not the request
     */
    private String submit(SubmitServlet.Commands commands, String fixture)
            throws IOException, ServletException {
        final org.apache.sling.servlethelpers.MockSlingHttpServletResponse response =
                answering(commands, fixture);
        return response.getOutputAsString();
    }

    private MockSlingHttpServletResponse answering(SubmitServlet.Commands commands, String fixture)
            throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(submissionBytes(fixture));
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(SubmitServlet.route().path());
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new SubmitServlet(commands).service(request, response);
        return response;
    }

    /**
     * One fixture's bytes, with the instant its client says its request began set to now.
     *
     * <p>The record refuses a request-start further from this side's clock than the contract
     * allows, which is a rule about clocks rather than about shapes — so the shape is the fixture's
     * and the instant is this run's.</p>
     *
     * @param fixture which submission
     * @return the bytes to send
     */
    private static byte[] submissionBytes(String fixture) {
        final String written = new String(bytes(FIXTURES.resolve(fixture)),
                StandardCharsets.UTF_8);
        return written.replaceAll("\"request_start_unix_milliseconds\": [0-9]+",
                        "\"request_start_unix_milliseconds\": " + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One operation admitted and not executed, which is what a request that lost a race finds.
     *
     * @param session the session to write under
     * @param fixture which submission
     * @return the record as admission wrote it
     * @throws RepositoryException if the repository fails
     */
    private LogicalOperation admitted(Session session, String fixture)
            throws RepositoryException {
        final DocumentValue.Mapping submission = assertInstanceOf(DocumentValue.Mapping.class,
                document(fixture), fixture + " is not an object");
        final rs.slingshot.agent.identity.DocumentProvenance provenance = assertInstanceOf(
                rs.slingshot.agent.identity.DocumentProvenance.Held.class,
                rs.slingshot.agent.identity.DocumentProvenance.of(
                        submission.member(SubmitServlet.PROVENANCE).orElseThrow(),
                        SubmitServlet.thisBuild(),
                        rs.slingshot.agent.identity.CommandContractIdentity.Bounds.from(CONTRACT)),
                fixture + " has no provenance").provenance();
        final rs.slingshot.agent.identity.SubmittedCommandDigest digest =
                rs.slingshot.agent.identity.SubmittedCommandDigest.derive(
                        provenance.commandContract(), provenance.canonicalContractDigest(),
                        provenance.transportContractDigest(),
                        "{\"under\":\"/content\"}".getBytes(StandardCharsets.UTF_8));
        final rs.slingshot.agent.digest.DigestValue key =
                new rs.slingshot.agent.identity.SubmissionBinding(
                        rs.slingshot.agent.identity.ArtifactManifestKind.EMPTY, 0, 0)
                        .keyFor(digest);
        return assertInstanceOf(rs.slingshot.agent.execution.AdmissionOutcome.Accepted.class,
                rs.slingshot.agent.execution.SubmissionAdmission.admit(session,
                        new rs.slingshot.agent.execution.SubmissionAdmission.Submission(
                                identityOf(fixture), key, provenance.commandContract(), caller(),
                                System.currentTimeMillis()),
                        System.currentTimeMillis(), CONTRACT),
                fixture + " was not admitted").operation();
    }

    private LogicalOperation stored(Session session, String fixture) throws RepositoryException {
        return assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identityOf(fixture)), "nothing holds " + fixture)
                .operation();
    }

    private static rs.slingshot.agent.identity.OperationIdentity identityOf(String fixture) {
        final DocumentValue.Mapping submission = assertInstanceOf(DocumentValue.Mapping.class,
                document(fixture), fixture + " is not an object");
        return assertInstanceOf(rs.slingshot.agent.identity.OperationIdentity.Held.class,
                rs.slingshot.agent.identity.OperationIdentity.of(
                        submission.member(SubmitServlet.OPERATION).orElseThrow(), CONTRACT),
                fixture + " names no operation").identity();
    }

    private static String identifierIn(String fixture) {
        return identityOf(fixture).identifier().rendered();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(rs.slingshot.agent.json.BoundedDocumentReader.Read.class,
                rs.slingshot.agent.json.BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        rs.slingshot.agent.json.BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private long records(Session session) throws RepositoryException {
        final String operations = StatePath.deployment(StatePath.OPERATIONS).path() + "/g1";
        if (!session.nodeExists(operations)) {
            return 0;
        }
        long held = 0;
        final javax.jcr.NodeIterator first = session.getNode(operations).getNodes();
        while (first.hasNext()) {
            final javax.jcr.NodeIterator second = first.nextNode().getNodes();
            while (second.hasNext()) {
                held = held + second.nextNode().getNodes().getSize();
            }
        }
        return held;
    }

    private String written(Session session, String fixture) throws RepositoryException {
        final Node record = session.getNode(OperationStore.pathOf(identityOf(fixture)).path());
        final StringBuilder held = new StringBuilder();
        final javax.jcr.PropertyIterator properties = record.getProperties();
        while (properties.hasNext()) {
            final javax.jcr.Property property = properties.nextProperty();
            if (!property.isMultiple()) {
                held.append(property.getName()).append('=').append(property.getString())
                        .append(' ');
            }
        }
        return held.toString();
    }

    private StatePath.Caller caller() {
        final String user = sling.resourceResolver().getUserID();
        return assertInstanceOf(StatePath.Held.class,
                StatePath.caller(user == null ? "admin" : user),
                "the caller was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        walked(session, StatePath.ROOT);
        GenerationStore.establish(session);
        LedgerAdmission.prepare(session, caller());
        CapacityLedger.prepare(session, AccountedQuantity.OPERATION_DETAIL_ROWS, caller());
        CapacityLedger.prepare(session, AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS, caller());
        walked(session, StatePath.deployment(StatePath.OPERATIONS).path());
        permitted(session);
        return session;
    }

    /**
     * Puts this suite's own caller in the group a fresh install permits.
     *
     * <p>Done through the repository's own user manager rather than around it, because the gate
     * asks the repository and a suite that answered for it would be proving something about the
     * suite.</p>
     *
     * @param session the session whose user is asking
     * @throws RepositoryException if the repository fails
     */
    private static void permitted(Session session) throws RepositoryException {
        final org.apache.jackrabbit.api.security.user.UserManager users =
                ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager();
        final org.apache.jackrabbit.api.security.user.Authorizable existing =
                users.getAuthorizable(PERMITTED_GROUP);
        final org.apache.jackrabbit.api.security.user.Group group = existing == null
                ? users.createGroup(PERMITTED_GROUP)
                : (org.apache.jackrabbit.api.security.user.Group) existing;
        group.addMember(java.util.Objects.requireNonNull(
                users.getAuthorizable(session.getUserID()),
                "this repository has no authorizable for the user its own session is"));
        session.save();
    }

    /** The group a fresh install permits, which this suite's caller is put in. */
    private static final String PERMITTED_GROUP = "administrators";

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
