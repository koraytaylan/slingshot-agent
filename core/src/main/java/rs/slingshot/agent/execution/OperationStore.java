// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.store.ClaimByCreation;
import rs.slingshot.agent.store.CompareAndSet;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/**
 * Where a logical operation is written down, and the only way it moves.
 *
 * <p>A record is created by claiming its own path, so acceptance and recording are one act: the
 * writer whose commit lands first owns the record, and a second writer reads what is there rather
 * than creating a second durable thing. A state moves only by compare-and-set from the exact state
 * the caller read, so a worker acting on a stale reading of the record cannot move it.</p>
 */
public final class OperationStore {
    /** The property the submission digest is written in. */
    public static final String SUBMISSION_DIGEST = "submission_digest";
    /** The property the command's wire name is written in. */
    public static final String WIRE_NAME = "command_wire_name";
    /** The property the command's semantic contract version is written in. */
    public static final String CONTRACT_VERSION = "command_semantic_contract_version";
    /** The property the command's limits digest is written in. */
    public static final String LIMITS_DIGEST = "command_contract_limits_digest";
    /** The property the command's argument schema digest is written in. */
    public static final String ARGUMENT_DIGEST = "argument_schema_digest";
    /** The property the command's result schema digest is written in. */
    public static final String RESULT_DIGEST = "result_schema_digest";
    /** The property the target this operation ran against is written in. */
    public static final String TARGET_DIGEST = "author_target_identity_digest";
    /** The property the environment revision is written in. */
    public static final String ENVIRONMENT_REVISION = "selected_environment_revision";
    /** The property the submitting caller is written in. */
    public static final String CALLER = "caller";
    /** The property the client's own request-start instant is written in. */
    public static final String REQUEST_START = "request_start_unix_milliseconds";
    /** The property the state is written in. */
    public static final String STATE = "state";
    /** The property the physical attempt count is written in. */
    public static final String ATTEMPTS = "attempts";

    private OperationStore() {
    }

    /** Why a record could not be created, read, or moved. */
    public enum Refusal {
        /** There is no record at that path, and reading for one does not create it. */
        NO_RECORD,
        /** The record is not in the state the caller read, so this move is not the one meant. */
        NOT_THE_STATE_THAT_WAS_READ,
        /** This build does not permit that move at all. */
        NOT_A_PERMITTED_MOVE,
        /** Somebody else was writing the record often enough that this writer gave up. */
        CONTENDED,
        /** A leased operation requires its current acquisition epoch for this transition. */
        FENCE_REQUIRED,
        /** What is written down is not a record this build can read back. */
        UNREADABLE
    }

    /** The result of asking the store: the record, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A record this store holds.
     *
     * @param operation the record
     */
    public record Held(LogicalOperation operation) implements Outcome {
    }

    /**
     * No record, for a reason.
     *
     * @param refusal why there is none
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * The result of creating: the record and whether this writer made it.
     *
     * @param operation the record this store now holds
     * @param outcome whether this writer created it or found it
     */
    public record Created(LogicalOperation operation, WriteOutcome outcome) {
    }

    /**
     * Creates a record for one submission, or reads the one already there.
     *
     * @param session the session to write under
     * @param operation the record to write
     * @return the record this store holds and whether this writer made it, or the one reason there
     *     is none
     * @throws RepositoryException if the repository fails
     */
    public static Object create(Session session, LogicalOperation operation)
            throws RepositoryException {
        return create(session, operation, node -> { });
    }

    /**
     * Creates an operation with the retained input declarations that belong to its acceptance.
     *
     * @param session the publication session
     * @param operation the operation being accepted
     * @param alongside initial values committed with the operation, only by the winning creator
     * @return the created or existing record, or the reason no record can be read
     * @throws RepositoryException if the repository or initial-value publication fails
     */
    public static Object create(Session session, LogicalOperation operation,
                                ClaimByCreation.InitialValues alongside) throws RepositoryException {
        final StatePath path = pathOf(operation.identity());
        bucketsFor(session, path);
        final WriteOutcome claimed = ClaimByCreation.claim(session, path, "nt:unstructured",
                node -> {
                    write(node, operation);
                    alongside.write(node);
                });
        final Outcome read = read(session, operation.identity());
        if (read instanceof final Refused refused) {
            return refused;
        }
        return new Created(((Held) read).operation(), claimed);
    }

