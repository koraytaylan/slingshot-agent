// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import org.apache.sling.api.SlingHttpServletRequest;
import rs.slingshot.agent.contract.AgentContract;

/**
 * Letting go of the request thread, which is the whole reason this route can exist at all.
 *
 * <p>An author serves from a bounded pool of request threads. A synchronous long-lived response
 * holds one of them for as long as somebody is subscribed, so a handful of subscribers on a
 * synchronous route is an author that has stopped serving anything at all — including the requests
 * that would tell an operator about it.</p>
 *
 * <p>A container that will not start an asynchronous context is answered honestly rather than
 * worked around: the stream is written on the thread that arrived, which is what a container
 * without asynchronous support leaves anybody. Every container this product is deployed on has it;
 * the one that does not is a suite's double.</p>
 */
public final class StreamHandoff {

    private StreamHandoff() {
    }

    /** Whether the request thread was released. */
    public enum Outcome {
        /** It is back in the pool, and the response outlives the call that started it. */
        THE_THREAD_IS_RELEASED,
        /** It was not released, because this container does not release them. */
        THIS_CONTAINER_HOLDS_THE_THREAD
    }

    /**
     * Releases the request thread, where the container will.
     *
     * <p>The container's own timeout is set past this side's session bound, so a session always
     * ends at the moment this side publishes rather than at one the container chose.</p>
     *
     * @param request the request to release
     * @param contract the authenticated contract, which declares the session bound
     * @return whether the thread was released
     */
    public static Outcome from(SlingHttpServletRequest request, AgentContract contract) {
        try {
            request.startAsync().setTimeout(SessionBound.milliseconds(contract)
                    + Heartbeat.intervalMilliseconds(contract));
            return Outcome.THE_THREAD_IS_RELEASED;
        } catch (final UnsupportedOperationException | IllegalStateException synchronousOnly) {
            return Outcome.THIS_CONTAINER_HOLDS_THE_THREAD;
        }
    }
}
