// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The one place work is admitted against what this store may hold.
 *
 * <p>It sits with the store primitives rather than beside its callers because everything after it
 * admits against these counts — the operation record, the event ledger, the subscription ledger,
 * the artifact store, and the intake a manifest declares. An authority that arrived after its
 * callers would be an authority several of them had already written their own version of, and two
 * admission paths over one count is exactly the arrangement in which the total stops meaning
 * anything.</p>
 *
 * <p>Admission advances first and checks afterwards, releasing the advance exactly when the check
 * refuses. Checking and then advancing is the arrangement in which two nodes both see the old total
 * and both admit; advancing and then checking means the loser has already been counted when it
 * looks. A refused admission therefore leaves the count where it found it rather than permanently
 * above the bound.</p>
 */
public final class CapacityLedger {

    private CapacityLedger() {
    }

    /** Which bound an admission was refused at. */
    public enum Reached {
        /** The whole generation's bound. */
        THE_TOTAL,
        /** One submitting caller's share of it. */
        THE_CALLERS_SHARE
    }

    /** The result of admitting: it was admitted, or the one reason it was not. */
    public sealed interface Admission permits Admitted, Refused, NotCounted {
    }

    /**
     * Work this store has room for, and has now counted.
     *
     * @param quantity what was counted
     * @param amount how much of it
     */
    public record Admitted(AccountedQuantity quantity, long amount) implements Admission {
    }

    /**
     * Work this store has no room for, which has not been counted.
     *
     * @param quantity what was being counted
     * @param reached which bound was reached
     * @param bound the number that was reached
     * @param wouldHaveBeen what the count would have come to
     */
    public record Refused(AccountedQuantity quantity, Reached reached, long bound,
                          long wouldHaveBeen) implements Admission {

        /**
         * Renders the refusal the way a failure message states one.
         *
         * @return the rendering, naming the quantity, the bound, and the value that crossed it
         */
        public String rendered() {
            return quantity.spelling() + " would come to " + wouldHaveBeen + ", past " + bound
                    + ", which is " + (reached == Reached.THE_TOTAL
                            ? "what this store may hold"
                            : "one caller's share of what this store may hold");
        }
    }

    /**
     * An admission that could not be decided, because the counting itself did not happen.
     *
     * <p>Distinct from a refusal on purpose: a caller told "there is no room" would stop asking, and
     * a caller told "the count could not be written" knows the store is in trouble rather than
     * full.</p>
     *
     * @param quantity what was being counted
     * @param outcome what the write did instead
     */
    public record NotCounted(AccountedQuantity quantity, WriteOutcome outcome)
            implements Admission {
    }

    /**
     * Admits work, counting it against the total and the caller's share at once.
     *
     * @param session the session to write under
     * @param quantity what to count
     * @param caller who is submitting
     * @param amount how much of it
     * @param contract the authenticated contract, which declares both bounds
     * @return whether it was admitted, and the one reason it was not
     * @throws RepositoryException if the repository fails
     */
    public static Admission admit(Session session, AccountedQuantity quantity,
                                  StatePath.Caller caller, long amount, AgentContract contract)
            throws RepositoryException {
        final StatePath total = totalPath(quantity);
        final StatePath share = callerPath(quantity, caller);
        if (!session.nodeExists(total.path()) || !session.nodeExists(share.path())) {
            // A counter that is not there is a store that was never prepared, which is a different
            // answer from a store that is full: a caller told "no room" would stop asking.
            return new NotCounted(quantity, WriteOutcome.VALUE_CHANGED);
        }
        final int shards = quantity.totalShards(contract);
        final WriteOutcome counted =
                ShardedCount.advance(session, total, caller.name(), amount, shards);
        if (counted != WriteOutcome.WRITTEN) {
            return new NotCounted(quantity, counted);
        }
        final long now = ShardedCount.total(session, total, shards);
        if (now > quantity.admissibleTotal(contract)) {
            release(session, total, caller, amount, shards);
            return new Refused(quantity, Reached.THE_TOTAL, quantity.admissibleTotal(contract), now);
        }
        return againstTheCallersShare(session, quantity, caller, amount, contract, total, share);
    }

    private static Admission againstTheCallersShare(Session session, AccountedQuantity quantity,
                                                    StatePath.Caller caller, long amount,
                                                    AgentContract contract, StatePath total,
                                                    StatePath share) throws RepositoryException {
        final int shards = quantity.callerShards(contract);
        final WriteOutcome counted =
                ShardedCount.advance(session, share, caller.name(), amount, shards);
        if (counted != WriteOutcome.WRITTEN) {
            release(session, total, caller, amount, quantity.totalShards(contract));
            return new NotCounted(quantity, counted);
        }
        final long now = ShardedCount.total(session, share, shards);
        if (now > quantity.admissibleCallerShare(contract)) {
            release(session, share, caller, amount, shards);
            release(session, total, caller, amount, quantity.totalShards(contract));
            return new Refused(quantity, Reached.THE_CALLERS_SHARE,
                    quantity.admissibleCallerShare(contract), now);
        }
        return new Admitted(quantity, amount);
    }

