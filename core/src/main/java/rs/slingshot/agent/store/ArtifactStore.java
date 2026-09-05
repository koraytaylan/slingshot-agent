// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;

/**
 * Bytes too large to carry in an answer, published so that a reference to them is always good.
 *
 * <p>The order is the whole design. Capacity is reserved from the size the caller declares before a
 * single byte is read, because a store that discovers it is full halfway through has already spent
 * what it was protecting. The bytes stream through this side without ever being held, because an
 * agent that buffered an artifact would be an agent whose memory is decided by whoever asks it for
 * one. And the content, the count, and the digest are committed together, because a reference to an
 * artifact that is half there — with a digest that looks right — is the failure worth spending the
 * most effort on: it is indistinguishable, to a reader, from a correct answer.</p>
 *
 * <p>A written size that differs from the declared size is refused and nothing is committed at all.
 * The staged write is discarded rather than removed afterwards, so there is no instant at which
 * anything could have read it.</p>
 */
public final class ArtifactStore {

    /** The child of an operation artifacts live under. */
    public static final String NODE = "artifacts";

    /** The property the bytes themselves are written in. */
    public static final String CONTENT = "content";

    /** The property the number of bytes written is written in. */
    public static final String BYTE_COUNT = "byte_count";

    /** The property what those bytes digest to is written in. */
    public static final String DIGEST = "digest";

    /** The property the instant the bytes became reachable is written in. */
    public static final String PUBLISHED_AT = "published_at_unix_milliseconds";

    /** How much of a row one artifact costs, which is one row. */
    private static final long ONE_ROW = 1;

    private ArtifactStore() {
    }

    /** Why nothing was published. */
    public enum Refusal {
        /** There is no operation to publish under. */
        NO_OPERATION,
        /** Something is already in that slot, and a slot holds one artifact. */
        SLOT_TAKEN,
        /** The bytes that arrived are not as many as the caller said they would be. */
        SIZE_DIFFERS,
        /** The bytes do not have the digest promised by the intake declaration. */
        DIGEST_DIFFERS,
        /** The bytes could not be read to their end. */
        TRANSFER_FAILED
    }

    /** What publishing did. */
    public sealed interface Outcome permits Published, Refused, AtCapacity, NotCounted {
    }

    /**
     * Bytes this store now holds, and a reference to them that is good.
     *
     * @param record what was written, with the count and the digest it was written with
     */
    public record Published(ArtifactRecord record) implements Outcome {
    }

    /**
     * Nothing published, for a reason about the artifact itself.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Nothing published, because the store has no room for what was declared.
     *
     * @param refusal what the capacity authority said, naming the bound that was reached
     */
    public record AtCapacity(CapacityLedger.Refused refusal) implements Outcome {
    }

    /**
     * Nothing published, because nothing was counted at all.
     *
     * @param notCounted what the capacity authority said
     */
    public record NotCounted(CapacityLedger.NotCounted notCounted) implements Outcome {
    }

    /**
     * One artifact as its caller offered it.
     *
     * @param slot where it goes
     * @param declaredByteCount how many bytes the caller says there are
     * @param content the bytes, which are read once and never held
     */
    public record Publication(ArtifactSlot slot, long declaredByteCount, InputStream content) {
    }

    /**
     * The durable intake promise and digest required before replacing its ownership.
     *
     * @param declaration where the retained declaration lives
     * @param expected the promised digest
     */
    public record Prepaid(StatePath declaration, DigestValue expected) {
    }

    /**
     * Reserves and publishes one artifact, or publishes nothing.
     *
     * @param session the publication session
     * @param caller whose row and bytes are charged
     * @param operation the owning operation
     * @param publication the offered bytes and declared length
     * @param nowUnixMilliseconds the publication time
     * @param contract the authenticated bounds
     * @return publication or the reason nothing was published
     * @throws RepositoryException if the repository fails
     */
    public static Outcome publish(Session session, StatePath.Caller caller, StatePath operation,
                                  Publication publication, long nowUnixMilliseconds,
                                  AgentContract contract) throws RepositoryException {
        final Optional<Refused> refused = before(session, operation, publication);
        return refused.isPresent() ? refused.get()
                : reserved(session, caller, operation, publication, nowUnixMilliseconds, contract);
    }

