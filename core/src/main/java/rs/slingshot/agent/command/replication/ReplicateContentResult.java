// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * How many items the platform admitted, and nothing else.
 *
 * <p>One number, and every word of it is chosen. <em>Admitted</em>, not published: what happens on
 * a publish instance happens where this side cannot see it. <em>Items</em>, not pages: what was
 * offered is a set of addresses and the queue does not care what kind of thing is at each one. And
 * a count rather than a list, because a caller who wants to know where each one got to asks the
 * queue commands, which can actually answer that.</p>
 *
 * <p>There is deliberately no member here that could be read as a claim about publication. A result
 * saying <em>published: true</em> would be the single most useful-looking and most false thing this
 * whole surface could say.</p>
 */
public final class ReplicateContentResult {

    private ReplicateContentResult() {
    }

    /** The member the count of admitted items is carried in. */
    public static final String ACCEPTED_ITEM_COUNT = "accepted_item_count";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(ACCEPTED_ITEM_COUNT);

    /**
     * The result one offer produces.
     *
     * @param acceptedItemCount how many items the platform admitted for replication
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(long acceptedItemCount) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(ACCEPTED_ITEM_COUNT, new DocumentValue.Whole(acceptedItemCount));
        return new DocumentValue.Mapping(result);
    }
}
