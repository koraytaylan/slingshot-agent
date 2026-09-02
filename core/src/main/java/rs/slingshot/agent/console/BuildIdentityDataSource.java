// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.discovery.AdvertisedCapabilities;
import rs.slingshot.agent.route.RouteAlias;

/**
 * Which build this is, which contracts it holds, and which deployment row it thinks it is on.
 *
 * <p>An operator diagnosing a version disagreement has to read both sides. This is one of them,
 * rendered for a person rather than for a client: the two contract digests, the event-store
 * generation, whether the continuation authority can issue a token, the routes with the aliases
 * carried for clients that have not caught up, and every registered command with the fields that
 * decide how it may be called.</p>
 *
 * <p>Everything discovery answers is read from what discovery reads, and from nothing else. A page
 * with its own copy of a digest is a page that can disagree with the route a client is comparing
 * against, and the disagreement would surface as the client being wrong. So this type has no field
 * a digest, a generation, or a readiness could be put in: there is one source, and the page cannot
 * be given a second.</p>
 *
 * <p>A row this build does not claim is shown as unclaimed rather than hidden. Running somewhere
 * unclaimed is not a failure — plenty of it works — but it is the first thing worth knowing when
 * something does not.</p>
 */
public final class BuildIdentityDataSource implements ConsoleDataSource.Rows {

    private final Supplier<AdvertisedCapabilities> discovery;

    private final Supplier<Build> build;

    private final Supplier<List<RouteAlias>> aliases;

    private final Supplier<List<RegistryRow>> commands;

    /**
     * Holds one source over discovery's own answer and the few facts discovery does not carry.
     *
     * @param discovery what the discovery route reads, which is where every digest comes from
     * @param build the version, the commit, and the deployment row, which are not on the wire
     * @param aliases the aliases the route table carries, in its own order
     * @param commands the registered commands, in the registry's own order
     */
    public BuildIdentityDataSource(Supplier<AdvertisedCapabilities> discovery,
                                   Supplier<Build> build, Supplier<List<RouteAlias>> aliases,
                                   Supplier<List<RegistryRow>> commands) {
        this.discovery = discovery;
        this.build = build;
        this.aliases = aliases;
        this.commands = commands;
    }

    /**
     * What this build is, as far as anything but discovery can say.
     *
     * <p>Deliberately holds no digest, no generation and no readiness. Those are discovery's, and a
     * second copy of one is the thing this page exists to make impossible.</p>
     *
     * @param version the bundle version this instance is running
     * @param commit the commit it was built from
     * @param deploymentRow which deployment row this instance matches
     * @param claim whether this build claims that row
     */
    public record Build(String version, String commit, String deploymentRow, Claim claim) {
    }

    /** Whether this build says it was made for the deployment it finds itself on. */
    public enum Claim {
        /** It does. */
        CLAIMED,
        /**
         * It does not.
         *
         * <p>Not a failure. Plenty of it works on a row nobody claimed — but it is the first thing
         * worth knowing when something does not, and an operator has no other way to find out.</p>
         */
        UNCLAIMED
    }

    /** How the reading spells a row this build claims. */
    public static final String CLAIMED = "claimed";

    /** How it spells one this build does not. */
    public static final String UNCLAIMED = "not claimed by this build";

    /** How it spells an authority that can issue a token a later request will resolve. */
    public static final String READY = "ready";

    /** How it spells one that cannot, which is a paged query refused rather than mis-answered. */
    public static final String NOT_READY = "not ready";

    /** How an alias row is named, so every alias sorts beside the others. */
    public static final String ALIAS = "alias ";

    /** How a command row is named. */
    public static final String COMMAND = "command ";

    @Override
    public ConsoleDataSource.Answer of(ConsoleDataSource.Request request) {
        final List<MaintenanceDataSource.Reading> readings =
                readingsOf(discovery.get(), build.get(), aliases.get(), commands.get());
        return new ConsoleDataSource.Rendered(new ConsolePage<>(readings, 0,
                new ConsolePage.Counted(readings.size())));
    }

    /**
     * What one build reads as: discovery's own answer, then the aliases, then the commands.
     *
     * @param capabilities what the discovery route reads
     * @param build the facts discovery does not carry
     * @param aliases the aliases the route table carries, in its own order
     * @param commands the registered commands, in the registry's own order
     * @return the readings, in the order somebody would be asked for them
     */
    public static List<MaintenanceDataSource.Reading> readingsOf(
            AdvertisedCapabilities capabilities, Build build, List<RouteAlias> aliases,
            List<RegistryRow> commands) {
        final List<MaintenanceDataSource.Reading> readings = new ArrayList<>();
        readings.add(new MaintenanceDataSource.Reading("version", build.version()));
        readings.add(new MaintenanceDataSource.Reading("commit", build.commit()));
        readings.add(new MaintenanceDataSource.Reading("transport_contract_digest",
                capabilities.transportContractDigest().rendered()));
        readings.add(new MaintenanceDataSource.Reading("canonical_contract_digest",
                capabilities.canonicalContractDigest().rendered()));
        readings.add(new MaintenanceDataSource.Reading("event_store_generation",
                String.valueOf(capabilities.generation().number())));
        readings.add(new MaintenanceDataSource.Reading("continuation_authority",
                capabilities.authorityIsReady() ? READY : NOT_READY));
        readings.add(new MaintenanceDataSource.Reading("deployment_row", build.deploymentRow()));
        readings.add(new MaintenanceDataSource.Reading("deployment_claim",
                build.claim() == Claim.CLAIMED ? CLAIMED : UNCLAIMED));
        aliases.forEach(alias -> readings.add(new MaintenanceDataSource.Reading(
                ALIAS + alias.path(), renderedAlias(alias))));
        commands.forEach(command -> readings.add(new MaintenanceDataSource.Reading(
                COMMAND + command.wireName(), renderedCommand(command))));
        return List.copyOf(readings);
    }

    /**
     * One alias, with everything that says when it may go.
     *
     * <p>All three, always. An alias rendered without the client version it exists for or the
     * correction it waits on is a second path with no end date, which is the thing the row exists
     * to prevent.</p>
     *
     * @param alias the alias the table carries
     * @return what a person reads
     */
    private static String renderedAlias(RouteAlias alias) {
        return "canonical=" + alias.routeName() + " client_version=" + alias.clientVersion()
                + " pending_correction=" + alias.pendingCorrection();
    }

    /**
     * One command, with the fields that decide how it may be called.
     *
     * @param command the row the registry declares
     * @return what a person reads
     */
    private static String renderedCommand(RegistryRow command) {
        return "contract_version=" + command.contractVersion()
                + " access_class=" + command.accessClass()
                + " operation_key=" + command.operationKey().spelling()
                + " result_bytes=" + command.resultBytes();
    }
}
