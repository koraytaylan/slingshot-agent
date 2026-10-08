// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.fragment.FragmentHandlers;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Bounded incremental catalogues under an exact readable root.
 *
 * <p>Pages use live provider depth-first order, with explicit completeness. A runtime-owned
 * cursor retains traversal position between requests and rechecks current authority before
 * materializing each row. Stable trees are enumerated once; concurrent changes are not a snapshot.
 * A missing root is refused rather than expanded into an unbounded ancestor search.</p>
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
    private final DiscoveryRegistry discovery;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     * @param kind which catalogue it lists
     * @param discovery the runtime-owned cursor registry
     */
    public ContentCatalogHandler(AgentContract contract, Kind kind, DiscoveryRegistry discovery) {
        this.contract = contract;
        this.kind = kind;
        this.discovery = discovery;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final Asked asked = asked(arguments);
        if (asked instanceof final Refused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final Held held = (Held) asked;
        return discovery.page(new DiscoveryRegistry.Request(wireName(), held.rootPath(), held.window(),
                arguments), resolver, context, new DiscoveryRegistry.Traversal(this::descends,
                        resource -> row(resource, held.rootPath())));
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

    private Optional<DocumentValue.Mapping> row(Resource resource, String root) {
        if (root.equals(resource.getPath())) {
            return Optional.empty();
        }
        final Optional<String> type = switch (kind) {
            case COMPONENT_DEFINITIONS -> definitionType(resource);
            case COMPONENTS -> instanceType(resource);
            case CONTENT_FRAGMENTS -> isContentFragment(resource) ? Optional.of("") : Optional.empty();
            case EXPERIENCE_FRAGMENTS -> isExperienceFragment(resource) ? Optional.of("") : Optional.empty();
        };
        if (type.isEmpty()) {
            return Optional.empty();
        }
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put("repository_path", new DocumentValue.Text(resource.getPath()));
        if (kind == Kind.COMPONENT_DEFINITIONS || kind == Kind.COMPONENTS) {
            members.put("resource_type", new DocumentValue.Text(type.orElseThrow()));
        }
        final String title = titleOf(resource);
        if (!title.isEmpty()) {
            members.put("title", new DocumentValue.Text(title));
        }
        return Optional.of(new DocumentValue.Mapping(members));
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
        if (!FragmentHandlers.CONTENT_FRAGMENT_TYPE.equals(ChildListingHandler.typeOf(resource))) {
            return false;
        }
        final Resource content = resource.getChild(ListChildPagesHandler.PAGE_CONTENT);
        return content != null
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

}
