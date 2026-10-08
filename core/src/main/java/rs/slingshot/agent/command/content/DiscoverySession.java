// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.continuation.ContinuationState;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.QueryDigest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.stream.ElapsedTime;

/** A serialized cursor and its last bounded answer; completed cursors retain no resolver. */
final class DiscoverySession implements AutoCloseable {

    /**
     * Authenticated cursor scope, checked again before replay or traversal.
     * @param actor the resolver's authenticated user
     * @param query the command, runtime namespace and argument digest
     * @param target the author partition
     * @param generation the event-store incarnation
     */
    record Identity(String actor, QueryDigest query, DigestValue target, EventStoreGeneration generation) {
    }

    /**
     * Current signing authority, never retained by the session.
     * @param identity the authenticated cursor scope
     * @param ring the current signing key ring
     * @param nowUnixMilliseconds the request instant used only to establish wire expiry
     */
    record Authority(Identity identity, KeyRing ring, long nowUnixMilliseconds) {
    }

    private record Specification(String root, Identity identity, long limit, long expiresAt,
                                  long lifetime, long identifier, long titleBytes) {
    }

    private sealed interface State permits Fresh, Cached, Closed {
    }

    private record Fresh() implements State {
    }

    private record Cached(long requested, long next, List<DocumentValue.Mapping> rows,
                           CommandHandler.Produced answer,
                           Map<String, ComponentPageEvidence> evidence) implements State {
        private Cached {
            rows = List.copyOf(rows);
            evidence = Map.copyOf(evidence);
        }
    }

    private record Closed() implements State {
    }

    private final Specification specification;
    private final ElapsedTime age;
    private final LongSupplier monotonic;
    private final LongSupplier positions;
    private final AtomicLong offset;
    private final AtomicReference<List<DiscoveryCursor<DocumentValue.Mapping>>> cursor;
    private final AtomicReference<State> state = new AtomicReference<>(new Fresh());
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final ReentrantLock monitor = new ReentrantLock();

    private DiscoverySession(Specification specification, DiscoveryCursor<DocumentValue.Mapping> cursor,
                              ElapsedTime age, LongSupplier monotonic, LongSupplier positions, long offset) {
        this.specification = specification;
        this.cursor = new AtomicReference<>(List.of(cursor));
        this.age = age;
        this.monotonic = monotonic;
        this.positions = positions;
        this.offset = new AtomicLong(offset);
    }

