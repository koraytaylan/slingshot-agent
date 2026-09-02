// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.io.ByteArrayInputStream;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.CommandFailure;
import rs.slingshot.agent.wire.ResultDelivery;

/**
 * What becomes of a result that did not fit, and what a caller is told when it cannot be kept.
 *
 * <p>An overflowing result is published as an artifact and answered with a reference: the slot it
 * occupies, how many bytes it is, and their digest. A caller fetches it on the artifact route and
 * checks both for itself, so a truncated or substituted body is caught by the caller rather than
 * trusted.</p>
 *
 * <h2>Room is taken before the command runs</h2>
 *
 * <p>A command whose registry row says it may overflow reserves its artifact capacity before
 * assembly begins. Reserving afterwards would mean running a read to completion, producing an
 * answer too large to hold, and only then discovering there is nowhere to put it — which spends the
 * whole cost of the command to arrive at a failure that was knowable before it started. So a caller
 * whose share is already full is refused first, and nothing runs.</p>
 *
 * <h2>A publication that fails answers nothing</h2>
 *
 * <p>The one thing that must not happen is a partial answer. Not a truncated result, which is
 * unparseable, and not a reference to an artifact that was never written, which sends the caller to
 * fetch something that is not there and turns one failure into two. So a failed publication is a
 * declared failure category and carries no result at all.</p>
 */
public final class OverflowPublication {

    private OverflowPublication() {
    }

    /** The slot an overflowing result occupies, which is the one the client fetches by. */
    public static final String RESULT_SLOT = "result";

    /**
     * That slot, held as a slot rather than as a name.
     *
     * <p>Named once here rather than at each publication. The name is this build's own constant and
     * is either a slot on every instance or on none, so a refusal is a defect in this file rather
     * than anything a caller did — and it stops the build instead of becoming a branch every
     * publication carries and no caller can reach.</p>
     */
    private static final ArtifactSlot RESULT = resultSlot();

    private static ArtifactSlot resultSlot() {
        final ArtifactSlot.Outcome named = ArtifactSlot.of(RESULT_SLOT);
        if (named instanceof final ArtifactSlot.Refused refused) {
            throw new IllegalStateException(RESULT_SLOT + " is not a slot name: "
                    + refused.detail());
        }
        return ((ArtifactSlot.Held) named).slot();
    }

    /** What a published overflow is answered with, or why there is no answer. */
    public sealed interface Outcome permits Published, Failed {
    }

    /**
     * The reference a caller fetches by.
     *
     * <p>It carries the wire's own artifact delivery rather than a count and digest of its own.
     * The answer a caller receives is a {@link ResultDelivery}, and a second pair of fields
     * meaning the same thing is a second place for them to disagree — which, for the two numbers a
     * caller checks a fetched result against, is the pair least worth having twice.</p>
     *
     * @param slot the slot the artifact occupies, which is where a caller fetches it from
     * @param delivery the count and digest, as the answer itself carries them
     */
    public record Published(String slot, ResultDelivery.Artifact delivery) implements Outcome {
    }

    /**
     * No answer at all, because the artifact could not be kept.
     *
     * @param category the category the row declares for it
     * @param detail what happened, which names no caller's bytes
     */
    public record Failed(CommandFailure.Category category, String detail) implements Outcome {
    }

    /**
     * Publishes an overflowing result, or answers with nothing at all.
     *
     * @param session the session to write under
     * @param caller whose share the artifact comes out of
     * @param operation the operation the result belongs to
     * @param overflowed the count and digest assembly measured
     * @param bytes the result, which is read once and never held
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @return the reference, or the category and detail of the failure
     * @throws RepositoryException if the repository fails
     */
    public static Outcome publish(Session session, StatePath.Caller caller, StatePath operation,
                                  ResultAssembly.Overflowed overflowed, byte[] bytes,
                                  long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final ArtifactStore.Publication publication = new ArtifactStore.Publication(
                RESULT, overflowed.byteCount(), new ByteArrayInputStream(bytes));
        return answerFor(ArtifactStore.publish(session, caller, operation, publication,
                nowUnixMilliseconds, contract), overflowed);
    }

    /**
     * What one publication outcome means for the caller.
     *
     * <p>Kept apart from the writing so that what a caller is told can be proved without a
     * repository, which is the same reason every other decision in this package is.</p>
     *
     * @param outcome what the store did
     * @param overflowed the count and digest assembly measured
     * @return the reference, or the category and detail of the failure
     */
    public static Outcome answerFor(ArtifactStore.Outcome outcome,
                                    ResultAssembly.Overflowed overflowed) {
        return switch (outcome) {
            case ArtifactStore.Published published -> new Published(
                    published.record().slot().name(),
                    new ResultDelivery.Artifact(published.record().byteCount(),
                            published.record().digest()));
            case ArtifactStore.AtCapacity capacity -> new Failed(
                    CommandFailure.Category.BUDGET_SPENT,
                    "the result did not fit and there is no room to keep it: "
                            + capacity.refusal().rendered());
            case ArtifactStore.NotCounted notCounted -> new Failed(
                    CommandFailure.Category.PLATFORM_FAILED,
                    "the result did not fit and the room for it could not be accounted: "
                            + notCounted.notCounted());
            case ArtifactStore.Refused refused -> new Failed(
                    CommandFailure.Category.PLATFORM_FAILED,
                    "the result did not fit and could not be kept: " + refused.detail());
        };
    }

    /**
     * Whether one command needs artifact room taken before it runs.
     *
     * <p>Every row declares a result bound, so having one says nothing. What decides it is whether
     * that bound is larger than an answer can carry: a command whose whole result always fits in
     * the answer can never need an artifact, and reserving room for one would charge every caller
     * of every small read for storage nothing will use. A command whose bound is larger than the
     * inline bound can overflow, so its room is taken first.</p>
     *
     * @param row the command's own registry row
     * @param contract the authenticated contract, which declares what an answer can carry
     * @return whether this command needs its room taken before assembly begins
     */
    public static Reservation reservationFor(RegistryRow row, AgentContract contract) {
        return row.resultBytes() > contract.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES)
                ? Reservation.BEFORE_THE_COMMAND_RUNS : Reservation.NONE_IS_NEEDED;
    }

    /** Whether room is taken before a command runs. */
    public enum Reservation {
        /**
         * It is, because this command can produce an answer too large to carry.
         *
         * <p>Taken first so that a caller with no room left is refused before the read happens
         * rather than after it has been paid for.</p>
         */
        BEFORE_THE_COMMAND_RUNS,
        /** It is not, because this command's whole answer always fits in the answer. */
        NONE_IS_NEEDED
    }
}
