// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.nio.charset.StandardCharsets;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.wire.EventSequence;

/**
 * One durable subscription: who is following, which incarnation, and how far they have been shown.
 *
 * <p>The cursor is the whole point. A subscriber that reconnects and is told where it got to
 * resumes; one that is not, replays — and a replay is this side showing somebody an event they have
 * already acted on. So the cursor is a promise, and the only promise this record makes: it never
 * goes backwards, and it is written before the events it accounts for are given away.</p>
 *
 * <p>How far a subscriber has been shown is stored as how many events it has been shown rather than
 * as which sequence it stopped at, because the first sequence is zero and a count of zero is
 * exactly "nothing yet" with no value standing in for absence.</p>
 *
 * @param identifier the following daemon's own name for this subscription
 * @param generation the incarnation of the store it follows
 * @param cursor how far the subscriber has been shown
 * @param lastAdvancedAtUnixMilliseconds when the cursor last moved
 */
public record SubscriptionRecord(Identifier identifier, EventStoreGeneration generation,
                                 Cursor cursor, long lastAdvancedAtUnixMilliseconds) {

    /** The property the subscriber's own name is written in. */
    public static final String IDENTIFIER = "subscription_identifier";

    /** The property the incarnation this subscription follows is written in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The property the number of events shown is written in. */
    public static final String EVENTS_SHOWN = "events_shown";

    /** The property the instant the cursor last moved is written in. */
    public static final String LAST_ADVANCED_AT = "last_advanced_at_unix_milliseconds";

    /** The deployment-level node every subscription record lives under. */
    public static final String NODE = "subscriptions";

    /** The whole numbers a record holds beside its own name, counted for the byte accounting. */
    private static final int WHOLE_NUMBERS_HELD = 3;

    /** Why a subscription identifier is not one this build will write down. */
    public enum Refusal {
        /** It is empty, and a subscription nobody can name is one nobody can resume. */
        EMPTY,
        /** It is past the bound the contract declares for a following daemon's own name. */
        PAST_THE_BOUND,
        /** It carries something a name does not, which is a path this side would have to escape. */
        NOT_A_NAME
    }

    /** The result of reading an identifier: one this build will write down, or why it will not. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An identifier this build will write down.
     *
     * @param identifier the identifier
     */
    public record Held(Identifier identifier) implements Outcome {
    }

    /**
     * One it will not.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * A following daemon's own name for its subscription, constrained to what a path may hold.
     *
     * @param rendered the name as it is written
     */
    public record Identifier(String rendered) {
    }

    /** How far a subscriber has been shown, which is either nothing yet or one sequence. */
    public sealed interface Cursor permits Shown, Unread {
    }

    /**
     * The newest sequence a subscriber has been shown.
     *
     * @param sequence the sequence
     */
    public record Shown(EventSequence sequence) implements Cursor {
    }

    /** That a subscriber has been shown nothing at all yet. */
    public enum Unread implements Cursor {
        /** Nothing has been given away under this subscription. */
        NOTHING_SHOWN_YET
    }

    /**
     * Reads a following daemon's own name, against the bound the contract declares for it.
     *
     * @param rendered the name as the daemon spelled it
     * @param contract the authenticated contract, which declares the bound
     * @return the identifier, or the one reason there is none
     */
    public static Outcome identifier(String rendered, AgentContract contract) {
        final int length = rendered.getBytes(StandardCharsets.UTF_8).length;
        final long bound = contract.value(ContractLimit.MAXIMUM_DAEMON_SUBSCRIPTION_IDENTIFIER_BYTES);
        if (length == 0) {
            return new Refused(Refusal.EMPTY,
                    "a subscription nobody can name is one nobody can resume");
        }
        if (length > bound) {
            return new Refused(Refusal.PAST_THE_BOUND,
                    length + " bytes is past the bound of " + bound);
        }
        if (rendered.chars().anyMatch(scalar -> !isNameCharacter(scalar))) {
            return new Refused(Refusal.NOT_A_NAME, "a name carries letters, digits, hyphens, and"
                    + " underscores and nothing else");
        }
        return new Held(new Identifier(rendered));
    }

    private static boolean isNameCharacter(int scalar) {
        return scalar >= 'a' && scalar <= 'z' || scalar >= 'A' && scalar <= 'Z'
                || scalar >= '0' && scalar <= '9' || scalar == '-' || scalar == '_';
    }

    /**
     * Where one subscription's record lives.
     *
     * @param identifier whose subscription
     * @return the path
     */
    public static StatePath pathOf(Identifier identifier) {
        return StatePath.deployment(NODE).child(identifier.rendered());
    }

    /**
     * The cursor a count of events shown means.
     *
     * @param shown how many events have been given away under this subscription
     * @return where that leaves the subscriber
     */
    public static Cursor cursorFor(long shown) {
        if (shown <= 0) {
            return Unread.NOTHING_SHOWN_YET;
        }
        final EventSequence.Outcome sequence = EventSequence.of(shown - 1);
        return sequence instanceof final EventSequence.Held held
                ? new Shown(held.sequence())
                : Unread.NOTHING_SHOWN_YET;
    }

    /**
     * How many events this record accounts for having shown.
     *
     * @return the count, which is one past the newest sequence shown
     */
    public long eventsShown() {
        return cursor instanceof final Shown shown ? shown.sequence().number() + 1 : 0;
    }

    /**
     * How many bytes this record occupies, which is what its caller is charged for holding it.
     *
     * @return the bytes
     */
    public long bytes() {
        return identifier.rendered().getBytes(StandardCharsets.UTF_8).length
                + (long) Long.BYTES * WHOLE_NUMBERS_HELD;
    }

    /**
     * The one reason an identifier was refused, where it was.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where there is an identifier
     */
    public static java.util.Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused
                ? java.util.Optional.of(refused)
                : java.util.Optional.empty();
    }
}
