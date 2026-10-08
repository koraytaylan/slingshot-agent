// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.stream.ElapsedTime;

/**
 * Owns the resolver behind one incremental walk, never the request's resolver.
 *
 * <p>The registry must authenticate the query, target and generation before reaching this object.
 * This object additionally binds use to the authenticated resolver user and rechecks every row
 * through the current request. Its fixed monotonic lifetime is never extended by another page.
 * The registry must call {@link #expire} while idle and {@link #close} during deactivation.</p>
 *
 * @param <R> one detached result row
 */
final class DiscoveryCursor<R> implements AutoCloseable {

    private final ResourceResolver owned;
    private final String actor;
    private final ResumableWalk<R> walk;
    private final long lifetimeMilliseconds;
    private final ElapsedTime age;
    private final AtomicBoolean released = new AtomicBoolean();
    private final ReentrantLock monitor = new ReentrantLock();

    private DiscoveryCursor(ResourceResolver owned, Resource root, int maximumDepth,
                            long lifetimeMilliseconds, ElapsedTime age) {
        this.owned = owned;
        this.actor = Objects.requireNonNull(owned.getUserID());
        this.walk = new ResumableWalk<>(root, maximumDepth);
        this.lifetimeMilliseconds = lifetimeMilliseconds;
        this.age = age;
    }

    /**
     * Acquires a cursor resolver using only the caller's existing authentication.
     * @param caller the borrowed request resolver, which is never closed or retained
     * @param rootPath the exact, already validated root
     * @param maximumDepth maximum descendant depth
     * @param lifetimeMilliseconds fixed retention duration
     * @param monotonic the monotonic millisecond source
     * @param <R> one result row
     * @return the cursor, or nothing if the root or cloned identity is unavailable
     * @throws LoginException if the provider cannot clone the caller's resolver
     * @throws IllegalArgumentException if a retention bound is invalid
     */
    static <R> Optional<DiscoveryCursor<R>> open(ResourceResolver caller, String rootPath,
                                                int maximumDepth, long lifetimeMilliseconds,
                                                LongSupplier monotonic) throws LoginException {
        if (maximumDepth < 0 || lifetimeMilliseconds <= 0) {
            throw new IllegalArgumentException("discovery retention bounds must permit progress");
        }
        if (caller.getResource(rootPath) == null) {
            return Optional.empty();
        }
        final ElapsedTime age = ElapsedTime.start(monotonic);
        try (var acquisition = new Acquisition(caller)) {
            return acquisition.transfer(caller.getUserID(), rootPath, maximumDepth,
                    lifetimeMilliseconds, age);
        }
    }

    /**
     * Serializes use of the provider iterators and releases ownership on every terminal path.
     * @param limits this page's work bounds
     * @param current the current borrowed resolver
     * @param opens whether to descend into a readable node
     * @param match materializes a detached row from a currently readable node
     * @param cancelled request cancellation
     * @param elapsed a fresh monotonic page timer
     * @return a page, or a closed refusal
     * @throws RuntimeException if the provider or materializer fails, after releasing the cursor
     */
    ResumableWalk.Outcome<R> advance(ResumableWalk.Limits limits, ResourceResolver current,
                                                  Predicate<Resource> opens,
                                                  Function<Resource, Optional<R>> match,
                                                  BooleanSupplier cancelled, ElapsedTime elapsed) {
        return advance(limits, current, opens, match, cancelled, elapsed,
                new ResumableWalk.Grouping(java.util.List.of(), MatchMode.ANY));
    }

    /**
     * Advances component aggregation with the same owned-resolver and lifetime guarantees.
     * @param limits this request's bounds
     * @param current borrowed current caller authority
     * @param opens provider traversal rules
     * @param match immediate detached row materialization
     * @param cancelled current cancellation state
     * @param elapsed fresh request duration
     * @param grouping detached page grouping rules
     * @return a bounded page or terminal refusal
     */
    ResumableWalk.Outcome<R> advance(ResumableWalk.Limits limits, ResourceResolver current,
                                   Predicate<Resource> opens, Function<Resource, Optional<R>> match,
                                   BooleanSupplier cancelled, ElapsedTime elapsed,
                                   ResumableWalk.Grouping grouping) {
        monitor.lock();
        try {
            return advanceLocked(limits, current, opens, match, cancelled, elapsed, grouping);
        } finally {
            monitor.unlock();
        }
    }

    private ResumableWalk.Outcome<R> advanceLocked(ResumableWalk.Limits limits, ResourceResolver current,
                                                   Predicate<Resource> opens,
                                                   Function<Resource, Optional<R>> match,
                                                   BooleanSupplier cancelled, ElapsedTime elapsed,
                                                   ResumableWalk.Grouping grouping) {
        if (!actor.equals(current.getUserID())) {
            return new ResumableWalk.Refused<>(ResumableWalk.Stop.WRONG_CALLER);
        }
        if (expire()) {
            return new ResumableWalk.Refused<>(ResumableWalk.Stop.EXPIRED);
        }
        if (released.get()) {
            return new ResumableWalk.Refused<>(ResumableWalk.Stop.CLOSED);
        }
        boolean continued = false;
        try {
            final ResumableWalk.Outcome<R> outcome = walk.advance(limits, current, opens, match,
                    () -> cancelled.getAsBoolean() || expired(), elapsed, grouping);
            if (expired()) {
                return new ResumableWalk.Refused<>(ResumableWalk.Stop.EXPIRED);
            }
            continued = outcome instanceof final ResumableWalk.Page<R> page && !page.complete();
            return outcome;
        } finally {
            if (!continued) {
                close();
            }
        }
    }

    /**
     * Releases an idle cursor once its fixed lifetime is spent.
     * @return whether its lifetime has ended
     */
    boolean expire() {
        if (!monitor.tryLock()) {
            return false;
        }
        try {
            if (!expired()) {
                return false;
            }
            close();
            return true;
        } finally {
            monitor.unlock();
        }
    }

    private boolean expired() {
        return age.milliseconds() >= lifetimeMilliseconds;
    }

    /** Releases both iterators and the owned resolver at most once. */
    @Override
    public void close() {
        monitor.lock();
        try {
            if (released.compareAndSet(false, true)) {
                walk.close();
                owned.close();
            }
        } finally {
            monitor.unlock();
        }
    }

    /** A lexical acquisition guard until ownership reaches a fully initialized cursor. */
    private static final class Acquisition implements AutoCloseable {
        private final ResourceResolver resolver;
        private final AtomicBoolean transferred = new AtomicBoolean();

        private Acquisition(ResourceResolver caller) throws LoginException {
            this.resolver = caller.clone(Map.of());
        }

        private <R> Optional<DiscoveryCursor<R>> transfer(String actor, String rootPath,
                                                         int maximumDepth, long lifetimeMilliseconds,
                                                         ElapsedTime age) {
            if (!Objects.equals(actor, resolver.getUserID())) {
                return Optional.empty();
            }
            final Optional<Resource> root = Optional.ofNullable(resolver.getResource(rootPath));
            if (root.filter(resource -> rootPath.equals(resource.getPath())).isEmpty()
                    || age.milliseconds() >= lifetimeMilliseconds) {
                return Optional.empty();
            }
            final DiscoveryCursor<R> cursor = new DiscoveryCursor<>(resolver, root.orElseThrow(),
                    maximumDepth, lifetimeMilliseconds, age);
            transferred.set(true);
            return Optional.of(cursor);
        }

        @Override
        public void close() {
            if (!transferred.get()) {
                resolver.close();
            }
        }
    }
}
