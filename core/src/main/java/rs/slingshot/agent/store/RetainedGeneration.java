// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * An incarnation this store no longer serves and still answers about.
 *
 * <p>A client reconciling work it submitted before a rotation has to be able to read what happened
 * to it. It must never be able to add to it: a retained generation is a record of something that is
 * over, and a write into one would be work nothing is serving.</p>
 *
 * @param generation the incarnation
 * @param retainedUntilUnixMilliseconds when it stops being answered about at all
 */
public record RetainedGeneration(EventStoreGeneration generation,
                                 long retainedUntilUnixMilliseconds) {

    /**
     * Whether this generation may still be dropped.
     *
     * @param nowUnixMilliseconds what this side's clock says
     * @return whether everything it holds is now past being kept
     */
    public boolean mayBeDropped(long nowUnixMilliseconds) {
        return nowUnixMilliseconds >= retainedUntilUnixMilliseconds;
    }
}
