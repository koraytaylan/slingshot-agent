// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.List;

/**
 * Everything four stores know about one operation, assembled into the answer somebody came for.
 *
 * <p>This is the reason the console exists. Somebody has an identifier and a question, and the
 * answer is spread across a snapshot, an event ledger, a set of physical attempts and a lease —
 * four stores that only this repository knows how to read. Assembling them is the difference
 * between a diagnosable system and one where the answer is "check the logs".</p>
 *
 * <p>Every part that is not there is explicitly absent rather than empty. An operation with no
 * artifacts and an operation whose artifacts were swept are different situations with different
 * answers, and a page that rendered both as an empty list would send the second person looking for
 * a bug in the download.</p>
 *
 * @param row the operation itself, as the list shows it
 * @param submittedDigest what the caller's submission hashed to, so a resend can be told from a
 *     different command under the same identifier
 * @param commandContract the five-field identity of the command it is running
 * @param events what the ledger holds, in sequence order
 * @param attempts what physically ran, each with the node that ran it
 * @param lease who holds it and until when, or that nobody does
 * @param artifacts what it published, each with what it would take to verify a download
 */
public record OperationDetail(OperationRow row, String submittedDigest, String commandContract,
                              List<String> events, List<Attempt> attempts, Lease lease,
                              List<ArtifactLink.Offer> artifacts) {

    /** Holds a detail whose lists nothing can change afterwards. */
    public OperationDetail {
        events = List.copyOf(events);
        attempts = List.copyOf(attempts);
        artifacts = List.copyOf(artifacts);
    }

    /**
     * One physical attempt, and which node made it.
     *
     * <p>The node matters more than it looks: an operation that has been attempted four times on
     * four different nodes is a cluster passing work around, and an operation attempted four times
     * on one node is a command that keeps failing. Those have nothing in common.</p>
     *
     * @param attempt which attempt this was, counting from one
     * @param worker the node that made it
     * @param outcome what happened, as this side recorded it
     */
    public record Attempt(long attempt, String worker, String outcome) {
    }

    /** Who is running this and until when, which is the question a stuck operation raises. */
    public sealed interface Lease permits Held, Expired, Unheld {
    }

    /**
     * Somebody holds it.
     *
     * @param worker which node
     * @param heldUntilUnixMilliseconds until when
     */
    public record Held(String worker, long heldUntilUnixMilliseconds) implements Lease {
    }

    /**
     * Somebody held it and the hold has run out.
     *
     * <p>Told apart from nobody holding it, because the two mean opposite things: an expired lease
     * is a node that stopped without finishing, and no lease is an operation nothing has picked up
     * yet.</p>
     *
     * @param worker which node held it
     * @param expiredAtUnixMilliseconds when the hold ran out
     */
    public record Expired(String worker, long expiredAtUnixMilliseconds) implements Lease {
    }

    /** Nobody holds it, and nobody has. */
    public record Unheld() implements Lease {
    }

    /**
     * Whether one lease is still live at an instant.
     *
     * <p>The instant is passed in rather than read, because a page that decided this from its own
     * clock would show a different answer to two people reading it a second apart and neither of
     * them would know why.</p>
     *
     * @param worker who holds it
     * @param heldUntilUnixMilliseconds until when they hold it
     * @param nowUnixMilliseconds what the asking side's clock says
     * @return the lease as it stands at that instant
     */
    public static Lease leaseAt(String worker, long heldUntilUnixMilliseconds,
                                long nowUnixMilliseconds) {
        return nowUnixMilliseconds < heldUntilUnixMilliseconds
                ? new Held(worker, heldUntilUnixMilliseconds)
                : new Expired(worker, heldUntilUnixMilliseconds);
    }
}
