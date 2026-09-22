// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
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
 * <p>Each is a walk of one subtree bounded by the caller's examination budget. What distinguishes
 * a match is a fact about the node — its type, a flag, or a resource type — applied to the nodes
 * the walk reaches. The walk stops at the budget rather than returning a shortened page, because a
 * shortened page reads as the whole answer.</p>
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
        final Resource root = resolver.getResource(held.rootPath());
        if (root == null) {
            return new Failed(ListChildPagesHandler.ROOT_NOT_FOUND,
                    ListChildPagesHandler.whyNothingIsListed(held.rootPath()));
        }
        final Gathered gathered = gather(root, context.discovery().limit());
        if (gathered.exceeded()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search examined more than the "
                    + context.discovery().limit() + " nodes it is allowed, and stopped rather than"
                    + " going on");
        }
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

    private Gathered gather(Resource root, long budget) {
        final List<ComponentListingResult.Component> components = new ArrayList<>();
        final List<PageListingResult.Page> pages = new ArrayList<>();
        final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
        Resource current = root;
        long examined = 0;
        while (current != null) {
            examined = examined + 1;
            if (examined > budget) {
                return new Gathered(List.of(), List.of(), true);
            }
            consider(current, root.getPath(), components, pages);
            pending.push(current.listChildren());
            final Resource next = nextChild(pending);
            if (next == null) {
                break;
            }
            current = next;
        }
        return new Gathered(ComponentListingResult.ascending(components),
                PageListingResult.ascending(pages), false);
    }

    private void consider(Resource resource, String anchor,
                          List<ComponentListingResult.Component> components,
                          List<PageListingResult.Page> pages) {
        if (anchor.equals(resource.getPath())) {
            return;
        }
        if (kind == Kind.COMPONENT_DEFINITIONS) {
            final String type = definitionType(resource);
            if (type != null) {
                components.add(new ComponentListingResult.Component(resource.getPath(), type,
                        titleOf(resource)));
            }
            return;
        }
        if (kind == Kind.COMPONENTS) {
            final String type = instanceType(resource);
            if (type != null) {
                components.add(new ComponentListingResult.Component(resource.getPath(), type,
                        titleOf(resource)));
            }
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

    private static Resource nextChild(Deque<Iterator<Resource>> pending) {
        while (!pending.isEmpty()) {
            final Iterator<Resource> children = pending.peek();
            if (children.hasNext()) {
                return children.next();
            }
            pending.pop();
        }
        return null;
    }

    private String definitionType(Resource resource) {
        if (!COMPONENT_DEFINITION_TYPE.equals(ChildListingHandler.typeOf(resource))) {
            return null;
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

    private String instanceType(Resource resource) {
        final Object held = resource.getValueMap().get(RESOURCE_TYPE_PROPERTY);
        return held instanceof final String type ? acceptable(type) : null;
    }

    private String acceptable(String type) {
        if (type.isEmpty()) {
            return null;
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_COMPONENT_RESOURCE_TYPE_BYTES);
        if (type.getBytes(StandardCharsets.UTF_8).length > bound) {
            return null;
        }
        if (type.endsWith("/") || type.contains("//") || type.contains(":") || type.contains("[")
                || type.contains("]") || type.contains("*") || type.contains("|")) {
            return null;
        }
        return type;
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
                            List<PageListingResult.Page> pages, boolean exceeded) {
    }
}
