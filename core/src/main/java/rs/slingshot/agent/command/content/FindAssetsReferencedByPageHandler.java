// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.SequencedSet;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The assets one page references, found by walking the page rather than by searching for it.
 *
 * <p>A page's own subtree is small and known, so this reads it directly. There is nothing to index
 * and nothing to search: the question is about one page, and walking one page is what answering it
 * costs.</p>
 *
 * <h2>Three ways a page can fail to be one</h2>
 *
 * <p>Nothing there, something there the caller may not read, and something there that is not a page
 * are three answers rather than one. That is a deliberate difference from the commands that anchor
 * on a subtree, where an unreadable root is answered as absent so that the pair of answers cannot be
 * used to find out whether content exists somewhere a caller may not look. Here the caller has
 * named one page they are asking about, and the client's own classification declares all three:
 * somebody whose path is wrong retypes it, somebody who lacks a permission asks for one, and
 * somebody who pointed at a folder goes looking for the page inside it.</p>
 */
public final class FindAssetsReferencedByPageHandler implements CommandHandler {

    /** Where assets live, which is what makes a path a reference to one. */
    public static final String ASSET_ROOT = "/content/dam/";

    /** The category a page nothing is at is refused under. */
    public static final String PAGE_NOT_FOUND = "page_not_found";

    /** The category a page the caller may not read is refused under. */
    public static final String PAGE_ACCESS_DENIED = "page_access_denied";

    /** The category something that is there and is not a page is refused under. */
    public static final String PAGE_INVALID = "page_invalid";

    /** The category a walk that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public FindAssetsReferencedByPageHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final FindAssetsReferencedByPageCommand.Outcome asked =
                FindAssetsReferencedByPageCommand.of(arguments, contract);
        if (asked instanceof final FindAssetsReferencedByPageCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return walked(((FindAssetsReferencedByPageCommand.Held) asked).command(), resolver,
                context);
    }

    private Answer walked(FindAssetsReferencedByPageCommand command, ResourceResolver resolver,
                          CallerContext context) {
        final Resource page = resolver.getResource(command.pagePath());
        if (page == null) {
            return new Failed(PAGE_NOT_FOUND, command.pagePath() + " is not there");
        }
        if (!ListChildPagesHandler.PAGE_TYPE.equals(String.valueOf(page.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new Failed(PAGE_INVALID, command.pagePath() + " is there and is not a page, so it"
                    + " has no page to look through; the page is probably inside it");
        }
        final Walk walk = new Walk(context.discovery().limit(), command.pagePath());
        walk.under(page);
        if (walk.reachedTheBudget()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this page holds more than the "
                    + context.discovery().limit() + " nodes this caller may examine");
        }
        return new Produced(FindAssetsReferencedByPageResult.documentOf(
                pageOf(walk.found(), command.window(), contract), ""));
    }

    /** One walk of one page, gathering every reference to an asset it is asked to look for. */
    private static final class Walk {

        private final long budget;
        private final String page;
        private final SequencedMap<String, SequencedSet<String>> found = new LinkedHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong examined =
                new java.util.concurrent.atomic.AtomicLong();

        Walk(long budget, String page) {
            this.budget = budget;
            this.page = page;
        }

        void under(Resource resource) {
            if (reachedTheBudget()) {
                return;
            }
            examined.incrementAndGet();
            resource.getValueMap().forEach((property, value) ->
                    referencesIn(resource, property, value));
            final Iterator<Resource> children = resource.listChildren();
            while (children.hasNext() && !reachedTheBudget()) {
                under(children.next());
            }
        }

        private void referencesIn(Resource resource, String property, Object value) {
            if (value instanceof final String text) {
                single(resource, property, text);
                return;
            }
            if (value instanceof final String[] items) {
                java.util.stream.Stream.of(items)
                        .filter(Walk::isAssetPath)
                        .forEach(item -> record(item, relative(resource, property)));
            }
        }

        private void single(Resource resource, String property, String text) {
            // A value that is wholly an asset path is the reference; there is nothing inside it to
            // look for. Searching a bare path for paths would find the path itself and record the
            // same reference twice under the one property.
            if (isAssetPath(text)) {
                record(text, relative(resource, property));
                return;
            }
            markupReferences(text).forEach(asset -> record(asset, relative(resource, property)));
        }

        private void record(String asset, String property) {
            found.computeIfAbsent(asset, path -> new LinkedHashSet<>()).add(property);
        }

        private String relative(Resource resource, String property) {
            final String path = resource.getPath();
            return path.length() > page.length()
                    ? path.substring(page.length() + 1) + "/" + property : property;
        }

        private static boolean isAssetPath(String value) {
            return value.startsWith(ASSET_ROOT);
        }

        boolean reachedTheBudget() {
            return examined.get() >= budget;
        }

        List<FindAssetsReferencedByPageResult.ReferencedAsset> found() {
            return found.entrySet().stream()
                    .map(asset -> new FindAssetsReferencedByPageResult.ReferencedAsset(
                            asset.getKey(), List.copyOf(asset.getValue())))
                    .toList();
        }
    }

    /**
     * Every asset path appearing inside one fragment of markup.
     *
     * <p>Written as its own method because it is the part most likely to be wrong: a path inside
     * markup is surrounded by whatever the component wrote around it, so it is found by looking for
     * where an asset path starts and taking it to the first character that cannot be in one.</p>
     *
     * @param markup the stored fragment
     * @return every asset path it mentions, in the order they appear
     */
    public static List<String> markupReferences(String markup) {
        final List<String> found = new ArrayList<>();
        int at = markup.indexOf(ASSET_ROOT);
        while (at >= 0) {
            int end = at;
            while (end < markup.length() && isPathCharacter(markup.charAt(end))) {
                end = end + 1;
            }
            found.add(markup.substring(at, end));
            at = markup.indexOf(ASSET_ROOT, end);
        }
        return java.util.Collections.unmodifiableList(found);
    }

    private static boolean isPathCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '/' || character == '.'
                || character == '-' || character == '_';
    }

    /**
     * The window's worth of referenced assets.
     *
     * @param found every referenced asset, each once
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the assets that page carries
     */
    public static List<FindAssetsReferencedByPageResult.ReferencedAsset> pageOf(
            List<FindAssetsReferencedByPageResult.ReferencedAsset> found, ResultWindow window,
            AgentContract contract) {
        final long offset = window instanceof final ResultWindow.Initial initial
                ? initial.offset() : 0;
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit() : contract.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        return found.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<String> categories() {
        return List.of(PAGE_ACCESS_DENIED, PAGE_INVALID, PAGE_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
