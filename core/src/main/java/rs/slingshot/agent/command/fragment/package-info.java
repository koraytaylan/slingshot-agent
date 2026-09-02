// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The commands that make, change and remove content fragments and experience fragments.
 *
 * <p>Both kinds are declared by something else: a content fragment by its model, an experience
 * fragment by its template. An element the model has never heard of is refused by name rather than
 * written as a loose property, because a fragment carrying properties outside its model is a
 * fragment that reads back differently through every tool that opens it — and the tool that notices
 * is usually the one somebody is demonstrating.</p>
 *
 * <p>Neither kind is written untyped. A model or template that will not resolve is a refusal rather
 * than a reason to proceed: an untyped write is the one that looks like it worked.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.fragment;
