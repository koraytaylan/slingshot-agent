// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * What offers addresses to replication and says what the platform did with them.
 *
 * <p>A seam, and it exists for a reason bigger than testing. The replication service is Adobe's,
 * and this bundle is the one that runs on plain Apache Sling; everything about which items are
 * offered — who may read them, how far the walk goes, what the bounds are — is decided here, and
 * only the handing over happens on the other side of this interface. That is the whole of the
 * platform dependency this command has.</p>
 *
 * <p>Three answers and not two. An offer that left this process without an answer is not a failure:
 * the platform may well have taken it, and telling a caller it failed would have them offer the
 * same subtree again.</p>
 */
@FunctionalInterface
public interface ContentAdmission {

    /** What the platform did with an offer. */
    sealed interface Outcome permits Admitted, Rejected, Unknown {
    }

    /**
     * It took them.
     *
     * @param acceptedItemCount how many it admitted
     */
    record Admitted(long acceptedItemCount) implements Outcome {
    }

    /**
     * It refused them, and said why.
     *
     * @param detail what it said, as somebody would act on it
     */
    record Rejected(String detail) implements Outcome {
    }

    /**
     * Nobody knows, which is not the same as no.
     *
     * @param detail what was seen before the answer stopped coming
     */
    record Unknown(String detail) implements Outcome {
    }

    /**
     * Offers the addresses.
     *
     * @param paths what to offer, already bounded and already known readable by this caller
     * @param session the caller's own session, so the platform acts as they would
     * @return what the platform did
     */
    Outcome offer(List<String> paths, ResourceResolver session);
}
