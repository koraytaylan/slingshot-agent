// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.SubmissionAdmission;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * One declared payload arriving, written into the slot its manifest already reserved room for.
 *
 * <p>The order is what makes this safe. The submission is admitted first, so the record is the
 * claim and the manifest is what the record is waiting for; the room for every declared byte was
 * taken then, so a caller whose payloads will not fit learns it before sending one; and the
 * operation becomes startable only when the last declared slot completes, so a command never starts
 * against a payload that is still arriving.</p>
 *
 * <p>A payload whose length or digest is not what the manifest declared leaves nothing reachable
 * and leaves the slot claimable again. A partial payload under a record that looks complete is the
 * failure worth spending the most effort on: every later reader of that artifact would be reading
 * bytes nobody sent.</p>
 */
public final class IntakeSlotWrite {

    /** The child of an operation the slots a manifest declared live under. */
    public static final String NODE = "intake";

    /** The property a declared slot's own size is written in. */
    public static final String DECLARED_BYTES = "declared_byte_count";

    /** The property a declared slot's own digest is written in. */
    public static final String DECLARED_DIGEST = "declared_digest";

    private IntakeSlotWrite() {
    }

    /** Why a payload was not written. */
    public enum IntakeRefusal {
        /** Nothing durable holds the operation it is for. */
        NO_OPERATION,
        /** Its manifest declares no such slot. */
        UNDECLARED_SLOT,
        /** That slot already holds what it declared. */
        ALREADY_COMPLETE,
        /** The bytes that arrived are not as many as the manifest declared. */
        LENGTH_MISMATCH,
        /** The bytes that arrived are not the ones the manifest declared. */
        DIGEST_MISMATCH,
        /** The operation has already ended, so nothing is waiting for this payload. */
        TERMINAL_OPERATION
    }

    /** What writing one payload did. */
    public sealed interface Outcome permits Written, Refused, Unavailable {
    }

    /**
     * A payload the store now holds, with the slots still outstanding after it.
     *
     * @param slot which slot it went into
     * @param byteCount how many bytes arrived
     * @param digest what they digest to
     * @param outstanding how many declared slots are still waiting
     */
    public record Written(ArtifactSlot slot, long byteCount, DigestValue digest, long outstanding)
            implements Outcome {
    }

    /**
     * One that was not written.
     *
     * @param refusal why not
     * @param detail what was observed, naming neither the manifest nor any other slot
     */
    public record Refused(IntakeRefusal refusal, String detail) implements Outcome {
    }

    /** The store could not count or commit this upload, so the declaration remains retryable. */
    public record Unavailable() implements Outcome {
    }

    /**
     * One slot a manifest declares.
     *
     * @param slot the slot's own name
     * @param byteCount how many bytes it will carry
     * @param digest what those bytes will digest to
     */
    public record Declared(ArtifactSlot slot, long byteCount, DigestValue digest) {
    }

    /** The operation admission decision or the capacity that prevented it. */
    public sealed interface Admission permits Decided, AtCapacity, NotCounted {
    }

    /**
     * The ordinary operation admission outcome, after preparing any required intake capacity.
     *
     * @param outcome acceptance, recognition, or refusal of the operation
     */
    public record Decided(AdmissionOutcome outcome) implements Admission {
    }

    /**
     * The whole manifest was refused without recording an accepted operation.
     *
     * @param refusal the quantity and bound that prevented admission
     */
    public record AtCapacity(CapacityLedger.Refused refusal) implements Admission {
    }

    /**
     * No manifest was admitted because capacity could not be counted.
     *
     * @param outcome contention or missing prepared state
     */
    public record NotCounted(WriteOutcome outcome) implements Admission {
    }

