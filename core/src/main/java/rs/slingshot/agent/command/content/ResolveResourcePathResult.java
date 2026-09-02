// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one request address resolved to, taken apart the way the platform takes it apart.
 *
 * <p>The path is only half the answer. A request carries selectors, an extension and a suffix, and
 * which of them the platform recognised is the difference between a page that renders and one that
 * answers a 404 — an operator chasing "this link works and that one does not" is usually looking at
 * a selector nobody expected. So the parts are reported beside the path rather than left for the
 * caller to re-derive from an address the platform has already parsed.</p>
 *
 * <p>Everything but the request address and the selectors may be absent, and absence is the answer:
 * a request that resolved to nothing has no path, and one that named no extension has none. Sending
 * an empty string for either would make "there is none" and "it is empty" the same answer.</p>
 */
public final class ResolveResourcePathResult {

    private ResolveResourcePathResult() {
    }

    /** The member the request address that was asked about is carried in. */
    public static final String REQUEST_ADDRESS = "request_address";

    /** The member the path it resolved to is carried in, where it resolved to one. */
    public static final String RESOLVED_PATH = "resolved_path";

    /** The member the resolved resource's type is carried in, where it has one. */
    public static final String RESOURCE_TYPE = "resource_type";

    /** The member the selectors the platform recognised are carried in. */
    public static final String SELECTORS = "selectors";

    /** The member the extension is carried in, where the request named one. */
    public static final String EXTENSION = "extension";

    /** The member the suffix is carried in, where the request named one. */
    public static final String SUFFIX = "suffix";

    /** The member the rules the resolution went through are carried in, where asked for. */
    public static final String TRACE = "trace";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(EXTENSION, REQUEST_ADDRESS, RESOLVED_PATH,
            RESOURCE_TYPE, SELECTORS, SUFFIX, TRACE);

    /** What an absent part is, which is not the same as one that is empty. */
    public static final String ABSENT = "";

    /**
     * One resolution as a caller receives it.
     *
     * @param requestAddress the address that was asked about, echoed so the answer says what it
     *     is of
     * @param resolvedPath where it resolved to, or {@link #ABSENT} where it resolved to nothing
     * @param resourceType what the resolved resource is, or {@link #ABSENT} where it has no type
     * @param selectors the selectors the platform recognised, in the order it recognised them
     * @param extension the extension, or {@link #ABSENT} where the request named none
     * @param suffix the suffix, or {@link #ABSENT} where the request named none
     * @param trace the rules it went through, which is empty where none were asked for
     */
    public record Resolution(String requestAddress, String resolvedPath, String resourceType,
                             List<String> selectors, String extension, String suffix,
                             List<String> trace) {

        /** Holds the lists apart from whatever produced them. */
        public Resolution {
            selectors = List.copyOf(selectors);
            trace = List.copyOf(trace);
        }

        /**
         * The selectors the platform recognised.
         *
         * @return the selectors, which nothing may add to
         */
        @Override
        public List<String> selectors() {
            return java.util.Collections.unmodifiableList(selectors);
        }

        /**
         * The rules this resolution went through.
         *
         * @return the rules, which nothing may add to
         */
        @Override
        public List<String> trace() {
            return java.util.Collections.unmodifiableList(trace);
        }
    }

    /**
     * The result one resolution produces.
     *
     * @param resolution what the platform did
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(Resolution resolution) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REQUEST_ADDRESS, new DocumentValue.Text(resolution.requestAddress()));
        put(result, RESOLVED_PATH, resolution.resolvedPath());
        put(result, RESOURCE_TYPE, resolution.resourceType());
        result.put(SELECTORS, new DocumentValue.Sequence(resolution.selectors().stream()
                .map(selector -> (DocumentValue) new DocumentValue.Text(selector))
                .toList()));
        put(result, EXTENSION, resolution.extension());
        put(result, SUFFIX, resolution.suffix());
        if (!resolution.trace().isEmpty()) {
            result.put(TRACE, new DocumentValue.Sequence(resolution.trace().stream()
                    .map(rule -> (DocumentValue) new DocumentValue.Text(rule))
                    .toList()));
        }
        return new DocumentValue.Mapping(result);
    }

    private static void put(SequencedMap<String, DocumentValue> result, String member,
                            String value) {
        if (!ABSENT.equals(value)) {
            result.put(member, new DocumentValue.Text(value));
        }
    }
}
