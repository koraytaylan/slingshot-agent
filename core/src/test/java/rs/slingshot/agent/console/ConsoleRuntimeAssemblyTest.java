// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;
import rs.slingshot.agent.identity.OperationIdentity;

final class ConsoleRuntimeAssemblyTest {

    @Test
    void assemblesLiveResourceTypesAndKeepsUnavailableStateUnreadable() {
        final AgentContract contract = ((AgentContract.Loaded) AgentContract.load()).contract();
        final Map<String, ConsoleDataSource> sources = ConsoleRuntimeAssembly.assemble(
                () -> new AdvertisedCapabilities(((rs.slingshot.agent.identity.EventStoreGeneration.Held) rs.slingshot.agent.identity.EventStoreGeneration.of(1)).generation(), rs.slingshot.agent.digest.DigestValue.ofBytes(new byte[] {1}), List.of(),
                        AdvertisedCapabilities.ContinuationAuthority.READY, rs.slingshot.agent.digest.DigestValue.ofBytes(new byte[] {2})),
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
