// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * How far a destructive change reaches, counted before any of it happens.
 *
 * <p>Four commands remove something and two move something, and all six have to answer the same two
 * questions first: how much would go, and what points at it. Written once because the answers are
 * only useful before the change — a subtree counted afterwards is a subtree already gone, and a
 * reference found afterwards is one already broken.</p>
 */
public final class RepositoryReach {

    /**
     * Reference discovery result, including whether the visibility budget covered the tree.
     *
     * @param found resources that mention the address
     * @param completeness whether the bounded walk reached its end
     */
    public record References(List<Resource> found, Completeness completeness) {

        /** Whether the bounded walk reached its end. */
        public boolean complete() {
            return completeness == Completeness.COMPLETE;
        }

        /** Holds the discovered resources independently of the traversal's mutable list. */
        public References {
            found = List.copyOf(found);
        }
    }

    /** Whether bounded reference discovery reached the end of its visible tree. */
    public enum Completeness {
        /** Every visible resource was examined. */
        COMPLETE,
        /** The caller's examination bound stopped discovery. */
        INCOMPLETE
    }

    private RepositoryReach() {
    }

    /** Where content lives, which is where a reference to something would be written. */
    public static final String CONTENT_ROOT = "/content";

    /**
     * Every node one removal would take, counted one past the bound and no further.
     *
     * <p>Stopped at one past on purpose: what the answer needs is whether the subtree is over the
     * bound, and counting a repository-sized subtree to find that out is the cost the bound exists
     * to avoid.</p>
     *
     * @param root what is being removed
     * @param bound how many nodes one removal may take
     * @return the addresses, which is one longer than the bound where the subtree is over it
     */
    public static List<String> under(Resource root, long bound) {
        final List<String> found = new ArrayList<>();
        final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
        java.util.Optional<Resource> held = java.util.Optional.of(root);
        while (held.isPresent() && found.size() <= bound) {
            final Resource current = held.orElseThrow();
            found.add(current.getPath());
            pending.push(current.listChildren());
            held = java.util.Optional.empty();
            while (!pending.isEmpty() && held.isEmpty()) {
                final Iterator<Resource> children = pending.peek();
                if (children.hasNext()) {
                    held = java.util.Optional.ofNullable(children.next());
                } else {
                    pending.pop();
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Every node within the caller's reach that mentions one address.
     *
     * <p>Gathered before anything changes and bounded by the caller's own examination budget, so a
     * repository nobody could search is one this does not try to. What counts as a mention is a
     * stored value that is exactly the address, which is how a reference is written.</p>
     *
     * <p>What is inside the thing itself does not count. A page's own children mention it
     * constantly, and reporting those as references would make everything look referenced.</p>
     *
     * @param session the caller's own session
     * @param address what is being removed or moved
     * @param budget how many nodes this caller may examine
     * @return the nodes that mention it
     */
    public static List<Resource> pointingAt(ResourceResolver session, String address, long budget) {
        return references(session, address, budget).found();
    }

    /**
     * Discovers references and reports whether the bounded walk reached the end.
     *
     * @param session the caller's own resource resolver
     * @param address the address whose references are wanted
     * @param budget the maximum number of resources to examine
     * @return the references found and whether discovery completed
     */
    public static References references(ResourceResolver session, String address, long budget) {
        final Resource root = session.getResource(CONTENT_ROOT);
        if (root == null) {
            return new References(List.of(), Completeness.COMPLETE);
        }
        final List<Resource> found = new ArrayList<>();
        final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
        java.util.Optional<Resource> held = java.util.Optional.of(root);
        long examined = 0;
        while (held.isPresent() && examined < budget) {
            final Resource current = held.orElseThrow();
            examined = examined + 1;
            if (!current.getPath().equals(address) && !current.getPath().startsWith(address + "/")
                    && mentions(current, address)) {
                found.add(current);
            }
            pending.push(current.listChildren());
            held = java.util.Optional.empty();
            while (!pending.isEmpty() && held.isEmpty()) {
                final Iterator<Resource> children = pending.peek();
                if (children.hasNext()) {
                    held = java.util.Optional.of(children.next());
                } else {
                    pending.pop();
                }
            }
        }
        return new References(List.copyOf(found), pending.isEmpty() && held.isEmpty()
                ? Completeness.COMPLETE : Completeness.INCOMPLETE);
    }

    /**
     * Points every gathered reference at a new address.
     *
     * @param pointing the nodes that mention the old address
     * @param from the old address
     * @param to the new one
     * @return how many were repointed
     */
    public static long repointed(List<Resource> pointing, String from, String to) {
        long moved = 0;
        for (final Resource held : pointing) {
            final ModifiableValueMap values = held.adaptTo(ModifiableValueMap.class);
            if (values == null) {
                continue;
            }
            for (final var property : List.copyOf(values.entrySet())) {
                if (from.equals(property.getValue())) {
                    values.put(property.getKey(), to);
                    moved = moved + 1;
                } else if (property.getValue() instanceof final String[] several) {
                    final long replacements = java.util.Arrays.stream(several)
                            .filter(from::equals).count();
                    final String[] rewritten = java.util.Arrays.stream(several)
                            .map(value -> from.equals(value) ? to : value).toArray(String[]::new);
                    if (replacements > 0) {
                        values.put(property.getKey(), rewritten);
                        moved = moved + replacements;
                    }
                }
            }
        }
        return moved;
    }

    private static boolean mentions(Resource held, String address) {
        return held.getValueMap().values().stream()
                .anyMatch(value -> value instanceof final String text && text.equals(address)
                        || value instanceof final String[] several
                        && java.util.Arrays.asList(several).contains(address));
    }
}
