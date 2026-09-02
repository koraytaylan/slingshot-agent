// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three ways a request can be the wrong shape for the route it reached.
 *
 * <p>They are separate because a caller does something different about each. A path spelled another
 * way is a client asking for something this agent does not serve; a method the route does not
 * answer is a client using the wrong verb on something it does serve; and a body in a media type
 * the route does not take is a client sending the right thing in the wrong form. One refusal
 * covering all three would tell a client to check everything.</p>
 */
public enum ShapeRefusal {

    /** It arrived at a second spelling of the path — a selector, extension, suffix, or segment. */
    NOT_THE_EXACT_PATH("not_the_exact_path", Status.NOT_FOUND),

    /** It used a method this route does not answer. */
    WRONG_METHOD("wrong_method", Status.METHOD_NOT_ALLOWED),

    /** It carried a body in a media type this route does not take, or one where none is taken. */
    WRONG_MEDIA_TYPE("wrong_media_type", Status.UNSUPPORTED_MEDIA_TYPE);

    /** The protocol's own answers about the shape of a request, spelled once. */
    private static final class Status {

        /** What a request at a spelling this agent does not serve is answered with. */
        private static final int NOT_FOUND = 404;

        /** What a request using a method a route does not answer is answered with. */
        private static final int METHOD_NOT_ALLOWED = 405;

        /** What a request carrying a body in the wrong form is answered with. */
        private static final int UNSUPPORTED_MEDIA_TYPE = 415;

        private Status() {
        }
    }

    private final String spelling;

    private final int status;

    ShapeRefusal(String spelling, int status) {
        this.spelling = spelling;
        this.status = status;
    }

    /**
     * How this refusal is spelled where it is written down.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * What a request refused this way is answered with.
     *
     * <p>These are the protocol's own answers about the shape of a request rather than this
     * product's answers about work: what a command's failure becomes on the wire is decided
     * elsewhere, against the categories the client already declares.</p>
     *
     * @return the status
     */
    public int status() {
        return status;
    }

    /**
     * The refusal one spelling names.
     *
     * @param spelling the spelling
     * @return the refusal, or nothing where this build has no such refusal
     */
    public static Optional<ShapeRefusal> named(String spelling) {
        return Arrays.stream(values())
                .filter(refusal -> refusal.spelling.equals(spelling))
                .findFirst();
    }
}
