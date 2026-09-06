// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;

final class ConsoleRuntimeAssemblyTest {

    @Test
    void assemblesLiveResourceTypesAndKeepsUnavailableStateUnreadable() {
        final AgentContract contract = ((AgentContract.Loaded) AgentContract.load()).contract();
        final rs.slingshot.agent.identity.EventStoreGeneration generation =
                ((rs.slingshot.agent.identity.EventStoreGeneration.Held)
                        rs.slingshot.agent.identity.EventStoreGeneration.of(1)).generation();
        final rs.slingshot.agent.digest.DigestValue canonical =
                rs.slingshot.agent.digest.DigestValue.ofBytes(new byte[] {1});
        final rs.slingshot.agent.digest.DigestValue transport =
                rs.slingshot.agent.digest.DigestValue.ofBytes(new byte[] {2});
        final Map<String, ConsoleDataSource> sources = ConsoleRuntimeAssembly.assemble(
                () -> new AdvertisedCapabilities(generation, canonical, List.of(),
                        AdvertisedCapabilities.ContinuationAuthority.READY, transport),
                () -> new BuildIdentityDataSource.Build("v", "c", "row",
                        BuildIdentityDataSource.Claim.CLAIMED),
                List::of, List::of,
                () -> new MaintenanceDataSource.Unavailable("state unavailable"),
                () -> new RetentionDataSource.Retention(List.of()),
                () -> new OperationListDataSource.Unavailable("operations unavailable"),
                contract);
        assertEquals(4, sources.size());
    }
}
