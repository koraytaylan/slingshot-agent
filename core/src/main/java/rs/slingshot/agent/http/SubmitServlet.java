// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import rs.slingshot.agent.command.Budget;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.ProgressSink;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.SubmissionAdmission;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.DocumentProvenance;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.identity.SubmissionBinding;
import rs.slingshot.agent.identity.SubmittedCommandDigest;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.route.AgentRoute;
import rs.slingshot.agent.store.StatePath;

/**
 * The one route that starts work, and the only place a caller's request becomes a durable record.
 *
 * <p>Everything it decides, something else decided first. The shape of the request is the base's;
 * who is asking is the authentication gate's; whether they may is the authorization gate's; how
 * much body may arrive is the bounded reader's; whether the work is new, a resend, or a conflict is
 * admission's. What is left here is the part that is on the wire: deriving the key rather than
 * believing the one a caller sent, and answering with the record's own values so that a client
 * comparing them against what it sent finds them equal or finds a real disagreement.</p>
 *
 * <p>An accepted operation with complete intake may wait for execution capacity. An identical
 * owner resend tries to start that operation inside the new authorized request; it never borrows
 * the original request's session. Saturation or a start conflict with no observed winner returns
 * a retryable refusal. Running and terminal operations are acknowledged without executing again.</p>
 *
 * <p>An acknowledgement naming a different operation, generation, target, or digest is never sent,
 * because the client is built to treat exactly that as evidence its request may or may not have
 * run — which is the one answer nobody can act on.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/slingshot/agent/submit",
        "sling.servlet.methods=POST"
})
public final class SubmitServlet extends AgentServlet {

    /** The route this servlet answers, by the name the committed table gives it. */
    public static final String ROUTE_NAME = "submit";

    /** The member the canonical argument bytes arrive in. */
    public static final String ARGUMENTS = "canonical_arguments";

    /** The member the artifact manifest arrives in. */
    public static final String MANIFEST = "artifact_manifest";

    /** The member the subscription this submission registers arrives in. */
    public static final String SUBSCRIPTION = "daemon_subscription_identifier";

    /** The member the operation identity arrives in. */
    public static final String OPERATION = "operation";

    /** The member the provenance arrives in. */
    public static final String PROVENANCE = "provenance";

    /** The header a client sends the key it derived in, which this side derives again. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** What a submission this side will not consider is answered with. */
    public static final int REFUSED = 400;

    /** What a submission naming work that means something else is answered with. */
    public static final int CONFLICT = 409;

    /** What an accepted submission is answered with. */
    public static final int ACCEPTED = 202;

    /** What this instance answers when it is already holding as many executions as it may. */
    public static final int AT_CAPACITY = 503;

    private static final long serialVersionUID = 1L;

    /**
     * What this build can run, and what running it produced.
     *
     * <p>An immediate command runs inside the request that submitted it, on that request's own
     * session, which is why this agent needs no power over anybody's identity. What it can run is a
     * seam rather than a list here: the commands themselves are somebody else's task, and a route
     * that hard-coded them would be a route that has to change every time one is added.</p>
     */
    public interface Commands extends java.io.Serializable {

        /**
         * Whether this build runs the command one wire name means.
         *
         * @param wireName the command's own name on the wire
         * @return whether anything here runs it
         */
        boolean serves(String wireName);

        /**
         * Runs one command, inside the caller's own request and on the caller's own session.
         *
         * @param operation the record the work is against
         * @param submission the submission as it arrived
         * @param session the caller's own session, which is the request's
         * @return the command's declared completion, including a typed failure
         */
        rs.slingshot.agent.execution.ExecutionOutcome.Completion run(LogicalOperation operation,
                                                                 DocumentValue.Mapping submission,
                                                                 Session session);

        /**
         * Runs with the request resolver available to a packaged command runtime.
         * @param operation the accepted operation
         * @param submission the submitted command
         * @param session the caller's JCR session
         * @param resolver the caller's resource resolver
         * @return the command completion
         */
        default rs.slingshot.agent.execution.ExecutionOutcome.Completion run(
                LogicalOperation operation, DocumentValue.Mapping submission, Session session,
                ResourceResolver resolver) {
            return run(operation, submission, session);
        }

        /**
         * Supplies request-scoped continuation authority when this runtime has one.
         * @param operation the accepted operation
         * @param contract the authenticated contract
         * @return the paging context
         */
        default CallerContext.Paging paging(LogicalOperation operation,
                                            AgentContract contract) {
            return CallerContext.Unavailable.INSTANCE;
        }

        /**
         * Runs with the complete request-scoped handler context.
         * @param operation the accepted operation
         * @param submission the submitted command
         * @param session the caller's JCR session
         * @param resolver the caller's resource resolver
         * @param context the request-scoped handler context
         * @return the command completion
         */
        default rs.slingshot.agent.execution.ExecutionOutcome.Completion run(
                LogicalOperation operation, DocumentValue.Mapping submission, Session session,
                ResourceResolver resolver, CallerContext context) {
            return run(operation, submission, session, resolver);
        }
    }

    /** What a build with no commands registered runs, which is nothing. */
    public static final Commands NOTHING_REGISTERED = new DefaultCommands();

    /**
     * A build with no commands registered, which serves none and runs none.
     *
     * <p>Named for the interface rather than for the situation because it is the only
     * implementation this bundle carries: what a deployment registers arrives as somebody else's.
     * The constant above says what it means.</p>
     */
    private static final class DefaultCommands implements Commands {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean serves(String wireName) {
            return false;
        }

        @Override
        public rs.slingshot.agent.execution.ExecutionOutcome.Completion run(
                LogicalOperation operation, DocumentValue.Mapping submission, Session session) {
            throw new IllegalStateException("nothing here runs anything, and this should have been"
                    + " refused before a record was written");
        }
    }

    /**
     * What this build runs, held for the life of the component rather than per request.
     *
     * <p>The type is serialisable because a servlet is one by inheritance, and a field whose type
     * is not would be a field a container could not write out. Nothing here is ever serialised;
     * what the declaration buys is that the compiler and the analyser agree with that rather than
     * a suppression saying so.</p>
     */
    private volatile Commands commands;

    /**
     * Holds a servlet with nothing in it.
     *
     * <p>A declarative-services component is one object the container hands to every caller at
     * once, so nothing is held between requests: every value this answers with is read from the
     * record it just wrote, under a scoped state session.</p>
     */
    public SubmitServlet() {
        this(NOTHING_REGISTERED);
    }

    /**
     * Holds a servlet that runs the commands it is given.
     *
     * @param commands what this build can run
     */
    public SubmitServlet(Commands commands) {
        super();
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
    }

    /** Binds the packaged command runtime when its complete adapter graph is active.
     * @param runtime the active command runtime
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void available(CommandRuntime runtime) {
        commands = runtime;
    }

    /** Removes a stopped runtime and returns to the fail-closed command surface.
     * @param runtime the runtime being removed
     */
    public void unavailable(CommandRuntime runtime) {
        if (commands == runtime) {
            commands = NOTHING_REGISTERED;
        }
    }

    /** Whether the currently bound runtime serves a command, for activation diagnostics. */
    boolean servesCommand(String wireName) {
        return commands.serves(wireName);
    }

    @Override
    protected String routeName() {
        return ROUTE_NAME;
    }

    /**
     * Takes one submission, or says exactly why it did not.
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
        answer(request, response, held.contract());
    }

    /** What a request is answered with when this build cannot read its own contract. */
    private static final int NOTHING_THIS_BUILD_CAN_SERVE = 500;

    private void answer(SlingHttpServletRequest request, SlingHttpServletResponse response,
                        AgentContract contract) throws IOException {
        final AuthenticationGate.Outcome admitted = AuthenticationGate.of(request);
        final CallerIdentity caller = ((AuthenticationGate.Admitted) admitted).caller();
        final Optional<Session> asking = sessionOf(request);
        if (asking.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        if (AuthorizationGate.refusalIn(AuthorizationGate.of(new AuthorizationGate.Request(
                ROUTE_NAME, permittedGroups(), groupsOf(asking.get(), caller),
                AuthorizationGate.Ownership.NOT_ABOUT_AN_OPERATION))).isPresent()) {
            refuse(response, AuthorizationGate.STATUS);
            return;
        }
        final BoundedRequestBody.Outcome body = BoundedRequestBody.read(request.getInputStream(),
                request.getContentLength(), contract);
        if (BoundedRequestBody.refusalIn(body).isPresent()) {
            refuse(response, REFUSED);
            return;
        }
        recorded(request, response, new Arriving(caller, ((BoundedRequestBody.Read) body).bytes(),
                contract, asking.get(), request.getResourceResolver()));
    }

    /**
     * One submission as it arrived, before anything has been decided about it.
     *
     * @param caller who is asking
     * @param body the bytes that arrived, already bounded
     * @param contract the authenticated contract, which declares every bound
     * @param effects the original caller's session, used only for requested content effects
     * @param resolver the original caller's resource resolver
     */
    private record Arriving(CallerIdentity caller, byte[] body, AgentContract contract, Session effects,
                            ResourceResolver resolver) {
    }

    private void recorded(SlingHttpServletRequest request, SlingHttpServletResponse response,
                          Arriving arriving) throws IOException {
        final BoundedDocumentReader.Outcome read = BoundedDocumentReader.read(arriving.body(),
                BoundedDocumentReader.Bounds.from(arriving.contract()));
        if (!(read instanceof final BoundedDocumentReader.Read document)
                || !(document.value() instanceof final DocumentValue.Mapping submission)) {
            refuse(response, REFUSED);
            return;
        }
        final Optional<SubmissionAdmission.Submission> asked = submissionOf(submission, arriving,
                text(request.getHeader(IDEMPOTENCY_KEY)));
        if (asked.isEmpty() || !commands.serves(asked.get().commandContract().wireName())) {
            refuse(response, REFUSED);
            return;
        }
        withState(response, state -> admitted(response, submission, arriving, state, asked.get()));
    }

    private void admitted(SlingHttpServletResponse response, DocumentValue.Mapping submission,
                          Arriving arriving, Session session, SubmissionAdmission.Submission asked)
            throws IOException, RepositoryException {
        final IntakeSlotWrite.Admission intake = SubmissionRegistration.admit(session,
                new SubmissionRegistration.Request(asked, text(submission, SUBSCRIPTION),
                        declaredSlots(submission)), System.currentTimeMillis(), arriving.contract());
        if (intake instanceof IntakeSlotWrite.AtCapacity || intake instanceof IntakeSlotWrite.NotCounted) {
            refuse(response, AT_CAPACITY);
            return;
        }
        final AdmissionOutcome outcome = ((IntakeSlotWrite.Decided) intake).outcome();
        if (outcome instanceof final AdmissionOutcome.Accepted accepted) {
            progress(response, accepted.operation(), submission, arriving, session,
                    SubmissionResponse.Acceptance.THE_FIRST_TIME);
            return;
        }
        if (outcome instanceof final AdmissionOutcome.Recognised recognised) {
            progress(response, recognised.operation(), submission, arriving, session,
                    SubmissionResponse.Acceptance.ALREADY_HELD);
            return;
        }
        refuse(response, outcome instanceof AdmissionOutcome.Conflicting ? CONFLICT : REFUSED);
    }

    private void progress(SlingHttpServletResponse response, LogicalOperation operation,
                          DocumentValue.Mapping submission, Arriving arriving, Session session,
                          SubmissionResponse.Acceptance acceptance) throws IOException, RepositoryException {
        if (operation.state() == rs.slingshot.agent.execution.OperationState.RUNNING
                && rs.slingshot.agent.execution.ExecutionJournal.pending(session,
                        rs.slingshot.agent.execution.OperationStore.pathOf(operation.identity()),
                        arriving.contract()).isPresent()) {
            finalised(response, operation, submission, arriving, session, acceptance);
            return;
        }
        if (operation.state() != rs.slingshot.agent.execution.OperationState.ACCEPTED
                || IntakeSlotWrite.outstanding(session,
                        rs.slingshot.agent.execution.OperationStore.pathOf(operation.identity())) > 0) {
            answered(response, operation, submission, arriving, acceptance);
            return;
        }
        executed(response, operation, submission, arriving, session, acceptance);
    }

    private void executed(SlingHttpServletResponse response, LogicalOperation accepted,
                          DocumentValue.Mapping submission, Arriving arriving, Session session,
                          SubmissionResponse.Acceptance acceptance)
            throws IOException, RepositoryException {
        final Optional<StatePath.Caller> caller = arriving.caller().counted();
        if (caller.isEmpty()) {
            refuse(response, REFUSED);
            return;
        }
        final rs.slingshot.agent.store.CapacityLedger.ReservationAdmission room =
                rs.slingshot.agent.store.CapacityLedger.take(session, caller.get(), java.util.List.of(
                        new rs.slingshot.agent.store.CapacityReservation.Charge(
                                rs.slingshot.agent.store.AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS, 1)),
                        arriving.contract());
        if (!(room instanceof final rs.slingshot.agent.store.CapacityLedger.Reserved reserved)) {
            // An executing command holds one of this instance's request threads. A bound on what
            // the store keeps is not a bound on how much of somebody's author this occupies.
            response.setHeader(RETRY_AFTER, String.valueOf(retryAfterSeconds(arriving.contract())));
            refuse(response, AT_CAPACITY);
            return;
        }
        try {
            running(response, accepted, submission, arriving, session, acceptance);
        } finally {
            rs.slingshot.agent.store.CapacityLedger.release(session, reserved.reservation(),
                    arriving.contract());
        }
    }

    /** The header this side asks a caller to wait on, which the client already reads. */
    public static final String RETRY_AFTER = "Retry-After";

    private static long retryAfterSeconds(AgentContract contract) {
        final long capped = Math.min(contract.value(ContractLimit.RETRY_AFTER_CAP_MILLISECONDS),
                contract.value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS));
        return Math.max(1, capped / MILLISECONDS_IN_A_SECOND);
    }

    /** How many milliseconds a second is, where a header is written in seconds. */
    private static final long MILLISECONDS_IN_A_SECOND = 1000;

    private void running(SlingHttpServletResponse response, LogicalOperation accepted,
                         DocumentValue.Mapping submission, Arriving arriving, Session session,
                         SubmissionResponse.Acceptance acceptance)
            throws IOException, RepositoryException {
        final Optional<LogicalOperation> started = rs.slingshot.agent.execution.ExecutionJournal.start(
                session, accepted, System.currentTimeMillis(), arriving.contract());
        if (started.isEmpty()) {
            startRefused(response, accepted, submission, arriving, session);
            return;
        }
        final LogicalOperation running = started.get();
        final StatePath path = rs.slingshot.agent.execution.OperationStore.pathOf(running.identity());
        try (var attempt = rs.slingshot.agent.execution.ExecutionJournal.attempt(session, path,
                arriving.contract())) {
            final AgentContract contract = arriving.contract();
            final CallerContext context = new CallerContext(running.identity().identifier(),
                    Budget.discovery(contract), Budget.time(contract),
                    new Budget(Budget.Kind.RESULT,
                            contract.value(ContractLimit.MAXIMUM_COMMAND_RESULT_BYTES)),
                    ProgressSink.under(contract),
                    commands.paging(running, contract));
            attempt.complete(commands.run(running, submission, arriving.effects(),
                    arriving.resolver(), context));
        }
        finalised(response, running, submission, arriving, session, acceptance);
    }

    private void finalised(SlingHttpServletResponse response, LogicalOperation running,
                           DocumentValue.Mapping submission, Arriving arriving, Session session,
                           SubmissionResponse.Acceptance acceptance) throws IOException, RepositoryException {
        final StatePath path = rs.slingshot.agent.execution.OperationStore.pathOf(running.identity());
        final Optional<rs.slingshot.agent.execution.ExecutionOutcome> pending =
                rs.slingshot.agent.execution.ExecutionJournal.pending(session, path, arriving.contract());
        if (pending.isEmpty()) {
            response.setHeader(RETRY_AFTER, String.valueOf(retryAfterSeconds(arriving.contract())));
            refuse(response, AT_CAPACITY);
            return;
        }
        final rs.slingshot.agent.execution.TerminalCommit.Outcome ended =
                rs.slingshot.agent.execution.TerminalCommit.commitReserved(session, running.caller(), running,
                        pending.get(), arriving.contract(),
                        path.child(rs.slingshot.agent.store.EventLedger.TERMINAL_BUDGET));
        if (ended instanceof rs.slingshot.agent.execution.TerminalCommit.Committed
                || ended instanceof rs.slingshot.agent.execution.TerminalCommit.Unchanged) {
            answered(response, running, submission, arriving, acceptance);
            return;
        }
        response.setHeader(RETRY_AFTER, String.valueOf(retryAfterSeconds(arriving.contract())));
        refuse(response, AT_CAPACITY);
    }

    private void startRefused(SlingHttpServletResponse response, LogicalOperation accepted,
                              DocumentValue.Mapping submission, Arriving arriving, Session session)
            throws IOException, RepositoryException {
        final var current = rs.slingshot.agent.execution.OperationStore.read(session, accepted.identity());
        if (current instanceof final rs.slingshot.agent.execution.OperationStore.Held held
                && held.operation().state() != rs.slingshot.agent.execution.OperationState.ACCEPTED) {
            progress(response, held.operation(), submission, arriving, session,
                    SubmissionResponse.Acceptance.ALREADY_HELD);
            return;
        }
        response.setHeader(RETRY_AFTER, String.valueOf(retryAfterSeconds(arriving.contract())));
        refuse(response, AT_CAPACITY);
    }

    private void answered(SlingHttpServletResponse response, LogicalOperation operation,
                          DocumentValue.Mapping submission, Arriving arriving,
                          SubmissionResponse.Acceptance acceptance) throws IOException {
        final Optional<String> rendered = SubmissionResponse.of(operation,
                        text(submission, SUBSCRIPTION),
                        arriving.contract().value(
                                ContractLimit.MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS),
                        acceptance)
                .rendered();
        if (rendered.isEmpty()) {
            refuse(response, NOTHING_THIS_BUILD_CAN_SERVE);
            return;
        }
        response.setStatus(ACCEPTED);
        response.setContentType(route().mediaType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(rendered.get());
    }

    /**
     * The submission a document describes, where every part of it is one this build reads.
     *
     * @param submission the document that arrived
     * @param arriving who sent it and what this build is holding it to
     * @param suppliedKey the key the caller says it derived, which this side derives again
     * @return the submission, or nothing where any part of it is refused
     */
    private static Optional<SubmissionAdmission.Submission> submissionOf(
            DocumentValue.Mapping submission, Arriving arriving, String suppliedKey) {
        final Optional<DocumentProvenance> provenance = provenanceOf(submission, arriving);
        final Optional<OperationIdentity> identity = identityOf(submission, arriving);
        final Optional<StatePath.Caller> caller = arriving.caller().counted();
        if (provenance.isEmpty() || identity.isEmpty() || caller.isEmpty()) {
            return Optional.empty();
        }
        final DigestValue derived = derive(provenance.get(), submission);
        if (!suppliedKey.isEmpty() && !suppliedKey.equals(derived.rendered())) {
            // The key a caller sends is compared with the one this side derived and never used in
            // its place. Neither is echoed: a refusal naming both would be a way to ask this side
            // what a submission derives to without submitting one.
            return Optional.empty();
        }
        return Optional.of(new SubmissionAdmission.Submission(identity.get(), derived,
                provenance.get().commandContract(), caller.get(), requestStart(submission)));
    }

    private static DigestValue derive(DocumentProvenance provenance,
                                      DocumentValue.Mapping submission) {
        final SubmittedCommandDigest digest = SubmittedCommandDigest.derive(
                provenance.commandContract(), provenance.canonicalContractDigest(),
                provenance.transportContractDigest(),
                text(submission, ARGUMENTS).getBytes(StandardCharsets.UTF_8));
        return manifestOf(submission).keyFor(digest);
    }

    /**
     * The slots one submission's manifest declares, where it declares any.
     *
     * @param submission the submission as it arrived
     * @return the declarations, which is empty for a manifest declaring no payload
     */
    private static java.util.List<IntakeSlotWrite.Declared> declaredSlots(
            DocumentValue.Mapping submission) {
        final java.util.List<IntakeSlotWrite.Declared> declared = new java.util.ArrayList<>();
        submission.member(MANIFEST)
                .filter(DocumentValue.Mapping.class::isInstance)
                .map(DocumentValue.Mapping.class::cast)
                .flatMap(manifest -> manifest.member("slots"))
                .filter(DocumentValue.Sequence.class::isInstance)
                .map(DocumentValue.Sequence.class::cast)
                .ifPresent(slots -> slots.items().stream()
                        .filter(DocumentValue.Mapping.class::isInstance)
                        .map(DocumentValue.Mapping.class::cast)
                        .forEach(slot -> declaredSlot(slot).ifPresent(declared::add)));
        return declared;
    }

    private static Optional<IntakeSlotWrite.Declared> declaredSlot(DocumentValue.Mapping slot) {
        final rs.slingshot.agent.store.ArtifactSlot.Outcome named =
                rs.slingshot.agent.store.ArtifactSlot.of(text(slot, "name"));
        final DigestValue.Outcome digest = DigestValue.of(text(slot, "digest"));
        return named instanceof final rs.slingshot.agent.store.ArtifactSlot.Held held
                && digest instanceof final DigestValue.Held known
                ? Optional.of(new IntakeSlotWrite.Declared(held.slot(),
                        whole(slot, "byte_count"), known.digest()))
                : Optional.empty();
    }

    private static SubmissionBinding manifestOf(DocumentValue.Mapping submission) {
        final Optional<DocumentValue.Mapping> manifest = submission.member(MANIFEST)
                .filter(DocumentValue.Mapping.class::isInstance)
                .map(DocumentValue.Mapping.class::cast);
        final String kind = manifest.map(held -> text(held, "kind")).orElse("empty");
        return new SubmissionBinding(
                rs.slingshot.agent.identity.ArtifactManifestKind.named(kind)
                        .orElse(rs.slingshot.agent.identity.ArtifactManifestKind.EMPTY),
                manifest.map(held -> whole(held, "artifact_rows")).orElse(0L),
                manifest.map(held -> whole(held, "artifact_bytes")).orElse(0L));
    }

    private static long requestStart(DocumentValue.Mapping submission) {
        return whole(submission, "request_start_unix_milliseconds") == 0
                ? System.currentTimeMillis()
                : whole(submission, "request_start_unix_milliseconds");
    }

    private static Optional<DocumentProvenance> provenanceOf(DocumentValue.Mapping submission,
                                                             Arriving arriving) {
        final Optional<DocumentValue> member = submission.member(PROVENANCE);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        final DocumentProvenance.Outcome read = DocumentProvenance.of(member.get(), thisBuild(),
                CommandContractIdentity.Bounds.from(arriving.contract()));
        return read instanceof final DocumentProvenance.Held held
                ? Optional.of(held.provenance())
                : Optional.empty();
    }

    private static Optional<OperationIdentity> identityOf(DocumentValue.Mapping submission,
                                                          Arriving arriving) {
        final Optional<DocumentValue> member = submission.member(OPERATION);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        final OperationIdentity.Outcome read =
                OperationIdentity.of(member.get(), arriving.contract());
        return read instanceof final OperationIdentity.Held held
                ? Optional.of(held.identity())
                : Optional.empty();
    }

    /**
     * What this build says its own contracts are, which a submission's provenance is compared with.
     *
     * @return the two digests this build speaks
     */
    public static DocumentProvenance.ThisBuild thisBuild() {
        return new DocumentProvenance.ThisBuild(
                digest(AgentContract.transportContractDigest()),
                rs.slingshot.agent.http.CapabilityServlet.canonicalContractDigest());
    }

    private static DigestValue digest(String rendered) {
        final DigestValue.Outcome held = DigestValue.of(rendered);
        if (held instanceof final DigestValue.Refused refused) {
            throw new IllegalStateException("this build's own digest is not one: "
                    + refused.detail());
        }
        return ((DigestValue.Held) held).digest();
    }

    /**
     * The groups an operator has permitted, which a deployment configures.
     *
     * <p>Read from the configuration every time rather than held, because an operator who widened
     * the configuration expects the next request to be admitted rather than the next restart.</p>
     *
     * @return the current immutable group set, empty while authorization is inactive
     */
    public static List<String> permittedGroups() {
        return AuthorizationGate.permittedGroups();
    }

    /**
     * Where a caller stands with respect to a group, asked of the repository the caller is on.
     *
     * <p>Asked of the user manager rather than of the servlet container, because the container can
     * only answer "is a member" and the interesting third answer — that nothing is called that — is
     * the one an operator who misspelled a group needs somebody to notice.</p>
     *
     * @param session the caller's own session
     * @param caller who is asking
     * @return where they stand, group by group
     */
    public static AuthorizationGate.Groups groupsOf(Session session, CallerIdentity caller) {
        return group -> standing(session, caller, group);
    }

    private static AuthorizationGate.Standing standing(Session session, CallerIdentity caller,
                                                       String group) {
        try {
            final org.apache.jackrabbit.api.security.user.UserManager users =
                    ((org.apache.jackrabbit.api.JackrabbitSession) session).getUserManager();
            final org.apache.jackrabbit.api.security.user.Authorizable named =
                    users.getAuthorizable(group);
            if (named == null || !named.isGroup()) {
                return AuthorizationGate.Standing.NO_SUCH_GROUP;
            }
            final org.apache.jackrabbit.api.security.user.Authorizable asking =
                    users.getAuthorizable(caller.authorizable());
            return asking != null
                    && ((org.apache.jackrabbit.api.security.user.Group) named).isMember(asking)
                    ? AuthorizationGate.Standing.A_MEMBER
                    : AuthorizationGate.Standing.NOT_A_MEMBER;
        } catch (final RepositoryException unreadable) {
            // A repository that cannot say where somebody stands has not said they are permitted.
            return AuthorizationGate.Standing.NOT_A_MEMBER;
        }
    }

    private static Optional<Session> sessionOf(SlingHttpServletRequest request) {
        return Optional.ofNullable(request.getResourceResolver().adaptTo(Session.class));
    }

    private static String text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse("");
    }

    private static long whole(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Whole.class::isInstance)
                .map(value -> ((DocumentValue.Whole) value).value())
                .orElse(0L);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * The route this servlet answers, read from the committed table.
     *
     * @return the route
     */
    public static AgentRoute route() {
        final rs.slingshot.agent.route.AgentRouteTable.Outcome outcome =
                rs.slingshot.agent.route.AgentRouteTable.load();
        if (outcome instanceof final rs.slingshot.agent.route.AgentRouteTable.Refused refused) {
            throw new IllegalStateException("no route table: " + refused.detail());
        }
        return ((rs.slingshot.agent.route.AgentRouteTable.Loaded) outcome).table().route(ROUTE_NAME);
    }
}
