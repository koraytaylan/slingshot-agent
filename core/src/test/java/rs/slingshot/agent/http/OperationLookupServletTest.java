// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationState;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.GenerationRotation;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.LedgerAdmission;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * The two answers this route must never confuse, and the store as the only thing it reads.
 *
 * <p>"Not there yet" and "never there" are the whole point. A client waits on the first and gives
 * up on the second, so answering the wrong one either wastes its budget or abandons work that ran —
 * and what separates them here is the incarnation asked about rather than a guess about how long
 * ago somebody submitted something.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class OperationLookupServletTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/operation-lookup");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("every state answers with the snapshot the store materialised")
    void everystateAnswersWithTheSnapshot() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        for (final JobEventKind kind : java.util.List.of(JobEventKind.ACCEPTED,
                JobEventKind.STARTED, JobEventKind.SUCCEEDED)) {
            appended(session, kind);
            final MockSlingHttpServletResponse answered = lookup(identifier(), "");
            assertEquals(OperationLookupServlet.SERVED, answered.getStatus(),
                    kind + " was not answered: " + answered.getOutputAsString());
            assertEquals(snapshotDocument(session), answered.getOutputAsString(),
                    "the answer is not the snapshot the store holds");
            assertTrue(answered.getOutputAsString().contains("\"" + JobEvent.KIND + "\":\""
                            + kind.spelling() + "\""),
                    kind + " is not what the answer says: " + answered.getOutputAsString());
        }
    }

    @Test
    @DisplayName("an operation nothing holds is not yet, with the contract's own grace as the hint")
    void anoperationNothingHoldsIsNotYet() throws RepositoryException, IOException,
            ServletException {
        prepared();
        final MockSlingHttpServletResponse answered = lookup(identifier(), "");
        assertEquals(OperationLookupServlet.NOT_YET, answered.getStatus(),
                "an operation nothing holds was answered with something");
        assertEquals("", answered.getOutputAsString(), "a refusal carried a body");
        assertEquals(String.valueOf(CONTRACT.value(
                        ContractLimit.MISSING_OPERATION_GRACE_MILLISECONDS) / 1000),
                answered.getHeader(OperationLookupServlet.RETRY_AFTER),
                "the hint is not the grace the contract declares");
    }

    @Test
    @DisplayName("an incarnation nothing answers about any more is gone rather than not yet")
    void anincarnationNothingAnswersAboutIsGone() throws RepositoryException, IOException,
            ServletException {
        final Session session = recorded();
        assertEquals(OperationLookupServlet.GONE,
                lookup(identifier(), String.valueOf(A_GENERATION_NOBODY_SERVED)).getStatus(),
                "a client asking about an incarnation nothing holds was told to wait");
        assertInstanceOf(GenerationRotation.Serving.class,
                GenerationRotation.accessTo(session, generation()),
                "the incarnation this store serves is not the one it says it serves");
    }

    /** An incarnation this store has never served, which is what a client stops waiting for. */
    private static final long A_GENERATION_NOBODY_SERVED = 9;

    @Test
    @DisplayName("an identifier this build does not read is refused rather than answered")
    void anidentifierThisBuildDoesNotReadIsRefused() throws RepositoryException, IOException,
            ServletException {
        prepared();
        assertEquals(OperationLookupServlet.REFUSED, lookup("not-an-identifier", "").getStatus());
        assertEquals(OperationLookupServlet.REFUSED, lookup("", "").getStatus());
    }

    @Test
    @DisplayName("a retained incarnation is read rather than refused")
    void aretainedIncarnationIsRead() throws RepositoryException, IOException, ServletException {
        final Session session = recorded();
        appended(session, JobEventKind.SUCCEEDED);
        assertInstanceOf(GenerationRotation.Rotated.class, GenerationRotation.rotate(session,
                        generationOf(2), NOW, CONTRACT),
                "the store would not rotate");
        final MockSlingHttpServletResponse answered =
                lookup(identifier(), String.valueOf(EventStoreGeneration.FIRST));
        assertEquals(OperationLookupServlet.SERVED, answered.getStatus(),
                "work from before a rotation cannot be read, so nobody can finish reconciling it");
        assertTrue(answered.getOutputAsString().contains("\"" + JobEvent.GENERATION + "\":1"),
                answered.getOutputAsString());
    }

    private MockSlingHttpServletResponse lookup(String identifier, String generation)
            throws IOException, ServletException {
        final MockSlingHttpServletRequest request =
                new MockSlingHttpServletRequest(sling.resourceResolver());
        request.setMethod("GET");
        ((MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath(OperationLookupServlet.route().path());
        final java.util.Map<String, Object> asked = new java.util.HashMap<>();
        asked.put(OperationLookupServlet.OPERATION_QUERY_MEMBER, identifier);
        if (!generation.isEmpty()) {
            asked.put(OperationLookupServlet.GENERATION_QUERY_MEMBER, generation);
        }
        request.setParameterMap(asked);
        final MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        new OperationLookupServlet().service(request, response);
        return response;
    }

    private String snapshotDocument(Session session) throws RepositoryException {
        final SnapshotStore.Snapshot snapshot = assertInstanceOf(SnapshotStore.Known.class,
                SnapshotStore.read(session, operation()), "the store holds no snapshot").snapshot();
        final java.util.SequencedMap<String, DocumentValue> members =
                new java.util.LinkedHashMap<>();
        members.put(JobEvent.GENERATION, new DocumentValue.Whole(EventStoreGeneration.FIRST));
        members.put(JobEvent.IDENTIFIER, new DocumentValue.Text(identifier()));
        members.put(JobEvent.KIND, new DocumentValue.Text(snapshot.kind().spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(snapshot.sequence().number()));
        return assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(new DocumentValue.Mapping(members)),
                "the snapshot has no canonical form").rendered();
    }

    private void appended(Session session, JobEventKind kind) throws RepositoryException {
        final long sequence = EventLedger.events(session, operation().child(EventLedger.NODE));
        final java.util.SequencedMap<String, DocumentValue> members =
                new java.util.LinkedHashMap<>();
        members.put(JobEvent.GENERATION, new DocumentValue.Whole(EventStoreGeneration.FIRST));
        members.put(JobEvent.IDENTIFIER, new DocumentValue.Text(identifier()));
        members.put(JobEvent.KIND, new DocumentValue.Text(kind.spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(sequence));
        final DocumentValue document = new DocumentValue.Mapping(members);
        final JobEvent event = assertInstanceOf(JobEvent.Held.class,
                JobEvent.read(document, generation(), CONTRACT), "the event was refused").event();
        final byte[] canonical = assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(document), "the event has no canonical form").bytes();
        assertInstanceOf(EventLedger.Appended.class,
                SnapshotStore.record(session, caller(), event, canonical, NOW, CONTRACT),
                kind + " was not recorded");
    }

    private Session recorded() throws RepositoryException {
        final Session session = prepared();
        OperationStore.create(session, assertInstanceOf(LogicalOperation.Held.class,
                LogicalOperation.accepted(identity(), Digest.of(
                                "a submission".getBytes(StandardCharsets.UTF_8)), commandContract(),
                        caller(), NOW, NOW, CONTRACT), "the record was refused").operation());
        assertEquals(OperationState.ACCEPTED, assertInstanceOf(OperationStore.Held.class,
                OperationStore.read(session, identity())).operation().state());
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

    private static StatePath operation() {
        return OperationStore.pathOf(identity());
    }

    private static EventStoreGeneration generation() {
        return generationOf(EventStoreGeneration.FIRST);
    }

    private static EventStoreGeneration generationOf(long number) {
        return assertInstanceOf(EventStoreGeneration.Held.class, EventStoreGeneration.of(number),
                number + " is not a generation").generation();
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
        walked(session, operation().path().substring(0, operation().path().lastIndexOf('/')));
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
