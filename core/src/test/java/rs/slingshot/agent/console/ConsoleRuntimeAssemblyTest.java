// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;

final class ConsoleRuntimeAssemblyTest {

    @Test
    void rejectsDisconnectedAssemblyInputsImmediately() {
        final ConsoleRuntimeAssembly.Inputs inputs = new ConsoleRuntimeAssembly.Inputs(
                (Supplier<AdvertisedCapabilities>) null,
                () -> new BuildIdentityDataSource.Build("v", "c", "r",
                        BuildIdentityDataSource.Claim.CLAIMED),
                (Supplier<List<rs.slingshot.agent.route.RouteAlias>>) List::of,
                (Supplier<List<rs.slingshot.agent.command.RegistryRow>>) List::of,
                () -> new MaintenanceDataSource.Unavailable("missing"),
                () -> new RetentionDataSource.Retention(List.of()),
                () -> new OperationListDataSource.Unavailable("missing"),
                List::of, null);
        assertThrows(NullPointerException.class, () -> ConsoleRuntimeAssembly.assemble(inputs));
    }

    @Test
    void assemblesLiveResourceTypesAndKeepsUnavailableStateUnreadable() {
        final AgentContract contract = ((AgentContract.Loaded) AgentContract.load()).contract();
        final rs.slingshot.agent.identity.EventStoreGeneration generation =
                ((rs.slingshot.agent.identity.EventStoreGeneration.Held)
                        rs.slingshot.agent.identity.EventStoreGeneration.of(1)).generation();
        final rs.slingshot.agent.digest.DigestValue canonical =
                rs.slingshot.agent.digest.DigestValue.ofBytes(new byte[32]);
        final rs.slingshot.agent.digest.DigestValue transport =
                rs.slingshot.agent.digest.DigestValue.ofBytes(new byte[32]);
        final ConsoleRuntimeAssembly.Inputs inputs = new ConsoleRuntimeAssembly.Inputs(
                () -> new AdvertisedCapabilities(generation, canonical, List.of(),
                        AdvertisedCapabilities.ContinuationAuthority.READY, transport),
                () -> new BuildIdentityDataSource.Build("v", "c", "row",
                        BuildIdentityDataSource.Claim.CLAIMED),
                List::of, List::of,
                () -> new MaintenanceDataSource.Unavailable("state unavailable"),
                () -> new RetentionDataSource.Retention(List.of()),
                () -> new OperationListDataSource.Unavailable("operations unavailable"),
                List::of,
                contract);
        final Map<String, ConsoleDataSource> sources = ConsoleRuntimeAssembly.assemble(inputs);
        assertEquals(5, sources.size());
        assertEquals(ConsoleDataSource.class, ConsoleRuntimeAssembly.operation("op",
                ignored -> new OperationDetailDataSource.Unavailable("unavailable")).getClass());
    }
}
