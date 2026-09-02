// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A list of pages: each an address and, where it has one, a title.
 *
 * <p>Four commands answer this shape — the children of a page, and the three searches that find
 * pages by phrase, by template, and by component. Written once because four copies of one document
 * are four places for it to drift, and because the claim that matters is the same claim in all
 * four: an address and a title reach the caller and nothing else does.</p>
 *
 * <p>A title is what makes a listing usable by a person, which is the point of answering one.
 * Anything further about a page — its properties, its content, an excerpt of what matched — is that
 * page's own read to ask for, under its own bound and its own permissions. A search result carrying
 * an excerpt would be answering a content read that nobody checked the caller could make.</p>
 *
 * <p>The order is whichever the command that filled it in chose, and each says which: a listing of
 * children is in the repository's own order, and a search is in the order its query returned.</p>
 *
 * <p>The member names are the client's, from the schema it publishes for all four commands. They
 * are not this side's to choose: a result whose members are spelled differently is a result the
 * other half cannot read, however sensible the spelling.</p>
 */
public final class PageListingResult {

    private PageListingResult() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member one match's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member one match's title is carried in, where the page has one. */
    public static final String TITLE = "title";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /**
     * Every member this result's document has, nested ones included.
     *
     * <p>A page's own address and title are members of this result as much as the list that holds
     * them: a caller reads them, and a schema declares them. Naming only the outermost three would
     * leave the two a caller actually looks at undeclared by the model, which is exactly the gap
     * the correspondence check exists to find.</p>
     */
    public static final List<String> MEMBERS =
            List.of(MATCHES, NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH, TITLE);

    /**
     * One page as a caller receives it.
     *
     * @param repositoryPath where it is
     * @param title what it is called, which is empty where the page carries no title
     */
    public record Page(String repositoryPath, String title) {
    }

    /**
     * The result one window of pages produces.
     *
     * @param children the pages, in whichever order the command that found them answers in
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<Page> children,
                                                   String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(children.stream()
                .map(PageListingResult::pageOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue pageOf(Page child) {
        final SequencedMap<String, DocumentValue> page = new LinkedHashMap<>();
        page.put(REPOSITORY_PATH, new DocumentValue.Text(child.repositoryPath()));
        // A page with no title carries none rather than an empty one. The two are different claims
        // — "it is called nothing" and "it is called the empty string" — and the client's own
        // schema makes the member optional so that the first can be said.
        if (!child.title().isEmpty()) {
            page.put(TITLE, new DocumentValue.Text(child.title()));
        }
        return new DocumentValue.Mapping(page);
    }
}
