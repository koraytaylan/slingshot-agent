// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
import rs.slingshot.agent.json.DocumentValue;

/** Per-request phrase matching and page-tree pruning, without retained repository authority. */
final class PhraseSearchTraversal {

    private static final List<String> FOLDER_TYPES =
            List.of("sling:Folder", "sling:OrderedFolder", "nt:folder");
    private final String root;
    private final String phrase;

    private PhraseSearchTraversal(String root, String phrase) {
        this.root = root;
        this.phrase = phrase;
    }

    /**
     * Builds rules that the runtime uses only during the current page or replay.
     * @param root the exact submitted anchor
     * @param phrase the already validated searched phrase
     * @return bounded traversal and detached row materialization
     */
    static DiscoveryRegistry.Traversal of(String root, String phrase) {
        final var search = new PhraseSearchTraversal(root, phrase);
        return new DiscoveryRegistry.Traversal(search::opens, search::row);
    }

    private boolean admitted(Resource resource) {
        final String path = resource.getPath();
        if (root.equals(path)) {
            return true;
        }
        if (PageTree.NOT_SITES.contains(path)) {
            return false;
        }
        return !"/".equals(root) || PageTree.CONTENT.equals(path)
                || path.startsWith(PageTree.CONTENT + "/");
    }

    private boolean opens(Resource resource) {
        if (!admitted(resource)) {
            return false;
        }
        if (root.equals(resource.getPath()) || isPage(resource)
                || PageTree.CONTENT.equals(resource.getPath())) {
            return true;
        }
        return FOLDER_TYPES.contains(ChildListingHandler.typeOf(resource))
                && Optional.ofNullable(resource.getParent()).filter(PhraseSearchTraversal::isPage)
                        .isEmpty();
    }

    private Optional<DocumentValue.Mapping> row(Resource resource) {
        if (!admitted(resource) || !isPage(resource)) {
            return Optional.empty();
        }
        final Optional<Resource> content = Optional.ofNullable(
                resource.getChild(ListChildPagesHandler.PAGE_CONTENT));
        if (content.filter(this::contains).isEmpty()) {
            return Optional.empty();
        }
        final SequencedMap<String, DocumentValue> values = new LinkedHashMap<>();
        values.put("repository_path", new DocumentValue.Text(resource.getPath()));
        final String title = ListChildPagesHandler.titleOf(resource);
        if (!title.isEmpty()) {
            values.put("title", new DocumentValue.Text(title));
        }
        return Optional.of(new DocumentValue.Mapping(values));
    }

    private boolean contains(Resource content) {
        final var values = content.getValueMap();
        return FindPagesContainingPhraseHandler.SEARCHED_PROPERTIES.stream()
                .map(property -> values.get(property, ""))
                .anyMatch(value -> value.contains(phrase));
    }

    private static boolean isPage(Resource resource) {
        return ListChildPagesHandler.PAGE_TYPE.equals(ChildListingHandler.typeOf(resource));
    }
}
