// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

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
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.Outbox;
import rs.slingshot.agent.execution.PhysicalAttempt;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.StatePath;

/**
 * The attempts one operation recorded, and nothing at all about the queue that carried them.
 *
 * <p>The disclosure test is the one worth insisting on: a queue's name, a topic, another
 * operation's identifier, or an address are facts about a customer's instance rather than about
 * this caller's work, and a route that answered any of them would be a way to survey the
 * instance.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class PhysicalJobServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/operation-lookup");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("no attempts, one, and several each answer exactly what the outbox holds")
    void theanswerIsExactlyWhatTheOutboxHolds() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        assertTrue(answer().contains("\"physical_sling_job_identifiers\":[]"), answer());
        recordAttempt(session, "delivery-one");
        assertTrue(answer().contains("[\"delivery-one\"]"), answer());
        recordAttempt(session, "delivery-two");
        assertTrue(answer().contains("[\"delivery-one\",\"delivery-two\"]"), answer());
        assertTrue(answer().contains("\"bounded\":false"), answer());
    }

    @Test
    @DisplayName("the match bound holds at exactly the limit and says so one past it")
    void thematchBoundHoldsAtBothSides() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_PHYSICAL_SLING_JOB_MATCHES);
        for (long attempt = 0; attempt < bound; attempt = attempt + 1) {
            recordAttempt(session, String.format("delivery-%03d", attempt));
        }
        assertTrue(answer().contains("\"bounded\":false"),
                "an answer at exactly the bound said it was cut: " + answer());
        assertEquals(bound, answer().split("\"delivery-", -1).length - 1,
                "an answer at the bound carried a different number of attempts: " + answer());
        recordAttempt(session, "delivery-past-the-bound");
        assertTrue(answer().contains("\"bounded\":true"),
                "an answer past the bound did not say it was cut: " + answer());
        assertEquals(bound, answer().split("\"delivery-", -1).length - 1,
                "an answer past the bound carried more than the bound: " + answer());
    }

    @Test
    @DisplayName("an operation nobody here holds answers exactly as one belonging to somebody else")
    void anunknownOperationAnswersAsAforeignOne() throws RepositoryException, IOException,
            ServletException {
        prepared();
        final MockSlingHttpServletResponse unknown = lookup(identifier());
        assertEquals(OperationLookupServlet.NOT_YET, unknown.getStatus());
        assertEquals("", unknown.getOutputAsString(), "a refusal carried a body");
    }

    @Test
    @DisplayName("nothing in an answer names a queue, a topic, or anything about the instance")
    void nothingInAnAnswerNamesTheQueue() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        recordAttempt(session, "delivery-one");
        final String answered = answer();
        for (final String disclosing : List.of("queue", "topic", "rs/slingshot", "http",
                "localhost", A_LOOPBACK_ADDRESS, "/var/slingshot")) {
            assertFalse(answered.contains(disclosing),
                    "an answer disclosed " + disclosing + ": " + answered);
        }
        assertEquals(4, answered.split("\":", -1).length - 1,
                "an answer carries a member this build did not mean to answer with: " + answered);
    }

    @Test
    @DisplayName("an identifier this build does not read is refused rather than waited on")
    void anidentifierThisBuildDoesNotReadIsRefused() throws RepositoryException, IOException,
            ServletException {
        prepared();
        assertEquals(OperationLookupServlet.REFUSED, lookup("not-an-identifier").getStatus());
        assertEquals(OperationLookupServlet.REFUSED, lookup("").getStatus());
    }

    @Test
    @DisplayName("a request from nobody in particular is refused before the store is read")
    void arequestFromNobodyIsRefused() throws RepositoryException, IOException, ServletException {
        prepared();
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(anonymous.resourceResolver());
        request.setMethod("GET");
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(PhysicalJobServlet.route().path());
        request.setParameterMap(java.util.Map.of(
                PhysicalJobServlet.OPERATION_QUERY_MEMBER, identifier()));
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new PhysicalJobServlet().service(request, response);
        assertEquals(AuthenticationGate.STATUS, response.getStatus(),
                "a request from nobody in particular was answered");
    }

    private final SlingContext anonymous =
            new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    /** An address a transport would be reached at, which no answer may name. */
    private static final String A_LOOPBACK_ADDRESS = String.join(".", "127", "0", "0", "1");

    private String answer() throws RepositoryException, IOException, ServletException {
        return lookup(identifier()).getOutputAsString();
    }

    private MockSlingHttpServletResponse lookup(String identifier)
            throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("GET");
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(PhysicalJobServlet.route().path());
        request.setParameterMap(java.util.Map.of(
                PhysicalJobServlet.OPERATION_QUERY_MEMBER, identifier));
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new PhysicalJobServlet().service(request, response);
        return response;
    }

    private void recordAttempt(Session session, String jobIdentifier) throws RepositoryException {
        final PhysicalAttempt attempt = assertInstanceOf(PhysicalAttempt.Held.class,
                PhysicalAttempt.of(jobIdentifier, "a-node", NOW, CONTRACT),
                jobIdentifier + " is not an attempt").attempt();
        assertInstanceOf(Outbox.Wrote.class, Outbox.record(session, identity(), attempt, CONTRACT),
                jobIdentifier + " was not recorded");
    }

    private Session recorded() throws RepositoryException {
        final Session session = prepared();
        OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                LogicalOperation.accepted(identity(), Digest.of(
                                "a submission".getBytes(StandardCharsets.UTF_8)), commandContract(),
                        caller(), NOW, NOW, CONTRACT), "the record was refused").operation());
        return session;
    }

    private static OperationIdentity identity() {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(assertInstanceOf(DocumentValue.Mapping.class,
                                document("a-submission.json")).member("operation").orElseThrow(),
                        CONTRACT), "the operation identity was refused").identity();
    }

    private static String identifier() {
        return identity().identifier().rendered();
    }

    private static CommandContractIdentity commandContract() {
        final DocumentValue.Mapping provenance = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(DocumentValue.Mapping.class, document("a-submission.json"))
                        .member("provenance").orElseThrow());
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(provenance.member("command_contract").orElseThrow(),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                "the command contract was refused").identity();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
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
        final String operation = OperationStore.pathOf(identity()).path();
        walked(session, operation.substring(0, operation.lastIndexOf('/')));
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

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