    /**
     * Gives back what work reserved and did not use.
     *
     * <p>A reservation released is a reservation that never happened as far as every later
     * admission is concerned: a failed or abandoned command must not permanently consume capacity,
     * or a store fills up with work nobody is doing.</p>
     *
     * @param session the session to write under
     * @param quantity what was counted
     * @param caller who reserved it
     * @param amount how much of it
     * @param contract the authenticated contract, which decides how the counts are spread
     * @throws RepositoryException if the repository fails
     */
    public static void release(Session session, AccountedQuantity quantity,
                               StatePath.Caller caller, long amount, AgentContract contract)
            throws RepositoryException {
        release(session, callerPath(quantity, caller), caller, amount,
                quantity.callerShards(contract));
        release(session, totalPath(quantity), caller, amount, quantity.totalShards(contract));
    }

    private static void release(Session session, StatePath path, StatePath.Caller caller,
                                long amount, int shards) throws RepositoryException {
        ShardedCount.advance(session, path, caller.name(), -amount, shards);
    }

    /**
     * What this store currently holds of one quantity.
     *
     * @param session the session to read under
     * @param quantity what to read
     * @param contract the authenticated contract, which decides how the counts are spread
     * @return the total
     * @throws RepositoryException if the repository fails
     */
    public static long held(Session session, AccountedQuantity quantity, AgentContract contract)
            throws RepositoryException {
        return ShardedCount.total(session, totalPath(quantity), quantity.totalShards(contract));
    }

    /**
     * What one caller currently holds of one quantity.
     *
     * @param session the session to read under
     * @param quantity what to read
     * @param caller whose share to read
     * @param contract the authenticated contract, which decides how the counts are spread
     * @return the caller's total
     * @throws RepositoryException if the repository fails
     */
    public static long heldBy(Session session, AccountedQuantity quantity, StatePath.Caller caller,
                              AgentContract contract) throws RepositoryException {
        return ShardedCount.total(session, callerPath(quantity, caller),
                quantity.callerShards(contract));
    }

    /**
     * Prepares the two counters one quantity is counted on, if they are not there.
     *
     * @param session the session to write under
     * @param quantity what will be counted
     * @param caller who will be counted
     * @throws RepositoryException if the repository fails
     */
    public static void prepare(Session session, AccountedQuantity quantity,
                               StatePath.Caller caller) throws RepositoryException {
        claim(session, StatePath.deployment(StatePath.CAPACITY));
        claim(session, totalPath(quantity));
        claim(session, StatePath.deployment(StatePath.CAPACITY).child(StatePath.CALLERS));
        prepareCaller(session, quantity, caller);
    }

    private static void prepareCaller(Session session, AccountedQuantity quantity,
                                      StatePath.Caller caller) throws RepositoryException {
        final StatePath counters = StatePath.caller(caller);
        final String[] segments = counters.path()
                .substring(StatePath.ROOT.length() + 1)
                .split("/");
        StatePath walked = StatePath.deployment(segments[0]);
        int index = 1;
        while (index < segments.length) {
            walked = walked.child(segments[index]);
            claim(session, walked);
            index = index + 1;
        }
        claim(session, callerPath(quantity, caller));
    }

    private static void claim(Session session, StatePath path) throws RepositoryException {
        ClaimByCreation.claim(session, path, "nt:unstructured", node -> { });
    }

    /**
     * Where one quantity's own total is counted.
     *
     * @param quantity the quantity
     * @return the path
     */
    public static StatePath totalPath(AccountedQuantity quantity) {
        return StatePath.deployment(StatePath.CAPACITY).child(quantity.spelling());
    }

    /**
     * Where one caller's share of one quantity is counted.
     *
     * @param quantity the quantity
     * @param caller the caller
     * @return the path
     */
    public static StatePath callerPath(AccountedQuantity quantity, StatePath.Caller caller) {
        return StatePath.caller(caller).child(quantity.spelling());
    }

    /**
     * The one reason an admission was refused, where it was refused.
     *
     * @param admission what admitting produced
     * @return the refusal, or nothing where the work was admitted
     */
    public static Optional<Refused> refusalIn(Admission admission) {
        return admission instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
