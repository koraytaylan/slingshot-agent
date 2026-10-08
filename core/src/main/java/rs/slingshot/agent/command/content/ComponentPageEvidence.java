// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.stream.ElapsedTime;

/** Detached, bounded witnesses that must still belong to one currently readable page. */
final class ComponentPageEvidence {

    /** The observed outcome of rechecking every contributing source and intervening ancestor. */
    enum State {
        ACCEPTED, CHANGED, TIME_BUDGET, CANCELLED
    }

    /**
     * Current request resources are returned only for immediate row materialization.
     * @param state the first observed refusal, or acceptance of the entire proof
     * @param examined provider lookups consumed by this validation attempt
     * @param pages the currently readable owning page, retained only for immediate materialization
     */
    record Validation(State state, long examined, List<Resource> pages) {
        Validation {
            pages = List.copyOf(pages);
        }
    }

    private record Expectation(String path, String componentType) {
    }

    private final String pagePath;
    private final List<Expectation> paths;

    /**
     * Retains at most one matching source per requested type and bounded ancestor paths.
     * @param pagePath the nearest page observed during traversal
     * @param sources component type to contributing resource path
     * @param maximumDepth the authenticated retained traversal depth
     * @throws IllegalArgumentException if a source cannot reach this page within that bound
     */
    ComponentPageEvidence(String pagePath, SequencedMap<String, String> sources, int maximumDepth) {
        this.pagePath = pagePath;
        final SequencedMap<String, String> expected = new LinkedHashMap<>();
        sources.forEach((type, path) -> expected.put(path, type));
        sources.values().forEach(path -> ancestry(path, maximumDepth).forEach(
                ancestor -> expected.putIfAbsent(ancestor, "")));
        this.paths = expected.entrySet().stream()
                .map(entry -> new Expectation(entry.getKey(), entry.getValue())).toList();
    }

    private List<String> ancestry(String path, int maximumDepth) {
        final List<String> ancestors = Stream.iterate(path, value -> !value.isEmpty(),
                value -> value.equals(pagePath) ? "" : parent(value))
                .limit((long) maximumDepth + 1).toList();
        if (ancestors.isEmpty() || !ancestors.getLast().equals(pagePath)) {
            throw new IllegalArgumentException("a component witness exceeds its page ancestry bound");
        }
        return ancestors;
    }

    private static String parent(String path) {
        final int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/".equals(path) ? "" : "/" : path.substring(0, separator);
    }

    /** Provider reads needed for one complete current-authority proof.
     * @return the counted source and distinct ancestor paths
     */
    long work() {
        return paths.size();
    }

    /** The page this proof belongs to.
     * @return the detached page address
     */
    String pagePath() {
        return pagePath;
    }

    /**
     * Rechecks the proof within the current request, never retaining that request's resources.
     * @param current current caller authority
     * @param elapsed fresh per-request duration
     * @param milliseconds the authenticated cooperative time bound
     * @param cancelled current cancellation state
     * @return counted work, immediate page resource and an exact observed outcome
     */
    Validation validate(ResourceResolver current, ElapsedTime elapsed, long milliseconds,
                        BooleanSupplier cancelled) {
        final var examined = new AtomicLong();
        final List<Resource> pages = new ArrayList<>();
        final State state = paths.stream().map(expected -> {
            if (cancelled.getAsBoolean()) {
                return State.CANCELLED;
            }
            if (elapsed.milliseconds() >= milliseconds) {
                return State.TIME_BUDGET;
            }
            examined.incrementAndGet();
            final Optional<Resource> readable = Optional.ofNullable(current.getResource(expected.path()));
            if (readable.filter(resource -> matches(resource, expected)).isEmpty()) {
                return State.CHANGED;
            }
            readable.filter(resource -> pagePath.equals(resource.getPath())).ifPresent(pages::add);
            return State.ACCEPTED;
        }).filter(observed -> observed != State.ACCEPTED).findFirst().orElse(State.ACCEPTED);
        return new Validation(state, examined.get(), pages);
    }

    private boolean matches(Resource resource, Expectation expected) {
        if (!resource.getPath().equals(expected.path())) {
            return false;
        }
        if (!expected.componentType().isEmpty() && !expected.componentType().equals(
                resource.getValueMap().get(FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY, ""))) {
            return false;
        }
        final boolean page = ListChildPagesHandler.PAGE_TYPE.equals(ChildListingHandler.typeOf(resource));
        return page == pagePath.equals(resource.getPath());
    }
}
