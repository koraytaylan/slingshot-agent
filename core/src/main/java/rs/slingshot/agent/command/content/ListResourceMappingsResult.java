// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The resolution rules, in the order the platform applies them, with no credential in any of them.
 *
 * <p>A mapping entry's address can carry a credential — {@code https://user:secret@host/} is a
 * perfectly ordinary thing to find in a resource mapping — and that is why this listing has a
 * redaction rule of its own rather than relying on the general one.</p>
 *
 * <p>The credential is <em>removed</em>, not masked. A mask still answers questions: it says a
 * credential was there, and how long it was, and which entries have one. An operator reading this
 * listing needs the rule, and the rule is the same rule without it — so what comes back is the
 * address as it would be written by somebody who never had the secret.</p>
 */
public final class ListResourceMappingsResult {

    private ListResourceMappingsResult() {
    }

    /** The member the mapping entries are carried in, in application order. */
    public static final String ENTRIES = "entries";

    /** The member one entry's pattern is carried in. */
    public static final String PATTERN = "pattern";

    /** The member the address of the entry itself is carried in. */
    public static final String ENTRY_PATH = "entry_path";

    /** The member one entry's replacement is carried in. */
    public static final String REPLACEMENTS = "replacements";

    /** The member one entry's declared kind is carried in. */
    public static final String KIND = "kind";

    /** The member an entry's own redirect status is carried in, where it declares one. */
    public static final String STATUS_CODE = "status_code";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(ENTRIES, ENTRY_PATH, KIND,
            NEXT_CONTINUATION_TOKEN, PATTERN, REPLACEMENTS, STATUS_CODE);

    /** Where an entry has no redirect status of its own, which most entries do not. */
    public static final long NO_STATUS = 0;

    /**
     * One mapping entry as a caller receives it.
     *
     * <p>The replacements are a list because one rule may name several: the platform tries them in
     * order, and an operator reading a rule that produced an unexpected address needs to see the
     * alternatives rather than only the first.</p>
     *
     * @param entryPath where the rule itself is, which is how an operator goes and edits it
     * @param kind what the platform calls this kind of entry
     * @param pattern the pattern it matches on
     * @param replacements what it replaces a match with, each carrying no credential
     * @param statusCode the redirect status this entry declares, or {@link #NO_STATUS} for none
     */
    public record MappingEntry(String entryPath, MappingKind kind, String pattern,
                               List<String> replacements, long statusCode) {

        /** Holds the replacements apart from whatever produced them. */
        public MappingEntry {
            replacements = List.copyOf(replacements);
        }

        /**
         * What this entry replaces a match with.
         *
         * @return the replacements, which nothing may add to
         */
        @Override
        public List<String> replacements() {
            return java.util.Collections.unmodifiableList(replacements);
        }
    }

    /**
     * The result one window of entries produces.
     *
     * @param entries the entries, in the order the platform applies them
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<MappingEntry> entries,
                                                   String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(ENTRIES, new DocumentValue.Sequence(entries.stream()
                .map(ListResourceMappingsResult::entryOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue entryOf(MappingEntry entry) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(ENTRY_PATH, new DocumentValue.Text(entry.entryPath()));
        held.put(KIND, new DocumentValue.Text(entry.kind().spelling()));
        held.put(PATTERN, new DocumentValue.Text(entry.pattern()));
        held.put(REPLACEMENTS, new DocumentValue.Sequence(entry.replacements().stream()
                .map(replacement -> (DocumentValue) new DocumentValue.Text(replacement))
                .toList()));
        // An entry that declares no redirect status carries no member for one rather than a zero
        // somebody would read as a status. The client's own schema makes it optional for exactly
        // that reason.
        if (entry.statusCode() != NO_STATUS) {
            held.put(STATUS_CODE, new DocumentValue.Whole(entry.statusCode()));
        }
        return new DocumentValue.Mapping(held);
    }

    /**
     * One address with any credential component taken out of it entirely.
     *
     * <p>Removed rather than masked, and removed rather than the whole address being withheld: the
     * rule is what the operator came for, and the rule without its credential is the same rule.
     * What comes back is the address as somebody who never had the secret would have written
     * it.</p>
     *
     * @param address the address as the platform holds it
     * @return the address with no credential in it
     */
    public static String withoutCredentials(String address) {
        final int scheme = address.indexOf("://");
        if (scheme < 0) {
            return address;
        }
        final int authorityStart = scheme + "://".length();
        final int authorityEnd = endOfAuthority(address, authorityStart);
        final int credential = address.lastIndexOf('@', authorityEnd);
        return credential < authorityStart ? address
                : address.substring(0, authorityStart) + address.substring(credential + 1);
    }

    private static int endOfAuthority(String address, int from) {
        final int path = address.indexOf('/', from);
        return path < 0 ? address.length() : path;
    }
}