    /**
     * Reserves a manifest and commits its declarations with operation acceptance.
     *
     * @param session the admission session
     * @param submission the operation being submitted
     * @param declared the payload declarations in manifest order
     * @param nowUnixMilliseconds the admission time
     * @param contract the authenticated bounds
     * @return the operation outcome or the capacity refusal
     * @throws RepositoryException if preparation, publication, or cleanup fails
     */
    public static Admission admit(Session session, SubmissionAdmission.Submission submission,
                                   List<Declared> declared, long nowUnixMilliseconds,
                                   AgentContract contract) throws RepositoryException {
        final List<Declared> manifest = List.copyOf(declared);
        if (manifest.isEmpty()
                || OperationStore.read(session, submission.identity()) instanceof OperationStore.Held) {
            return new Decided(SubmissionAdmission.admit(session, submission, nowUnixMilliseconds, contract));
        }
        try (CapacityReservation.Batch batch = CapacityReservation.batch(session, contract)) {
            for (final Declared slot : manifest) {
                if (batch.create(submission.caller(), charges(slot)).isEmpty()) {
                    return new NotCounted(WriteOutcome.CONTENDED);
                }
            }
            final CapacityLedger.Admission room =
                    CapacityLedger.reserve(session, batch.reservations(), contract);
            if (room instanceof final CapacityLedger.Refused refused) {
                return new AtCapacity(refused);
            }
            if (room instanceof final CapacityLedger.NotCounted missed) {
                return new NotCounted(missed.outcome());
            }
            return new Decided(SubmissionAdmission.admit(session, submission, nowUnixMilliseconds, contract,
                    node -> declare(node, manifest, batch.reservations())));
        }
    }

