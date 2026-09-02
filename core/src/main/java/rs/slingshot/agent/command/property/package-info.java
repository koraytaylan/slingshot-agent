// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * What one repository property value is, said the same way wherever it is said.
 *
 * <p>A caller compares against a value when they search and writes one when they mutate, and it is
 * the same value either way: a kind and the text of it. The client's own schemas declare one shape
 * for both, so this build holds one type for both — a searching copy and a writing copy would agree
 * until the day one of them learned a new kind.</p>
 *
 * <p>The kind is carried rather than inferred. {@code "1"} the string and {@code 1} the whole number
 * are different properties, and a repository that stored one and was asked about the other should
 * say so rather than quietly matching nothing.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.property;
