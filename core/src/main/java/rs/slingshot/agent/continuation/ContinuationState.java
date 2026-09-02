// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.identity.IdentityRefusal;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where a continuation token says to resume, in full.
 *
 * <p>Five members and all five required: which partition the query ran in, which incarnation of the
 * store it ran against, exactly which query it was, where in that query's results to resume, and
 * when the token stops being honoured. A position on its own would resume the wrong search; a
 * position and a query would resume it against the wrong target; and a token with no expiry would
 * outlive the key that signed it.</p>
 *
 * @param generation which incarnation of the store the query ran against
 * @param targetDigest the partition the query ran in, which this side compares and never parses
 * @param queryDigest exactly which query this token belongs to
 * @param position where in that query's results to resume
 * @param expiresAtUnixMilliseconds when this token stops being honoured
 */
public record ContinuationState(EventStoreGeneration generation, DigestValue targetDigest,
                                DigestValue queryDigest, long position,
                                long expiresAtUnixMilliseconds) {

    /** The member the store's incarnation is carried in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The member the partition digest is carried in. */
    public static final String TARGET_DIGEST = "author_target_identity_digest";

    /** The member the query digest is carried in. */
    public static final String QUERY_DIGEST = "query_digest";

    /** The member the position is carried in. */
    public static final String POSITION = "position";

    /** The member the expiry is carried in. */
    public static final String EXPIRES_AT = "expires_at_unix_milliseconds";

    /** Every member this document has, and there is no sixth. */
    public static final List<String> MEMBERS =
            List.of(GENERATION, TARGET_DIGEST, EXPIRES_AT, POSITION, QUERY_DIGEST);

    /** The result of reading one: the state, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document carrying all five members, each in shape.
     *
     * @param state the state it carried
     */
    public record Held(ContinuationState state) implements Outcome {
    }

    /**
     * A document that is not one.
     *
     * @param refusal why it is not, naming the member
     */
    public record Refused(IdentityRefusal refusal) implements Outcome {
    }

    /**
     * Reads a continuation state out of a document.
     *
     * @param document the document
     * @return the state, or the one reason there is none
     */
    public static Outcome of(DocumentValue document) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DOCUMENT, "",
                    "a continuation state is an object with five members"));
        }
        final Optional<IdentityRefusal> shape = shapeOf(mapping);
        if (shape.isPresent()) {
            return new Refused(shape.get());
        }
        return read(mapping);
    }

    private static Optional<IdentityRefusal> shapeOf(DocumentValue.Mapping mapping) {
        final Optional<IdentityRefusal> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .map(name -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_UNKNOWN, name,
                        "a continuation state carries no member nobody declared"))
                .findFirst();
        if (unknown.isPresent()) {
            return unknown;
        }
        return MEMBERS.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .map(member -> new IdentityRefusal(IdentityRefusal.Failure.MEMBER_ABSENT, member,
                        "a continuation state is all five members or none"))
                .findFirst();
    }

    private static Outcome read(DocumentValue.Mapping mapping) {
        final Optional<Long> generation = whole(mapping, GENERATION);
        final Optional<Long> position = whole(mapping, POSITION);
        final Optional<Long> expiry = whole(mapping, EXPIRES_AT);
        if (generation.isEmpty() || position.isEmpty() || expiry.isEmpty()) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_TEXT, POSITION,
                    "the generation, the position, and the expiry are whole numbers"));
        }
        if (position.get() < 0 || expiry.get() < 0) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.OUT_OF_RANGE, POSITION,
                    "a position and an expiry are counted from zero"));
        }
        final EventStoreGeneration.Outcome held = EventStoreGeneration.of(generation.get());
        if (held instanceof final EventStoreGeneration.Refused refused) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.OUT_OF_RANGE, GENERATION,
                    refused.refusal() + ": " + refused.detail()));
        }
        return digests(mapping, ((EventStoreGeneration.Held) held).generation(), position.get(),
                expiry.get());
    }

    private static Outcome digests(DocumentValue.Mapping mapping, EventStoreGeneration generation,
                                   long position, long expiry) {
        final Optional<DigestValue> target = digest(mapping, TARGET_DIGEST);
        final Optional<DigestValue> query = digest(mapping, QUERY_DIGEST);
        if (target.isEmpty()) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DIGEST,
                    TARGET_DIGEST, "a digest is sixty-four lower-case hexadecimal characters"));
        }
        if (query.isEmpty()) {
            return new Refused(new IdentityRefusal(IdentityRefusal.Failure.NOT_A_DIGEST,
                    QUERY_DIGEST, "a digest is sixty-four lower-case hexadecimal characters"));
        }
        return new Held(new ContinuationState(generation, target.get(), query.get(), position,
                expiry));
    }

    private static Optional<DigestValue> digest(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .map(DigestValue::of)
                .filter(DigestValue.Held.class::isInstance)
                .map(outcome -> ((DigestValue.Held) outcome).digest());
    }

    private static Optional<Long> whole(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Whole.class::isInstance)
                .map(value -> ((DocumentValue.Whole) value).value());
    }
}
