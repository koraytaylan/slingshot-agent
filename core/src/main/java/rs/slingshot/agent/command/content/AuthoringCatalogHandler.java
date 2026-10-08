// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.command.fragment.FragmentHandlers;
import rs.slingshot.agent.command.page.CreatePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The editable page templates, or the content fragment models, under one anchor.
 *
 * <p>Neither is searched for, because neither can be just anywhere. Each sits directly inside one
 * fixed folder of a configuration — {@code settings/wcm/templates} or {@code
 * settings/dam/cfm/models} — and configurations sit under {@code /conf}, nested as deep as a
 * site's own folders go, with {@code /apps} and {@code /libs} as the two global ones. So this goes
 * to those folders by name: it walks only the configuration folders under the anchor, reads each
 * one's catalogue folder directly, and lists that folder's children. It never opens the rest of a
 * configuration's settings, a template's own structure, or any content, which is where an author's
 * nodes actually are. An anchor above {@code /conf}, such as {@code /}, is every configuration; an
 * anchor that holds no configuration holds no catalogue.</p>
 *
 * <p>The alternative was a walk of every node under the anchor, which at {@code /} is the whole
 * repository and outlives the request it is answered on; a query would need an index, and nothing
 * here issues one. What this does examine is still counted against the discovery budget, so a
 * configuration tree nobody expected is refused as too large rather than left to run the request
 * out, and the answer is never a shortened page that reads as the whole catalogue.</p>
 */
public final class AuthoringCatalogHandler implements CommandHandler {

    /** Which catalogue this handler lists. */
    public enum Kind {
        /** Editable page templates. */
        PAGE_TEMPLATES,
        /** Content fragment models. */
        FRAGMENT_MODELS
    }

    /** The folder, relative to a configuration, every editable page template sits directly in. */
    private static final String PAGE_TEMPLATE_FOLDER = "settings/wcm/templates";

    /** The folder, relative to a configuration, every content fragment model sits directly in. */
    private static final String FRAGMENT_MODEL_FOLDER = "settings/dam/cfm/models";

    /** The node a configuration keeps its settings in, which is never another configuration. */
    private static final String SETTINGS = "settings";

    /** Where configurations are, nested as deep as a site's own folders. */
    private static final String CONFIGURATIONS = "/conf";

    /** The two global configurations, each of which is the folder itself and nothing below it. */
    private static final List<String> GLOBAL_CONFIGURATIONS = List.of("/apps", "/libs");

    /** A configuration's own properties, which are never another configuration. */
    private static final String CONTENT = "jcr:content";

