// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

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
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;

/**
 * Whether a resend converges on what is already there, and whether something else wearing the same
 * name is told so.
 *
 * <p>The three differences that matter are exercised one at a time — the derived digest, the target
 * the work is against, and the revision of the caller's environment it was selected under — because
 * the digest covers the first and not the other two, and a suite that only changed the command
 * would prove the easy half.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class SubmissionAdmissionTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/submission-admission");

    private static final AgentContract CONTRACT = contract();

    private static final long NOW = 1788000000000L;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("a first submission is recorded and an identical resend is recognised")
    void aresendConvergesOnWhatIsAlreadyThere() throws RepositoryException {
        final Session session = prepared();
        final AdmissionOutcome first = admit(session, submission("operation.json",
                "command-contract.json", "a submission"));
        final LogicalOperation recorded = assertInstanceOf(AdmissionOutcome.Accepted.class, first,
                "a first submission was not recorded").operation();
        final AdmissionOutcome again = admit(session, submission("operation.json",
                "command-contract.json", "a submission"));
        assertEquals(recorded.submissionDigest().rendered(),
                assertInstanceOf(AdmissionOutcome.Recognised.class, again,
                        "a resend was recorded as new work").operation().submissionDigest()
                        .rendered());
        assertEquals(1, children(session, recorded), "a resend created a second durable thing");
    }

    @Test
    @DisplayName("a different command under one identifier is a conflict, and the record stands")
    void adifferentCommandIsAConflict() throws RepositoryException {
        final Session session = prepared();
        admit(session, submission("operation.json", "command-contract.json", "a submission"));
        final String before = written(session);
        final AdmissionOutcome.Conflicting conflicting = assertInstanceOf(
                AdmissionOutcome.Conflicting.class,
                admit(session, submission("operation.json", "another-command.json",
                        "another submission")),
                "another command under one identifier was admitted");
        assertEquals("submission_digest", conflicting.member());
        assertEquals(before, written(session), "a conflicting submission changed the record");
    }

    @Test
    @DisplayName("another target and another environment revision are two conflicts, named")
    void thetwoThingsTheDigestDoesNotCoverAreCompared() throws RepositoryException {
        final Session session = prepared();
        admit(session, submission("operation.json", "command-contract.json", "a submission"));
        assertEquals("author_target_identity_digest",
                assertInstanceOf(AdmissionOutcome.Conflicting.class,
                        admit(session, submission("another-target.json", "command-contract.json",
                                "a submission")),
                        "the same command aimed at another target was recognised as the same work")
                        .member());
        assertEquals("selected_environment_revision",
                assertInstanceOf(AdmissionOutcome.Conflicting.class,
                        admit(session, submission("another-revision.json", "command-contract.json",
                                "a submission")),
                        "the same command against another revision was recognised as the same work")
                        .member());
    }

    @Test
    @DisplayName("the comparison is against the derived digest rather than anything a caller sent")
    void thecomparisonIsAgainstWhatThisSideDerived() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation recorded = assertInstanceOf(AdmissionOutcome.Accepted.class,
                admit(session, submission("operation.json", "command-contract.json",
                        "a submission"))).operation();
        // A resend whose derived digest is another submission's is a different submission, whatever
        // key it carried: this side compares what it derived and never what it was handed.
        assertInstanceOf(AdmissionOutcome.Conflicting.class,
                admit(session, submission("operation.json", "command-contract.json",
                        "somebody else's submission")),
                "a submission was recognised on a key rather than on what this side derived");
        assertEquals(recorded.submissionDigest().rendered(),
                assertInstanceOf(OperationStore.Held.class,
                        OperationStore.read(session, identity("operation.json")))
                        .operation().submissionDigest().rendered());
    }

    @Test
    @DisplayName("a resend of a record nothing has started starts it, and racing resends start it"
            + " once")
    void aresendStartsWhatNothingHasStarted() throws RepositoryException {
        final Session session = prepared();
        final LogicalOperation accepted = assertInstanceOf(AdmissionOutcome.Accepted.class,
                admit(session, submission("operation.json", "command-contract.json",
                        "a submission"))).operation();
        final LogicalOperation running = assertInstanceOf(OperationStore.Held.class,
                SubmissionAdmission.start(session, accepted),
                "a resend did not start a record nothing had started").operation();
        assertEquals(OperationState.RUNNING, running.state());
        assertInstanceOf(OperationStore.Refused.class,
                SubmissionAdmission.start(session, accepted),
                "a second resend started a second execution");
        assertEquals(OperationState.RUNNING, assertInstanceOf(OperationStore.Held.class,
                        OperationStore.read(session, identity("operation.json"))).operation()
                        .state(),
                "a losing resend moved the record anyway");
    }

    @Test
    @DisplayName("a foreign generation is refused before any record is read, distinctly")
    void aforeignGenerationIsRefusedBeforeAnythingIsRead() throws RepositoryException {
        final Session session = prepared();
        GenerationStore.rotate(session, generationOf(2));
        final AdmissionOutcome.Refused unknown = assertInstanceOf(AdmissionOutcome.Refused.class,
                admit(session, submission("operation.json", "command-contract.json",
                        "a submission")),
                "a submission naming an incarnation this store no longer serves was admitted");
        assertEquals(AdmissionOutcome.Reason.RETAINED_GENERATION, unknown.refusal());
        final AdmissionOutcome.Refused never = assertInstanceOf(AdmissionOutcome.Refused.class,
                admit(session, submission("unknown-generation.json", "command-contract.json",
                        "a submission")));
        assertEquals(AdmissionOutcome.Reason.UNKNOWN_GENERATION, never.refusal(),
                "an incarnation this store has never served was not told apart from one it has");
    }

    @Test
    @DisplayName("a request this side's clock cannot believe is refused and nothing is recorded")
    void anunbelievableRequestStartRecordsNothing() throws RepositoryException {
        final Session session = prepared();
        final long allowance = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        final SubmissionAdmission.Submission ahead = new SubmissionAdmission.Submission(
                identity("operation.json"), digest("a submission"),
                commandContract("command-contract.json"), caller(), NOW + allowance + 1);
        assertEquals(AdmissionOutcome.Reason.UNBELIEVABLE_REQUEST_START,
                assertInstanceOf(AdmissionOutcome.Refused.class,
                        SubmissionAdmission.admit(session, ahead, NOW, CONTRACT)).refusal());
        assertInstanceOf(OperationStore.Refused.class,
                OperationStore.read(session, identity("operation.json")),
                "a refused submission left a record behind");
    }

    private AdmissionOutcome admit(Session session, SubmissionAdmission.Submission submission)
            throws RepositoryException {
        return SubmissionAdmission.admit(session, submission, NOW, CONTRACT);
    }

    private static SubmissionAdmission.Submission submission(String operation, String contract,
                                                             String seed) {
        return new SubmissionAdmission.Submission(identity(operation), digest(seed),
                commandContract(contract), caller(), NOW);
    }

    private static int children(Session session, LogicalOperation operation)
            throws RepositoryException {
        final Node record = session.getNode(OperationStore.pathOf(operation.identity()).path());
        return (int) record.getParent().getNodes().getSize();
    }

    private static String written(Session session) throws RepositoryException {
        final Node record = session.getNode(
                OperationStore.pathOf(identity("operation.json")).path());
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

    private static OperationIdentity identity(String fixture) {
        return assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document(fixture), CONTRACT),
                fixture + " is not an operation identity").identity();
    }

    private static CommandContractIdentity commandContract(String fixture) {
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(document(fixture),
                        CommandContractIdentity.Bounds.from(CONTRACT)),
                fixture + " is not a command contract").identity();
    }

    private static DocumentValue document(String fixture) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(read(FIXTURES.resolve(fixture)),
                        BoundedDocumentReader.Bounds.from(CONTRACT)),
                fixture + " is not a document this reader accepts").value();
    }

    private static StatePath.Caller caller() {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller("the-submitting-caller"),
                "the caller was refused").caller();
    }

    private static DigestValue digest(String seed) {
        return Digest.of(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static rs.slingshot.agent.identity.EventStoreGeneration generationOf(long number) {
        return assertInstanceOf(rs.slingshot.agent.identity.EventStoreGeneration.Held.class,
                rs.slingshot.agent.identity.EventStoreGeneration.of(number),
                number + " is not a generation").generation();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        final String path = OperationStore.pathOf(identity("operation.json")).path();
        final String[] segments = path.substring(1).split("/");
        Node walked = session.getRootNode();
        int index = 0;
        while (index < segments.length - 1) {
            walked = walked.hasNode(segments[index])
                    ? walked.getNode(segments[index])
                    : walked.addNode(segments[index], "nt:unstructured");
            index = index + 1;
        }
        session.save();
        GenerationStore.establish(session);
        return session;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
