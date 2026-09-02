// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * A runtime a scenario can be run against.
 *
 * <p>Two of these exist and a third is one of them driven by the client's own executable. What they
 * have in common is exactly this interface, which is why a scenario is written once and run on
 * either: a suite that had to know which tier it was on would be a suite that proves something
 * different depending on where it runs.</p>
 */
public interface InteropTier {

    /** Why a tier is not there to run a scenario against. */
    enum Failure {
        /** A pinned input is absent, and no tier fetches one. */
        INPUT_ABSENT,
        /** The runtime would not start, or would not become ready inside its deadline. */
        RUNTIME_NOT_READY,
        /** The product would not install, or did not reach a usable state once installed. */
        NOT_INSTALLED
    }

    /** The result of bringing a tier up: a running tier, or the one reason there is none. */
    sealed interface Outcome permits Running, Refused {
    }

    /**
     * A tier that started, installed what it can resolve, and is ready to be asked things.
     *
     * @param tier the tier
     */
    record Running(InteropTier tier) implements Outcome {
    }

    /**
     * A start that produced no tier, with everything it did start already stopped.
     *
     * @param failure why there is none
     * @param detail what was observed, so the cause is readable rather than inferred
     */
    record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * What this tier is called, which is the letter the tier inventory gives it.
     *
     * @return the tier's own name
     */
    String name();

    /**
     * Where the running instance answers.
     *
     * @return the address a caller would reach it at
     */
    String address();

    /**
     * Asks the running instance for something, as a user it authenticated.
     *
     * @param path the path to read, which comes from the committed route table
     * @return what the instance answered
     */
    HttpResponse<String> readAsAuthenticatedUser(String path);

    /**
     * Asks the running instance for something as nobody in particular.
     *
     * @param path the path to read
     * @return what the instance answered
     */
    HttpResponse<String> readAsNobody(String path);

    /**
     * What the platform says about one installed bundle.
     *
     * @param symbolicName the bundle's own name
     * @return the state the platform reports, or nothing where it holds no such bundle
     */
    Optional<String> bundleState(String symbolicName);

    /**
     * Everything the running instance wrote while this tier held it.
     *
     * <p>A response is not the only place something leaves an agent. A log line reaches an
     * operator's console, a support bundle, and whatever ships logs off the instance, so an audit
     * that read only the responses would be auditing half of what leaves.</p>
     *
     * @return what it wrote, bounded by what the harness captures
     */
    String capturedOutput();

    /**
     * Stops everything this tier started, through the handles that started it.
     */
    void stop();
}
