// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.EnumSet;
import java.util.List;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.DefaultPrincipalDirectory;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.principal.PrincipalCommands;
import rs.slingshot.agent.command.principal.PrincipalHandler;
import rs.slingshot.agent.contract.AgentContract;

/**
 * The commands about the platform's own state that this bundle can run, and what its deployment
 * permits them to do.
 *
 * <p>Kept apart from the content commands because these answer two questions rather than one:
 * whether the caller may, which the repository decides through the caller's own session, and
 * whether the deployment permits the control at all, which {@link PlatformControl} decides before
 * anything is touched. A command is registered here only once something real answers its seam; a
 * handler whose platform nobody wired would be advertised to a client and fail on every call.</p>
 */
final class PlatformRegistrations {

    /**
     * The deployment row this build is made for.
     *
     * <p>{@code support/deployments.toml} names it with {@code built_for = true}, and a suite
     * compares this against that row so the two cannot drift apart.</p>
     */
    static final String BUILT_FOR = "aem-cloud-service";

    private PlatformRegistrations() {
    }

    /**
     * What the deployment this build is made for provides.
     *
     * <p>Configuration changes and bundle lifecycle are absent on purpose: that deployment does not
     * keep either, so a change written through the running platform would be accepted, reported as
     * done, and gone by the next release. Refusing it is the feature.</p>
     *
     * @return the gate every control command asks before it proceeds
     */
    static PlatformControl deploymentControl() {
        return PlatformControl.of(BUILT_FOR, EnumSet.of(ControlCapability.WORKFLOW_CONTROL,
                ControlCapability.JOB_CONTROL, ControlCapability.PRINCIPAL_ADMINISTRATION,
                ControlCapability.REPLICATION_CONTROL));
    }

    /**
     * Every platform command this bundle answers, each bound to the seam that answers it.
     *
     * @param contract the authenticated contract
     * @return the registrations, in the order they are declared
     */
    static List<CommandDispatch.Registration> registrations(AgentContract contract) {
        final PlatformControl control = deploymentControl();
        return List.of(
                principal(contract, PrincipalCommands.CREATE_USER_WIRE_NAME,
                        PrincipalHandler.Kind.USER_CREATION, control),
                principal(contract, PrincipalCommands.CREATE_GROUP_WIRE_NAME,
                        PrincipalHandler.Kind.GROUP_CREATION, control),
                principal(contract, PrincipalCommands.UPDATE_PROFILE_WIRE_NAME,
                        PrincipalHandler.Kind.PROFILE, control),
                principal(contract, PrincipalCommands.SET_DISABLED_WIRE_NAME,
                        PrincipalHandler.Kind.ACCOUNT, control),
                principal(contract, PrincipalCommands.DELETE_WIRE_NAME,
                        PrincipalHandler.Kind.REMOVAL, control),
                principal(contract, PrincipalCommands.ADD_MEMBER_WIRE_NAME,
                        PrincipalHandler.Kind.GRANT, control),
                principal(contract, PrincipalCommands.REMOVE_MEMBER_WIRE_NAME,
                        PrincipalHandler.Kind.WITHDRAWAL, control),
                principal(contract, PrincipalCommands.LIST_MEMBERS_WIRE_NAME,
                        PrincipalHandler.Kind.LISTING, control));
    }

    private static CommandDispatch.Registration principal(AgentContract contract, String wireName,
                                                         PrincipalHandler.Kind kind,
                                                         PlatformControl control) {
        return new CommandDispatch.Registration(wireName, new PrincipalHandler(contract, kind,
                DefaultPrincipalDirectory.directories(), control));
    }
}
