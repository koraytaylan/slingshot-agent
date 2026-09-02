// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The container harness every interoperability tier is built on.
 *
 * <p>It drives the engine rootlessly through a process wrapper this repository owns and takes no
 * container-orchestration test dependency. Every suite depends on it behaving, so it is code
 * somebody here can read; and a dependency that reached a daemon socket would bring an ambient
 * requirement with it, which is the opposite of what a hermetic tier is for.</p>
 *
 * <p>It starts one instance or two against one shared repository. The second arrangement is not a
 * refinement: an author on the row this product is built for is a cluster, and every property about
 * contention is one a single instance cannot exhibit.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.interop.harness;
