// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.stream.ElapsedTime;

/**
 * A depth-first cursor that never restarts an already consumed child iterator.
 *
 * <p>The root must belong to a cursor-owned resolver. The runtime owner serializes calls and closes
 * that resolver when this cursor ends; the request resolver is used only during {@link #advance}.
 * Every returned row is computed from that request's current readable resource. Stable trees and
 * permissions consume each traversal iterator once in provider order. Changed trees are live,
 * without snapshot or global-sort guarantees; a removed or newly unreadable subtree is skipped.</p>
 *
 * <p>Node, match and iterator-depth bounds constrain retained work. Time and cancellation are
 * checked between traversal steps and after visiting a node; they cannot preempt a blocking
 * repository provider call. No complete-tree match list is retained.</p>
 *
 * @param <R> one materialized result row
 */
final class ResumableWalk<R> implements AutoCloseable {

    private final String rootPath;
    private final int maximumDepth;
    private final Deque<Frame> pending = new ArrayDeque<>();
    private final Deque<ComponentPageGroup> groups = new ArrayDeque<>();
    private final Map<String, ComponentPageEvidence> evidence = new LinkedHashMap<>();
    private final AtomicReference<Grouping> grouping =
            new AtomicReference<>(new Grouping(List.of(), MatchMode.ANY));
    private final AtomicBoolean configured = new AtomicBoolean();
    private final AtomicBoolean ancestryReady = new AtomicBoolean();
    private final AtomicReference<String> ancestorPath;
    private final AtomicLong ancestryExamined = new AtomicLong();
    private final AtomicLong pageNodeBudget = new AtomicLong();

    private enum Completion {
        ITERATOR, COMPONENT_PAGE
    }

    private record Frame(String parentPath, int depth, Iterator<Resource> children, Completion completion) {
    }

    /**
     * Detached grouping rules; an empty type list is ordinary per-node discovery.
     * @param types exact requested component types, empty for direct traversal
     * @param mode whether one or all requested types must qualify a nearest page
     */
    record Grouping(List<String> types, MatchMode mode) {
        Grouping {
            types = List.copyOf(types);
        }
    }

    private record Step(long work, long examined, List<Boundary> boundary, List<Stop> refused) {
        private static Step moved(long work, long examined) {
            return new Step(work, examined, List.of(), List.of());
        }

        private static Step ended(Boundary boundary) {
            return new Step(0, 0, List.of(boundary), List.of());
        }

        private static Step stopped(Stop reason) {
            return new Step(0, 0, List.of(), List.of(reason));
        }
    }

    /**
     * Starts with the root, which is counted like every other resource.
     * @param root resource from the cursor owner's resolver
     * @param maximumDepth maximum descendant depth, with the root at zero
     * @throws IllegalArgumentException if the depth is negative
     */
    ResumableWalk(Resource root, int maximumDepth) {
        if (maximumDepth < 0) {
            throw new IllegalArgumentException("discovery depth must be nonnegative");
        }
        this.rootPath = root.getPath();
        this.maximumDepth = maximumDepth;
        this.ancestorPath = new AtomicReference<>(rootPath);
        pending.push(new Frame(rootPath, 0, List.of(root).iterator(), Completion.ITERATOR));
    }

    /**
     * Per-page limits; iterator housekeeping also spends the node-work budget.
     * @param nodes maximum traversal steps, including exhausted-frame removal
     * @param matches maximum materialized matches
     * @param milliseconds cooperative monotonic duration bound
     */
    record Limits(long nodes, long matches, long milliseconds) {

        /** Rejects a page that cannot make progress. */
        Limits {
            if (nodes <= 0 || matches <= 0 || milliseconds <= 0) {
                throw new IllegalArgumentException("discovery page limits must be positive");
            }
        }
    }

    /** The reason a successful page ended. */
    enum Boundary {
        /** Every retained iterator is exhausted. */
        COMPLETE,
        /** The per-page traversal work was spent. */
        NODE_BUDGET,
        /** The monotonic duration was spent. */
        TIME_BUDGET,
        /** The requested number of matches was produced. */
        MATCH_LIMIT
    }

