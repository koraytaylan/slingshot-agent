// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.EnumSet;
import java.util.List;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.framework.FrameworkHandler;
import rs.slingshot.agent.command.framework.ListBundlesCommand;
import rs.slingshot.agent.command.framework.ListComponentsCommand;
import rs.slingshot.agent.command.framework.SetBundleStateCommand;
import rs.slingshot.agent.command.job.JobCommands;
import rs.slingshot.agent.command.job.JobHandler;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.ControlCapability;
import rs.slingshot.agent.command.platform.DefaultPrincipalDirectory;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.PlatformControl;
import rs.slingshot.agent.command.platform.WorkflowService;
import rs.slingshot.agent.command.principal.PrincipalCommands;
import rs.slingshot.agent.command.principal.PrincipalHandler;
import rs.slingshot.agent.command.replication.ReplicateContentCommand;
import rs.slingshot.agent.command.replication.ReplicateContentHandler;
import rs.slingshot.agent.command.workflow.FindWorkflowInstancesCommand;
import rs.slingshot.agent.command.workflow.ListWorkflowModelsCommand;
import rs.slingshot.agent.command.workflow.StartWorkflowCommand;
import rs.slingshot.agent.command.workflow.WorkflowHandler;
import rs.slingshot.agent.command.workflow.WorkflowInstanceCommand;
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
        return registrations(contract, PlatformSeams.NONE);
    }

    /**
     * Every platform command this bundle answers with the adapters another bundle bound.
     *
     * <p>The user and group commands need no adapter beyond the caller's own session, so they are
     * always registered. The rest are registered exactly when something answers their seam.</p>
     *
     * @param contract the authenticated contract
     * @param seams the adapters bound, each list empty where nothing provides it
     * @return the registrations, in the order they are declared
     */
    static List<CommandDispatch.Registration> registrations(AgentContract contract,
                                                           PlatformSeams seams) {
        final PlatformControl control = deploymentControl();
        final List<CommandDispatch.Registration> registered =
                new java.util.ArrayList<>(principals(contract, control));
        seams.jobs().forEach(inventory -> registered.addAll(jobs(contract, inventory, control)));
        seams.admissions().forEach(admission -> registered.add(new CommandDispatch.Registration(
                ReplicateContentCommand.WIRE_NAME, new ReplicateContentHandler(contract,
                        admission))));
        seams.workflows().forEach(workflows ->
                registered.addAll(workflows(contract, workflows, control)));
        seams.bundles().forEach(inventory ->
                registered.addAll(bundles(contract, inventory, control)));
        return List.copyOf(registered);
    }

    private static List<CommandDispatch.Registration> bundles(AgentContract contract,
                                                             BundleInventory inventory,
                                                             PlatformControl control) {
        return List.of(
                new CommandDispatch.Registration(ListBundlesCommand.WIRE_NAME,
                        new FrameworkHandler(contract, FrameworkHandler.Kind.BUNDLES, inventory,
                                control)),
                new CommandDispatch.Registration(ListComponentsCommand.WIRE_NAME,
                        new FrameworkHandler(contract, FrameworkHandler.Kind.COMPONENTS, inventory,
                                control)),
                new CommandDispatch.Registration(SetBundleStateCommand.WIRE_NAME,
                        new FrameworkHandler(contract, FrameworkHandler.Kind.TRANSITION, inventory,
                                control)));
    }

    private static List<CommandDispatch.Registration> workflows(AgentContract contract,
                                                               WorkflowService workflows,
                                                               PlatformControl control) {
        return List.of(
                workflow(contract, ListWorkflowModelsCommand.WIRE_NAME, WorkflowHandler.Kind.MODELS,
                        workflows, control),
                workflow(contract, StartWorkflowCommand.WIRE_NAME, WorkflowHandler.Kind.START,
                        workflows, control),
                workflow(contract, FindWorkflowInstancesCommand.WIRE_NAME,
                        WorkflowHandler.Kind.INSTANCES, workflows, control),
                workflow(contract, WorkflowInstanceCommand.INSPECT_WIRE_NAME,
                        WorkflowHandler.Kind.INSPECTION, workflows, control),
                workflow(contract, WorkflowInstanceCommand.TERMINATE_WIRE_NAME,
                        WorkflowHandler.Kind.TERMINATION, workflows, control),
                workflow(contract, WorkflowInstanceCommand.SUSPEND_WIRE_NAME,
                        WorkflowHandler.Kind.SUSPENSION, workflows, control));
    }

    private static CommandDispatch.Registration workflow(AgentContract contract, String wireName,
                                                        WorkflowHandler.Kind kind,
                                                        WorkflowService workflows,
                                                        PlatformControl control) {
        return new CommandDispatch.Registration(wireName, new WorkflowHandler(contract, kind,
                workflows, control));
    }

    private static List<CommandDispatch.Registration> jobs(AgentContract contract,
                                                          JobInventory inventory,
                                                          PlatformControl control) {
        return List.of(
                job(contract, JobCommands.QUEUES_WIRE_NAME, JobHandler.Kind.QUEUES, inventory,
                        control),
                job(contract, JobCommands.JOBS_WIRE_NAME, JobHandler.Kind.JOBS, inventory,
                        control),
                job(contract, JobCommands.INSPECT_WIRE_NAME, JobHandler.Kind.INSPECTION,
                        inventory, control),
                job(contract, JobCommands.CANCEL_WIRE_NAME, JobHandler.Kind.CANCELLATION,
                        inventory, control));
    }

    private static CommandDispatch.Registration job(AgentContract contract, String wireName,
                                                   JobHandler.Kind kind, JobInventory inventory,
                                                   PlatformControl control) {
        return new CommandDispatch.Registration(wireName, new JobHandler(contract, kind,
                inventory, control));
    }

    private static List<CommandDispatch.Registration> principals(AgentContract contract,
                                                                 PlatformControl control) {
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
