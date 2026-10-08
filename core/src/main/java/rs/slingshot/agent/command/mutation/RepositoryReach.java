// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
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
     * Reference discovery result, including whether the budget covered all possible external references.
     *
     * @param found resources that mention the address
     * @param completeness whether the bounded walk reached its end
     */
    public record References(List<Resource> found, Completeness completeness) {

        /** Reports whether the bounded traversal reached the end of external-reference discovery.
         * @return whether traversal covered every possible visible external reference
         */
        public boolean complete() {
            return completeness == Completeness.COMPLETE;
        }

        /** Holds the discovered resources independently of the traversal's mutable list. */
        public References {
            found = List.copyOf(found);
        }
    }

    /** Whether bounded reference discovery inspected every relevant visible reference property. */
    public enum Completeness {
        /** Every resource that could hold an external reference was examined. */
        COMPLETE,
        /** Discovery stopped before every possible external reference could be examined. */
        INCOMPLETE
    }

    /** Whether a caller needs every reference or just evidence that deletion must be refused. */
    private enum ReferenceDemand {
        /** A move must gather every visible reference before changing anything. */
        ALL,
        /** One external reference already decides a deletion refusal. */
        FIRST
    }

    /** Whether assignment preserves the legacy helper or checks a bounded move's native values. */
    private enum AssignmentAdmission {
        /** Preserve the legacy helper's metadata access and assignment behavior. */
        LEGACY_HELPER,
        /** Admit the actual complete native replacement before a bounded move assigns it. */
        BOUNDED_MOVE
    }

    /**
     * Native identities held independently of the provider's decoded-key cache.
     * @param readable properties whose actual stored values must be read for assignment
     * @param writable properties whose exact stored names and native types must be preserved
     */
    private record ReferenceMaps(java.util.Map<String, javax.jcr.Property> readable,
                                 java.util.Map<String, javax.jcr.Property> writable) {
    }

    /**
     * One resource and the traversal frontier that must receive its children.
     * @param resource the next caller-visible resource to examine
     * @param frontier the disjoint traversal that yielded this resource
     */
    private record ReferenceVisit(Resource resource, Deque<Iterator<Resource>> frontier) {
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
     * constantly, and reporting those as references would make everything look referenced.
     * Its subtree is pruned rather than spending the external-reference budget on those nodes.</p>
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
        return references(session, address, budget, rs.slingshot.agent.stream.ElapsedTime.start());
    }

    /**
     * Discovers every reference using an explicitly shared monotonic timer.
     * @param session the caller's own resolver
     * @param address the address whose references are wanted
     * @param budget the maximum number of resources to examine
     * @param elapsed the search's shared monotonic duration
     * @return discovered references and whether their search completed
     */
    static References references(ResourceResolver session, String address, long budget,
                                 rs.slingshot.agent.stream.ElapsedTime elapsed) {
        return references(session, address, budget, ReferenceDemand.ALL, elapsed);
    }

    private static References references(ResourceResolver session, String address, long budget,
                                         ReferenceDemand demand, rs.slingshot.agent.stream.ElapsedTime
                                                 elapsed) {
        final Resource root = session.getResource(CONTENT_ROOT);
        if (root == null) {
            return new References(List.of(), completedWithinTime(elapsed));
        }
        final List<Resource> found = new ArrayList<>();
        final String descendantPrefix = address + "/";
        final List<Resource> roots = budget > 0 ? referenceRoots(session, root, address, demand, elapsed)
                : List.of(root);
        final List<String> prioritized = roots.size() > 1 ? List.of(roots.getFirst().getPath())
                : List.of();
        final Deque<Deque<Iterator<Resource>>> frontiers = referenceFrontiers(roots);
        java.util.Optional<ReferenceVisit> held = nextVisit(frontiers, elapsed);
        long examined = 0;
        while (held.isPresent() && examined < budget
                && elapsed.milliseconds() <= SEARCH_MILLISECONDS) {
            final Resource current = held.orElseThrow().resource();
            examined = examined + 1;
            final java.util.Optional<Boolean> mentions = mentions(current, address, descendantPrefix, demand,
                    elapsed);
            if (mentions.isEmpty() || elapsed.milliseconds() > SEARCH_MILLISECONDS) {
                return new References(found, Completeness.INCOMPLETE);
            }
            if (mentions.orElseThrow()) {
                found.add(current);
            }
            if (demand == ReferenceDemand.FIRST && !found.isEmpty()) {
                return new References(found, Completeness.INCOMPLETE);
            }
            final Iterator<Resource> children =
                    childrenOutsideTarget(current, address, descendantPrefix, prioritized, elapsed);
            if (demand == ReferenceDemand.FIRST) {
                held.orElseThrow().frontier().addLast(children);
            } else {
                held.orElseThrow().frontier().push(children);
            }
            held = nextVisit(frontiers, elapsed);
        }
        return new References(List.copyOf(found), frontiers.isEmpty() && held.isEmpty()
                ? completedWithinTime(elapsed) : Completeness.INCOMPLETE);
    }

    private static Completeness completedWithinTime(rs.slingshot.agent.stream.ElapsedTime elapsed) {
        return elapsed.milliseconds() <= SEARCH_MILLISECONDS ? Completeness.COMPLETE :
                Completeness.INCOMPLETE;
    }

    private static List<Resource> referenceRoots(ResourceResolver session, Resource root,
                                                  String address, ReferenceDemand demand,
                                                          rs.slingshot.agent.stream.ElapsedTime elapsed) {
        final int separator = address.lastIndexOf('/');
        if (demand != ReferenceDemand.FIRST || separator <= CONTENT_ROOT.length()
                || elapsed.milliseconds() > SEARCH_MILLISECONDS) {
            return List.of(root);
        }
        final String parentAddress = address.substring(0, separator);
        if (!parentAddress.startsWith(CONTENT_ROOT + "/")) {
            return List.of(root);
        }
        final Resource parent = session.getResource(parentAddress);
        if (parent == null || elapsed.milliseconds() > SEARCH_MILLISECONDS
                || !parentAddress.equals(parent.getPath())
                || elapsed.milliseconds() > SEARCH_MILLISECONDS) {
            return List.of(root);
        }
        return List.of(parent, root);
    }

    private static Deque<Deque<Iterator<Resource>>> referenceFrontiers(List<Resource> roots) {
        final Deque<Deque<Iterator<Resource>>> frontiers = new ArrayDeque<>();
        roots.forEach(root -> {
            final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
            pending.push(List.of(root).iterator());
            frontiers.addLast(pending);
        });
        return frontiers;
    }

    private static java.util.Optional<ReferenceVisit> nextVisit(
            Deque<Deque<Iterator<Resource>>> frontiers, rs.slingshot.agent.stream.ElapsedTime elapsed) {
        while (!frontiers.isEmpty() && elapsed.milliseconds() <= SEARCH_MILLISECONDS) {
            final Deque<Iterator<Resource>> frontier = frontiers.removeFirst();
            final java.util.Optional<Resource> resource = nextReference(frontier, elapsed);
            if (resource.isPresent()) {
                frontiers.addLast(frontier);
                return java.util.Optional.of(new ReferenceVisit(resource.orElseThrow(), frontier));
            }
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<Resource> nextReference(Deque<Iterator<Resource>> pending,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        while (!pending.isEmpty() && elapsed.milliseconds() <= SEARCH_MILLISECONDS) {
            final Iterator<Resource> children = pending.peek();
            final boolean available = children.hasNext();
            if (elapsed.milliseconds() > SEARCH_MILLISECONDS) {
                return java.util.Optional.empty();
            }
            if (available) {
                return java.util.Optional.of(children.next());
            }
            pending.pop();
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<Boolean> mentions(Resource resource, String address,
                                                       String descendantPrefix, ReferenceDemand demand,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (withinTarget(resource, address, descendantPrefix)) {
            return java.util.Optional.of(false);
        }
        return demand == ReferenceDemand.FIRST ? ReferenceProperties.firstMention(resource, address, elapsed)
                : ReferenceProperties.mentions(resource, address, elapsed);
    }

    private static Iterator<Resource> childrenOutsideTarget(Resource resource, String address,
                                                             String descendantPrefix,
                                                             List<String> prioritized,
                                                                     rs.slingshot.agent.stream.ElapsedTime
                                                                     elapsed) {
        if (elapsed.milliseconds() > SEARCH_MILLISECONDS
                || withinTarget(resource, address, descendantPrefix)
                || elapsed.milliseconds() > SEARCH_MILLISECONDS) {
            return java.util.Collections.emptyIterator();
        }
        if (prioritized.isEmpty() || !prioritized.getFirst().startsWith(resource.getPath() + "/")) {
            return elapsed.milliseconds() <= SEARCH_MILLISECONDS ? resource.listChildren()
                    : java.util.Collections.emptyIterator();
        }
        if (elapsed.milliseconds() > SEARCH_MILLISECONDS) {
            return java.util.Collections.emptyIterator();
        }
        final Iterator<Resource> children = resource.listChildren();
        return java.util.stream.Stream.iterate(children,
                        held -> elapsed.milliseconds() <= SEARCH_MILLISECONDS && held.hasNext()
                                && elapsed.milliseconds() <= SEARCH_MILLISECONDS, held -> held)
                .map(Iterator::next).takeWhile(child -> elapsed.milliseconds() <= SEARCH_MILLISECONDS)
                .filter(child -> !prioritized.contains(child.getPath())).iterator();
    }

    private static boolean withinTarget(Resource resource, String address, String descendantPrefix) {
        final String path = resource.getPath();
        return path.equals(address) || path.startsWith(descendantPrefix);
    }

    /**
     * Whether anything may still point at an address, where a search that could not finish counts.
     *
     * <p>A search that ran out of nodes or time before it had looked everywhere cannot say that
     * nothing does, and a caller who asked to be refused when something might is refused.</p>
     *
     * <p>One confirmed external reference already requires refusal. Stop there rather than
     * examining unrelated content to collect references this boolean answer cannot use.
     * Native property inspection also stops at that decisive reference.</p>
     *
     * <p>Deletion-only discovery alternates the caller-visible parent subtree with the global
     * content walk when the parent's canonical address is below the content root. The global
     * walk excludes that subtree, so each resource is examined by one frontier. Alternation
     * keeps early references in either frontier reachable without finishing the other first.
     * Each frontier visits siblings before their descendants, so a shallow decisive reference
     * does not wait for an unrelated deep branch. Children remain lazy iterators, and the number
     * of queued iterators is bounded by the shared examination budget. Both frontiers share one
     * resource and time budget; complete absence still requires every relevant caller-visible
     * branch to be examined. Move discovery retains its complete depth-first ordering.</p>
     *
     * @param session the caller's own resolver
     * @param address the address
     * @param budget how many nodes the search may examine
     * @return whether it may
     */
    public static boolean possiblyReferenced(ResourceResolver session, String address,
                                             long budget) {
        return possiblyReferenced(session, address, budget,
                rs.slingshot.agent.stream.ElapsedTime.start());
    }

    /**
     * Observes decisive reference evidence under one caller-supplied search clock.
     * @param session caller-visible resolver
     * @param address exact target address
     * @param budget original resource examination bound
     * @param elapsed shared monotonic search duration
     * @return whether refusal remains necessary
     */
    static boolean possiblyReferenced(ResourceResolver session, String address, long budget,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        final References found = references(session, address, budget, ReferenceDemand.FIRST, elapsed);
        return !found.complete() || !found.found().isEmpty();
    }

    /**
     * How long one search for references may take.
     *
     * <p>Well inside a command's own execution budget: the search walks content rather than asking
     * an index, and a walk of a whole site that outlived the request would be an answer nobody
     * receives. A search stopped by it is incomplete, and says so.</p>
     *
     * <p>The same inclusive monotonic timer is shared by root resolution, traversal, native
     * metadata, and scalar or multiple text reads. A provider call that returns after the
     * deadline makes discovery incomplete; later provider calls are not started. An already
     * blocked repository or provider call cannot be interrupted by these checks, so this is
     * not a hard wall-clock limit on a request. Complete move discovery still needs every
     * caller-visible external branch to fit the unchanged resource examination budget.</p>
     */
    static final long SEARCH_MILLISECONDS = 15_000;

    /**
     * Moves one resource to a new address, renaming it where the address's last name differs.
     *
     * <p>Through the repository's own session where the resolver has one, because that is the
     * move that can rename in the same step; a resolver move keeps the old name, so without a
     * session a rename is refused rather than performed as a move to the wrong address.</p>
     *
     * @param session the caller's own resolver
     * @param source where it is
     * @param destination where it goes, whole
     * @throws PersistenceException where the repository refuses it
     */
    public static void moveTo(ResourceResolver session, String source, String destination)
            throws PersistenceException {
        final javax.jcr.Session repository = session.adaptTo(javax.jcr.Session.class);
        if (repository != null) {
            try {
                repository.move(source, destination);
                return;
            } catch (final javax.jcr.RepositoryException refused) {
                throw new PersistenceException(refused.getMessage(), refused);
            }
        }
        final int sourceSlash = source.lastIndexOf('/');
        final int destinationSlash = destination.lastIndexOf('/');
        if (!source.substring(sourceSlash + 1).equals(destination.substring(destinationSlash + 1))) {
            throw new PersistenceException("this repository offers no session to rename through,"
                    + " so " + source + " cannot become " + destination + " in one step");
        }
        session.move(source, destinationSlash <= 0 ? "/" : destination.substring(0,
                destinationSlash));
    }

    /**
     * Requires every gathered reference to expose writable values and complete text metadata.
     *
     * <p>Checked before movement or assignment, because skipping one readable reference while
     * reporting success leaves a link pointing at an address the move removed.</p>
     *
     * @param pointing every caller-visible resource whose references must be adjusted
     * @throws PersistenceException where a reference has no writable values or readable metadata
     */
    public static void requireAdjustable(List<Resource> pointing) throws PersistenceException {
        for (final Resource held : pointing) {
            writableValues(held);
            if (!ReferenceProperties.select(held).complete()) {
                throw new PersistenceException("reference property metadata could not be inspected");
            }
        }
    }

    /**
     * Requires provider writability and native admission for the properties a move will rewrite.
     *
     * <p>A writable node map does not prove permission to change every property, checkout state,
     * or ownership of a lock. Inspect matching values before movement so these known native
     * constraints leave the caller's source and earlier references untouched. Properties with
     * the repository's IGNORE versioning action remain writable after check-in.</p>
     *
     * @param pointing the gathered resources whose references must be adjusted
     * @param from the exact old address whose matching values will be rewritten
     * @throws PersistenceException if a reference is protected, denied, checked in, locked by
     *     another session, or cannot be inspected
     */
    public static void requireAdjustable(List<Resource> pointing, String from) throws PersistenceException {
        requireAdjustable(pointing);
        for (final Resource held : pointing) {
            final javax.jcr.Node nativeNode = held.adaptTo(javax.jcr.Node.class);
            if (nativeNode != null) {
                requireNativeAdjustment(held, nativeNode, from);
            }
        }
    }

    /**
     * Requires native value constraints to admit the complete proposed reference replacement.
     *
     * <p>The declaring type belongs to the actual property definition, including definitions from
     * mixins. Multiple values are checked together with their unchanged members before movement,
     * because a repository may defer constraint validation until commit.</p>
     *
     * @param pointing the gathered resources whose references must be adjusted
     * @param from the exact old address
     * @param to the proposed new address
     * @throws PersistenceException if native admission or replacement constraints refuse the change
     */
    public static void requireAdjustable(List<Resource> pointing, String from, String to)
            throws PersistenceException {
        requireAdjustable(pointing, from);
        for (final Resource held : pointing) {
            final javax.jcr.Node nativeNode = held.adaptTo(javax.jcr.Node.class);
            if (nativeNode != null) {
                requireNativeReplacement(held, nativeNode, from, to);
            }
        }
    }

    private static void requireNativeReplacement(Resource resource, javax.jcr.Node nativeNode,
                                                String from, String to) throws PersistenceException {
        final ReferenceProperties.Selection selection = ReferenceProperties.select(resource);
        if (!selection.complete()) {
            throw new PersistenceException("reference property metadata could not be inspected");
        }
        final org.apache.sling.api.resource.ValueMap values = resource.getValueMap();
        try {
            for (final String name : selection.names()) {
                final java.util.Optional<Object> stored = ReferenceValues.stored(nativeNode, values, name);
                if (stored.isEmpty() || matchingReferences(stored.orElseThrow(), from, 0) == 0) {
                    continue;
                }
                if (!acceptsReplacement(nativeNode.getProperty(name), stored.orElseThrow(), from, to)) {
                    throw new PersistenceException("a discovered reference rejects the replacement value");
                }
            }
        } catch (final javax.jcr.RepositoryException unreadable) {
            throw new PersistenceException("native reference value constraints could not be inspected",
                    unreadable);
        }
    }

    private static boolean acceptsReplacement(javax.jcr.Property property, Object stored,
                                              String from, String to) throws javax.jcr.RepositoryException {
        final javax.jcr.nodetype.PropertyDefinition definition = property.getDefinition();
        if (definition.getValueConstraints().length == 0) {
            return true;
        }
        if (stored instanceof final String[] several) {
            final String[] rewritten = java.util.Arrays.stream(several)
                    .map(value -> from.equals(value) ? to : value)
                    .toArray(String[]::new);
            return acceptsAssigned(property, rewritten);
        }
        return acceptsAssigned(property, to);
    }

    private static boolean acceptsAssigned(javax.jcr.Property property, Object proposed)
            throws javax.jcr.RepositoryException {
        final javax.jcr.nodetype.PropertyDefinition definition = property.getDefinition();
        if (definition.getValueConstraints().length == 0) {
            return true;
        }
        final javax.jcr.nodetype.NodeType declaring = definition.getDeclaringNodeType();
        if (proposed instanceof final String[] several) {
            return declaring.canSetProperty(property.getName(), nativeValues(property, several));
        }
        return declaring.canSetProperty(property.getName(), property.getSession().getValueFactory()
                .createValue(String.class.cast(proposed), property.getType()));
    }

    private static javax.jcr.Value[] nativeValues(javax.jcr.Property property, String... strings)
            throws javax.jcr.RepositoryException {
        final javax.jcr.ValueFactory factory = property.getSession().getValueFactory();
        final int kind = property.getType();
        final List<javax.jcr.Value> converted = new ArrayList<>();
        for (final String text : strings) {
            converted.add(factory.createValue(text, kind));
        }
        return converted.toArray(javax.jcr.Value[]::new);
    }

    private static void requireNativeAdjustment(Resource resource, javax.jcr.Node nativeNode, String from)
            throws PersistenceException {
        final ReferenceProperties.Selection selection = ReferenceProperties.select(resource);
        if (!selection.complete()) {
            throw new PersistenceException("reference property metadata could not be inspected");
        }
        final org.apache.sling.api.resource.ValueMap values = resource.getValueMap();
        try {
            for (final String name : selection.names()) {
                if (ReferenceValues.stored(nativeNode, values, name)
                        .map(stored -> matchingReferences(stored, from, 0)).orElse(0L) == 0) {
                    continue;
                }
                requireNativeProperty(nativeNode, nativeNode.getProperty(name));
            }
        } catch (final javax.jcr.RepositoryException unreadable) {
            throw new PersistenceException("native reference metadata or permissions could not be inspected",
                    unreadable);
        }
    }

    private static void requireNativeProperty(javax.jcr.Node nativeNode, javax.jcr.Property property)
            throws javax.jcr.RepositoryException, PersistenceException {
        if (property.getDefinition().isProtected()) {
            throw new PersistenceException("a discovered reference property is protected");
        }
        if (!nativeNode.isCheckedOut() && property.getDefinition().getOnParentVersion()
                != javax.jcr.version.OnParentVersionAction.IGNORE) {
            throw new PersistenceException("a discovered reference belongs to checked-in content");
        }
        if (nativeNode.isLocked() && !nativeNode.getSession().getWorkspace().getLockManager()
                .getLock(nativeNode.getPath()).isLockOwningSession()) {
            throw new PersistenceException("a discovered reference is locked by another session");
        }
        if (!nativeNode.getSession().hasPermission(property.getPath(),
                javax.jcr.Session.ACTION_SET_PROPERTY)) {
            throw new PersistenceException("a discovered reference property cannot be changed"
                    + " by this caller");
        }
    }

    private static ModifiableValueMap writableValues(Resource resource) throws PersistenceException {
        final ModifiableValueMap values = resource.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("a discovered reference has no writable values for this caller");
        }
        return values;
    }

    /**
     * Checks the number of matching scalar and multiple values before any reference is rewritten.
     *
     * <p>A resource can hold many references, so counting resources cannot enforce the bound on
     * the number the move reports. Counting stops at the first matching value beyond that bound.</p>
     *
     * @param pointing the gathered resources that mention the source
     * @param from the exact old address
     * @param bound the maximum number of values one move may adjust
     * @return whether all matching values fit within the bound
     * @throws PersistenceException if reference property metadata cannot be read completely
     */
    public static boolean adjustmentsWithin(List<Resource> pointing, String from, long bound)
            throws PersistenceException {
        long counted = 0;
        for (final Resource held : pointing) {
            final ReferenceProperties.Selection selection = ReferenceProperties.select(held);
            if (!selection.complete()) {
                throw new PersistenceException("reference property metadata could not be inspected");
            }
            final org.apache.sling.api.resource.ValueMap values = held.getValueMap();
            for (final String name : selection.names()) {
                final long remaining = bound - counted;
                final long matching = ReferenceValues.stored(held, values, name)
                        .map(stored -> matchingReferences(stored, from, remaining)).orElse(0L);
                if (matching > remaining) {
                    return false;
                }
                counted = counted + matching;
            }
        }
        return true;
    }

    private static long matchingReferences(Object stored, String from, long remaining) {
        if (from.equals(stored)) {
            return 1;
        }
        if (!(stored instanceof final String[] several)) {
            return 0;
        }
        final long limit = remaining == Long.MAX_VALUE ? remaining : remaining + 1;
        return java.util.Arrays.stream(several).filter(from::equals).limit(limit).count();
    }

    /**
     * Points every gathered reference at a new address.
     *
     * @param pointing the nodes that mention the old address
     * @param from the old address
     * @param to the new one
     * @return how many were repointed
     * @throws PersistenceException if a reference cannot be modified or its metadata cannot be read
     */
    public static long repointed(List<Resource> pointing, String from, String to)
            throws PersistenceException {
        return repointed(pointing, from, to, Long.MAX_VALUE, AssignmentAdmission.LEGACY_HELPER);
    }

    /** A refreshed reference set exceeds the move's original adjustment budget before commit. */
    public static final class ReferenceAdjustmentBudgetExceeded extends PersistenceException {
        private static final long serialVersionUID = 1L;

        private ReferenceAdjustmentBudgetExceeded() {
            super("matching reference values changed beyond the move's adjustment budget");
        }
    }

    /**
     * Points gathered references at a new address while bounding the values actually written.
     *
     * <p>A native resolver may expose refreshed values after earlier admission. Apply the shared
     * bound to each value read for assignment, stopping before an overflowing property is written,
     * so a move cannot commit more adjustments than its contract permits.</p>
     *
     * <p>Native value constraints admit the complete proposed value again at assignment, because
     * a refreshed text property may not have existed during earlier admission.</p>
     *
     * @param pointing the nodes that mention the old address
     * @param from the old address
     * @param to the new one
     * @param bound the maximum number of values one move may adjust
     * @return how many were repointed
     * @throws PersistenceException if the values exceed the bound or cannot be modified or inspected
     */
    public static long repointed(List<Resource> pointing, String from, String to, long bound)
            throws PersistenceException {
        return repointed(pointing, from, to, bound, AssignmentAdmission.BOUNDED_MOVE);
    }

    private static long repointed(List<Resource> pointing, String from, String to, long bound,
                                  AssignmentAdmission admission) throws PersistenceException {
        if (bound < 0) {
            throw new ReferenceAdjustmentBudgetExceeded();
        }
        requireAdjustable(pointing);
        long moved = 0;
        for (final Resource held : pointing) {
            moved = moved + repointProperties(held, writableValues(held), from, to, bound - moved,
                    admission);
        }
        return moved;
    }

    private static long repointProperties(Resource resource, ModifiableValueMap values,
                                          String from, String to, long bound,
                                          AssignmentAdmission admission) throws PersistenceException {
        final ReferenceProperties.Selection selection = ReferenceProperties.select(resource);
        if (!selection.complete()) {
            throw new PersistenceException("reference property metadata could not be inspected");
        }
        final java.util.Map<String, javax.jcr.Property> nativeProperties = nativeProperties(resource);
        final boolean nativeReads = admission == AssignmentAdmission.BOUNDED_MOVE
                || selection.names().stream().anyMatch(ReferenceValues::nativeName);
        final ReferenceMaps maps = new ReferenceMaps(nativeReads ? nativeProperties : java.util.Map.of(),
                nativeProperties);
        long moved = 0;
        for (final String name : selection.names()) {
            moved = moved + repointProperty(maps, values, name, from, to, bound - moved,
                    admission);
        }
        return moved;
    }

    private static long repointProperty(ReferenceMaps nativeProperties,
                                        ModifiableValueMap values,
                                        String propertyName,
                                        String from, String to, long remaining,
                                        AssignmentAdmission admission) throws PersistenceException {
        final java.util.Optional<Object> held = ReferenceValues.stored(nativeProperties.readable(),
                values, propertyName);
        if (held.isEmpty()) {
            return 0;
        }
        final Object stored = held.orElseThrow();
        final long replacements = matchingReferences(stored, from, remaining);
        if (replacements > remaining) {
            throw new ReferenceAdjustmentBudgetExceeded();
        }
        if (from.equals(stored)) {
            putReference(nativeProperties.writable(), values, propertyName, to, admission);
            return 1;
        }
        if (!(stored instanceof final String[] several)) {
            return 0;
        }
        if (replacements == 0) {
            return 0;
        }
        final String[] rewritten = java.util.Arrays.stream(several)
                .map(value -> from.equals(value) ? to : value).toArray(String[]::new);
        putReference(nativeProperties.writable(), values, propertyName, rewritten, admission);
        return replacements;
    }

    private static void putReference(java.util.Map<String, javax.jcr.Property> nativeProperties,
                                     ModifiableValueMap values, String name,
                                     Object rewritten, AssignmentAdmission admission)
            throws PersistenceException {
        try {
            final javax.jcr.Property property = nativeProperties.get(name);
            if (admission == AssignmentAdmission.BOUNDED_MOVE && property != null
                    && !acceptsAssigned(property, rewritten)) {
                throw new PersistenceException("a discovered reference rejects the replacement value");
            }
            if (property == null || property.getType() == javax.jcr.PropertyType.STRING
                    && !ReferenceValues.nativeName(name)) {
                values.put(name, rewritten);
                return;
            }
            if (rewritten instanceof final String[] several) {
                property.setValue(nativeValues(property, several));
                return;
            }
            property.setValue(property.getSession().getValueFactory()
                    .createValue(String.class.cast(rewritten), property.getType()));
        } catch (final javax.jcr.RepositoryException refused) {
            throw new PersistenceException("a native reference replacement could not be written", refused);
        }
    }

    private static java.util.Map<String, javax.jcr.Property> nativeProperties(Resource resource)
            throws PersistenceException {
        final javax.jcr.Node nativeNode = resource.adaptTo(javax.jcr.Node.class);
        if (nativeNode == null) {
            return java.util.Map.of();
        }
        try {
            final java.util.Map<String, javax.jcr.Property> found = new java.util.HashMap<>();
            final javax.jcr.PropertyIterator properties = nativeNode.getProperties();
            while (properties.hasNext()) {
                final javax.jcr.Property property = properties.nextProperty();
                found.put(property.getName(), property);
            }
            return java.util.Map.copyOf(found);
        } catch (final javax.jcr.RepositoryException unreadable) {
            throw new PersistenceException("native reference property metadata could not be inspected",
                    unreadable);
        }
    }

}
