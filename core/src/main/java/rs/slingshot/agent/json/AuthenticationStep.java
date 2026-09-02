// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

/**
 * The four steps a role schema is believed in, in the order they happen.
 *
 * <p>The order is the whole point. A role schema carries an annotation naming the canonical-form
 * contract it was written under, and checking that annotation before the contract's own bytes have
 * been authenticated would let contract drift hide inside annotation drift — two different failures
 * with two different causes, one of them reported as the other. A system that authenticates
 * something in the wrong order reports success and is wrong.</p>
 */
public enum AuthenticationStep {

    /** The committed canonical-form contract's bytes against the digest committed beside them. */
    CONTRACT_BYTES,

    /** The role schema's annotation against the digest of the contract just authenticated. */
    SCHEMA_ANNOTATION,

    /** The role schema's own canonical bytes against the digest committed for it. */
    SCHEMA_DIGEST,

    /** What the schema says it describes against the command and role it is being used for. */
    IDENTITY_ASSEMBLY
}
