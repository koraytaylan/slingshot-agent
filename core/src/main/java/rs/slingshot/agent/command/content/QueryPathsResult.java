// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Addresses, and where to resume — and nothing else at all.
 *
 * <p>This command answers a list of paths. It carries no property, no node type, no size, no
 * timestamp: nothing but where things are. That is worth stating as a property of the result type
 * rather than as a habit of whoever wrote the handler, because this is the one command where an
 * accidental disclosure would be obvious to a reviewer only if the result had stayed this narrow.
 * A result that already carried six harmless fields is a result where a seventh goes unnoticed.</p>
 *
 * <p>A caller wanting to know anything <em>about</em> one of these addresses asks for it, with a
 * command whose row says what it may answer and whose failures say what it may not reach. Answering
 * it here as a convenience would make this command's own bound and permissions a lie.</p>
 */
public final class QueryPathsResult {

    private QueryPathsResult() {
    }

    /** The member the addresses are carried in, in the order the query returned them. */
    public static final String MATCHES = "matches";

    /** The member one match's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";


    /** Every member this result has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(MATCHES, NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH);

    /**
     * The result one page of addresses produces.
     *
     * <p>The token member is present only where a token exists. An absent token is a definite end,
     * and writing it as an empty string would make the end indistinguishable from a token that
     * happens to be empty.</p>
     *
     * @param paths the addresses this page carries, in the order the query returned them
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<String> paths, String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(paths.stream()
                .map(QueryPathsResult::matchOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue matchOf(String path) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(REPOSITORY_PATH, new DocumentValue.Text(path));
        return new DocumentValue.Mapping(match);
    }

    /**
     * Whether one rendered result discloses anything that is not an address.
     *
     * <p>Written as a check rather than trusted to review, and used by this command's own suite
     * over a corpus whose nodes carry distinctive values. What it looks for is any text in the
     * result that is not one of the addresses and not a member name — which is what a property
     * value leaking into a result would look like.</p>
     *
     * @param result the rendered result
     * @param addresses the addresses it is supposed to carry
     * @return every disclosed value that is not an address, which should always be empty
     */
    public static List<String> disclosedBeyondAddresses(DocumentValue.Mapping result,
                                                        List<String> addresses) {
        return result.members().entrySet().stream()
                .filter(member -> !NEXT_CONTINUATION_TOKEN.equals(member.getKey()))
                .flatMap(member -> textIn(member.getValue()).stream())
                .filter(disclosed -> !addresses.contains(disclosed))
                .toList();
    }

    private static List<String> textIn(DocumentValue value) {
        return switch (value) {
            case DocumentValue.Text text -> List.of(text.value());
            case DocumentValue.Sequence sequence -> sequence.items().stream()
                    .flatMap(item -> textIn(item).stream())
                    .toList();
            case DocumentValue.Mapping mapping -> mapping.members().values().stream()
                    .flatMap(member -> textIn(member).stream())
                    .toList();
            // Exhaustive over the sealed set on purpose: a variant added to the
            // protocol stops the compiler here rather than slipping past a default
            // branch as a kind of value this scan quietly does not look inside.
            case DocumentValue.Whole ignored -> List.of();
            case DocumentValue.Flag ignored -> List.of();
            case DocumentValue.Nothing ignored -> List.of();
        };
    }
}
