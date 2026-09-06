// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.RestartRecovery;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.repository.AgentSession;

/**
 * Owns the durable state work that must run with the bundle, rather than only when a request
 * happens to arrive.
 *
 * <p>The service performs recovery before its first maintenance pass and uses the maintenance
 * service identity for both operations. A failed login or an unreadable contract leaves the
 * service unavailable; it never runs deferred caller work as a substitute for state recovery.</p>
 */
@Component(service = StateLifecycleService.class, immediate = true)
public final class StateLifecycleService {

    /** What lifecycle state the installed component is in. */
    public enum Availability {
        /** State services initialized and the latest pass completed. */
        READY,
        /** State services could not initialize or a pass failed. */
        UNAVAILABLE
    }

    /** A durable lifecycle observation suitable for discovery and health callers.
     * @param availability whether the latest lifecycle pass is ready
     * @param generation the generation observed by that pass
     * @param detail a human-readable outcome detail
     */
    public record Snapshot(Availability availability, long generation, String detail) {
    }

    private static final Snapshot STARTING = new Snapshot(Availability.UNAVAILABLE, 0,
            "state lifecycle has not initialized");
    private static final AtomicReference<Snapshot> OBSERVED = new AtomicReference<>(STARTING);

    private final AtomicReference<AgentSession> sessions = new AtomicReference<>();
    private final AtomicReference<Optional<Scheduler>> scheduler = new AtomicReference<>(Optional.empty());

    /** Creates the declarative-services component. */
    public StateLifecycleService() {
    }

    /** Binds the scoped state-session provider.
     * @param source the provider used for maintenance work
     */
    @Reference
    public void available(AgentSession source) {
        sessions.set(source);
    }

    /** Starts recovery immediately and schedules bounded maintenance afterward. */
    @Activate
    public void activate() {
        scheduler.set(Optional.of(Scheduler.open()));
        runOnce();
        final AgentContract.Loaded contract = contract();
        final long interval = RestartRecovery.intervalMilliseconds(contract.contract());
        scheduler.get().orElseThrow().schedule(this::runOnce, interval);
    }

    /** Stops scheduled work and revokes readiness before the component is released. */
    @Deactivate
    public void deactivate() {
        scheduler.getAndSet(Optional.empty()).ifPresent(Scheduler::stop);
        OBSERVED.set(new Snapshot(Availability.UNAVAILABLE, 0,
                "state lifecycle has stopped"));
    }

    /** Returns the latest lifecycle observation.
     * @return the most recent lifecycle snapshot
     */
    public static Snapshot observed() {
        return OBSERVED.get();
    }

    private void runOnce() {
        final AgentContract.Loaded loaded;
        try {
            loaded = contract();
        } catch (final IllegalStateException refused) {
            OBSERVED.set(new Snapshot(Availability.UNAVAILABLE, 0, refused.getMessage()));
            return;
        }
        final AgentSession source = sessions.get();
        if (source == null) {
            OBSERVED.set(new Snapshot(Availability.UNAVAILABLE, 0,
                    "state session provider is not bound"));
            return;
        }
        try {
            final AgentSession.Outcome<Run> outcome = source.withAgentState(
                    AgentSession.MAINTENANCE_SUBSERVICE,
                    resolver -> runIn(resolver, loaded.contract()));
            if (outcome instanceof AgentSession.Completed<Run> completed) {
                final Run run = completed.result();
                OBSERVED.set(new Snapshot(Availability.READY, run.generation().number(),
                        run.detail()));
            } else {
                OBSERVED.set(new Snapshot(Availability.UNAVAILABLE, 0,
                        "maintenance service login was refused"));
            }
        } catch (final IllegalStateException refused) {
            OBSERVED.set(new Snapshot(Availability.UNAVAILABLE, 0,
                    "state lifecycle failed: " + refused.getMessage()));
        }
    }

    private static Run runIn(ResourceResolver resolver, AgentContract contract) {
        return runIn(resolver.adaptTo(Session.class), contract);
    }

    private static Run runIn(Session session, AgentContract contract) {
        if (session == null) {
            throw new IllegalStateException("maintenance resolver has no JCR session");
        }
        try {
            final GenerationStore.Outcome established = GenerationStore.establish(session);
            if (!(established instanceof GenerationStore.Held held)) {
                throw new IllegalStateException("generation unavailable: " + established);
            }
            final EventStoreGeneration generation = held.generation();
            final DefaultContinuationKeyAuthority.Opening opened =
                    DefaultContinuationKeyAuthority.open(session, contract);
            if (!(opened instanceof DefaultContinuationKeyAuthority.Opened authority)) {
                throw new IllegalStateException("continuation authority unavailable: " + opened);
            }
            final ContinuationKeyAuthority.ReadOutcome ring = authority.authority().establish();
            if (!(ring instanceof ContinuationKeyAuthority.Read)) {
                throw new IllegalStateException("continuation key ring unavailable: " + ring);
            }
            final long now = System.currentTimeMillis();
            final RestartRecovery.Reconciliation recovery = RestartRecovery.reconcile(
                    session, generation, now, contract);
            final SweepReport sweep = MaintenanceSweep.run(
                    session, generation, now, contract);
            return new Run(generation, "recovery examined " + recovery.examined()
                    + "; maintenance examined " + sweep.examined());
        } catch (final RepositoryException failure) {
            throw new IllegalStateException("durable state pass failed: " + failure.getMessage(),
                    failure);
        }
    }

    private static AgentContract.Loaded contract() {
        final AgentContract.Outcome loaded = AgentContract.load();
        if (loaded instanceof AgentContract.Loaded present) {
            return present;
        }
        final AgentContract.Refused refused = (AgentContract.Refused) loaded;
        throw new IllegalStateException("contract unavailable: " + refused.failure()
                + refused.detail());
    }

    private record Run(EventStoreGeneration generation, String detail) {
    }

    private static final class Scheduler {

        private final ScheduledExecutorService executor;

        private Scheduler(ScheduledExecutorService source) {
            executor = source;
        }

        private static Scheduler open() {
            return new Scheduler(Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread worker = new Thread(runnable, "slingshot-state-lifecycle");
                worker.setDaemon(true);
                return worker;
            }));
        }

        private void schedule(Runnable task, long interval) {
            executor.scheduleWithFixedDelay(task, interval, interval, TimeUnit.MILLISECONDS);
        }

        private void stop() {
            executor.shutdownNow();
        }
    }
}
