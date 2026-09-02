// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.StatePath;

/**
 * How many streams this instance is already holding, and whether it may hold another.
 *
 * <p>An open stream is an entry on this bundle's own executor and a response somebody is waiting
 * on, so the count is a bound on what this agent occupies rather than on what it stores. It goes
 * through the one capacity authority like everything else: a bound counted here would be a second
 * place that decides how much of somebody's author this product takes.</p>
 *
 * <p>Per caller as well as in total, because one client opening every permitted stream is one
 * client deciding that nobody else may follow anything.</p>
 */
public final class StreamAdmission {

    /** How much of the count one stream costs. */
    private static final long ONE_STREAM = 1;

    private StreamAdmission() {
    }

    /** The result of asking for room. */
    public sealed interface Outcome permits Admitted, Refused, NotCounted {
    }

    /** Room for one more stream. */
    public record Admitted() implements Outcome {
    }

    /**
     * No room, with the bound that was reached and which of the two it was.
     *
     * @param refusal what the capacity authority said
     */
    public record Refused(CapacityLedger.Refused refusal) implements Outcome {
    }

    /**
     * Nothing counted at all, which is a store nobody prepared or one under contention.
     *
     * @param notCounted what the capacity authority said
     */
    public record NotCounted(CapacityLedger.NotCounted notCounted) implements Outcome {
    }

    /**
     * Takes room for one stream.
     *
     * @param store the session to write under
     * @param caller whose share it comes out of
     * @param contract the authenticated contract, which declares both bounds
     * @return whether there is room
     * @throws RepositoryException if the repository fails
     */
    public static Outcome open(Session store, StatePath.Caller caller, AgentContract contract)
            throws RepositoryException {
        final CapacityLedger.Admission admitted = CapacityLedger.admit(store,
                AccountedQuantity.CONCURRENT_EVENT_STREAMS, caller, ONE_STREAM, contract);
        if (admitted instanceof CapacityLedger.Admitted) {
            return new Admitted();
        }
        return admitted instanceof final CapacityLedger.Refused refused
                ? new Refused(refused)
                : new NotCounted((CapacityLedger.NotCounted) admitted);
    }

    /**
     * Gives back the room one stream took, on every ending there is.
     *
     * @param store the session to write under
     * @param caller whose share it goes back to
     * @param contract the authenticated contract, which decides how the counts are spread
     * @throws RepositoryException if the repository fails
     */
    public static void close(Session store, StatePath.Caller caller, AgentContract contract)
            throws RepositoryException {
        CapacityLedger.release(store, AccountedQuantity.CONCURRENT_EVENT_STREAMS, caller,
                ONE_STREAM, contract);
    }

    /**
     * Prepares the counters a stream is admitted against.
     *
     * @param store the session to write under
     * @param caller who will be counted
     * @throws RepositoryException if the repository fails
     */
    public static void prepare(Session store, StatePath.Caller caller) throws RepositoryException {
        CapacityLedger.prepare(store, AccountedQuantity.CONCURRENT_EVENT_STREAMS, caller);
    }

    /**
     * The one reason there is no room, where that is why.
     *
     * @param outcome what asking produced
     * @return the refusal, or nothing where there is room or nothing was counted
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
