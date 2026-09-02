// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The root package of the Slingshot agent's Sling-only bundle.
 *
 * <p>Everything under this root resolves against a plain Apache Sling runtime: the transport, the
 * durable store, the canonical bytes, and the execution framework. Nothing here imports a
 * {@code com.day.cq} or {@code com.adobe.granite} package, which is what lets the whole protocol
 * surface be proved on a public container image with no licensed input.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent;
