// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
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
 * <p>Both are a walk of one subtree bounded by the caller's examination budget. A query would ask
 * the repository for every {@code cq:Template} and then throw away the ones that are not the
 * catalogue being asked for, and the two catalogues are told apart by where they sit, which is a
 * fact about the path rather than a property an index covers. The walk stops at the budget rather
 * than returning a shortened page, because a shortened page reads as the whole answer.</p>
 */
public final class AuthoringCatalogHandler implements CommandHandler {

    /** Which catalogue this handler lists. */
    public enum Kind {
        /** Editable page templates. */
        PAGE_TEMPLATES,
        /** Content fragment models. */
        FRAGMENT_MODELS
    }

    /** The segments immediately above an editable page template, in order. */
    private static final List<String> PAGE_TEMPLATE_PARENTS =
            List.of("settings", "wcm", "templates");

    /** The segments immediately above a content fragment model, in order. */
    private static final List<String> FRAGMENT_MODEL_PARENTS =
            List.of("settings", "dam", "cfm", "models");

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
        final Resource root = resolver.getResource(held.rootPath());
        if (root == null) {
            return new Failed(ListChildPagesHandler.ROOT_NOT_FOUND, held.rootPath()
                    + " is not a path this caller can read, which is the same answer as nothing"
                    + " being there");
        }
        final Gathered gathered = gather(root, context.discovery().limit());
        if (gathered.exceeded()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search examined more than the "
                    + context.discovery().limit() + " nodes it is allowed, and stopped rather than"
                    + " going on");
        }
        final PagingSupport.Outcome<PageListingResult.Page> page = PagingSupport.page(
                gathered.found(), held.window(), wireName(), arguments, context, contract);
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

    private Gathered gather(Resource root, long budget) {
        final List<PageListingResult.Page> found = new ArrayList<>();
        final Deque<Iterator<Resource>> pending = new ArrayDeque<>();
        Resource current = root;
        long examined = 0;
        while (current != null) {
            examined = examined + 1;
            if (examined > budget) {
                return new Gathered(List.of(), true);
            }
            if (matches(current, root.getPath())) {
                found.add(new PageListingResult.Page(current.getPath(), titleOf(current)));
            }
            pending.push(current.listChildren());
            current = null;
            while (!pending.isEmpty() && current == null) {
                final Iterator<Resource> children = pending.peek();
                if (children.hasNext()) {
                    current = children.next();
                } else {
                    pending.pop();
                }
            }
        }
        return new Gathered(PageListingResult.ascending(found), false);
    }

    private boolean matches(Resource resource, String anchor) {
        if (anchor.equals(resource.getPath())) {
            return false;
        }
        if (!CreatePageHandler.TEMPLATE_TYPE.equals(ChildListingHandler.typeOf(resource))) {
            return false;
        }
        final List<String> parents = kind == Kind.PAGE_TEMPLATES
                ? PAGE_TEMPLATE_PARENTS : FRAGMENT_MODEL_PARENTS;
        if (!directChildOf(resource.getPath(), parents)) {
            return false;
        }
        return kind == Kind.PAGE_TEMPLATES
                || resource.getChild(FragmentHandlers.MODEL_ELEMENTS) != null;
    }

    /**
     * Whether {@code path} is the node directly inside {@code folders}.
     *
     * @param path the repository path
     * @param folders the segments immediately above the node, in order
     * @return whether it sits there
     */
    static boolean directChildOf(String path, List<String> folders) {
        if (path.length() < 2 || path.charAt(0) != '/') {
            return false;
        }
        final String[] segments = path.substring(1).split("/", -1);
        if (segments.length <= folders.size()) {
            return false;
        }
        final int start = segments.length - folders.size() - 1;
        for (int index = 0; index < folders.size(); index = index + 1) {
            if (!folders.get(index).equals(segments[start + index])) {
                return false;
            }
        }
        return true;
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

    private record Gathered(List<PageListingResult.Page> found, boolean exceeded) {
    }
}
