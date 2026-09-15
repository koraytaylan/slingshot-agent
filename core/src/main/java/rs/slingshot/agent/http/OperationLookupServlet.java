// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.Outbox;
import rs.slingshot.agent.execution.TerminalCommit;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.identity.DocumentProvenance;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.route.AgentRouteTable;
import rs.slingshot.agent.store.GenerationRotation;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionRecord;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * What one logical operation has become, answered from the store and from nothing else.
 *
 * <p>This is the route the client's whole ambiguity story rests on. Not knowing prompts a lookup;
 * believing something incorrect does not. So the two answers this route must never confuse are "it
 * is not there yet" and "it was never there": a client waits on the first and gives up on the
 * second, and telling it the wrong one either wastes a budget or abandons work that ran.</p>
 *
 * <p>What separates them is the incarnation. A record absent from the incarnation this store is
 * serving may be a record another node has written and this one has not read yet, so the answer is
 * "not yet" with the contract's own grace as the hint. A record absent from an incarnation this
 * store no longer serves is gone, and saying so is what lets a client stop.</p>
 *
 * <p>Somebody else's operation is answered exactly as an unknown one is. A caller who could tell
 * the two apart could ask this route which identifiers exist.</p>
 *
 * <p>The answer is the client's own snapshot document, member for member. Fifteen of its members
 * are the record — the identity, the contracts, the subscription, the digest, the retention, the
 * physical jobs — and the last two are what has happened, which is the newest event's kind and
 * sequence, the attempt count, the progress, and either the retained result or the retained
 * failure. The record is read back through the store's own reader rather than assembled here, so
 * a member the client compares is the value the store holds.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/snapshot",
        "sling.servlet.methods=GET"
})
public final class OperationLookupServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "operation-lookup";

    /** The query member naming which operation is wanted, spelled as the client spells it. */
    public static final String OPERATION_QUERY_MEMBER = "agent_operation_identifier";

    /** The query member naming which incarnation it belongs to. */
    public static final String GENERATION_QUERY_MEMBER = "agent_event_store_generation";

    /** What a lookup nobody can read is answered with. */
    public static final int NOT_YET = 404;

    /** What a lookup into an incarnation nothing answers about any more is answered with. */
    public static final int GONE = 410;

    /** What a lookup this build cannot read at all is answered with. */
    public static final int REFUSED = 400;

    /** What a lookup that found what it asked about is answered with. */
    public static final int SERVED = 200;

    /** The header this side asks a caller to wait on before looking again. */
    public static final String RETRY_AFTER = "Retry-After";

    /** The member the subscription cursor is carried in. */
    public static final String SUBSCRIPTION_WATERMARK = "subscription_watermark";

    /** The member the recorded provenance is carried in. */
    public static final String PROVENANCE = "provenance";

    /** The member the retained terminal result is carried in, where one was produced. */
    public static final String TERMINAL_RESULT = "terminal_result";

    /** The member the retained terminal failure is carried in, where one was produced. */
    public static final String TERMINAL_FAILURE = "terminal_failure";

    /** The member the complete physical job set is carried in. */
    public static final String PHYSICAL_JOBS = "physical_sling_job_identifiers";

    /** The member the granted retention is carried in. */
    public static final String RETENTION = "granted_retention_milliseconds";

    /** The member the physical attempt count is carried in. */
    public static final String ATTEMPT = "attempt";

    /** The member the logical progress is carried in. */
    public static final String PROGRESS = "progress";

    /** The member the target the record is against is carried in. */
    public static final String TARGET_DIGEST = OperationStore.TARGET_DIGEST;

    /** The member the selected environment revision is carried in. */
    public static final String ENVIRONMENT_REVISION = OperationStore.ENVIRONMENT_REVISION;

    /** The member the subscription the submission registered is carried in. */
    public static final String SUBSCRIPTION = "daemon_subscription_identifier";

    /** The member the submission digest is carried in. */
    public static final String SUBMITTED_DIGEST = OperationStore.SUBMISSION_DIGEST;

    /** The property the subscription a submission registered is written in. */
    public static final String SUBSCRIPTION_PROPERTY = "subscription_identifier";

    /** The member an identity document's incarnation is carried in. */
    public static final String IDENTITY_GENERATION = OperationIdentity.GENERATION;

    /** The member an identity document's operation name is carried in. */
    public static final String IDENTITY_IDENTIFIER = OperationIdentity.IDENTIFIER;

    /** What a finished operation's progress completes at, which is the client's own value. */
    private static final long COMPLETE_PROGRESS = 100;

    /** What a cursor says before anything has been shown, and the one value that is not a sequence. */
    private static final String NOTHING_SHOWN = "0:0";

    /** What a request is answered with when this build cannot read its own contract or store. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    /** How many milliseconds a second is, where a header is written in seconds. */
    private static final long MILLISECONDS_IN_A_SECOND = 1000;

    /** Which segment of an operation's path names the incarnation, counting from the root. */
    private static final int GENERATION_SEGMENT = 4;

    private static final long serialVersionUID = 1L;

    /**
     * Holds a servlet with nothing in it.
     *
     * <p>Every answer is read from the store at the moment of asking, under a scoped state session,
     * so there is nothing for a stale answer to live in.</p>
     */
    public OperationLookupServlet() {
        super();
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Answers what one operation has become.
     *
     * @param request the request, whose shape the base has already settled
     * @param response what to answer with
     * @throws IOException if the answer cannot be written
     */
    @Override
    protected void serve(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        final AgentContract.Outcome loaded = AgentContract.load();
        if (!(loaded instanceof final AgentContract.Loaded held)) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        if (AuthenticationGate.refusalIn(AuthenticationGate.of(request)).isPresent()) {
            refuse(response, AuthenticationGate.STATUS);
            return;
        }
        withState(response, state -> answer(request, response, held.contract(), state));
    }

    private void answer(SlingHttpServletRequest request, SlingHttpServletResponse response,
                        AgentContract contract, Session session)
            throws IOException, RepositoryException {
        final Optional<AgentOperationIdentifier> asked =
                identifierIn(request.getParameter(OPERATION_QUERY_MEMBER), contract);
        if (asked.isEmpty()) {
            refuse(response, REFUSED);
            return;
        }
        final EventStoreGeneration named = generationIn(request, session);
        final GenerationRotation.Access access = GenerationRotation.accessTo(session, named);
        if (access instanceof GenerationRotation.Retired) {
            // An incarnation nothing answers about any more is a thing a client may stop waiting
            // for, and the only answer that lets it stop is one that says so.
            refuse(response, GONE);
            return;
        }
        final Optional<StateAuthority.Viewer> viewer = StateAuthority.viewer(request);
        final StatePath operation = StatePath.operation(named, asked.get());
        if (viewer.isEmpty() || !StateAuthority.operation(session, operation, viewer.get(), ROUTE_NAME)) {
            notYet(response, contract);
            return;
        }
        found(response, session, operation, contract);
    }

    private void found(SlingHttpServletResponse response, Session session, StatePath operation,
                       AgentContract contract) throws IOException, RepositoryException {
        final SnapshotStore.Materialised current = SnapshotStore.read(session, operation);
        if (!(current instanceof final SnapshotStore.Known known)) {
            // Somebody else's operation is answered exactly as one nobody has: a caller who could
            // tell the two apart could ask this route which identifiers exist.
            notYet(response, contract);
            return;
        }
        final OperationStore.Outcome read =
                OperationStore.readAt(session, EventStoreGeneration.of(generationOf(operation))
                        instanceof final EventStoreGeneration.Held held ? held.generation()
                                : throwMissingGeneration(), operation);
        if (!(read instanceof final OperationStore.Held held)) {
            notYet(response, contract);
            return;
        }
        final Optional<String> rendered =
                rendered(session, operation, held.operation(), known.snapshot(), contract);
        if (rendered.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        response.setStatus(SERVED);
        response.setContentType(route().mediaType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(rendered.get());
    }

    private void notYet(SlingHttpServletResponse response, AgentContract contract)
            throws IOException {
        response.setHeader(RETRY_AFTER, String.valueOf(Math.max(1,
                contract.value(ContractLimit.MISSING_OPERATION_GRACE_MILLISECONDS)
                        / MILLISECONDS_IN_A_SECOND)));
        refuse(response, NOT_YET);
    }

    private static Optional<String> rendered(Session session, StatePath operation,
                                             LogicalOperation record, SnapshotStore.Snapshot snapshot,
                                             AgentContract contract) throws RepositoryException {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SUBSCRIPTION_WATERMARK,
                new DocumentValue.Text(watermarkOf(session, operation)));
        members.put(PROVENANCE, provenanceOf(record));
        members.put(TARGET_DIGEST,
                new DocumentValue.Text(record.identity().targetDigest().rendered()));
        members.put(ENVIRONMENT_REVISION,
                new DocumentValue.Text(record.identity().environmentRevision()));
        members.put(SUBSCRIPTION, new DocumentValue.Text(subscriptionOf(session, operation)));
        members.put(SUBMITTED_DIGEST,
                new DocumentValue.Text(record.submissionDigest().rendered()));
        members.put(PHYSICAL_JOBS, new DocumentValue.Sequence(
                Outbox.identifiersFor(session, record.identity()).stream()
                        .map(DocumentValue.Text::new)
                        .map(DocumentValue.class::cast)
                        .toList()));
        members.put(RETENTION, new DocumentValue.Whole(
                contract.value(ContractLimit.MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS)));
        members.put(ATTEMPT, new DocumentValue.Whole(record.attempts()));
        members.put(PROGRESS, new DocumentValue.Whole(
                snapshot.kind().finality() == JobEventKind.Finality.ENDS ? COMPLETE_PROGRESS : 0));
        members.put(JobEvent.GENERATION,
                new DocumentValue.Whole(record.identity().generation().number()));
        members.put(JobEvent.IDENTIFIER,
                new DocumentValue.Text(record.identity().identifier().rendered()));
        members.put(JobEvent.KIND, new DocumentValue.Text(snapshot.kind().spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(snapshot.sequence().number()));
        // A terminal snapshot is also the durable recovery point for its answer, so the answer
        // travels in the same response a lost submission answer is recovered through.
        final Optional<ExecutionOutcome.Result> answer = answerIn(session, operation);
        if (answer.isPresent()) {
            if (snapshot.kind() == JobEventKind.FAILED) {
                terminalDocument(session, operation, record, answer.get(), Ending.FAILED)
                        .ifPresent(failure -> members.put(TERMINAL_FAILURE, failure));
            }
            if (snapshot.kind() == JobEventKind.SUCCEEDED) {
                terminalDocument(session, operation, record, answer.get(), Ending.SUCCEEDED)
                        .ifPresent(result -> members.put(TERMINAL_RESULT, result));
            }
        }
        final CanonicalByteWriter.Outcome written =
                CanonicalByteWriter.write(new DocumentValue.Mapping(members));
        return written instanceof final CanonicalByteWriter.Written bytes
                ? Optional.of(bytes.rendered())
                : Optional.empty();
    }

    /**
     * The client's terminal envelope for one ended operation.
     *
     * <p>One document with two spellings: a successful ending carries its canonical result and the
     * artifacts it declared, and a failed one carries its canonical failure. Both echo the exact
     * operation, subscription, provenance, and digest the record holds, because the client
     * authenticates them against what it submitted before it will believe either.</p>
     *
     * @param session the state session
     * @param operation the operation's record
     * @param record the record as this store holds it
     * @param result the stored answer
     * @param ending which of the two spellings this ending takes
     * @return the document, or nothing where this side cannot render it
     */
    private static Optional<DocumentValue> terminalDocument(Session session, StatePath operation,
                                                            LogicalOperation record,
                                                            ExecutionOutcome.Result result,
                                                            Ending ending)
            throws RepositoryException {
        final String canonical;
        if (result instanceof final ExecutionOutcome.Inline inline) {
            canonical = inline.document();
        } else if (result instanceof final ExecutionOutcome.Published published
                && published.canonicalResult() instanceof final ExecutionOutcome.Written written) {
            // An answer too large to carry inline still has a document: it is the one that names
            // the artifact, and it is what the client validates the ending against.
            canonical = written.document();
        } else {
            return Optional.empty();
        }
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        if (ending == Ending.FAILED) {
            members.put("canonical_failure", new DocumentValue.Text(canonical));
        } else {
            members.put("canonical_result", new DocumentValue.Text(canonical));
            members.put("declared_artifacts", declaredArtifacts(result));
        }
        members.put(SUBSCRIPTION, new DocumentValue.Text(subscriptionOf(session, operation)));
        members.put("operation", identityDocument(record));
        members.put(PROVENANCE, provenanceOf(record));
        members.put(SUBMITTED_DIGEST,
                new DocumentValue.Text(record.submissionDigest().rendered()));
        return Optional.of(new DocumentValue.Mapping(members));
    }

    /**
     * Which of the two spellings one terminal envelope takes.
     *
     * <p>A named type rather than a flag, because a call site reading {@code true} says nothing
     * about which ending it is asking for, and the two spellings carry different members.</p>
     */
    private enum Ending {
        /** The operation succeeded, so the envelope carries its result and what it declared. */
        SUCCEEDED,
        /** The operation failed, so the envelope carries its canonical failure. */
        FAILED
    }

    /**
     * The artifacts one result declares, which the client checks against the typed result.
     *
     * <p>Read from the command's own document rather than from the descriptor, because the two are
     * compared: a slot, media type, byte count, or file name that disagreed would be a result this
     * side answered under a name the command did not declare.</p>
     *
     * @param result the stored answer
     * @return the echoes, which is empty where the result declares none
     */
    private static DocumentValue declaredArtifacts(ExecutionOutcome.Result result) {
        if (!(result instanceof final ExecutionOutcome.Published published)
                || !(published.canonicalResult() instanceof final ExecutionOutcome.Written
                        written)) {
            return new DocumentValue.Sequence(List.of());
        }
        final rs.slingshot.agent.json.BoundedDocumentReader.Outcome read =
                rs.slingshot.agent.json.BoundedDocumentReader.read(
                        written.document().getBytes(StandardCharsets.UTF_8),
                        rs.slingshot.agent.json.BoundedDocumentReader.Bounds.from(loaded()));
        final Optional<DocumentValue.Mapping> artifact = read
                instanceof final rs.slingshot.agent.json.BoundedDocumentReader.Read document
                && document.value() instanceof final DocumentValue.Mapping mapping
                ? mapping.member("artifact").filter(DocumentValue.Mapping.class::isInstance)
                        .map(DocumentValue.Mapping.class::cast)
                : Optional.empty();
        if (artifact.isEmpty()) {
            return new DocumentValue.Sequence(List.of());
        }
        final DocumentValue.Mapping descriptor = artifact.get();
        final SequencedMap<String, DocumentValue> echo = new LinkedHashMap<>();
        echo.put("byte_length", descriptor.member(rs.slingshot.agent.command.ArtifactDescriptor
                .BYTE_LENGTH).orElse(new DocumentValue.Whole(0)));
        echo.put("media_type", descriptor.member(rs.slingshot.agent.command.ArtifactDescriptor
                .MEDIA_TYPE).orElse(new DocumentValue.Text("")));
        echo.put("slot", descriptor.member(rs.slingshot.agent.command.ArtifactDescriptor.SLOT)
                .orElse(new DocumentValue.Text("")));
        echo.put("suggested_name", descriptor.member(rs.slingshot.agent.command.ArtifactDescriptor
                .SUGGESTED_FILE_NAME).orElse(new DocumentValue.Text("")));
        return new DocumentValue.Sequence(List.of(new DocumentValue.Mapping(echo)));
    }

    private static DocumentValue identityDocument(LogicalOperation record) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(IDENTITY_GENERATION,
                new DocumentValue.Whole(record.identity().generation().number()));
        members.put(IDENTITY_IDENTIFIER,
                new DocumentValue.Text(record.identity().identifier().rendered()));
        members.put(TARGET_DIGEST,
                new DocumentValue.Text(record.identity().targetDigest().rendered()));
        members.put(ENVIRONMENT_REVISION,
                new DocumentValue.Text(record.identity().environmentRevision()));
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue.Mapping provenanceOf(LogicalOperation record) {
        return DocumentProvenance.composed(SubmitServlet.thisBuild(), record.commandContract())
                .document();
    }

    private static Optional<ExecutionOutcome.Result> answerIn(Session session, StatePath operation) {
        try {
            return TerminalCommit.answerIn(session, operation);
        } catch (RepositoryException unreadable) {
            return Optional.empty();
        }
    }

    private static String subscriptionOf(Session session, StatePath operation)
            throws RepositoryException {
        return textProperty(session, operation, SUBSCRIPTION_PROPERTY);
    }

    /**
     * How far the subscription this operation registered has been served.
     *
     * <p>A snapshot carries the position its own effects are accounted for through, and the client
     * resumes its stream from that position. So it is the subscription's own cursor where there is
     * one, and the one value that says nothing has been shown where there is not.</p>
     *
     * @param session the session to read under
     * @param operation the operation
     * @return the cursor, as the stream's own identifier carries it
     * @throws RepositoryException if the repository fails
     */
    private static String watermarkOf(Session session, StatePath operation)
            throws RepositoryException {
        final SubscriptionRecord.Outcome named =
                SubscriptionRecord.identifier(subscriptionOf(session, operation), loaded());
        if (!(named instanceof final SubscriptionRecord.Held held)) {
            return NOTHING_SHOWN;
        }
        return rs.slingshot.agent.store.HighWaterMark.read(session, held.identifier())
                instanceof final SubscriptionRecord.Shown shown
                ? generationOf(operation) + ":" + shown.sequence().number()
                : NOTHING_SHOWN;
    }

    private static AgentContract loaded() {
        final AgentContract.Outcome outcome = AgentContract.load();
        if (outcome instanceof final AgentContract.Refused refused) {
            throw new IllegalStateException("no contract: " + refused.detail());
        }
        return ((AgentContract.Loaded) outcome).contract();
    }

    private static long generationOf(StatePath operation) {
        final String[] segments = operation.path().split("/");
        return Long.parseLong(segments[GENERATION_SEGMENT].substring(1));
    }

    private static EventStoreGeneration throwMissingGeneration() {
        throw new IllegalStateException("an operation's own path does not name its incarnation");
    }

    private static String textProperty(Session session, StatePath operation, String property)
            throws RepositoryException {
        return session.nodeExists(operation.path())
                && session.getNode(operation.path()).hasProperty(property)
                ? session.getNode(operation.path()).getProperty(property).getString()
                : "";
    }

    private static EventStoreGeneration generationIn(SlingHttpServletRequest request,
                                                     Session session) throws RepositoryException {
        final String asked = request.getParameter(GENERATION_QUERY_MEMBER);
        final EventStoreGeneration.Outcome named = asked == null || asked.isBlank()
                ? serving(session)
                : EventStoreGeneration.of(wholeOf(asked));
        return named instanceof final EventStoreGeneration.Held held
                ? held.generation()
                : ((EventStoreGeneration.Held) serving(session)).generation();
    }

    private static EventStoreGeneration.Outcome serving(Session session)
            throws RepositoryException {
        final rs.slingshot.agent.store.GenerationStore.Outcome held =
                rs.slingshot.agent.store.GenerationStore.serving(session);
        return held instanceof final rs.slingshot.agent.store.GenerationStore.Held serving
                ? EventStoreGeneration.of(serving.generation().number())
                : EventStoreGeneration.of(EventStoreGeneration.FIRST);
    }

    private static long wholeOf(String asked) {
        return asked.chars().allMatch(scalar -> scalar >= '0' && scalar <= '9')
                ? Long.parseLong(asked)
                : 0;
    }

    private static Optional<AgentOperationIdentifier> identifierIn(String asked,
                                                                   AgentContract contract) {
        if (asked == null || asked.isBlank()) {
            return Optional.empty();
        }
        final AgentOperationIdentifier.Outcome held = AgentOperationIdentifier.of(asked, contract);
        return held instanceof final AgentOperationIdentifier.Held identifier
                ? Optional.of(identifier.identifier())
                : Optional.empty();
    }

    /**
     * The route this servlet answers, read from the committed table.
     *
     * @return the route
     */
    public static AgentRoute route() {
        final AgentRouteTable.Outcome outcome = AgentRouteTable.load();
        if (outcome instanceof final AgentRouteTable.Refused refused) {
            throw new IllegalStateException("no route table: " + refused.detail());
        }
        return ((AgentRouteTable.Loaded) outcome).table().route(ROUTE_NAME);
    }

}
