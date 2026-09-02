// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The command that offers content to replication, and the vocabulary it needs.
 *
 * <p>An author instance cannot observe a publish instance. What happens after content is handed to
 * the replication service happens somewhere this side cannot see, on a schedule this side does not
 * control, and a command that reported content as <em>published</em> would be claiming something it
 * has no way to know. So this reports an admission: how many items the platform accepted for
 * replication, and nothing about what became of them.</p>
 *
 * <p>Which is why this is the one mutation that owes no commit. It changes a queue that belongs to
 * the platform rather than a repository that belongs to the caller, and a wrapper that demanded a
 * commit from it would be demanding a write nobody asked for.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.replication;
