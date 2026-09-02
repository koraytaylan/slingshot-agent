// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Arrays;
import java.util.Optional;

/**
 * What reconciliation decided about one operation, out of a closed set of seven answers.
 *
 * <p>Every one of them is a decision to do nothing except say so. Recovery has no session for
 * anybody's caller — an immediate command runs on its own request's session, and there is no
 * request here — so it classifies rather than executes, and the client's own resend under the same
 * derived identifier is what starts work again.</p>
 *
 * <p>The set is closed because a store that answered "we do not know" by saying nothing is a store
 * a client cannot act on. Undetermined is a real answer: it says that whether the one commit landed
 * is a thing this side genuinely does not know, which is different from saying it did not.</p>
 */
public enum RecoveryDisposition {

    /** It ended. Whatever the job system still believes about it, it ended. */
    FINISHED("finished"),

    /** Another node holds its lease, so it is running somewhere that is not here. */
    RUNNING_ELSEWHERE("running_elsewhere"),

    /** It is inside the budget a command runs in, so it is somebody's request still running. */
    STILL_RUNNING("still_running"),

    /** It was accepted and never started, and the client's own resend is what starts it. */
    RESTARTABLE("restartable"),

    /** Its declared payloads have not all arrived, and there is still time for them to. */
    AWAITING_INTAKE("awaiting_intake"),

    /** Its payloads never arrived and its retention has passed, so nobody is waiting for it. */
    ABANDONED("abandoned"),

    /** Whether its one commit landed is a thing this side does not know. */
    UNDETERMINED("undetermined");

    private final String spelling;

    RecoveryDisposition(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this disposition is spelled where it is written down.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The disposition one spelling names.
     *
     * @param spelling the spelling
     * @return the disposition, or nothing where this build knows no such answer
     */
    public static Optional<RecoveryDisposition> named(String spelling) {
        return Arrays.stream(values())
                .filter(disposition -> disposition.spelling.equals(spelling))
                .findFirst();
    }
}
