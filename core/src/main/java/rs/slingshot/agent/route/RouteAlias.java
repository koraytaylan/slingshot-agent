// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.route;

/**
 * A second path to one route, carried for a client that has not caught up yet.
 *
 * <p>An alias is a path and nothing else. It reaches the same servlet the canonical route reaches,
 * answers byte for byte what that route answers, and has no behaviour of its own — a second
 * implementation behind a second spelling is two products with one name, and the first thing that
 * differs between them is the thing nobody tests.</p>
 *
 * <p>Every alias names the client version it exists for and the correction it is waiting on, so the
 * set has an end. An alias that outlives the client it was carried for is a permanent surface
 * somebody added temporarily, and the row is what makes the difference visible.</p>
 *
 * @param path the path the client asks for
 * @param routeName the canonical route it is a second path to
 * @param clientVersion the client version that asks for it
 * @param pendingCorrection what the client repository has to change for this row to go
 * @param reason why it is carried at all
 */
public record RouteAlias(String path, String routeName, String clientVersion,
                         String pendingCorrection, String reason) {

    /**
     * Holds an alias whose every part is stated.
     *
     * @throws IllegalArgumentException if any part is blank, because an alias with no client
     *     version and no pending correction is a path nobody has agreed to remove
     */
    public RouteAlias {
        requireStated(path, "path");
        requireStated(routeName, "route");
        requireStated(clientVersion, "client version");
        requireStated(pendingCorrection, "pending correction");
        requireStated(reason, "reason");
    }

    private static void requireStated(String value, String part) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("an alias states no " + part);
        }
    }
}
