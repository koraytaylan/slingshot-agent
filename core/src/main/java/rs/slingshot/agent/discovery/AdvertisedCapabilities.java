// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.discovery;

import java.util.Collections;
import java.util.List;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * What this agent tells a client it is.
 *
 * <p>The shape is the client's, field for field, because discovery is the one exchange whose answer
 * was fully specified before this side existed. A client reads this document, compares it with what
 * it requires, and reports one distinct reason per thing that differs — so each thing that can
 * differ is a separate member: a transport disagreement is a version problem, a command-contract
 * disagreement is a build problem, and a changed generation is a store that was rebuilt underneath
 * rows that refer to it.</p>
 *
 * @param generation which incarnation of its event store this agent is serving
 * @param canonicalContractDigest the canonical-byte contract its schemas are written under
 * @param commandContracts the command contracts it holds, in wire order
 * @param continuationAuthority whether its continuation-key authority can issue and validate
 * @param transportContractDigest the transport contract it speaks
 */
public record AdvertisedCapabilities(EventStoreGeneration generation,
                                     DigestValue canonicalContractDigest,
                                     List<CommandContractIdentity> commandContracts,
                                     ContinuationAuthority continuationAuthority,
                                     DigestValue transportContractDigest) {

    /** Whether the continuation-key authority can issue and validate a token. */
    public enum ContinuationAuthority {
        /** It can, so a paged query can hand out a token a later request will resolve. */
        READY,
        /** It cannot, so a paged query is refused rather than answered with a token nothing
         * honours. */
        NOT_READY
    }

    /**
     * Where readiness is observed, so it is a value read at the moment of asking.
     *
     * <p>An agent whose authority stopped being ready while a client held an old answer would be an
     * agent advertising something that has stopped being true. Nothing here caches it.</p>
     */
    @FunctionalInterface
    public interface Readiness {

        /**
         * Whether the authority can issue and validate a token right now.
         *
         * @return what was observed
         */
        ContinuationAuthority observe();
    }

    /** Holds a document whose command contracts nothing can change afterwards. */
    public AdvertisedCapabilities {
        commandContracts = List.copyOf(commandContracts);
    }

    /**
     * The command contracts this agent holds.
     *
     * @return the identities, as a view nothing can change
     */
    @Override
    public List<CommandContractIdentity> commandContracts() {
        return Collections.unmodifiableList(commandContracts);
    }

    /**
     * Whether the continuation-key authority is ready.
     *
     * @return whether a paged query can be answered with a token
     */
    public boolean authorityIsReady() {
        return continuationAuthority == ContinuationAuthority.READY;
    }
}