    private static List<CapacityReservation.Charge> charges(Declared slot) {
        return List.of(new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_BYTES, slot.byteCount()),
                new CapacityReservation.Charge(AccountedQuantity.OPERATION_RESERVATION_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.OPERATION_RESERVATION_BYTES,
                        slot.byteCount()));
    }

    private static void declare(Node record, List<Declared> declared,
                                 List<CapacityReservation> reservations) throws RepositoryException {
        final Node intake = record.addNode(NODE, "nt:unstructured");
        int index = 0;
        for (final Declared slot : declared) {
            final Node written = intake.addNode(slot.slot().name(), "nt:unstructured");
            written.setProperty(DECLARED_BYTES, slot.byteCount());
            written.setProperty(DECLARED_DIGEST, slot.digest().rendered());
            CapacityReservation.retain(record.getSession(), reservations.get(index), written);
            index = index + 1;
        }
    }

    /**
     * Writes one payload into the slot its manifest declared.
     *
     * @param session the caller's own session
     * @param caller whose share the room was taken from at admission
     * @param arriving the payload as it is arriving
     * @param contract the authenticated contract, which declares every bound
     * @return what writing it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome write(Session session, StatePath.Caller caller, Arriving arriving,
                                AgentContract contract) throws RepositoryException {
        if (!session.nodeExists(arriving.operation().path())) {
            return new Refused(IntakeRefusal.NO_OPERATION,
                    "nothing durable holds the operation this payload is for");
        }
        final Optional<Declared> declared = declaredAt(session, arriving);
        if (declared.isEmpty()) {
            // Naming neither the manifest nor another slot: a caller who could learn what a
            // manifest declares by sending payloads at it could survey somebody else's work.
            return new Refused(IntakeRefusal.UNDECLARED_SLOT,
                    "this operation is waiting for no such payload");
        }
        if (ended(session, arriving.operation())) {
            return new Refused(IntakeRefusal.TERMINAL_OPERATION,
                    "this operation has ended, so nothing is waiting for a payload");
        }
        if (ArtifactStore.read(session, arriving.operation(), arriving.slot()).isPresent()) {
            return new Refused(IntakeRefusal.ALREADY_COMPLETE,
                    "that slot already holds what it declared");
        }
        return streamed(session, caller, arriving, declared.get(), contract);
    }

    /**
     * One payload as it is arriving.
     *
     * @param operation the operation it belongs to
     * @param slot the slot it goes into
     * @param body the bytes, read once and never held
     * @param nowUnixMilliseconds what this side's clock says
     */
    public record Arriving(StatePath operation, ArtifactSlot slot, InputStream body,
                           long nowUnixMilliseconds) {
    }

    private static Outcome streamed(Session session, StatePath.Caller caller, Arriving arriving,
                                    Declared declared, AgentContract contract)
            throws RepositoryException {
        final ArtifactStore.Outcome written = ArtifactStore.publishReserved(session, caller,
                arriving.operation(),
                new ArtifactStore.Publication(arriving.slot(), declared.byteCount(),
                        arriving.body()),
                arriving.nowUnixMilliseconds(), contract,
                new ArtifactStore.Prepaid(arriving.operation().child(NODE).child(arriving.slot().name()),
                        declared.digest()));
        if (written instanceof final ArtifactStore.Refused refused) {
            if (refused.refusal() == ArtifactStore.Refusal.DIGEST_DIFFERS) {
                return new Refused(IntakeRefusal.DIGEST_MISMATCH, refused.detail());
            }
            return new Refused(refused.refusal() == ArtifactStore.Refusal.SIZE_DIFFERS
                    ? IntakeRefusal.LENGTH_MISMATCH
                    : IntakeRefusal.UNDECLARED_SLOT, refused.detail());
        }
        if (!(written instanceof final ArtifactStore.Published published)) {
            return new Unavailable();
        }
        return new Written(arriving.slot(), published.record().byteCount(),
                published.record().digest(), outstanding(session, arriving.operation()));
    }

    /**
     * How many declared slots are still waiting for their bytes.
     *
     * @param session the session to read under
     * @param operation the operation
     * @return the count, which is zero where an operation is startable
     * @throws RepositoryException if the repository fails
     */
    public static long outstanding(Session session, StatePath operation)
            throws RepositoryException {
        final String intake = operation.child(NODE).path();
        if (!session.nodeExists(intake)) {
            return 0;
        }
        long waiting = 0;
        final javax.jcr.NodeIterator slots = session.getNode(intake).getNodes();
        while (slots.hasNext()) {
            final Node slot = slots.nextNode();
            final ArtifactSlot.Outcome named = ArtifactSlot.of(slot.getName());
            if (named instanceof final ArtifactSlot.Held held
                    && ArtifactStore.read(session, operation, held.slot()).isEmpty()) {
                waiting = waiting + 1;
            }
        }
        return waiting;
    }

    /**
     * The slots one operation's manifest declared.
     *
     * @param session the session to read under
     * @param operation the operation
     * @return the declarations, in the order the store holds them
     * @throws RepositoryException if the repository fails
     */
    public static List<Declared> declaredBy(Session session, StatePath operation)
            throws RepositoryException {
        final String intake = operation.child(NODE).path();
        final List<Declared> declared = new ArrayList<>();
        if (!session.nodeExists(intake)) {
            return declared;
        }
        final javax.jcr.NodeIterator slots = session.getNode(intake).getNodes();
        while (slots.hasNext()) {
            final Node slot = slots.nextNode();
            final ArtifactSlot.Outcome named = ArtifactSlot.of(slot.getName());
            final DigestValue.Outcome digest = DigestValue.of(
                    slot.hasProperty(DECLARED_DIGEST)
                            ? slot.getProperty(DECLARED_DIGEST).getString() : "");
            if (named instanceof final ArtifactSlot.Held held
                    && digest instanceof final DigestValue.Held known) {
                declared.add(new Declared(held.slot(),
                        slot.hasProperty(DECLARED_BYTES)
                                ? slot.getProperty(DECLARED_BYTES).getLong() : 0,
                        known.digest()));
            }
        }
        return declared;
    }

    private static Optional<Declared> declaredAt(Session session, Arriving arriving)
            throws RepositoryException {
        return declaredBy(session, arriving.operation()).stream()
                .filter(declared -> declared.slot().name().equals(arriving.slot().name()))
                .findFirst();
    }

    private static boolean ended(Session session, StatePath operation) throws RepositoryException {
        final Node record = session.getNode(operation.path());
        return record.hasProperty(OperationStore.STATE)
                && rs.slingshot.agent.execution.OperationState
                        .named(record.getProperty(OperationStore.STATE).getString())
                        .filter(state -> state.finality() == JobEventKind.Finality.ENDS)
                        .isPresent();
    }

    /**
     * The one reason a payload was not written, where it was not.
     *
     * @param outcome what writing it did
     * @return the refusal, or nothing where it was written
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