    /**
     * Opens one owned traversal after admission has reserved a capacity slot.
     * @param root the exact subtree root
     * @param resolver the borrowed current caller resolver
     * @param ready validated initial offset and limit
     * @param authority authenticated scope and signing authority
     * @param contract authenticated bounds
     * @param monotonic the local duration source
     * @param positions the runtime's never-reused position issuer
     * @return a session, or nothing when its root cannot be acquired
     * @throws LoginException if the caller's resolver cannot be cloned
     */
    static Optional<DiscoverySession> open(String root, ResourceResolver resolver, PagingSupport.Ready ready,
                                            Authority authority, AgentContract contract,
                                            LongSupplier monotonic,
                        LongSupplier positions) throws LoginException {
        final long lifetime = contract.value(ContractLimit.CONTINUATION_TOKEN_LIFETIME_MILLISECONDS);
        final var specification = new Specification(root, authority.identity(), ready.limit(),
                Math.addExact(authority.nowUnixMilliseconds(), lifetime), lifetime, positions.getAsLong(),
                contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES));
        final ElapsedTime age = ElapsedTime.start(monotonic);
        return DiscoveryCursor.<DocumentValue.Mapping>open(resolver, root,
                Math.toIntExact(contract.value(ContractLimit.MAXIMUM_DISCOVERY_CURSOR_DEPTH)),
                        lifetime, monotonic)
                .map(cursor -> new DiscoverySession(specification, cursor, age, monotonic,
                        positions, ready.offset()));
    }

    /** The key owning this registry slot.
     * @return the unique runtime session identifier
     */
    long identifier() {
        return specification.identifier();
    }

    /** Whether a token can address the next or immediately previous page.
     * @param position the validated token position
     * @return whether this session owns that position
     */
    boolean accepts(long position) {
        return position > 0 && state.get() instanceof final Cached cached
                && (cached.requested() == position || cached.next() == position);
    }

    /** Whether the registry may release this session's capacity.
     * @return true after terminal refusal or an initial complete page
     */
    boolean ended() {
        return state.get() instanceof Closed;
    }

    /**
     * Advances or replays one page without waiting for another page's provider calls.
     * @param position zero initially, or the authenticated continuation position
     * @param resolver the current borrowed resolver
     * @param context this request's budgets
     * @param traversal the per-call catalogue rules
     * @param authority current identity and signing authority
     * @return the bounded answer or declared refusal
     */
    CommandHandler.Answer page(long position, ResourceResolver resolver, CallerContext context,
                                 DiscoveryRegistry.Traversal traversal, Authority authority) {
        if (!monitor.tryLock()) {
            return DiscoveryRegistry.failed("discovery_budget_exceeded",
                    "this discovery cursor is already in use");
        }
        try {
            return pageLocked(position, resolver, context, traversal, authority);
        } finally {
            monitor.unlock();
        }
    }

    private CommandHandler.Answer pageLocked(long position, ResourceResolver resolver, CallerContext context,
                                               DiscoveryRegistry.Traversal traversal, Authority authority) {
        if (!specification.identity().equals(authority.identity())) {
            return DiscoveryRegistry.failed("continuation_token_wrong_query",
                    "the cursor belongs to another caller or query");
        }
        if (expired() || stopped.get() || ended()) {
            close();
            return DiscoveryRegistry.expired();
        }
        final Optional<Resource> root = Optional.ofNullable(resolver.getResource(specification.root()));
        if (root.filter(resource -> specification.root().equals(resource.getPath())).isEmpty()) {
            close();
            return DiscoveryRegistry.failed("root_access_denied",
                    "the original discovery root is no longer readable");
        }
        final State current = state.get();
        if (current instanceof final Cached cached && cached.requested() == position) {
            return replay(cached, resolver, context, traversal);
        }
        if (!isNext(current, position)) {
            return DiscoveryRegistry.expired();
        }
        return advance(position, resolver, context, traversal, authority);
    }

    private static boolean isNext(State current, long position) {
        return current instanceof Fresh && position == 0
                || current instanceof final Cached cached && position > 0 && cached.next() == position;
    }

    private CommandHandler.Answer replay(Cached cached, ResourceResolver resolver, CallerContext context,
                                          DiscoveryRegistry.Traversal traversal) {
        final ElapsedTime elapsed = ElapsedTime.start(monotonic);
        final long proofWork = cached.evidence().values().stream()
                .mapToLong(ComponentPageEvidence::work).sum();
        final boolean unchanged = proofWork + cached.rows().size() - cached.evidence().size()
                <= context.discovery().limit()
                && unchangedRows(cached, resolver, traversal, context, elapsed);
        if (!unchanged || cancelled() || elapsed.milliseconds() >= context.time().limit()) {
            close();
            return DiscoveryRegistry.expired();
        }
        return cached.answer();
    }

    private boolean unchangedRows(Cached cached, ResourceResolver resolver,
                                  DiscoveryRegistry.Traversal traversal, CallerContext context,
                                  ElapsedTime elapsed) {
        try {
            return cached.rows().stream().allMatch(row -> currentRow(row, resolver, traversal,
                    context, elapsed, cached.evidence()));
        } catch (final DiscoveryRegistry.RowTooLarge unrepresentable) {
            return false;
        }
    }

    private boolean currentRow(DocumentValue.Mapping row, ResourceResolver resolver,
                                DiscoveryRegistry.Traversal traversal, CallerContext context,
                                ElapsedTime elapsed, Map<String, ComponentPageEvidence> evidence) {
        if (cancelled() || elapsed.milliseconds() >= context.time().limit()) {
            return false;
        }
        final DocumentValue path = row.members().get("repository_path");
        if (!(path instanceof final DocumentValue.Text text)) {
            return false;
        }
        if (evidence.containsKey(text.value())) {
            final var checked = evidence.get(text.value()).validate(resolver, elapsed,
                    context.time().limit(), this::cancelled);
            return checked.state() == ComponentPageEvidence.State.ACCEPTED
                    && traversal.row().apply(checked.pages().getFirst()).filter(row::equals).isPresent();
        }
        return Optional.ofNullable(resolver.getResource(text.value()))
                .filter(resource -> resource.getPath().equals(text.value()))
                .flatMap(traversal.row()).filter(row::equals).isPresent();
    }

    private CommandHandler.Answer advance(long position, ResourceResolver resolver, CallerContext context,
                                           DiscoveryRegistry.Traversal traversal, Authority authority) {
        final AtomicLong bytes = new AtomicLong();
        try {
            final var limits = new ResumableWalk.Limits(context.discovery().limit(), specification.limit(),
                    context.time().limit());
            final var outcome = cursor.get().getFirst().advance(limits, resolver, traversal.opens(),
                    resource -> row(resource, traversal, context.result().limit(), bytes),
                    this::cancelled, ElapsedTime.start(monotonic),
                    new ResumableWalk.Grouping(traversal.componentTypes(), traversal.componentMode()));
            if (outcome instanceof final ResumableWalk.Refused<DocumentValue.Mapping> refused) {
                return refused(refused.reason());
            }
            return publish(position, (ResumableWalk.Page<DocumentValue.Mapping>) outcome,
                    context.result().limit(), authority);
        } catch (final PageTooLarge | DiscoveryRegistry.RowTooLarge exceeded) {
            close();
            return DiscoveryRegistry.failed("discovery_budget_exceeded",
                    "the discovery result exceeds a field, row or page byte bound; "
                            + "narrow the root or reduce the limit");
        }
    }

    private Optional<DocumentValue.Mapping> row(Resource resource, DiscoveryRegistry.Traversal traversal,
                                                 long bound, AtomicLong bytes) {
        final Optional<DocumentValue.Mapping> row = traversal.row().apply(resource);
        if (row.isEmpty()) {
            return row;
        }
        if (offset.get() > 0) {
            offset.decrementAndGet();
            return Optional.empty();
        }
        final long size = size(row.orElseThrow(), bound);
        if (bytes.addAndGet(size) > bound) {
            throw new PageTooLarge();
        }
        return row;
    }

    private CommandHandler.Answer publish(long position, ResumableWalk.Page<DocumentValue.Mapping> page,
                                           long bound, Authority authority) {
        if (cancelled()) {
            close();
            return DiscoveryRegistry.expired();
        }
        final long next = page.complete() ? 0 : positions.getAsLong();
        final var answer = new CommandHandler.Produced(IncrementalDiscoveryResult.documentOf(
                page.rows(), page.complete() ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE,
                page.examined(), next > 0 ? token(next, authority) : ""));
        size(answer.result(), bound);
        state.set(new Cached(position, next, page.rows(), answer, page.evidence()));
        if (page.complete()) {
            releaseCursor();
            if (position == 0) {
                state.set(new Closed());
            }
        }
        return answer;
    }

    private String token(long position, Authority authority) {
        final Identity identity = specification.identity();
        return ContinuationToken.issue(new ContinuationState(identity.generation(), identity.target(),
                identity.query().value(), position, specification.limit(), specification.expiresAt()),
                        authority.ring().current()).rendered();
    }

    private CommandHandler.Answer refused(ResumableWalk.Stop reason) {
        close();
        return switch (reason) {
            case CLOSED, EXPIRED, SOURCE_CHANGED -> DiscoveryRegistry.expired();
            case ROOT_UNREADABLE, WRONG_CALLER -> DiscoveryRegistry.failed("root_access_denied",
                    "current authority cannot read this discovery cursor");
            case CANCELLED, DEPTH_EXCEEDED -> DiscoveryRegistry.failed("discovery_budget_exceeded",
                    "discovery was cancelled or exceeded its retained depth bound");
            case VALIDATION_BUDGET -> DiscoveryRegistry.failed("discovery_budget_exceeded",
                    "one complete current-authority proof exceeds this request's work allowance");
        };
    }

    private long size(DocumentValue.Mapping row, long bound) {
        if (row.member("title").filter(DocumentValue.Text.class::isInstance)
                .map(DocumentValue.Text.class::cast).map(DocumentValue.Text::value)
                .filter(title -> title.length() > specification.titleBytes()
                        || title.getBytes(StandardCharsets.UTF_8).length > specification.titleBytes())
                .isPresent()) {
            throw new PageTooLarge();
        }
        if (row.members().values().stream().filter(DocumentValue.Text.class::isInstance)
                .map(DocumentValue.Text.class::cast).anyMatch(text -> text.value().length() > bound)) {
            throw new PageTooLarge();
        }
        final var written = CanonicalByteWriter.write(row);
        if (!(written instanceof final CanonicalByteWriter.Written accepted)
                || accepted.bytes().length > bound) {
            throw new PageTooLarge();
        }
        return accepted.bytes().length;
    }

    private static final class PageTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private boolean expired() {
        return age.milliseconds() >= specification.lifetime();
    }

    private boolean cancelled() {
        return stopped.get() || expired() || Thread.currentThread().isInterrupted();
    }

    /** Releases expired state without waiting for a busy cursor.
     * @return whether expired state was released
     */
    boolean expire() {
        if (!expired() || !monitor.tryLock()) {
            return false;
        }
        try {
            close();
            return true;
        } finally {
            monitor.unlock();
        }
    }

    /** Signals cancellation before shutdown waits for provider ownership. */
    void stop() {
        stopped.set(true);
    }

    private void releaseCursor() {
        cursor.getAndSet(List.of()).forEach(DiscoveryCursor::close);
    }

    /** Releases the owned resolver and cached answer once traversal has stopped. */
    @Override
    public void close() {
        stop();
        monitor.lock();
        try {
            state.set(new Closed());
            releaseCursor();
        } finally {
            monitor.unlock();
        }
    }
}
