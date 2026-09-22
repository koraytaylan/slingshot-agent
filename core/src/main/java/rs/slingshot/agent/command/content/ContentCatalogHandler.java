// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.command.fragment.FragmentHandlers;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Component definitions, component instances, content fragments, or experience fragments under one
 * anchor.
 *
 * <p>Each is a walk of the whole subtree the caller named. What distinguishes a match is a fact
 * about the node — its type, a flag, or a resource type — applied to every node the walk reaches.
 * The walk finishes that subtree. A cap that stopped it would answer a question about part of the
 * tree as though it were a question about the tree, which is the thing a caller cannot already
 * learn from the authoring interface.</p>
 */
public final class ContentCatalogHandler implements CommandHandler {

    /** Which catalogue this handler lists. */
    public enum Kind {
        /** Component definitions, each with the resource type its path resolves as. */
        COMPONENT_DEFINITIONS,
        /** Component instances, each with the resource type it declares. */
        COMPONENTS,
        /** Content fragments, and not the assets beside them. */
        CONTENT_FRAGMENTS,
        /** Experience fragments, and not the variations each one holds. */
        EXPERIENCE_FRAGMENTS
    }

    /** The primary type a component definition has. */
    static final String COMPONENT_DEFINITION_TYPE = "cq:Component";

    /** The property a component instance declares its type in. */
    static final String RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    /** The category a search that ran out of its examination budget is refused under. */
    static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;
    private final Kind kind;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     * @param kind which catalogue it lists
     */
    public ContentCatalogHandler(AgentContract contract, Kind kind) {
        this.contract = contract;
        this.kind = kind;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final Asked asked = asked(arguments);
        if (asked instanceof final Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final Held held = (Held) asked;
        final Optional<Resource> root = anchor(resolver, held.rootPath(), kind);
        if (root.isEmpty()) {
            return new Failed(ListChildPagesHandler.ROOT_NOT_FOUND,
                    ListChildPagesHandler.whyNothingIsListed(held.rootPath()));
        }
        final Optional<Gathered> walked = gather(root.get(), context);
        if (walked.isEmpty()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this listing examined more than the "
                    + context.discovery().limit() + " nodes or ran longer than the "
                    + context.time().limit() + " milliseconds it is allowed before it had visited"
                    + " every node under " + root.get().getPath() + ", and stopped rather than"
                    + " answer with part of them; name a narrower root instead");
        }
        final Gathered gathered = walked.get();
        return switch (kind) {
            case COMPONENT_DEFINITIONS, COMPONENTS -> pagedComponents(gathered.components(), held,
                    arguments, context);
            case CONTENT_FRAGMENTS, EXPERIENCE_FRAGMENTS -> pagedPages(gathered.pages(), held,
                    arguments, context);
        };
    }