    /** A reason this cursor can no longer be used. */
    enum Stop {
        /** The owner already released this walk. */
        CLOSED,
        /** The cursor's fixed retention lifetime ended. */
        EXPIRED,
        /** The request belongs to another authenticated resolver user. */
        WRONG_CALLER,
        /** The caller or owner cancelled traversal. */
        CANCELLED,
        /** Current authority cannot read the original root. */
        ROOT_UNREADABLE,
        /** A provider returned a resource outside the original subtree. */
        SOURCE_CHANGED,
        /** Descending would exceed the retained iterator depth. */
        DEPTH_EXCEEDED,
        /** A complete current-authority proof cannot fit one request's work allowance. */
        VALIDATION_BUDGET
    }

    /** A bounded page or a terminal cursor refusal.
     * @param <R> one result row
     */
    sealed interface Outcome<R> permits Page, Refused {
    }

    /**
     * A page may be empty and still require continuation.
     * @param <R> one result row
     * @param rows the materialized matches, detached from the page builder
     * @param examined traversal visits and bounded component ancestry and witness checks this page
     * @param boundary why the page stopped
     * @param evidence detached component witnesses for immediate result replay
     */
    record Page<R>(List<R> rows, long examined, Boundary boundary,
                   Map<String, ComponentPageEvidence> evidence) implements Outcome<R> {

        /** Keeps a published page independent of its builder. */
        Page {
            rows = List.copyOf(rows);
            evidence = Map.copyOf(evidence);
        }

        /** Whether this page proves the enumeration ended.
         * @return true only after all iterators are exhausted
         */
        boolean complete() {
            return boundary == Boundary.COMPLETE;
        }
    }

    /**
     * The cursor is released and no partial rows are published.
     * @param <R> the result row type
     * @param reason why traversal was refused
     */
    record Refused<R>(Stop reason) implements Outcome<R> {
    }

    /**
     * Advances retained iterators and evaluates only currently readable resources.
     * @param limits bounds for this page
     * @param current the current request's resolver, never retained
     * @param opens whether a readable resource's children belong to this catalogue
     * @param match materializes a match from the current readable resource
     * @param cancelled the owner or request cancellation signal
     * @param elapsed a fresh monotonic duration for this page
     * @return a bounded page, or a refusal that closes the walk
     * @throws RuntimeException if a repository call or row mapper fails; the cursor is closed
     */
    Outcome<R> advance(Limits limits, ResourceResolver current, Predicate<Resource> opens,
                               Function<Resource, Optional<R>> match, BooleanSupplier cancelled,
                               ElapsedTime elapsed) {
        return advance(limits, current, opens, match, cancelled, elapsed,
                new Grouping(List.of(), MatchMode.ANY));
    }

    /**
     * Advances a grouped walk without retaining per-request callbacks or resources.
     * @param limits per-request work and row bounds
     * @param current current caller authority
     * @param opens ordinary discovery pruning
     * @param match immediate detached row materialization
     * @param cancelled current cancellation state
     * @param elapsed fresh request duration
     * @param requested immutable component grouping, or an empty direct mode
     * @return a bounded page or terminal refusal
     */
    Outcome<R> advance(Limits limits, ResourceResolver current, Predicate<Resource> opens,
                       Function<Resource, Optional<R>> match, BooleanSupplier cancelled,
                       ElapsedTime elapsed, Grouping requested) {
        if (pending.isEmpty()) {
            return new Refused<>(Stop.CLOSED);
        }
        boolean continued = false;
        try {
            if (cancelled.getAsBoolean()) {
                return refuse(Stop.CANCELLED);
            }
            if (current.getResource(rootPath) == null) {
                return refuse(Stop.ROOT_UNREADABLE);
            }
            if (configured.get() && !grouping.get().equals(requested)) {
                return refuse(Stop.SOURCE_CHANGED);
            }
            grouping.set(requested);
            configured.set(true);
            pageNodeBudget.set(limits.nodes());
            evidence.clear();
            final Outcome<R> answer = visit(limits, current, opens, match, cancelled, elapsed);
            continued = answer instanceof final Page<R> page && !page.complete();
            return answer;
        } finally {
            if (!continued) {
                close();
            }
        }
    }

