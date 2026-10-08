// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Address rows and their disclosure check, shared by bounded query pages.
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

    /** The member carrying addresses in repository provider order. */
    public static final String MATCHES = "matches";

    /** The member one match's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Address and page-progress members, borrowing the shared writer's vocabulary. */
    public static final List<String> MEMBERS = List.of(MATCHES, IncrementalDiscoveryResult.COMPLETE,
            IncrementalDiscoveryResult.EXAMINED_NODES, NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH);

    /**
     * Materializes one currently readable address without disclosing its stored values.
     * @param path the exact resource path
     * @return the detached result row
     */
    public static DocumentValue.Mapping matchOf(String path) {
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
