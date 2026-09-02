// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * What a search asks about a node, said structurally.
 *
 * <p>No command in this build takes query text. A caller cannot send JCR-SQL2, XPath, or anything
 * else a repository would interpret, because a string that reaches a query engine is a string that
 * can address content the command was never pointed at. What a caller can send is this vocabulary:
 * an operator from a closed set, a property to look at, and — for the operators that need one — a
 * typed value to compare against.</p>
 *
 * <p>It lives in a package of its own because two commands share it and more will. A predicate
 * language written inside whichever command needed it first is a language the second command copies
 * and the third one subtly changes.</p>
 *
 * <p>The values it compares against are not its own: a typed property value is the same thing a
 * mutation writes, so it lives beside neither and is shared by both.</p>
 *
 * <p>Nothing here issues a query. A predicate is applied to rows a command's own indexed query has
 * already returned, bounded by the caller's examination budget — which is the split that lets a
 * caller ask about a property nobody indexed without that question becoming a walk of somebody's
 * repository.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.search;
