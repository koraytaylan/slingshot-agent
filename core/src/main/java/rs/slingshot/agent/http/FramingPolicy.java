// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Optional;

/**
 * How a request says how long its body is, and the two ways of saying it that are refused rather
 * than resolved.
 *
 * <p>A request that carries both a declared length and a chunked encoding has been framed twice,
 * and the two framings can disagree. Every rule for resolving them is a rule for choosing which of
 * two senders to believe — and the interesting case is when the two senders are a proxy and an
 * attacker. So this refuses, which is the one answer that cannot be smuggled past.</p>
 *
 * <p>A content coding this side did not ask for is refused rather than decoded, for a plainer
 * reason: the bound this agent enforces is on bytes, and a decoded length is a length nobody knew
 * when the bound was checked. Refusing an unrequested coding is how the bound stays a bound.</p>
 *
 * @param declaredLength what the request says its body is, negative where it says nothing
 * @param chunked whether it says its body is chunked
 * @param contentCoding what coding it says its body is in, empty where it says none
 */
public record FramingPolicy(long declaredLength, Chunked chunked, String contentCoding) {

    /** Whether a request frames its body as chunks, which is a fact about the request. */
    public enum Chunked {
        /** It does. */
        FRAMED_IN_CHUNKS,
        /** It does not. */
        NOT_FRAMED_IN_CHUNKS
    }

    /** What a request says when it declares no length at all. */
    public static final long NO_LENGTH_DECLARED = -1;

    /** Why a request's framing is not one this side will read. */
    public enum Refusal {
        /** It is framed twice, and the two framings can disagree. */
        FRAMED_TWICE,
        /** It is in a coding this side did not ask for, whose decoded length nobody knows. */
        AN_UNREQUESTED_CODING,
        /** It declares no length and is not chunked, so how long it is is nobody's answer. */
        NO_FRAMING_AT_ALL
    }

    /** The result of reading a framing. */
    public sealed interface Outcome permits Framed, Refused {
    }

    /**
     * A request framed one way.
     *
     * @param declaredLength how long it says it is, or that it is chunked
     * @param chunked whether it is framed in chunks
     */
    public record Framed(long declaredLength, Chunked chunked) implements Outcome {
    }

    /**
     * One that is not.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Whether this framing is one this side will read a body under.
     *
     * @return the framing, or the one reason there is none
     */
    public Outcome read() {
        final boolean hasLength = declaredLength >= 0;
        final boolean isChunked = chunked == Chunked.FRAMED_IN_CHUNKS;
        if (hasLength && isChunked) {
            return new Refused(Refusal.FRAMED_TWICE, "this request declares a length of "
                    + declaredLength + " and says it is chunked, and resolving that would be"
                    + " choosing which of two senders to believe");
        }
        if (!contentCoding.isBlank()) {
            return new Refused(Refusal.AN_UNREQUESTED_CODING, "this request says its body is in "
                    + contentCoding + ", which this side did not ask for and whose decoded length"
                    + " nothing here would know");
        }
        if (!hasLength && !isChunked) {
            return new Refused(Refusal.NO_FRAMING_AT_ALL,
                    "this request says neither how long its body is nor that it is chunked");
        }
        return new Framed(declaredLength, chunked);
    }

    /**
     * The one reason a framing is refused, where it is.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where the framing is one this side reads
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
