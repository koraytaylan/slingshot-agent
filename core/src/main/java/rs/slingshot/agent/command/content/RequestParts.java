// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.List;

/**
 * The parts of a request address the platform did not use to resolve it.
 *
 * <p>Sling records them as one string beginning at the first dot after the resolved path —
 * {@code .print.html/a/b} — and this takes that record apart. It is a value with a factory rather
 * than three methods on a handler because it is the part most likely to be wrong and the part
 * cheapest to prove: a selector nobody expected is the usual reason one link renders and another
 * answers a 404, and proving that needs the parsing, not a repository.</p>
 *
 * @param selectors the selectors, in the order they appeared
 * @param extension the extension, or {@link ResolveResourcePathResult#ABSENT} where there is none
 * @param suffix the suffix, or {@link ResolveResourcePathResult#ABSENT} where there is none
 */
public record RequestParts(List<String> selectors, String extension, String suffix) {

    /** Holds the selectors apart from whatever produced them. */
    public RequestParts {
        selectors = List.copyOf(selectors);
    }

    /**
     * The selectors this request carried.
     *
     * @return the selectors, which nothing may add to
     */
    @Override
    public List<String> selectors() {
        return Collections.unmodifiableList(selectors);
    }

    /**
     * The parts one leftover record names.
     *
     * <p>An empty record means the whole address resolved, which is neither an error nor a request
     * with an empty extension: it is a request that named none.</p>
     *
     * @param leftOver what the platform did not use, as it records it
     * @return the parts
     */
    public static RequestParts of(String leftOver) {
        final int suffix = leftOver.indexOf('/');
        final String named = suffix < 0 ? leftOver : leftOver.substring(0, suffix);
        final String trailing = suffix < 0 ? ResolveResourcePathResult.ABSENT
                : leftOver.substring(suffix);
        if (named.isEmpty() || named.charAt(0) != '.') {
            return new RequestParts(List.of(), ResolveResourcePathResult.ABSENT, trailing);
        }
        final List<String> dotted = List.of(named.substring(1).split("\\.", -1));
        return new RequestParts(dotted.subList(0, dotted.size() - 1), dotted.getLast(), trailing);
    }
}
