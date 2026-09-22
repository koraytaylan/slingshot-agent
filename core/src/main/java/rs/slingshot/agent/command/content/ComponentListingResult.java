// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A list of components: each an address, the resource type it is, and a title where it has one.
 *
 * <p>Two commands answer this shape. A definition's resource type is the type its path resolves
 * as, which is what {@code add_component} takes. An instance's resource type is the one it
 * declares, which is what {@code find_pages_using_components} searches for. A title is what makes
 * the row usable by a person. Anything further about the component is that component's own read.</p>
 */
public final class ComponentListingResult {

    private ComponentListingResult() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member one match's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member one match's resource type is carried in. */
    public static final String RESOURCE_TYPE = "resource_type";

    /** The member one match's title is carried in, where it has one. */
    public static final String TITLE = "title";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS =
            List.of(MATCHES, NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH, RESOURCE_TYPE, TITLE);

    /**
     * One component as a caller receives it.
     *
     * @param repositoryPath where it is
     * @param resourceType the type it is, or the type its definition resolves as
     * @param title what it is called, which is empty where it carries no title
     */
    public record Component(String repositoryPath, String resourceType, String title) {
    }

    /**
     * The result one window of components produces.
     *
     * @param found the components, in whichever order the command that found them answers in
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<Component> found, String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(found.stream()
                .map(ComponentListingResult::componentOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    /**
     * The components of one listing, strictly ascending by repository-path bytes.
     *
     * @param found the components, in whatever order the repository returned them
     * @return the same components, strictly ascending by path
     */
    public static List<Component> ascending(List<Component> found) {
        return found.stream()
                .sorted(java.util.Comparator.comparing(Component::repositoryPath))
                .toList();
    }

    private static DocumentValue componentOf(Component component) {
        final SequencedMap<String, DocumentValue> row = new LinkedHashMap<>();
        row.put(REPOSITORY_PATH, new DocumentValue.Text(component.repositoryPath()));
        row.put(RESOURCE_TYPE, new DocumentValue.Text(component.resourceType()));
        if (!component.title().isEmpty()) {
            row.put(TITLE, new DocumentValue.Text(component.title()));
        }
        return new DocumentValue.Mapping(row);
    }
}