    private Answer pagedComponents(List<ComponentListingResult.Component> found, Held held,
                                   DocumentValue.Mapping arguments, CallerContext context) {
        final PagingSupport.Outcome<ComponentListingResult.Component> page = PagingSupport.page(
                found, held.window(), wireName(), arguments, context, contract);
        if (page instanceof final PagingSupport.Refused<ComponentListingResult.Component> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<ComponentListingResult.Component> accepted =
                ((PagingSupport.Accepted<ComponentListingResult.Component>) page).page();
        return new Produced(ComponentListingResult.documentOf(accepted.rows(),
                accepted.continuationToken()));
    }

    private Answer pagedPages(List<PageListingResult.Page> found, Held held,
                              DocumentValue.Mapping arguments, CallerContext context) {
        final PagingSupport.Outcome<PageListingResult.Page> page = PagingSupport.page(
                found, held.window(), wireName(), arguments, context, contract);
        if (page instanceof final PagingSupport.Refused<PageListingResult.Page> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<PageListingResult.Page> accepted =
                ((PagingSupport.Accepted<PageListingResult.Page>) page).page();
        return new Produced(PageListingResult.documentOf(accepted.rows(),
                accepted.continuationToken()));
    }

    private String wireName() {
        return switch (kind) {
            case COMPONENT_DEFINITIONS -> ListComponentDefinitionsCommand.WIRE_NAME;
            case COMPONENTS -> ComponentInstancesCommand.WIRE_NAME;
            case CONTENT_FRAGMENTS -> ListContentFragmentsCommand.WIRE_NAME;
            case EXPERIENCE_FRAGMENTS -> ListExperienceFragmentsCommand.WIRE_NAME;
        };
    }

    private Asked asked(DocumentValue.Mapping arguments) {
        return switch (kind) {
            case COMPONENT_DEFINITIONS -> askedDefinitions(arguments);
            case COMPONENTS -> askedComponents(arguments);
            case CONTENT_FRAGMENTS -> askedFragments(arguments);
            case EXPERIENCE_FRAGMENTS -> askedExperiences(arguments);
        };
    }

    private Asked askedDefinitions(DocumentValue.Mapping arguments) {
        final ListComponentDefinitionsCommand.Outcome outcome =
                ListComponentDefinitionsCommand.of(arguments, contract);
        if (outcome instanceof final ListComponentDefinitionsCommand.Refused refused) {
            return new Refused(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final ListComponentDefinitionsCommand command =
                ((ListComponentDefinitionsCommand.Held) outcome).command();
        return new Held(command.rootPath(), command.window());
    }

    private Asked askedComponents(DocumentValue.Mapping arguments) {
        final ComponentInstancesCommand.Outcome outcome =
                ComponentInstancesCommand.of(arguments, contract);
        if (outcome instanceof final ComponentInstancesCommand.Refused refused) {
            return new Refused(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final ComponentInstancesCommand command = ((ComponentInstancesCommand.Held) outcome).command();
        return new Held(command.rootPath(), command.window());
    }

    private Asked askedFragments(DocumentValue.Mapping arguments) {
        final ListContentFragmentsCommand.Outcome outcome =
                ListContentFragmentsCommand.of(arguments, contract);
        if (outcome instanceof final ListContentFragmentsCommand.Refused refused) {
            return new Refused(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final ListContentFragmentsCommand command =
                ((ListContentFragmentsCommand.Held) outcome).command();
        return new Held(command.rootPath(), command.window());
    }

    private Asked askedExperiences(DocumentValue.Mapping arguments) {
        final ListExperienceFragmentsCommand.Outcome outcome =
                ListExperienceFragmentsCommand.of(arguments, contract);
        if (outcome instanceof final ListExperienceFragmentsCommand.Refused refused) {
            return new Refused(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final ListExperienceFragmentsCommand command =
                ((ListExperienceFragmentsCommand.Held) outcome).command();
        return new Held(command.rootPath(), command.window());
    }

    /**
     * Every match under one root, or nothing where the walk ran out of its budget first.
     *
     * <p>A partial catalogue is not answered: a list that stopped partway reads as the whole of
     * what is there. The node count and the elapsed time are both checked, because an author that
     * answers slowly exhausts the request it is answered on long before it exhausts the count.</p>
     */
    private Optional<Gathered> gather(Resource root, CallerContext context) {
        final List<ComponentListingResult.Component> components = new ArrayList<>();
        final List<PageListingResult.Page> pages = new ArrayList<>();
        final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
        final long started = System.currentTimeMillis();
        long examined = 0;
        Optional<Resource> current = Optional.of(root);
        while (current.isPresent()) {
            examined++;
            if (context.exceeded(examined, System.currentTimeMillis() - started, 0).isPresent()) {
                return Optional.empty();
            }
            consider(current.get(), root.getPath(), components, pages);
            if (descends(current.get())) {
                pending.push(current.get().listChildren());
            }
            current = nextChild(pending);
        }
        return Optional.of(new Gathered(ComponentListingResult.ascending(components),
                PageListingResult.ascending(pages)));
    }

    private void consider(Resource resource, String anchor,
                          List<ComponentListingResult.Component> components,
                          List<PageListingResult.Page> pages) {
        if (anchor.equals(resource.getPath())) {
            return;
        }
        if (kind == Kind.COMPONENT_DEFINITIONS) {
            definitionType(resource).ifPresent(type -> components.add(
                    new ComponentListingResult.Component(resource.getPath(), type,
                            titleOf(resource))));
            return;
        }
        if (kind == Kind.COMPONENTS) {
            instanceType(resource).ifPresent(type -> components.add(
                    new ComponentListingResult.Component(resource.getPath(), type,
                            titleOf(resource))));
            return;
        }
        if (kind == Kind.CONTENT_FRAGMENTS && isContentFragment(resource)) {
            pages.add(new PageListingResult.Page(resource.getPath(), titleOf(resource)));
            return;
        }
        if (kind == Kind.EXPERIENCE_FRAGMENTS && isExperienceFragment(resource)) {
            pages.add(new PageListingResult.Page(resource.getPath(), titleOf(resource)));
        }
    }

    /**
     * The node a catalogue walks, which is the path the caller named when that node is there.
     *
     * <p>Every component available on an author is a definition under {@code /apps}. An
     * environment name is that author, so {@code /apps/acme-rde} names no folder and
     * the walk starts at the nearest {@code /apps} or {@code /libs} ancestor. A project folder
     * the caller named exactly, such as {@code /apps/acme}, stays that folder. Other
     * catalogues still accept a missing segment that is one existing child's name extended by
     * a hyphen.</p>
     *
     * @param resolver the caller's read-only resolver
     * @param path the path the caller named
     * @param kind which catalogue is being listed
     * @return the node to walk, or nothing where no ancestor of it is readable
     */
    private static Optional<Resource> anchor(ResourceResolver resolver, String path, Kind kind) {
        final Optional<Resource> exact = Optional.ofNullable(resolver.getResource(path));
        if (exact.isPresent()) {
            return exact;
        }
        final Optional<Resource> deepest = deepestAncestor(resolver, path);
        if (deepest.isEmpty()) {
            return deepest;
        }
        if (kind == Kind.COMPONENT_DEFINITIONS && underAppsOrLibs(deepest.get().getPath())) {
            return deepest;
        }
        return descend(deepest.get(), path.substring(deepest.get().getPath().length()));
    }

    private static Optional<Resource> deepestAncestor(ResourceResolver resolver, String path) {
        String cursor = path;
        while (cursor.length() > 1) {
            final int slash = cursor.lastIndexOf('/');
            cursor = slash <= 0 ? "/" : cursor.substring(0, slash);
            final Optional<Resource> found = Optional.ofNullable(resolver.getResource(cursor));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Resource> descend(Resource parent, String rest) {
        if (rest.isEmpty() || "/".equals(rest)) {
            return Optional.of(parent);
        }
        final String body = rest.startsWith("/") ? rest.substring(1) : rest;
        final int slash = body.indexOf('/');
        final String segment = slash < 0 ? body : body.substring(0, slash);
        final String after = slash < 0 ? "" : body.substring(slash);
        return Optional.ofNullable(parent.getChild(segment))
                .or(() -> uniqueExtension(parent, segment))
                .flatMap(child -> descend(child, after));
    }

    /**
     * The one child whose name the missing segment extends, or that extends the missing segment.
     *
     * <p>The boundary is a hyphen, so {@code acme-rde} is {@code acme} and {@code
     * brand} is not. Two children that both qualify is no answer: guessing between them would list
     * the wrong project.</p>
     */
    private static Optional<Resource> uniqueExtension(Resource parent, String wanted) {
        final List<Resource> matches = new ArrayList<>();
        for (final Resource child : parent.getChildren()) {
            final String name = child.getName();
            if (extendsName(wanted, name) || extendsName(name, wanted)) {
                matches.add(child);
            }
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static boolean extendsName(String longer, String shorter) {
        return longer.length() > shorter.length() && longer.startsWith(shorter)
                && longer.charAt(shorter.length()) == '-';
    }

    private static boolean underAppsOrLibs(String path) {
        return "/apps".equals(path) || path.startsWith("/apps/") || "/libs".equals(path)
                || path.startsWith("/libs/");
    }

    /**
     * Whether the walk opens this node's children.
     *
     * <p>A content fragment is the asset itself. Its children are renditions and metadata, and a
     * folder's {@code jcr:content} is the folder, not another fragment. Opening either turns a
     * catalogue of assets into a walk of every binary under the dam, which outlives the request
     * the catalogue is answered on.</p>
     */
    private boolean descends(Resource current) {
        return kind != Kind.CONTENT_FRAGMENTS
                || !"jcr:content".equals(current.getName())
                && !FragmentHandlers.CONTENT_FRAGMENT_TYPE.equals(ChildListingHandler.typeOf(current));
    }

    private static Optional<Resource> nextChild(Deque<Iterator<Resource>> pending) {
        while (!pending.isEmpty()) {
            final Iterator<Resource> children = pending.peek();
            if (children.hasNext()) {
                return Optional.of(children.next());
            }
            pending.pop();
        }
        return Optional.empty();
    }

    private Optional<String> definitionType(Resource resource) {
        if (!COMPONENT_DEFINITION_TYPE.equals(ChildListingHandler.typeOf(resource))) {
            return Optional.empty();
        }
        return acceptable(resourceTypeOf(resource.getPath()));
    }

    /**
     * The resource type one definition path resolves as.
     *
     * <p>A definition under {@code /apps} or {@code /libs} resolves as the remainder of its path.
     * Anywhere else, the absolute path is the type.</p>
     *
     * @param path the definition's repository path
     * @return the resource type, or empty where the path is the search root itself
     */
    static String resourceTypeOf(String path) {
        if (path.startsWith("/apps/")) {
            return path.substring("/apps/".length());
        }
        if (path.startsWith("/libs/")) {
            return path.substring("/libs/".length());
        }
        return path;
    }

    private Optional<String> instanceType(Resource resource) {
        final Object held = resource.getValueMap().get(RESOURCE_TYPE_PROPERTY);
        return held instanceof final String type ? acceptable(type) : Optional.empty();
    }

    private Optional<String> acceptable(String type) {
        if (type.isEmpty()) {
            return Optional.empty();
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_COMPONENT_RESOURCE_TYPE_BYTES);
        if (type.getBytes(StandardCharsets.UTF_8).length > bound) {
            return Optional.empty();
        }
        if (type.endsWith("/") || type.contains("//") || type.contains(":") || type.contains("[")
                || type.contains("]") || type.contains("*") || type.contains("|")) {
            return Optional.empty();
        }
        return Optional.of(type);
    }

    private static boolean isContentFragment(Resource resource) {
        final Resource content = resource.getChild(ListChildPagesHandler.PAGE_CONTENT);
        return FragmentHandlers.CONTENT_FRAGMENT_TYPE.equals(ChildListingHandler.typeOf(resource))
                && content != null
                && content.getValueMap().get(FragmentHandlers.CONTENT_FRAGMENT_FLAG, false);
    }

    private static boolean isExperienceFragment(Resource resource) {
        if (!FragmentHandlers.EXPERIENCE_FRAGMENT_TYPE.equals(ChildListingHandler.typeOf(resource))) {
            return false;
        }
        final Resource content = resource.getChild(ListChildPagesHandler.PAGE_CONTENT);
        if (content == null) {
            return false;
        }
        final ValueMap values = content.getValueMap();
        return FragmentHandlers.EXPERIENCE_FRAGMENT_RESOURCE_TYPE.equals(
                values.get(RESOURCE_TYPE_PROPERTY, String.class));
    }

    private static String titleOf(Resource resource) {
        final String titled = ListChildPagesHandler.titleOf(resource);
        if (!titled.isEmpty()) {
            return titled;
        }
        return String.valueOf(resource.getValueMap().get(ListChildPagesHandler.TITLE_PROPERTY, ""));
    }

    @Override
    public List<String> categories() {
        return List.of(ListChildPagesHandler.ROOT_ACCESS_DENIED, ListChildPagesHandler.ROOT_NOT_FOUND,
                DISCOVERY_BUDGET_EXCEEDED, "continuation_token_malformed",
                "continuation_token_integrity_invalid", "continuation_token_wrong_target",
                "continuation_token_wrong_query", "continuation_token_expired");
    }

    private sealed interface Asked permits Held, Refused {
    }

    private record Held(String rootPath, rs.slingshot.agent.command.ResultWindow window)
            implements Asked {
    }

    private record Refused(String category, String detail) implements Asked {
    }

    private record Gathered(List<ComponentListingResult.Component> components,
                            List<PageListingResult.Page> pages) {
    }
}