    /**
     * Publishes declared input by transferring its retained reservation to the validated artifact.
     *
     * @param session the publication session
     * @param caller the declaration owner
     * @param operation the owning operation
     * @param publication the offered bytes and declared length
     * @param nowUnixMilliseconds the publication time
     * @param contract the authenticated bounds
     * @param prepaid the retained declaration and the digest required before publication
     * @return publication or the reason the declaration remains outstanding
     * @throws RepositoryException if reservation ownership or the repository fails
     */
    public static Outcome publishReserved(Session session, StatePath.Caller caller, StatePath operation,
                                          Publication publication, long nowUnixMilliseconds,
                                          AgentContract contract, Prepaid prepaid)
            throws RepositoryException {
        final Optional<Refused> refused = before(session, operation, publication);
        if (refused.isPresent()) {
            return refused.get();
        }
        final Optional<CapacityReservation> pending = CapacityReservation.create(session,
                CapacityReservation.processOwner(), caller, charges(publication));
        if (pending.isEmpty()) {
            return contended();
        }
        try (CapacityReservation.Guard guard = pending.get().guard(session, contract)) {
            return streamed(session, operation, publication, nowUnixMilliseconds,
                    node -> CapacityLedger.transfer(session, session.getNode(prepaid.declaration().path()),
                            node, guard.reservation(), contract),
                    prepaid.expected()::matches);
        }
    }

    private static Optional<Refused> before(Session session, StatePath operation, Publication publication)
            throws RepositoryException {
        if (!session.nodeExists(operation.path())) {
            return Optional.of(new Refused(Refusal.NO_OPERATION,
                    "there is no operation at " + operation.path()));
        }
        final StatePath slot = publication.slot().under(operation);
        return session.nodeExists(slot.path())
                ? Optional.of(new Refused(Refusal.SLOT_TAKEN, slot.path() + " already holds an artifact"))
                : Optional.empty();
    }

