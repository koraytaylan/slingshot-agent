// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

/** Stored counter fixtures from before reservations carried identities. */
public final class LegacyCapacity {

    private LegacyCapacity() {
    }

    /**
     * Seeds the exact historical counts for one caller in prepared, otherwise empty counters.
     *
     * @param session the fixture session
     * @param quantity the historical quantity
     * @param caller the only historical caller for this quantity
     * @param amount the exact persisted count
     * @throws RepositoryException if fixture storage fails
     */
    public static void seed(Session session, AccountedQuantity quantity, StatePath.Caller caller,
                            long amount) throws RepositoryException {
        session.getNode(CapacityLedger.totalPath(quantity).path())
                .setProperty(ShardedCount.SHARD_PREFIX + 0, amount);
        session.getNode(CapacityLedger.callerPath(quantity, caller).path())
                .setProperty(ShardedCount.SHARD_PREFIX + 0, amount);
        session.save();
    }
}
