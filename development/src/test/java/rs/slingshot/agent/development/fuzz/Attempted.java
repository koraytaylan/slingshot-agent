// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * What happened when something was asked, including it having thrown.
 *
 * <p>Every target here has the same property to state — that the code under it answers rather than
 * throws — and stating it means being able to observe a throw of any kind. Catching one broadly is
 * refused in this repository for a good reason: code that catches everything is usually code that
 * swallows something. So the observation is made through a boundary that turns a throw into a
 * value, which says the same thing and says it deliberately.</p>
 *
 * <p>Run on the calling thread. There is no concurrency here at all; what is wanted is a boundary
 * across which anything thrown becomes something a caller can read.</p>
 */
final class Attempted {

    private final Optional<Throwable> thrown;

    private Attempted(Optional<Throwable> thrown) {
        this.thrown = thrown;
    }

    /**
     * Asks something, and answers what happened rather than letting it happen.
     *
     * @param asked what to do
     * @param <T> what it would answer
     * @return what it did, whether it answered or threw
     */
    static <T> Answered<T> of(Callable<T> asked) {
        final FutureTask<T> asking = new FutureTask<>(asked);
        asking.run();
        try {
            return new Answered<>(Optional.of(asking.get()), new Attempted(Optional.empty()));
        } catch (final ExecutionException thrown) {
            return new Answered<>(Optional.empty(),
                    new Attempted(Optional.of(thrown.getCause())));
        } catch (final InterruptedException taken) {
            Thread.currentThread().interrupt();
            return new Answered<>(Optional.empty(), new Attempted(Optional.of(taken)));
        }
    }

    /**
     * What one attempt produced.
     *
     * @param value what it answered, or nothing where it threw
     * @param attempt what it threw, or nothing where it answered
     * @param <T> what it would answer
     */
    record Answered<T>(Optional<T> value, Attempted attempt) {

        /**
         * Whether it threw.
         *
         * @return whether it did
         */
        boolean threw() {
            return attempt.thrown.isPresent();
        }

        /**
         * What it threw, as the sentence a finding carries.
         *
         * @return the name and the message
         */
        String threwWhat() {
            return attempt.thrown
                    .map(held -> held.getClass().getName() + ": " + held.getMessage())
                    .orElse("");
        }
    }
}