    private Outcome<R> visit(Limits limits, ResourceResolver current, Predicate<Resource> opens,
                              Function<Resource, Optional<R>> match, BooleanSupplier cancelled,
                              ElapsedTime elapsed) {
        final List<R> rows = new ArrayList<>();
        long examined = 0;
        long steps = 0;
        while (!pending.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return refuse(Stop.CANCELLED);
            }
            if (elapsed.milliseconds() >= limits.milliseconds()) {
                return page(rows, examined, Boundary.TIME_BUDGET);
            }
            if (rows.size() >= limits.matches()) {
                return page(rows, examined, Boundary.MATCH_LIMIT);
            }
            if (steps >= limits.nodes()) {
                return page(rows, examined, Boundary.NODE_BUDGET);
            }
            final Step step = step(new Limits(limits.nodes() - steps, limits.matches(),
                    limits.milliseconds()), current, opens, match, rows, cancelled, elapsed);
            if (!step.refused().isEmpty()) {
                return refuse(step.refused().getFirst());
            }
            if (!step.boundary().isEmpty()) {
                return page(rows, examined + step.examined(), step.boundary().getFirst());
            }
            steps = Math.addExact(steps, step.work());
            examined = Math.addExact(examined, step.examined());
        }
        if (cancelled.getAsBoolean()) {
            return refuse(Stop.CANCELLED);
        }
        return page(rows, examined, Boundary.COMPLETE);
    }

    private Page<R> page(List<R> rows, long examined, Boundary boundary) {
        return new Page<>(rows, examined, boundary, evidence);
    }

    private Step step(Limits remaining, ResourceResolver current, Predicate<Resource> opens,
                      Function<Resource, Optional<R>> match, List<R> rows,
                      BooleanSupplier cancelled, ElapsedTime elapsed) {
        if (!grouping.get().types().isEmpty() && !ancestryReady.get()) {
            return ancestor(current);
        }
        final Frame frame = pending.peek();
        if (current.getResource(frame.parentPath()) == null) {
            if (frame.completion() == Completion.COMPONENT_PAGE) {
                return Step.stopped(Stop.SOURCE_CHANGED);
            }
            pending.pop();
            return Step.moved(1, 0);
        }
        if (!frame.children().hasNext()) {
            if (frame.completion() == Completion.COMPONENT_PAGE) {
                return finish(remaining, current, match, rows, cancelled, elapsed);
            }
            pending.pop();
            return Step.moved(1, 0);
        }
        final Optional<Stop> stopped = examine(frame, current, opens, match, rows);
        return stopped.map(Step::stopped).orElseGet(() -> Step.moved(1, 1));
    }

    private Step ancestor(ResourceResolver current) {
        if (ancestryExamined.get() > maximumDepth) {
            return Step.stopped(Stop.DEPTH_EXCEEDED);
        }
        final String path = ancestorPath.get();
        final Optional<Resource> resource = Optional.ofNullable(current.getResource(path));
        ancestryExamined.incrementAndGet();
        if (resource.filter(value -> value.getPath().equals(path)).isEmpty()) {
            return Step.stopped(Stop.SOURCE_CHANGED);
        }
        if (isPage(resource.orElseThrow())) {
            ancestryReady.set(true);
            if (!rootPath.equals(path)) {
                groups.push(new ComponentPageGroup(path, grouping.get().types(), grouping.get().mode()));
                final Frame root = pending.pop();
                pending.push(new Frame(root.parentPath(), root.depth(), root.children(),
                        Completion.COMPONENT_PAGE));
            }
        } else if ("/".equals(path)) {
            ancestryReady.set(true);
        } else {
            final int separator = path.lastIndexOf('/');
            ancestorPath.set(separator <= 0 ? "/" : path.substring(0, separator));
        }
        return Step.moved(1, 1);
    }

    private Step finish(Limits remaining, ResourceResolver current, Function<Resource, Optional<R>> match,
                        List<R> rows, BooleanSupplier cancelled, ElapsedTime elapsed) {
        final ComponentPageGroup group = groups.peek();
        if (!group.qualifies()) {
            groups.pop();
            pending.pop();
            return Step.moved(1, 0);
        }
        final ComponentPageEvidence proof;
        try {
            proof = group.evidence(maximumDepth);
        } catch (final IllegalArgumentException excessiveAncestry) {
            return Step.stopped(Stop.DEPTH_EXCEEDED);
        }
        if (proof.work() > pageNodeBudget.get()) {
            return Step.stopped(Stop.VALIDATION_BUDGET);
        }
        if (proof.work() > remaining.nodes()) {
            return Step.ended(Boundary.NODE_BUDGET);
        }
        final var checked = proof.validate(current, elapsed, remaining.milliseconds(), cancelled);
        if (checked.state() == ComponentPageEvidence.State.TIME_BUDGET) {
            return new Step(checked.examined(), checked.examined(), List.of(Boundary.TIME_BUDGET), List.of());
        }
        if (checked.state() != ComponentPageEvidence.State.ACCEPTED) {
            return Step.stopped(checked.state() == ComponentPageEvidence.State.CANCELLED
                    ? Stop.CANCELLED : Stop.SOURCE_CHANGED);
        }
        final Optional<R> row = match.apply(checked.pages().getFirst());
        row.ifPresent(value -> {
            rows.add(value);
            evidence.put(group.path(), proof);
        });
        groups.pop();
        pending.pop();
        return Step.moved(checked.examined(), checked.examined());
    }

    private Optional<Stop> examine(Frame frame, ResourceResolver current, Predicate<Resource> opens,
                                    Function<Resource, Optional<R>> match, List<R> rows) {
        final Resource held = frame.children().next();
        if (!inside(held.getPath())) {
            return Optional.of(Stop.SOURCE_CHANGED);
        }
        final Optional<Resource> readable = Optional.ofNullable(current.getResource(held.getPath()));
        if (readable.isEmpty()) {
            return Optional.empty();
        }
        if (!held.getPath().equals(readable.orElseThrow().getPath())) {
            return Optional.of(Stop.SOURCE_CHANGED);
        }
        final Resource resource = readable.orElseThrow();
        final boolean componentPage = !grouping.get().types().isEmpty() && isPage(resource);
        if (grouping.get().types().isEmpty()) {
            match.apply(resource).ifPresent(rows::add);
        } else {
            if (componentPage) {
                groups.push(new ComponentPageGroup(resource.getPath(),
                        grouping.get().types(), grouping.get().mode()));
            }
            if (!groups.isEmpty()) {
                groups.peek().match(resource);
            }
        }
        return opens.test(resource) && !descend(frame, held,
                componentPage
                        ? Completion.COMPONENT_PAGE : Completion.ITERATOR)
                ? Optional.of(Stop.DEPTH_EXCEEDED) : Optional.empty();
    }

    private boolean inside(String path) {
        return rootPath.equals(path) || path.startsWith("/".equals(rootPath) ? "/" : rootPath + "/");
    }

    private boolean descend(Frame frame, Resource resource, Completion completion) {
        final Iterator<Resource> children = resource.listChildren();
        if (!children.hasNext()) {
            if (completion == Completion.COMPONENT_PAGE) {
                pending.push(new Frame(resource.getPath(), frame.depth(), children, completion));
            }
            return true;
        }
        if (frame.depth() >= maximumDepth) {
            return false;
        }
        pending.push(new Frame(resource.getPath(), frame.depth() + 1, children, completion));
        return true;
    }

    private static boolean isPage(Resource resource) {
        return ListChildPagesHandler.PAGE_TYPE.equals(ChildListingHandler.typeOf(resource));
    }

    private Refused<R> refuse(Stop reason) {
        close();
        return new Refused<>(reason);
    }

    /** Releases iterator references; the runtime owner separately closes its resolver. */
    @Override
    public void close() {
        pending.clear();
        groups.clear();
        evidence.clear();
    }
}