    private static List<CapacityReservation.Charge> charges(Publication publication) {
        return List.of(new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_ROWS, ONE_ROW),
                new CapacityReservation.Charge(AccountedQuantity.ARTIFACT_BYTES,
                        publication.declaredByteCount()));
    }

    private static Outcome reserved(Session session, StatePath.Caller caller, StatePath operation,
                                    Publication publication, long nowUnixMilliseconds,
                                    AgentContract contract) throws RepositoryException {
        final CapacityLedger.ReservationAdmission admission = CapacityLedger.take(session, caller,
                charges(publication), contract);
        if (!(admission instanceof final CapacityLedger.Reserved reserved)) {
            return of(admission);
        }
        try (CapacityReservation.Guard guard = reserved.reservation().guard(session, contract)) {
            return streamed(session, operation, publication, nowUnixMilliseconds,
                    node -> {
                        CapacityReservation.retain(node.getSession(), guard.reservation(), node);
                        return new CapacityLedger.Admitted(AccountedQuantity.ARTIFACT_ROWS, ONE_ROW);
                    }, digest -> true);
        }
    }

    /** Stages ownership in the same repository transaction as the artifact. */
    @FunctionalInterface
    private interface Binding {

        /**
         * Binds the artifact to the capacity paying for it.
         *
         * @param node the staged artifact
         * @return admission of ownership or a capacity refusal
         * @throws RepositoryException if ownership cannot be retained
         */
        CapacityLedger.Admission accept(Node node) throws RepositoryException;
    }

    private static Outcome streamed(Session session, StatePath operation,
                                    Publication publication, long nowUnixMilliseconds,
                                    Binding binding, Predicate<DigestValue> acceptsDigest)
            throws RepositoryException {
        if (ClaimByCreation.claim(session, operation.child(NODE), "nt:unstructured", node -> { })
                == WriteOutcome.CONTENDED) {
            return contended();
        }
        final Staged staged;
        try (Counted counted = new Counted(digesting(publication.content()))) {
            final Binary binary = session.getValueFactory().createBinary(counted);
            staged = new Staged(binary, counted.bytes(),
                    DigestValue.ofBytes(counted.digested().digest()));
        } catch (final IOException unreadable) {
            return new Refused(Refusal.TRANSFER_FAILED, "the bytes could not be read to their end: "
                    + unreadable.getMessage());
        }
        try {
            if (staged.byteCount() != publication.declaredByteCount()) {
                return new Refused(Refusal.SIZE_DIFFERS, staged.byteCount() + " bytes arrived and "
                        + publication.declaredByteCount() + " were declared, so nothing was written at"
                        + " all");
            }
            if (!acceptsDigest.test(staged.digest())) {
                return new Refused(Refusal.DIGEST_DIFFERS, "the bytes differ from the declared digest");
            }
            return committed(session, new Committing(operation, publication, staged, binding),
                    nowUnixMilliseconds);
        } finally {
            staged.binary().dispose();
        }
    }

    /**
     * Bytes that have arrived and are not yet anywhere anybody can read them.
     *
     * @param binary the bytes, held by the store rather than by this side
     * @param byteCount how many arrived
     * @param digest what they digest to
     */
    private record Staged(Binary binary, long byteCount, DigestValue digest) {
    }

    /**
     * One artifact being committed, as everything that decides the commit.
     *
     * @param operation where it goes
     * @param publication what its caller offered
     * @param staged the bytes that arrived, with their count and digest
     * @param binding stages the reservation ownership alongside the artifact
     */
    private record Committing(StatePath operation, Publication publication, Staged staged,
                              Binding binding) {
    }

    private static Outcome committed(Session session, Committing committing,
                                     long nowUnixMilliseconds) throws RepositoryException {
        final StatePath operation = committing.operation();
        final Publication publication = committing.publication();
        final Staged staged = committing.staged();
        try {
            final Node parent = session.getNode(operation.child(NODE).path());
            CompareAndSet.stamp(parent);
            final Node written = parent.addNode(publication.slot().name(), "nt:unstructured");
            written.setProperty(CONTENT, staged.binary());
            written.setProperty(BYTE_COUNT, staged.byteCount());
            written.setProperty(DIGEST, staged.digest().rendered());
            written.setProperty(PUBLISHED_AT, nowUnixMilliseconds);
            final CapacityLedger.Admission ownership = committing.binding().accept(written);
            if (!(ownership instanceof CapacityLedger.Admitted)) {
                return ownership instanceof final CapacityLedger.Refused refused
                        ? new AtCapacity(refused) : new NotCounted((CapacityLedger.NotCounted) ownership);
            }
            session.save();
        } catch (final javax.jcr.ItemExistsException taken) {
            session.refresh(false);
            return occupied(publication);
        } catch (final javax.jcr.InvalidItemStateException contended) {
            session.refresh(false);
            return session.nodeExists(publication.slot().under(operation).path())
                    ? occupied(publication) : contended();
        }
        return new Published(new ArtifactRecord(publication.slot(), staged.byteCount(),
                staged.digest(), nowUnixMilliseconds));
    }

    private static Refused occupied(Publication publication) {
        return new Refused(Refusal.SLOT_TAKEN, "another writer committed "
                + publication.slot().name() + " while these bytes were arriving");
    }

    private static NotCounted contended() {
        return new NotCounted(new CapacityLedger.NotCounted(AccountedQuantity.ARTIFACT_ROWS,
                WriteOutcome.CONTENDED));
    }

    /**
     * What one slot holds, where anything does.
     *
     * @param session the session to read under
     * @param operation the operation
     * @param slot the slot
     * @return the record, or nothing where the slot is empty
     * @throws RepositoryException if the repository fails
     */
    public static Optional<ArtifactRecord> read(Session session, StatePath operation,
                                                ArtifactSlot slot) throws RepositoryException {
        final StatePath path = slot.under(operation);
        if (!session.nodeExists(path.path())) {
            return Optional.empty();
        }
        final Node held = session.getNode(path.path());
        final DigestValue.Outcome digest = DigestValue.of(held.getProperty(DIGEST).getString());
        return digest instanceof final DigestValue.Held known
                ? Optional.of(new ArtifactRecord(slot, held.getProperty(BYTE_COUNT).getLong(),
                        known.digest(), held.getProperty(PUBLISHED_AT).getLong()))
                : Optional.empty();
    }

    /**
     * Opens one artifact's bytes, which the reader closes and may digest for itself.
     *
     * @param session the session to read under
     * @param operation the operation
     * @param slot the slot
     * @return the bytes, or nothing where the slot is empty
     * @throws RepositoryException if the repository fails
     */
    public static Optional<InputStream> open(Session session, StatePath operation,
                                             ArtifactSlot slot) throws RepositoryException {
        final StatePath path = slot.under(operation);
        return session.nodeExists(path.path())
                ? Optional.of(session.getNode(path.path()).getProperty(CONTENT).getBinary()
                        .getStream())
                : Optional.empty();
    }

    /**
     * Prepares the counters an artifact is admitted against.
     *
     * @param session the session to write under
     * @param caller who will be counted
     * @throws RepositoryException if the repository fails
     */
    public static void prepare(Session session, StatePath.Caller caller)
            throws RepositoryException {
        CapacityLedger.prepare(session, AccountedQuantity.ARTIFACT_ROWS, caller);
        CapacityLedger.prepare(session, AccountedQuantity.ARTIFACT_BYTES, caller);
    }

    /**
     * The one reason nothing was published, where that is why.
     *
     * @param outcome what publishing did
     * @return the refusal, or nothing where something was published or the store had no room
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }

    private static Outcome of(CapacityLedger.ReservationAdmission admission) {
        return admission instanceof final CapacityLedger.Refused refused
                ? new AtCapacity(refused)
                : new NotCounted((CapacityLedger.NotCounted) admission);
    }

    private static DigestInputStream digesting(InputStream content) {
        try {
            return new DigestInputStream(content, MessageDigest.getInstance(Digest.ALGORITHM));
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException(Digest.ALGORITHM
                    + " is required of every Java platform", absent);
        }
    }

    /**
     * A stream that counts what passes through it and digests it on the way.
     *
     * <p>Counting here rather than afterwards is what makes the size check possible without holding
     * the bytes: nothing this class does depends on how many there are. It is visible to this
     * package's own suite because which of the two reads a store happens to use is the store's
     * decision, and both of them have to count.</p>
     */
    static final class Counted extends FilterInputStream {

        private final DigestInputStream digesting;

        /** How many bytes have passed through, held where a counter may be held. */
        private final java.util.concurrent.atomic.AtomicLong bytes =
                new java.util.concurrent.atomic.AtomicLong();

        /**
         * Wraps one stream so its bytes are counted and digested as they pass.
         *
         * @param digesting the stream to count, already digesting
         */
        Counted(DigestInputStream digesting) {
            super(digesting);
            this.digesting = digesting;
        }

        /**
         * Reads one byte and counts it.
         *
         * @return the byte, or the end of the stream
         * @throws IOException if the stream fails
         */
        @Override
        public int read() throws IOException {
            final int read = super.read();
            if (read >= 0) {
                bytes.incrementAndGet();
            }
            return read;
        }

        /**
         * Reads a run of bytes and counts them.
         *
         * @param into where to put them
         * @param from where in there to start
         * @param length how many to read at most
         * @return how many were read, or the end of the stream
         * @throws IOException if the stream fails
         */
        @Override
        public int read(byte[] into, int from, int length) throws IOException {
            final int read = super.read(into, from, length);
            if (read > 0) {
                bytes.addAndGet(read);
            }
            return read;
        }

        /**
         * How many bytes have passed through so far.
         *
         * @return the count
         */
        long bytes() {
            return bytes.get();
        }

        /**
         * The digest of everything that has passed through so far.
         *
         * @return the digest
         */
        MessageDigest digested() {
            return digesting.getMessageDigest();
        }
    }
}
