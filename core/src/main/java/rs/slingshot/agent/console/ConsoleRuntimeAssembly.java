// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;
import rs.slingshot.agent.route.RouteAlias;

/**
 * The installed console's datasource composition root.
 *
 * <p>Each resource type is assembled once from live suppliers. A missing supplier is represented
 * by an unavailable source, so the console cannot turn a disconnected store into an empty or
 * misleading page.</p>
 */
public final class ConsoleRuntimeAssembly {

    /** Resource type used by the operations table. */
    public static final String OPERATIONS = "slingshot-agent/datasource/operations";
    /** Resource type used by the operation detail table. */
    public static final String OPERATION = "slingshot-agent/datasource/operation";
    /** Resource type used by the maintenance table. */
    public static final String MAINTENANCE = "slingshot-agent/datasource/maintenance";
    /** Resource type used by the retention table. */
    public static final String RETENTION = "slingshot-agent/datasource/retention";
    /** Resource type used by the identity table. */
    public static final String IDENTITY = "slingshot-agent/datasource/identity";

    private ConsoleRuntimeAssembly() {
        // Utility class.
    }

    /**
     * Creates the operation-detail source for one requested operation.
     *
     * @param operationIdentifier requested operation identifier
     * @param assembly live operation snapshot reader
     * @return authorization-wrapped operation-detail source
     */
    public static ConsoleDataSource operation(String operationIdentifier,
            Function<String, OperationDetailDataSource.Assembly> assembly) {
        return new ConsoleDataSource(new OperationDetailDataSource(operationIdentifier, assembly));
    }

    /** The live readers needed to assemble the installed console.
     * @param discovery live discovery document
     * @param build live build identity
     * @param aliases live route aliases
     * @param commands live command rows
     * @param maintenance live maintenance state
     * @param retention live retention state
     * @param operations live operation listing
     * @param contract authenticated contract
     */
    public record Inputs(Supplier<AdvertisedCapabilities> discovery,
                         Supplier<BuildIdentityDataSource.Build> build,
                         Supplier<java.util.List<RouteAlias>> aliases,
                         Supplier<java.util.List<RegistryRow>> commands,
                         Supplier<MaintenanceDataSource.State> maintenance,
                         Supplier<RetentionDataSource.Retention> retention,
                         Supplier<OperationListDataSource.Listing> operations,
                         AgentContract contract) {
    }

    /**
     * Assemble the resource type map from live state readers.
     *
     * @param inputs live discovery, state readers, and authenticated contract
     * @return immutable resource type map
     */
    public static Map<String, ConsoleDataSource> assemble(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(inputs.discovery(), "discovery");
        Objects.requireNonNull(inputs.build(), "build");
        Objects.requireNonNull(inputs.aliases(), "aliases");
        Objects.requireNonNull(inputs.commands(), "commands");
        Objects.requireNonNull(inputs.maintenance(), "maintenance");
        Objects.requireNonNull(inputs.retention(), "retention");
        Objects.requireNonNull(inputs.operations(), "operations");
        Objects.requireNonNull(inputs.contract(), "contract");
        return Map.of(
                OPERATIONS, new ConsoleDataSource(new OperationListDataSource(inputs.operations())),
                MAINTENANCE, new ConsoleDataSource(new MaintenanceDataSource(inputs.maintenance())),
                RETENTION, new ConsoleDataSource(new RetentionDataSource(inputs.retention(),
                        inputs.contract())),
                IDENTITY, new ConsoleDataSource(new BuildIdentityDataSource(
                        inputs.discovery(), inputs.build(), inputs.aliases(), inputs.commands())));
    }

}
