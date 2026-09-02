// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The eight commands about who may use this instance and what they may do.
 *
 * <p>Group membership is how every permission in this product is granted, including the permission
 * to use this agent at all. That makes this the package where a mistake compounds: everything else
 * here can be undone by somebody with access, and a mistake here can be the reason nobody has
 * access.</p>
 *
 * <p>Three rules run through all eight. No password is ever set, read, or carried — an agent that
 * could set one is an agent that can become anybody. Every change goes through the caller's own
 * session, so the repository refuses exactly what it would have refused had they done it by hand.
 * And a group with members is not deleted: cascading would remove permissions from people who are
 * not in the request and have no idea it happened.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.principal;
