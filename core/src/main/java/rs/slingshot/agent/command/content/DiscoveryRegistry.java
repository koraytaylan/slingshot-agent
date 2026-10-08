// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.stream.DefaultStreamTicker;

/**
 * Runtime-owned, bounded discovery state. Tokens from another runtime cannot name these cursors.
 * Every cursor retains at most one replay page, its original limit and its original expiry.
 * The runtime closes this registry during deactivation; request resolvers are never retained.
 */
public final class DiscoveryRegistry implements AutoCloseable {

    /** A typed row materializer refuses an unrepresentable or over-bound repository field. */
    static final class RowTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final AgentContract contract;
    private final LongSupplier monotonic;
    private final String namespace = "incremental-discovery/2/" + UUID.randomUUID();
    private final ConcurrentMap<Long, DiscoverySession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong positions = new AtomicLong();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Semaphore capacity;
    private final ScheduledExecutorService collector;

    /**
     * Creates a runtime registry; call {@link #start} once activation has succeeded.
     * @param contract authenticated capacity and lifetime bounds
     */
    public DiscoveryRegistry(AgentContract contract) {
        this(contract, new DefaultStreamTicker()::elapsedMilliseconds);
    }

    /**
     * Creates a registry against an injected monotonic source.
     * @param contract authenticated bounds
     * @param monotonic local elapsed milliseconds, without epoch meaning
     */
    DiscoveryRegistry(AgentContract contract, LongSupplier monotonic) {
        this.contract = contract;
        this.monotonic = monotonic;
        this.capacity =
                new Semaphore(Math.toIntExact(contract.value(ContractLimit.MAXIMUM_DISCOVERY_CURSORS)));
        this.collector = Executors.newSingleThreadScheduledExecutor(work -> {
            final Thread thread = new Thread(work, "slingshot-discovery-expiry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts periodic idle collection after activation has published this registry. */
    public void start() {
        if (!stopped.get() && started.compareAndSet(false, true)) {
            schedule();
        }
    }

    private void schedule() {
        if (stopped.get()) {
            return;
        }
        final long interval = contract.value(ContractLimit.DISCOVERY_CURSOR_COLLECTION_INTERVAL_MILLISECONDS);
        try {
            collector.schedule(this::sweep, interval, TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException rejected) {
            if (!stopped.get()) {
                throw rejected;
            }
        }
    }

    private void sweep() {
        try {
            collect();
        } finally {
            schedule();
        }
    }

    /**
     * A parsed listing request, before repository work begins.
     * @param wireName the command name
     * @param rootPath the exact subtree root
     * @param window the requested page
     * @param arguments all submitted arguments, used to authenticate continuation scope
     */
    public record Request(String wireName, String rootPath, ResultWindow window,
                          DocumentValue.Mapping arguments) {
    }

    /**
     * Per-call catalogue rules; neither callback is retained after the page finishes.
     * @param opens whether to visit a readable resource's children
     * @param row a detached row, or no match, computed using current request authority
     * @param componentTypes bounded requested types, or empty for direct discovery
     * @param componentMode whether grouped pages require one or every requested type
     */
    public record Traversal(Predicate<Resource> opens,
                            Function<Resource, Optional<DocumentValue.Mapping>> row,
                            List<String> componentTypes, MatchMode componentMode) {
        /** Keeps requested types detached from caller-owned collection state. */
        public Traversal {
            componentTypes = List.copyOf(componentTypes);
        }

        /**
         * Ordinary per-node discovery has no component-page aggregation.
         * @param opens current per-request pruning
         * @param row current detached row materialization
         */
        public Traversal(Predicate<Resource> opens,
                         Function<Resource, Optional<DocumentValue.Mapping>> row) {
            this(opens, row, List.of(), MatchMode.ANY);
        }
    }

    /**
     * Validates authority before opening or accessing any retained traversal.
     * @param request the parsed listing request
     * @param resolver the current borrowed caller resolver
     * @param context the request's budgets and paging authority
     * @param traversal the catalogue's current row materializer
     * @return a bounded page with explicit completeness, or a declared refusal
     */
    public CommandHandler.Answer page(Request request, ResourceResolver resolver,
                                       CallerContext context, Traversal traversal) {
        final PagingSupport.Preparation prepared = PagingSupport.prepare(request.window(),
                namespace + "/" + request.wireName(), request.arguments(), context, contract);
        if (prepared instanceof final PagingSupport.WindowRefused refused) {
            return new CommandHandler.Failed(refused.category(), refused.detail());
        }
        if (!(context.paging() instanceof final CallerContext.Available paging)
                || !(paging.authority().read() instanceof final ContinuationKeyAuthority.Read authority)) {
            return failed("continuation_token_integrity_invalid", "continuation authority is unavailable");
        }
        if (stopped.get()) {
            return expired();
        }
        final PagingSupport.Ready ready = (PagingSupport.Ready) prepared;
        final var identity = new DiscoverySession.Identity(resolver.getUserID(), ready.digest(),
                paging.targetDigest(), paging.generation());
        if (request.window() instanceof ResultWindow.Initial) {
            return begin(request, resolver, context, traversal, ready,
                    new DiscoverySession.Authority(identity, authority.ring(), paging.nowUnixMilliseconds()));
        }
        final Optional<DiscoverySession> session = sessions.values().stream()
                .filter(held -> held.accepts(ready.offset())).findFirst();
        if (session.isEmpty()) {
            return expired();
        }
        return advance(session.orElseThrow(), ready.offset(), resolver, context, traversal,
                new DiscoverySession.Authority(identity, authority.ring(), paging.nowUnixMilliseconds()));
    }

    private CommandHandler.Answer begin(Request request, ResourceResolver resolver, CallerContext context,
                                          Traversal traversal, PagingSupport.Ready ready,
                                          DiscoverySession.Authority authority) {
        if (!capacity.tryAcquire()) {
            return failed("discovery_budget_exceeded", "the runtime's discovery cursor capacity is occupied");
        }
        boolean registered = false;
        try {
            final Optional<DiscoverySession> opened = DiscoverySession.open(request.rootPath(), resolver,
                    ready, authority, contract, monotonic, this::position);
            if (opened.isEmpty()) {
                return failed("root_not_found", "the exact discovery root is unavailable");
            }
            final DiscoverySession session = opened.orElseThrow();
            sessions.put(session.identifier(), session);
            registered = true;
            if (stopped.get()) {
                retire(session);
                return expired();
            }
            return advance(session, 0, resolver, context, traversal, authority);
        } catch (final LoginException unavailable) {
            return failed("root_access_denied", "the caller's discovery resolver could not be opened");
        } finally {
            if (!registered) {
                capacity.release();
            }
        }
    }

    private CommandHandler.Answer advance(DiscoverySession session, long position, ResourceResolver resolver,
                                            CallerContext context, Traversal traversal,
                                            DiscoverySession.Authority authority) {
        boolean answered = false;
        try {
            final CommandHandler.Answer answer = session.page(position, resolver, context,
                    traversal, authority);
            answered = true;
            return answer;
        } finally {
            if (!answered || session.ended()) {
                retire(session);
            }
        }
    }

    private long position() {
        final long next = positions.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1);
        if (next == Long.MAX_VALUE) {
            throw new IllegalStateException("discovery continuation positions are exhausted");
        }
        return next;
    }

    /** Collects at most the configured cursor capacity without waiting on active provider calls. */
    void collect() {
        collectRemaining(sessions.values().stream()
                .limit(contract.value(ContractLimit.MAXIMUM_DISCOVERY_CURSORS)).toList().iterator());
    }

    private void collectRemaining(Iterator<DiscoverySession> remaining) {
        if (!remaining.hasNext()) {
            return;
        }
        try {
            collectOne(remaining.next());
        } finally {
            collectRemaining(remaining);
        }
    }

    private void collectOne(DiscoverySession session) {
        if (session.expire()) {
            retire(session);
        }
    }

    private void retire(DiscoverySession session) {
        if (sessions.remove(session.identifier(), session)) {
            try {
                session.close();
            } finally {
                capacity.release();
            }
        }
    }

    /** The refusal for released runtime state.
     * @return the declared expired-continuation failure
     */
    static CommandHandler.Failed expired() {
        return failed("continuation_token_expired",
                "the discovery cursor is no longer available; start a new listing");
    }

    /**
     * Creates a discovery refusal without reflecting token contents.
     * @param category the command's declared category
     * @param detail the observed reason
     * @return the failure
     */
    static CommandHandler.Failed failed(String category, String detail) {
        return new CommandHandler.Failed(category, detail);
    }

    /** Revokes new work, stops idle collection and releases every retained resolver and replay page. */
    @Override
    public void close() {
        stopped.set(true);
        collector.shutdownNow();
        sessions.values().forEach(DiscoverySession::stop);
        closeRemaining(sessions.values().iterator());
    }

    private void closeRemaining(Iterator<DiscoverySession> remaining) {
        if (!remaining.hasNext()) {
            return;
        }
        try {
            retire(remaining.next());
        } finally {
            closeRemaining(remaining);
        }
    }
}
