// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The event ledger's use of the one capacity authority, and not a second authority beside it.
 *
 * <p>An event costs two things at once — a row and its bytes — and both have to be admitted before
 * either is spent, or a store admits the row it has no bytes for. So this takes both, gives the
 * first back where the second is refused, and gives both back where the write that follows does not
 * happen. Nothing here counts anything itself: every number it moves is moved by
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
     */
    public record Admitted(long bytes) implements Outcome {
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
        final CapacityLedger.Admission rows = CapacityLedger.admit(session,
                AccountedQuantity.EVENT_ROWS, caller, ONE_ROW, contract);
        if (!(rows instanceof CapacityLedger.Admitted)) {
            return of(rows);
        }
        final CapacityLedger.Admission written = CapacityLedger.admit(session,
                AccountedQuantity.EVENT_BYTES, caller, bytes, contract);
        if (!(written instanceof CapacityLedger.Admitted)) {
            CapacityLedger.release(session, AccountedQuantity.EVENT_ROWS, caller, ONE_ROW,
                    contract);
            return of(written);
        }
        return new Admitted(bytes);
    }

    /**
     * Gives back the room one event took, where the event did not happen.
     *
     * @param session the session to write under
     * @param caller whose share it goes back to
     * @param bytes how large the event was going to be
     * @param contract the authenticated contract, which decides how the counts are spread
     * @throws RepositoryException if the repository fails
     */
    public static void release(Session session, StatePath.Caller caller, long bytes,
                               AgentContract contract) throws RepositoryException {
        CapacityLedger.release(session, AccountedQuantity.EVENT_BYTES, caller, bytes, contract);
        CapacityLedger.release(session, AccountedQuantity.EVENT_ROWS, caller, ONE_ROW, contract);
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

    private static Outcome of(CapacityLedger.Admission admission) {
        return admission instanceof final CapacityLedger.Refused refused
                ? new Refused(refused)
                : new NotCounted((CapacityLedger.NotCounted) admission);
    }
}
