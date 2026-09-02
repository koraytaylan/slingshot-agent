// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
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

    /** Whether the room for these bytes still has to be taken, or was taken already. */
    public enum Reservation {
        /** Take it now, which is what a command producing an answer does. */
        TAKE_IT_NOW,
        /**
         * It was taken already, which is what an inbound payload does.
         *
         * <p>A manifest declares its byte counts before anything is sent and the whole declaration
         * is reserved when the submission is admitted, so a caller whose payloads will not fit
         * learns it before sending one. Reserving again here would charge a retried upload twice.
         * </p>
         */
        ALREADY_TAKEN
    }

    /**
     * Publishes one artifact, or publishes nothing at all.
     *
     * @param session the session to write under
     * @param caller whose share the row and the bytes come out of
     * @param operation the operation the artifact belongs to
     * @param publication the artifact as its caller offered it
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @return what publishing it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome publish(Session session, StatePath.Caller caller, StatePath operation,
                                  Publication publication, long nowUnixMilliseconds,
                                  AgentContract contract) throws RepositoryException {
        return publish(session, caller, operation, publication, nowUnixMilliseconds, contract,
                Reservation.TAKE_IT_NOW);
    }

    /**
     * Publishes one artifact whose room is taken now or was taken already.
     *
     * @param session the session to write under
     * @param caller whose share the row and the bytes come out of
     * @param operation the operation the artifact belongs to
     * @param publication the artifact as its caller offered it
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @param reservation whether the room still has to be taken
     * @return what publishing it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome publish(Session session, StatePath.Caller caller, StatePath operation,
                                  Publication publication, long nowUnixMilliseconds,
                                  AgentContract contract, Reservation reservation)
            throws RepositoryException {
        if (!session.nodeExists(operation.path())) {
            return new Refused(Refusal.NO_OPERATION, "there is no operation at " + operation.path()
                    + " for an artifact to belong to");
        }
        final StatePath slot = publication.slot().under(operation);
        if (session.nodeExists(slot.path())) {
            return new Refused(Refusal.SLOT_TAKEN, slot.path() + " already holds an artifact, and"
                    + " a slot holds one");
        }
        return reservation == Reservation.TAKE_IT_NOW
                ? reserved(session, caller, operation, publication, nowUnixMilliseconds, contract)
                : streamed(session, caller, operation, publication, nowUnixMilliseconds, contract,
                        Reservation.ALREADY_TAKEN);
    }

    private static Outcome reserved(Session session, StatePath.Caller caller, StatePath operation,
                                    Publication publication, long nowUnixMilliseconds,
                                    AgentContract contract) throws RepositoryException {
        final CapacityLedger.Admission rows = CapacityLedger.admit(session,
                AccountedQuantity.ARTIFACT_ROWS, caller, ONE_ROW, contract);
        if (!(rows instanceof CapacityLedger.Admitted)) {
            return of(rows);
        }
        final CapacityLedger.Admission bytes = CapacityLedger.admit(session,
                AccountedQuantity.ARTIFACT_BYTES, caller, publication.declaredByteCount(),
                contract);
        if (!(bytes instanceof CapacityLedger.Admitted)) {
            CapacityLedger.release(session, AccountedQuantity.ARTIFACT_ROWS, caller, ONE_ROW,
                    contract);
            return of(bytes);
        }
        return streamed(session, caller, operation, publication, nowUnixMilliseconds, contract,
                Reservation.TAKE_IT_NOW);
    }

    private static Outcome streamed(Session session, StatePath.Caller caller, StatePath operation,
                                    Publication publication, long nowUnixMilliseconds,
                                    AgentContract contract, Reservation reservation)
            throws RepositoryException {
        ClaimByCreation.claim(session, operation.child(NODE), "nt:unstructured", node -> { });
        final Staged staged;
        try (Counted counted = new Counted(digesting(publication.content()))) {
            final Binary binary = session.getValueFactory().createBinary(counted);
            staged = new Staged(binary, counted.bytes(),
                    DigestValue.ofBytes(counted.digested().digest()));
        } catch (final IOException unreadable) {
            releasing(session, caller, publication, contract, reservation);
            return new Refused(Refusal.TRANSFER_FAILED, "the bytes could not be read to their end: "
                    + unreadable.getMessage());
        }
        if (staged.byteCount() != publication.declaredByteCount()) {
            releasing(session, caller, publication, contract, reservation);
            return new Refused(Refusal.SIZE_DIFFERS, staged.byteCount() + " bytes arrived and "
                    + publication.declaredByteCount() + " were declared, so nothing was written at"
                    + " all");
        }
        return committed(session, caller,
                new Committing(operation, publication, staged, reservation), nowUnixMilliseconds,
                contract);
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
     * @param reservation whether the room was taken here or already
     */
    private record Committing(StatePath operation, Publication publication, Staged staged,
                              Reservation reservation) {
    }

    private static Outcome committed(Session session, StatePath.Caller caller,
                                     Committing committing, long nowUnixMilliseconds,
                                     AgentContract contract) throws RepositoryException {
        final StatePath operation = committing.operation();
        final Publication publication = committing.publication();
        final Staged staged = committing.staged();
        final Reservation reservation = committing.reservation();
        try {
            final Node written = session.getNode(operation.child(NODE).path())
                    .addNode(publication.slot().name(), "nt:unstructured");
            written.setProperty(CONTENT, staged.binary());
            written.setProperty(BYTE_COUNT, staged.byteCount());
            written.setProperty(DIGEST, staged.digest().rendered());
            written.setProperty(PUBLISHED_AT, nowUnixMilliseconds);
            session.save();
        } catch (final javax.jcr.ItemExistsException | javax.jcr.InvalidItemStateException taken) {
            // Somebody else took this slot while these bytes were arriving. One slot holds one
            // artifact, and the writer that lost gives back everything it reserved.
            session.refresh(false);
            releasing(session, caller, publication, contract, reservation);
            return new Refused(Refusal.SLOT_TAKEN, "another writer committed "
                    + publication.slot().name() + " while these bytes were arriving");
        }
        return new Published(new ArtifactRecord(publication.slot(), staged.byteCount(),
                staged.digest(), nowUnixMilliseconds));
    }

    private static void releasing(Session session, StatePath.Caller caller,
                                  Publication publication, AgentContract contract,
                                  Reservation reservation) throws RepositoryException {
        if (reservation == Reservation.TAKE_IT_NOW) {
            release(session, caller, publication, contract);
        }
    }

    private static void release(Session session, StatePath.Caller caller, Publication publication,
                                AgentContract contract) throws RepositoryException {
        CapacityLedger.release(session, AccountedQuantity.ARTIFACT_BYTES, caller,
                publication.declaredByteCount(), contract);
        CapacityLedger.release(session, AccountedQuantity.ARTIFACT_ROWS, caller, ONE_ROW, contract);
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

    private static Outcome of(CapacityLedger.Admission admission) {
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