    /**
     * The record one operation identity names.
     *
     * @param session the session to read under
     * @param identity which operation
     * @return the record, or the one reason there is none
     * @throws RepositoryException if the repository fails
     */
    public static Outcome read(Session session, OperationIdentity identity)
            throws RepositoryException {
        final StatePath path = pathOf(identity);
        session.refresh(false);
        if (!session.nodeExists(path.path())) {
            return new Refused(Refusal.NO_RECORD,
                    "no record at " + path.path() + ", and reading for one does not create it");
        }
        return readBack(session.getNode(path.path()), identity);
    }

    /**
     * Reads a recovery candidate using its known generation and durable target identity.
     *
     * @param session the recovery state session
     * @param generation the generation being reconciled
     * @param path the candidate operation path
     * @return the record, or a refusal if its identity does not name that exact path
     * @throws RepositoryException if the record cannot be read
     */
    public static Outcome readAt(Session session, rs.slingshot.agent.identity.EventStoreGeneration generation,
                                 StatePath path) throws RepositoryException {
        session.refresh(false);
        if (!session.nodeExists(path.path())) {
            return new Refused(Refusal.NO_RECORD, "the recovery candidate no longer exists");
        }
        final Node node = session.getNode(path.path());
        final java.util.SequencedMap<String, rs.slingshot.agent.json.DocumentValue> members =
                new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION,
                new rs.slingshot.agent.json.DocumentValue.Whole(generation.number()));
        members.put(OperationIdentity.IDENTIFIER,
                new rs.slingshot.agent.json.DocumentValue.Text(node.getName()));
        members.put(OperationIdentity.TARGET_DIGEST,
                new rs.slingshot.agent.json.DocumentValue.Text(node.getProperty(TARGET_DIGEST).getString()));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new rs.slingshot.agent.json.DocumentValue.Text(
                        node.getProperty(ENVIRONMENT_REVISION).getString()));
        final OperationIdentity.Outcome identity = OperationIdentity.of(
                new rs.slingshot.agent.json.DocumentValue.Mapping(members), loaded());
        if (!(identity instanceof final OperationIdentity.Held held)
                || !pathOf(held.identity()).equals(path)) {
            return new Refused(Refusal.UNREADABLE,
                    "the recovery candidate's identity does not name its path");
        }
        return readBack(node, held.identity());
    }

    /**
     * Moves a record to another state, only if it is still in the one the caller read.
     *
     * @param session the session to write under
     * @param operation the record as the caller read it
     * @param next the state to move to
     * @return the moved record, or the one reason it did not move
     * @throws RepositoryException if the repository fails
     */
    public static Outcome move(Session session, LogicalOperation operation, OperationState next)
            throws RepositoryException {
        return move(session, operation, next,
                transaction -> !transaction.nodeExists(ExecutionFence.pathOf(operation.identity()).path()));
    }

    /**
     * Moves a leased operation only while the presented acquisition epoch still owns it.
     *
     * @param session the write session
     * @param operation the operation as read by the worker
     * @param next the requested state
     * @param holder the worker's acquired fence
     * @param nowUnixMilliseconds when the authority must be live
     * @return the moved record, or the refusal without any committed writes
     * @throws RepositoryException if persistence fails
     */
    public static Outcome move(Session session, LogicalOperation operation, OperationState next,
                               FenceHolder holder, long nowUnixMilliseconds) throws RepositoryException {
        return move(session, operation, next,
                transaction -> ExecutionFence.stageHeld(transaction, operation.identity(), holder,
                        nowUnixMilliseconds));
    }

    /**
     * Starts accepted work with the durable resources required to record its completion.
     *
     * @param session the write session
     * @param operation the accepted record as read by the caller
     * @param alongside resources staged only with the winning start
     * @return the running record, or a refusal without committed resources
     * @throws RepositoryException if the start or resource publication fails
     */
    public static Outcome start(Session session, LogicalOperation operation,
                                ClaimByCreation.InitialValues alongside) throws RepositoryException {
        if (operation.state() != OperationState.ACCEPTED) {
            return new Refused(Refusal.NOT_A_PERMITTED_MOVE, "only accepted work can start");
        }
        return move(session, operation, OperationState.RUNNING,
                transaction -> !transaction.nodeExists(ExecutionFence.pathOf(operation.identity()).path()),
                alongside);
    }

    @FunctionalInterface
    private interface Authority {
        /**
         * Stages any authority fence required by the enclosing transition.
         *
         * @param session the enclosing transaction
         * @return whether its authority permits the transition
         * @throws RepositoryException if authority cannot be read or fenced
         */
        boolean permits(Session session) throws RepositoryException;
    }

    private static Outcome move(Session session, LogicalOperation operation, OperationState next,
                                Authority authority) throws RepositoryException {
        return move(session, operation, next, authority, node -> { });
    }

    private static Outcome move(Session session, LogicalOperation operation, OperationState next,
                                Authority authority, ClaimByCreation.InitialValues alongside)
            throws RepositoryException {
        final Optional<LogicalOperation> moved = operation.moved(next);
        if (moved.isEmpty()) {
            return new Refused(Refusal.NOT_A_PERMITTED_MOVE, operation.state().spelling()
                    + " does not move to " + next.spelling());
        }
        final StatePath path = pathOf(operation.identity());
        final Outcome current = read(session, operation.identity());
        if (current instanceof Refused) {
            return current;
        }
        if (((Held) current).operation().state() != operation.state()) {
            return new Refused(Refusal.NOT_THE_STATE_THAT_WAS_READ, "the record is "
                    + ((Held) current).operation().state().spelling() + " and this move was made"
                    + " from " + operation.state().spelling());
        }
        return written(session, path, moved.get(), operation.state(), authority, alongside);
    }

    private static Outcome written(Session session, StatePath path, LogicalOperation moved,
                                   OperationState from, Authority authority,
                                   ClaimByCreation.InitialValues alongside) throws RepositoryException {
        try {
            return stageMove(session, path, moved, from, authority, alongside);
        } finally {
            session.refresh(false);
        }
    }

    private static Outcome stageMove(Session session, StatePath path, LogicalOperation moved,
                                     OperationState from, Authority authority,
                                   ClaimByCreation.InitialValues alongside) throws RepositoryException {
        final Node node = session.getNode(path.path());
        CompareAndSet.stamp(node);
        if (!authority.permits(session)) {
            return new Refused(Refusal.FENCE_REQUIRED, "the current acquisition epoch is required");
        }
        if (!from.spelling().equals(node.getProperty(STATE).getString())) {
            session.refresh(false);
            return new Refused(Refusal.NOT_THE_STATE_THAT_WAS_READ,
                    "the record moved while this move was being made");
        }
        node.setProperty(STATE, moved.state().spelling());
        node.setProperty(ATTEMPTS, moved.attempts());
        alongside.write(node);
        try {
            session.save();
        } catch (final javax.jcr.InvalidItemStateException contended) {
            session.refresh(false);
            return new Refused(Refusal.CONTENDED,
                    "somebody else moved the record while this move was being made");
        }
        return new Held(moved);
    }

    /**
     * Claims every bucket a record sits under, so a first record in a bucket does not fail for
     * want of a parent nobody has created yet.
     */
    private static void bucketsFor(Session session, StatePath path) throws RepositoryException {
        final String[] segments = path.path().substring(StatePath.ROOT.length() + 1).split("/");
        final StringBuilder walked = new StringBuilder(StatePath.ROOT);
        int index = 0;
        while (index < segments.length - 1) {
            walked.append('/').append(segments[index]);
            if (!session.nodeExists(walked.toString())) {
                ClaimByCreation.claim(session, StatePath.deployment(
                        walked.substring(StatePath.ROOT.length() + 1)), "nt:unstructured",
                        node -> { });
            }
            index = index + 1;
        }
    }

    /**
     * Where one operation's record sits.
     *
     * @param identity which operation
     * @return the path
     */
    public static StatePath pathOf(OperationIdentity identity) {
        return StatePath.operation(identity.generation(), identity.identifier());
    }

    private static void write(Node node, LogicalOperation operation) {
        try {
            node.setProperty(SUBMISSION_DIGEST, operation.submissionDigest().rendered());
            node.setProperty(WIRE_NAME, operation.commandContract().wireName());
            node.setProperty(CONTRACT_VERSION, operation.commandContract().contractVersion());
            node.setProperty(LIMITS_DIGEST, operation.commandContract().limitsDigest().rendered());
            node.setProperty(ARGUMENT_DIGEST,
                    operation.commandContract().argumentSchemaDigest().rendered());
            node.setProperty(RESULT_DIGEST,
                    operation.commandContract().resultSchemaDigest().rendered());
            node.setProperty(TARGET_DIGEST, operation.identity().targetDigest().rendered());
            node.setProperty(ENVIRONMENT_REVISION, operation.identity().environmentRevision());
            node.setProperty(CALLER, operation.caller().name());
            node.setProperty(REQUEST_START, operation.requestStartUnixMilliseconds());
            node.setProperty(STATE, operation.state().spelling());
            node.setProperty(ATTEMPTS, operation.attempts());
        } catch (final RepositoryException unwritable) {
            throw new IllegalStateException("the record could not be written", unwritable);
        }
    }

    private static Outcome readBack(Node node, OperationIdentity asked)
            throws RepositoryException {
        final OperationIdentity identity = storedIdentity(node, asked);
        final Optional<OperationState> state =
                OperationState.named(node.getProperty(STATE).getString());
        final Optional<StatePath.Caller> caller = held(node.getProperty(CALLER).getString());
        final Optional<DigestValue> digest = digest(node.getProperty(SUBMISSION_DIGEST).getString());
        if (state.isEmpty() || caller.isEmpty() || digest.isEmpty()) {
            return new Refused(Refusal.UNREADABLE,
                    "what is written at " + node.getPath() + " is not a record this build reads");
        }
        return contract(node, identity, state.get(), caller.get(), digest.get());
    }

    /**
     * The identity the record holds, which is not always the one a caller asked about: an
     * identifier reused against another target is a different piece of work, and a record that
     * answered with the asking identity would agree with every submission that named it.
     */
    private static OperationIdentity storedIdentity(Node node, OperationIdentity asked)
            throws RepositoryException {
        final java.util.SequencedMap<String, rs.slingshot.agent.json.DocumentValue> members =
                new java.util.LinkedHashMap<>();
        members.put(OperationIdentity.GENERATION,
                new rs.slingshot.agent.json.DocumentValue.Whole(asked.generation().number()));
        members.put(OperationIdentity.IDENTIFIER,
                new rs.slingshot.agent.json.DocumentValue.Text(asked.identifier().rendered()));
        members.put(OperationIdentity.TARGET_DIGEST,
                new rs.slingshot.agent.json.DocumentValue.Text(
                        node.getProperty(TARGET_DIGEST).getString()));
        members.put(OperationIdentity.ENVIRONMENT_REVISION,
                new rs.slingshot.agent.json.DocumentValue.Text(
                        node.getProperty(ENVIRONMENT_REVISION).getString()));
        final OperationIdentity.Outcome held = OperationIdentity.of(
                new rs.slingshot.agent.json.DocumentValue.Mapping(members), loaded());
        return held instanceof final OperationIdentity.Held stored ? stored.identity() : asked;
    }

    private static Outcome contract(Node node, OperationIdentity identity, OperationState state,
                                    StatePath.Caller caller, DigestValue digest)
            throws RepositoryException {
        final Optional<DigestValue> limits = digest(node.getProperty(LIMITS_DIGEST).getString());
        final Optional<DigestValue> argument = digest(node.getProperty(ARGUMENT_DIGEST).getString());
        final Optional<DigestValue> result = digest(node.getProperty(RESULT_DIGEST).getString());
        if (limits.isEmpty() || argument.isEmpty() || result.isEmpty()) {
            return new Refused(Refusal.UNREADABLE, "the command contract written at "
                    + node.getPath() + " is not one this build reads");
        }
        final CommandContractIdentity.Outcome held = CommandContractIdentity.of(
                document(node, limits.get(), argument.get(), result.get()), IDENTITY_BOUNDS.get());
        if (held instanceof CommandContractIdentity.Refused) {
            return new Refused(Refusal.UNREADABLE, "the command contract written at "
                    + node.getPath() + " is not an identity");
        }
        return new Held(new LogicalOperation(identity, digest,
                ((CommandContractIdentity.Held) held).identity(), caller,
                CompareAndSet.held(node, REQUEST_START), state, CompareAndSet.held(node, ATTEMPTS)));
    }

    /**
     * The bounds an identity read back from the store is held to, which are the contract's own.
     *
     * <p>Held here rather than passed in because a record is read on paths where nothing else needs
     * the contract, and reading a bound from anywhere but the contract is the defect the contract
     * exists to prevent.</p>
     */
    private static final java.util.function.Supplier<CommandContractIdentity.Bounds>
            IDENTITY_BOUNDS = () -> CommandContractIdentity.Bounds.from(loaded());

    private static AgentContract loaded() {
        final AgentContract.Outcome outcome = AgentContract.load();
        if (outcome instanceof final AgentContract.Refused refused) {
            throw new IllegalStateException("no contract: " + refused.detail());
        }
        return ((AgentContract.Loaded) outcome).contract();
    }

    private static rs.slingshot.agent.json.DocumentValue document(Node node, DigestValue limits,
                                                                  DigestValue argument,
                                                                  DigestValue result)
            throws RepositoryException {
        final java.util.SequencedMap<String, rs.slingshot.agent.json.DocumentValue> members =
                new java.util.LinkedHashMap<>();
        members.put(CommandContractIdentity.WIRE_NAME, new rs.slingshot.agent.json.DocumentValue
                .Text(node.getProperty(WIRE_NAME).getString()));
        members.put(CommandContractIdentity.CONTRACT_VERSION,
                new rs.slingshot.agent.json.DocumentValue.Text(
                        node.getProperty(CONTRACT_VERSION).getString()));
        members.put(CommandContractIdentity.LIMITS_DIGEST,
                new rs.slingshot.agent.json.DocumentValue.Text(limits.rendered()));
        members.put(CommandContractIdentity.ARGUMENT_DIGEST,
                new rs.slingshot.agent.json.DocumentValue.Text(argument.rendered()));
        members.put(CommandContractIdentity.RESULT_DIGEST,
                new rs.slingshot.agent.json.DocumentValue.Text(result.rendered()));
        return new rs.slingshot.agent.json.DocumentValue.Mapping(members);
    }

    private static Optional<StatePath.Caller> held(String name) {
        final StatePath.Outcome outcome = StatePath.caller(name);
        return outcome instanceof final StatePath.Held caller
                ? Optional.of(caller.caller())
                : Optional.empty();
    }

    private static Optional<DigestValue> digest(String rendered) {
        final DigestValue.Outcome outcome = DigestValue.of(rendered);
        return outcome instanceof final DigestValue.Held held
                ? Optional.of(held.digest())
                : Optional.empty();
    }
}
