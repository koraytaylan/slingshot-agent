// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One published runtime, started once and answered to every scenario that asks for it.
 *
 * <p>A scenario that started its own took the same three seconds to boot it, the same two to hand
 * it the bundle, and the same second to take it away again, for one or two assertions against a
 * route. A hundred and two of them did that in turn, which was most of an hour spent proving a
 * hundred and two times that a container starts.</p>
 *
 * <p>What is given up is a pristine instance per scenario, and it is given up deliberately rather
 * than quietly: a scenario that needs the instance to itself - because it uninstalls what a
 * deployment installs, or upgrades the storage under it, or stops the platform to see what happens
 * next - still starts its own through {@link PublicSlingTier}, and says in its own setup that it
 * is doing so. Everything else reads a route and is not changed by another scenario having read
 * one.</p>
 *
 * <p>Nothing is left running. The instance is taken away when the test runtime that asked for it
 * ends, and the leak check every scenario already makes is what proves it: it names this container
 * as the one that is allowed to be there, so a second one is still a failure.</p>
 */
public final class SharedPublicSlingTier {

    private static final Lock LOCK = new ReentrantLock();

    /** The one runtime, or nothing. Final, and what changes is what it holds. */
    private static final List<InteropTier> HELD = new ArrayList<>();

    private SharedPublicSlingTier() {
    }

    /**
     * The shared runtime, started on the first ask and answered to every ask after it.
     *
     * @param root the repository root
     * @param image the pinned image, at the digest it was prepared at
     * @param bundle the built Sling-only bundle to install
     * @return the running tier, or the one reason there is none
     */
    public static InteropTier.Outcome get(Path root, String image, Path bundle) {
        LOCK.lock();
        try {
            if (!HELD.isEmpty()) {
                return new InteropTier.Running(HELD.getFirst());
            }
            final InteropTier.Outcome outcome = PublicSlingTier.start(root, image, bundle);
            if (outcome instanceof final InteropTier.Running running) {
                HELD.add(running.tier());
                // Taken away when the runtime that asked for it ends, rather than by whichever
                // scenario happened to run last - which is not something a scenario can know it is.
                Runtime.getRuntime().addShutdownHook(new Thread(SharedPublicSlingTier::release));
            }
            return outcome;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * What the engine calls the shared container, for the leak check to allow and nothing else.
     *
     * @return the identifier, or nothing where no shared runtime was ever started
     */
    public static Optional<String> identifier() {
        LOCK.lock();
        try {
            return HELD.isEmpty() ? Optional.empty()
                    : Optional.of(PublicSlingTier.identifierOf(HELD.getFirst()));
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Everything the engine still holds that nobody meant to leave.
     *
     * <p>The shared runtime is allowed to be running and is the only thing that is. The engine
     * reports what it holds by an abbreviated identifier, so this compares the way the engine
     * spells it rather than making every scenario know that.</p>
     *
     * @param root the repository root
     * @return the identifiers of anything else, which is empty when nothing was left behind
     */
    public static List<String> leftBeside(Path root) {
        final Optional<String> shared = identifier();
        return rs.slingshot.agent.interop.harness.ContainerHarness.at(root).leaked().stream()
                .filter(left -> shared.filter(running -> running.startsWith(left)).isEmpty())
                .toList();
    }

    /** Takes the shared runtime away, which the test runtime does when it ends. */
    public static void release() {
        LOCK.lock();
        try {
            HELD.forEach(InteropTier::stop);
            HELD.clear();
        } finally {
            LOCK.unlock();
        }
    }
}
