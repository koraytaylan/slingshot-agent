// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.List;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The event ledger's use of the one capacity authority, and not a second authority beside it.
 *
 * <p>An event costs two things at once — a row and its bytes — and both have to be admitted before
 * either is spent, or a store admits the row it has no bytes for. Both are activated in one
 * reservation transaction and cancelled by that identity if publication does not happen. Nothing
 * here counts anything itself: every number it moves is moved by
 * {@link CapacityLedger}, which is what makes a bound one thing to change rather than two.</p>
 */
public final class LedgerAdmission {

    /** How much of a row one event costs, which is one row. */
    private static final long ONE_ROW = 1;

    private LedgerAdmission() {
    }

    /** What admitting one event did. */
    public sealed interface Outcome permits Admitted, Refused, NotCounted {
    }

    /**
     * Room for one event, taken from both counts.
     *
     * @param bytes how many bytes were admitted alongside the row
     * @param reservation the identity paying for both quantities
     */
    public record Admitted(long bytes, CapacityReservation reservation) implements Outcome {
    }

    /**
     * No room, with the count that ran out and the bound it ran out against.
     *
     * @param refusal what the capacity authority said
     */
    public record Refused(CapacityLedger.Refused refusal) implements Outcome {
    }

    /**
     * Nothing was counted, which is a store that was never prepared or one under contention.
     *
     * @param notCounted what the capacity authority said
     */
    public record NotCounted(CapacityLedger.NotCounted notCounted) implements Outcome {
    }

    /**
     * Takes room for one event of a given size, or takes nothing at all.
     *
     * @param session the session to write under
     * @param caller whose share it comes out of
     * @param bytes how large the event is
     * @param contract the authenticated contract, which declares both bounds
     * @return what admitting it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome admit(Session session, StatePath.Caller caller, long bytes,
                                AgentContract contract) throws RepositoryException {
        final CapacityLedger.ReservationAdmission admission = CapacityLedger.take(session, caller,
                List.of(new CapacityReservation.Charge(AccountedQuantity.EVENT_ROWS, ONE_ROW),
                        new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES, bytes)), contract);
        if (admission instanceof final CapacityLedger.Reserved reserved) {
            return new Admitted(bytes, reserved.reservation());
        }
        return admission instanceof final CapacityLedger.Refused refused
                ? new Refused(refused) : new NotCounted((CapacityLedger.NotCounted) admission);
    }

    /**
     * Gives back the room one event took, where the event did not happen.
     *
     * @param session the session to write under
     * @param admitted the event's own reservation, preserved if publication already committed
     * @param contract the authenticated contract, which decides how the counts are spread
     * @throws RepositoryException if the repository fails
     */
    public static void release(Session session, Admitted admitted,
                               AgentContract contract) throws RepositoryException {
        CapacityLedger.cancel(session, admitted.reservation(), contract);
    }

    /**
     * Prepares the counters an event ledger is admitted against.
     *
     * @param session the session to write under
     * @param caller who will be counted
     * @throws RepositoryException if the repository fails
     */
    public static void prepare(Session session, StatePath.Caller caller)
            throws RepositoryException {
        CapacityLedger.prepare(session, AccountedQuantity.EVENT_ROWS, caller);
        CapacityLedger.prepare(session, AccountedQuantity.EVENT_BYTES, caller);
    }

    /**
     * The one reason there was no room, where that is why nothing was admitted.
     *
     * @param outcome what admitting it did
     * @return the refusal, or nothing where room was taken or nothing was counted at all
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }

}
