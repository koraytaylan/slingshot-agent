// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The console an operator finds under Tools, and the data sources that fill it.
 *
 * <p>Everything built before this is answerable only by a client that already knows what to ask,
 * which is the wrong shape for the two people who most need an answer: the operator who has just
 * installed this and has no idea whether it works, and whoever is looking at an operation that went
 * wrong and has an identifier and a question. The second one's answer is spread across four stores
 * that only this repository knows how to read, and without a console the answer is "check the
 * logs".</p>
 *
 * <p>One inversion runs through the whole package and it is worth stating plainly. The stores live
 * where no person's session can reach them, so the data sources read them as the service user —
 * which means the reading cannot also be what decides whether the reading should happen. Authority
 * is decided first, from the person's own session and the groups an operator permitted, and only
 * then is anything read.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.console;