    /** How access-control nodes are named, which are never a configuration either. */
    private static final String ACCESS_CONTROL = "rep:";

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
    public AuthoringCatalogHandler(AgentContract contract, Kind kind) {
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
        final PagingSupport.Preparation prepared = PagingSupport.prepare(held.window(), wireName(),
                arguments, context, contract);
        if (prepared instanceof final PagingSupport.WindowRefused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final Resource root = resolver.getResource(held.rootPath());
        if (root == null) {
            return new Failed(ListChildPagesHandler.ROOT_NOT_FOUND, held.rootPath()
                    + " is not a path this caller can read, which is the same answer as nothing"
                    + " being there");
        }
        final Search search = new Search(kind, context.discovery().limit());
        search.from(resolver, root.getPath());
        if (search.exhausted()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this listing examined more than the "
                    + context.discovery().limit() + " nodes it is allowed, and stopped rather than"
                    + " answer with part of the catalogue; name one configuration under "
                    + CONFIGURATIONS + " as the anchor instead");
        }
        final PagingSupport.Outcome<PageListingResult.Page> page = PagingSupport.page(
                search.pages(), (PagingSupport.Ready) prepared, wireName(), context, contract);
        if (page instanceof final PagingSupport.Refused<PageListingResult.Page> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<PageListingResult.Page> accepted =
                ((PagingSupport.Accepted<PageListingResult.Page>) page).page();
        return new Produced(PageListingResult.documentOf(accepted.rows(),
                accepted.continuationToken()));
    }

    private String wireName() {
        return kind == Kind.PAGE_TEMPLATES
                ? ListPageTemplatesCommand.WIRE_NAME
                : ListContentFragmentModelsCommand.WIRE_NAME;
    }

    private Asked asked(DocumentValue.Mapping arguments) {
        if (kind == Kind.PAGE_TEMPLATES) {
            final ListPageTemplatesCommand.Outcome outcome =
                    ListPageTemplatesCommand.of(arguments, contract);
            if (outcome instanceof final ListPageTemplatesCommand.Refused refused) {
                return new Refused(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
            }
            final ListPageTemplatesCommand command = ((ListPageTemplatesCommand.Held) outcome)
                    .command();
            return new Held(command.rootPath(), command.window());
        }
        final ListContentFragmentModelsCommand.Outcome outcome =
                ListContentFragmentModelsCommand.of(arguments, contract);
        if (outcome instanceof final ListContentFragmentModelsCommand.Refused refused) {
            return new Refused(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final ListContentFragmentModelsCommand command =
                ((ListContentFragmentModelsCommand.Held) outcome).command();
        return new Held(command.rootPath(), command.window());
    }

    private static String titleOf(Resource resource) {
        final String titled = ListChildPagesHandler.titleOf(resource);
        if (!titled.isEmpty()) {
            return titled;
        }
        return String.valueOf(resource.getValueMap().get(ListChildPagesHandler.TITLE_PROPERTY, ""));
    }

    /**
     * Whether {@code path} is {@code anchor} or somewhere beneath it.
     *
     * @param path the repository path
     * @param anchor the path it may sit under
     * @return whether it does
     */
    static boolean atOrBelow(String path, String anchor) {
        if (path.equals(anchor) || "/".equals(anchor)) {
            return path.startsWith("/");
        }
        return path.startsWith(anchor + "/");
    }

    /**
     * The configuration whose settings a path is inside, when it is inside any.
     *
     * @param path the repository path
     * @return the configuration's path, or nothing where the path is above every settings folder
     */
    static Optional<String> configurationHolding(String path) {
        final int settings = (path + "/").indexOf("/" + SETTINGS + "/");
        return settings < 0 ? Optional.empty() : Optional.of(path.substring(0, settings));
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

    /**
     * One listing: what it found, and how much of the discovery budget finding it spent.
     *
     * <p>Every node it reads is one examination, including the ones it passes over, so the budget
     * bounds the work done rather than the size of the answer.</p>
     */
    private static final class Search {

        private final Kind kind;
        private final String folder;
        private final long budget;
        private final List<PageListingResult.Page> found = new ArrayList<>();
        private final AtomicLong examined = new AtomicLong();

        private Search(Kind kind, long budget) {
            this.kind = kind;
            this.folder = kind == Kind.PAGE_TEMPLATES ? PAGE_TEMPLATE_FOLDER : FRAGMENT_MODEL_FOLDER;
            this.budget = budget;
        }

        /**
         * Finds the catalogue under one anchor.
         *
         * <p>The anchor is placed against the configuration layout first. Above {@code /conf} it
         * is every configuration there; inside it, it is the configurations under it, or the one
         * catalogue folder it is on the way to; and it is each global configuration whose
         * catalogue folder it is at or above.</p>
         */
        private void from(ResourceResolver resolver, String anchor) {
            if (atOrBelow(CONFIGURATIONS, anchor)) {
                Optional.ofNullable(resolver.getResource(CONFIGURATIONS))
                        .ifPresent(this::everyConfigurationUnder);
            } else if (atOrBelow(anchor, CONFIGURATIONS)) {
                insideConfigurations(resolver, anchor);
            }
            GLOBAL_CONFIGURATIONS.forEach(global -> catalogueOf(resolver, anchor, global));
        }

        private void insideConfigurations(ResourceResolver resolver, String anchor) {
            final Optional<String> configuration = configurationHolding(anchor);
            if (configuration.isPresent()) {
                catalogueOf(resolver, anchor, configuration.orElseThrow());
                return;
            }
            Optional.ofNullable(resolver.getResource(anchor))
                    .ifPresent(this::everyConfigurationUnder);
        }

        /** Lists one configuration's catalogue, where its folder is at or below the anchor. */
        private void catalogueOf(ResourceResolver resolver, String anchor, String configuration) {
            final String path = configuration + "/" + folder;
            if (exhausted() || !atOrBelow(path, anchor)) {
                return;
            }
            Optional.ofNullable(resolver.getResource(path)).ifPresent(this::list);
        }

        /**
         * Visits every configuration folder under one node, and lists each one's catalogue.
         *
         * <p>A configuration's settings, its own properties, and its access control are passed
         * over rather than opened: none of them is another configuration, and the settings folder
         * is where every template's structure and every other kind of setting is.</p>
         */
        private void everyConfigurationUnder(Resource top) {
            final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
            examine();
            Optional<Resource> next = Optional.of(top);
            while (next.isPresent() && !exhausted()) {
                final Resource configuration = next.orElseThrow();
                Optional.ofNullable(configuration.getChild(folder)).ifPresent(this::list);
                pending.push(configuration.listChildren());
                next = nextConfiguration(pending);
            }
        }

        private Optional<Resource> nextConfiguration(Deque<Iterator<Resource>> pending) {
            while (!pending.isEmpty() && !exhausted()) {
                final Iterator<Resource> children = pending.peek();
                final Optional<Resource> child = children.hasNext()
                        ? Optional.of(children.next()) : Optional.empty();
                if (child.isEmpty()) {
                    pending.pop();
                } else if (worthOpening(child.orElseThrow())) {
                    return child;
                }
            }
            return Optional.empty();
        }

        /** Examines one child of a configuration, and answers whether it may be another one. */
        private boolean worthOpening(Resource child) {
            examine();
            final String name = child.getName();
            return !SETTINGS.equals(name) && !CONTENT.equals(name)
                    && !name.startsWith(ACCESS_CONTROL);
        }

        /** Takes the catalogue out of one catalogue folder, which is its direct children. */
        private void list(Resource catalogue) {
            examine();
            final Iterator<Resource> children = catalogue.listChildren();
            while (children.hasNext() && !exhausted()) {
                final Resource child = children.next();
                examine();
                if (belongs(child)) {
                    found.add(new PageListingResult.Page(child.getPath(), titleOf(child)));
                }
            }
        }

        private boolean belongs(Resource child) {
            if (!CreatePageHandler.TEMPLATE_TYPE.equals(ChildListingHandler.typeOf(child))) {
                return false;
            }
            return kind == Kind.PAGE_TEMPLATES
                    || child.getChild(FragmentHandlers.MODEL_ELEMENTS) != null;
        }

        private void examine() {
            examined.incrementAndGet();
        }

        private boolean exhausted() {
            return examined.get() > budget;
        }

        private List<PageListingResult.Page> pages() {
            return PageListingResult.ascending(found);
        }
    }
}
