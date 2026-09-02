// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Optional;
import rs.slingshot.agent.store.StatePath;

/**
 * Who is asking, as the platform established it, and nothing else about them.
 *
 * <p>What this deliberately does not hold: a credential, a token, a header, a session, or anything
 * else a request arrived with. This repository establishes no identity — the platform does that —
 * and the only thing it has any business carrying past the gate is the name the platform decided
 * on. A type that also carried what proved that name would be a type somebody would eventually
 * log.</p>
 *
 * @param authorizable the caller's own authorizable identifier
 */
public record CallerIdentity(String authorizable) {

    /**
     * The caller as the store counts them, where the name is one a path may be built from.
     *
     * <p>Capacity is accounted per caller, and a caller's counters live at a path derived from
     * their name. A name a path cannot hold is therefore a caller nothing can count, which is a
     * refusal rather than a caller counted as somebody else.</p>
     *
     * @return the caller, or nothing where the name is not one the store can count
     */
    public Optional<StatePath.Caller> counted() {
        final StatePath.Outcome held = StatePath.caller(authorizable);
        return held instanceof final StatePath.Held caller
                ? Optional.of(caller.caller())
                : Optional.empty();
    }
}
