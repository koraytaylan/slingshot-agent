// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.command.StagingArea;
import rs.slingshot.agent.command.asset.AssetMutationHandler;
import rs.slingshot.agent.command.asset.CreateAssetCommand;
import rs.slingshot.agent.command.asset.CreateAssetFolderCommand;
import rs.slingshot.agent.command.asset.DeleteAssetCommand;
import rs.slingshot.agent.command.asset.MoveAssetCommand;
import rs.slingshot.agent.command.asset.UpdateAssetMetadataCommand;
import rs.slingshot.agent.command.component.AddComponentCommand;
import rs.slingshot.agent.command.component.AddComponentHandler;
import rs.slingshot.agent.command.component.ComponentPathCommand;
import rs.slingshot.agent.command.component.ComponentPathHandler;
import rs.slingshot.agent.command.component.DeleteComponentCommand;
import rs.slingshot.agent.command.component.ReorderComponentCommand;
import rs.slingshot.agent.command.component.UpdateComponentCommand;
import rs.slingshot.agent.command.content.AuthoringCatalogHandler;
import rs.slingshot.agent.command.content.ChildListingHandler;
import rs.slingshot.agent.command.content.ComponentInstancesCommand;
import rs.slingshot.agent.command.content.ContentCatalogHandler;
import rs.slingshot.agent.command.content.DiscoveryRegistry;
import rs.slingshot.agent.command.content.DownloadContentPackageCommand;
import rs.slingshot.agent.command.content.DownloadContentPackageHandler;
import rs.slingshot.agent.command.content.FindAssetsByMetadataCommand;
import rs.slingshot.agent.command.content.FindAssetsByMetadataHandler;
import rs.slingshot.agent.command.content.FindAssetsReferencedByPageCommand;
import rs.slingshot.agent.command.content.FindAssetsReferencedByPageHandler;
import rs.slingshot.agent.command.content.FindPagesByTemplateCommand;
import rs.slingshot.agent.command.content.FindPagesByTemplateHandler;
import rs.slingshot.agent.command.content.FindPagesContainingPhraseCommand;
import rs.slingshot.agent.command.content.FindPagesContainingPhraseHandler;
import rs.slingshot.agent.command.content.FindPagesUsingComponentsCommand;
import rs.slingshot.agent.command.content.FindPagesUsingComponentsHandler;
import rs.slingshot.agent.command.content.ListAssetRenditionsCommand;
import rs.slingshot.agent.command.content.ListAssetRenditionsHandler;
import rs.slingshot.agent.command.content.ListChildNodesByTypeCommand;
import rs.slingshot.agent.command.content.ListChildNodesCommand;
import rs.slingshot.agent.command.content.ListChildPagesCommand;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
import rs.slingshot.agent.command.content.ListComponentDefinitionsCommand;
import rs.slingshot.agent.command.content.ListContentFragmentModelsCommand;
import rs.slingshot.agent.command.content.ListContentFragmentsCommand;
import rs.slingshot.agent.command.content.ListExperienceFragmentsCommand;
import rs.slingshot.agent.command.content.ListPageTemplatesCommand;
import rs.slingshot.agent.command.content.ListResourceMappingsCommand;
import rs.slingshot.agent.command.content.ListResourceMappingsHandler;
import rs.slingshot.agent.command.content.LoadContentHandler;
import rs.slingshot.agent.command.content.MapResourcePathCommand;
import rs.slingshot.agent.command.content.MapResourcePathHandler;
import rs.slingshot.agent.command.content.QueryPathsCommand;
import rs.slingshot.agent.command.content.QueryPathsHandler;
import rs.slingshot.agent.command.content.ReadContentFragmentCommand;
import rs.slingshot.agent.command.content.ReadContentFragmentHandler;
import rs.slingshot.agent.command.content.ResolveResourcePathCommand;
import rs.slingshot.agent.command.content.ResolveResourcePathHandler;
import rs.slingshot.agent.command.fragment.CreateContentFragmentCommand;
import rs.slingshot.agent.command.fragment.CreateExperienceFragmentCommand;
import rs.slingshot.agent.command.fragment.FragmentDeletion;
import rs.slingshot.agent.command.fragment.FragmentMutationHandler;
import rs.slingshot.agent.command.fragment.UpdateContentFragmentCommand;
import rs.slingshot.agent.command.fragment.UpdateExperienceFragmentCommand;
import rs.slingshot.agent.command.page.CreatePageCommand;
import rs.slingshot.agent.command.page.CreatePageHandler;
import rs.slingshot.agent.command.page.DeletePageCommand;
import rs.slingshot.agent.command.page.DeletePageHandler;
import rs.slingshot.agent.command.page.MovePageCommand;
import rs.slingshot.agent.command.page.MovePageHandler;
import rs.slingshot.agent.command.page.UpdatePageCommand;
import rs.slingshot.agent.command.page.UpdatePageHandler;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.ConfigurationCatalogues;
import rs.slingshot.agent.command.platform.ContentAdmission;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.command.platform.WorkflowService;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.DefaultContinuationKeyAuthority;
import rs.slingshot.agent.wire.CommandFailure;

/** Adapts a verified command dispatch to the submission servlet's execution contract. */
@Component(service = CommandRuntime.class, immediate = true)
public final class DefaultCommandRuntime implements CommandRuntime {

    private static final String ARGUMENTS = SubmitServlet.ARGUMENTS;
    private static final long serialVersionUID = 1L;
    /** Runtime state, which is temporarily replaced by the fail-closed state during serialization. */
    private final AtomicReference<State> state = new AtomicReference<>(Missing.INSTANCE);

    /**
     * The platform adapters another bundle provides, which are replaced by none during
     * serialization.
     *
     * <p>Bound before activation and changed only by rebinding, which reactivates this component,
     * so the command set a runtime advertises is always the set its bound adapters can answer.</p>
     */
    private final AtomicReference<PlatformSeams> seams =
            new AtomicReference<>(PlatformSeams.NONE);

    private sealed interface State extends Serializable permits Active, Missing {
    }

    private record Active(CommandDispatch dispatch, AgentContract contract,
            DiscoveryRegistry discovery) implements State {
    }

    private enum Missing implements State {
        INSTANCE
    }

    /**
     * Holds a dispatch and the contract used to bound its argument reader.
     * @param dispatch the validated registry/handler dispatch
     * @param contract the authenticated contract
     */
    public DefaultCommandRuntime(CommandDispatch dispatch, AgentContract contract) {
        state.set(new Active(java.util.Objects.requireNonNull(dispatch, "dispatch"),
                java.util.Objects.requireNonNull(contract, "contract"), new DiscoveryRegistry(contract)));
    }

    /** Creates the fail-closed runtime before declarative-services activation. */
    public DefaultCommandRuntime() {
    }

    /** The directory inside this bundle's own data area each package build is given a room in. */
    private static final String STAGING = "staging";

    /** The directory under the runtime's own temporary area used where there is no data area. */
    private static final String TEMPORARY_STAGING = "slingshot-agent-staging";

    /**
     * Loads and activates every handler this bundle can answer, including the one that needs room
     * to work, which is given rooms inside this bundle's own data area.
     *
     * <p>A framework may keep no data area for a bundle, and a managed platform may not say why.
     * The rooms then go under the runtime's own temporary area instead: each is still this
     * command's alone, bounded by its row and given back however the run ends, so the only thing
     * that changes is which directory holds them.</p>
     *
     * @param context this bundle's own context, through which its data area is reached
     */
    @Activate
    public void activated(BundleContext context) {
        final java.io.File area = context.getDataFile(STAGING);
        activate(java.util.List.of(area == null ? Path.of(System.getProperty("java.io.tmpdir",
                "."), TEMPORARY_STAGING) : area.toPath()));
    }

    /**
     * Loads and activates the handlers that need no room to work, which is every handler but the
     * package build: a runtime with no data area has nowhere to give one.
     */
    public void activate() {
        activate(java.util.List.of());
    }

    /**
     * Loads and activates every handler that can run with the staging directories given.
     *
     * @param staging where package builds are given rooms, as a list of at most one that is empty
     *     where there is nowhere
     */
    private void activate(java.util.List<Path> staging) {
        final AgentContract.Outcome loaded = AgentContract.load();
        if (!(loaded instanceof final AgentContract.Loaded present)) {
            return;
        }
        final CommandRegistry.Outcome rows = CommandRegistry.read();
        if (!(rows instanceof final CommandRegistry.Loaded embedded)) {
            return;
        }
        try (var activation = new DiscoveryActivation(present.contract())) {
            activateDispatch(present.contract(), embedded.registry(), staging, activation);
        }
    }

    private void activateDispatch(AgentContract contract, CommandRegistry registry,
                                    java.util.List<Path> staging, DiscoveryActivation activation) {
        final java.util.List<CommandDispatch.Registration> registrations =
                java.util.stream.Stream.of(registrations(contract, activation.discovery),
                        PlatformRegistrations.registrations(contract, seams.get()),
                        staged(contract, registry, staging))
                        .flatMap(java.util.List::stream).toList();
        final CommandRegistry.Outcome active = registry.active(registrations.stream()
                .map(CommandDispatch.Registration::wireName).toList());
        if (!(active instanceof final CommandRegistry.Loaded selected)) {
            return;
        }
        final CommandDispatch.Outcome dispatch = CommandDispatch.from(selected.registry(), registrations);
        if (dispatch instanceof final CommandDispatch.Held ready) {
            activation.discovery.start();
            final State previous = state.getAndSet(new Active(ready.dispatch(), contract,
                    activation.discovery));
            activation.retained.set(true);
            closeDiscovery(previous);
        }
    }

    private static final class DiscoveryActivation implements AutoCloseable {
        private final DiscoveryRegistry discovery;
        private final AtomicBoolean retained = new AtomicBoolean();

        private DiscoveryActivation(AgentContract contract) {
            this.discovery = new DiscoveryRegistry(contract);
        }

        @Override
        public void close() {
            if (!retained.get()) {
                discovery.close();
            }
        }
    }

    /**
     * Binds the job inventory the platform bundle provides.
     *
     * @param inventory what answers the four job commands
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY, unbind = "jobsUnavailable")
    public void jobsAvailable(JobInventory inventory) {
        seams.updateAndGet(held -> held.withJobs(java.util.List.of(inventory)));
    }

    /**
     * Unbinds the job inventory, after which the job commands are not advertised.
     *
     * @param inventory the inventory going away
     */
    public void jobsUnavailable(JobInventory inventory) {
        seams.updateAndGet(held -> held.withJobs(java.util.List.of()));
    }

    /**
     * Binds what offers content to the platform's replication service.
     *
     * @param admission what the replication command offers content through
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY, unbind = "admissionUnavailable")
    public void admissionAvailable(ContentAdmission admission) {
        seams.updateAndGet(held -> held.withAdmissions(java.util.List.of(admission)));
    }

    /**
     * Unbinds the replication admission, after which the replication command is not advertised.
     *
     * @param admission the admission going away
     */
    public void admissionUnavailable(ContentAdmission admission) {
        seams.updateAndGet(held -> held.withAdmissions(java.util.List.of()));
    }

    /**
     * Binds the workflow service the platform bundle provides.
     *
     * @param workflows what answers the six workflow commands
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY, unbind = "workflowsUnavailable")
    public void workflowsAvailable(WorkflowService workflows) {
        seams.updateAndGet(held -> held.withWorkflows(java.util.List.of(workflows)));
    }

    /**
     * Unbinds the workflow service, after which the workflow commands are not advertised.
     *
     * @param workflows the service going away
     */
    public void workflowsUnavailable(WorkflowService workflows) {
        seams.updateAndGet(held -> held.withWorkflows(java.util.List.of()));
    }

    /**
     * Binds the bundle inventory the platform bundle provides.
     *
     * @param bundles what answers the three bundle and component commands
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY, unbind = "bundlesUnavailable")
    public void bundlesAvailable(BundleInventory bundles) {
        seams.updateAndGet(held -> held.withBundles(java.util.List.of(bundles)));
    }

    /**
     * Unbinds the bundle inventory, after which the bundle and component commands are not
     * advertised.
     *
     * @param bundles the inventory going away
     */
    public void bundlesUnavailable(BundleInventory bundles) {
        seams.updateAndGet(held -> held.withBundles(java.util.List.of()));
    }

    /**
     * Binds the replication agent inventory the platform bundle provides.
     *
     * @param agents what answers the five replication agent and queue commands
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY, unbind = "agentsUnavailable")
    public void agentsAvailable(ReplicationInventory agents) {
        seams.updateAndGet(held -> held.withAgents(java.util.List.of(agents)));
    }

    /**
     * Unbinds the replication agent inventory, after which the agent commands are not advertised.
     *
     * @param agents the inventory going away
     */
    public void agentsUnavailable(ReplicationInventory agents) {
        seams.updateAndGet(held -> held.withAgents(java.util.List.of()));
    }

    /**
     * Binds the configuration catalogues the platform bundle provides.
     *
     * @param configurations what answers the four configuration commands
     */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY, unbind = "configurationsUnavailable")
    public void configurationsAvailable(ConfigurationCatalogues configurations) {
        seams.updateAndGet(held -> held.withConfigurations(java.util.List.of(configurations)));
    }

    /**
     * Unbinds the configuration catalogues, after which the configuration commands are not
     * advertised.
     *
     * @param configurations the catalogues going away
     */
    public void configurationsUnavailable(ConfigurationCatalogues configurations) {
        seams.updateAndGet(held -> held.withConfigurations(java.util.List.of()));
    }

    /** Revokes the runtime before the DS component is released. */
    @Deactivate
    public void deactivate() {
        closeDiscovery(state.getAndSet(Missing.INSTANCE));
    }

    private static void closeDiscovery(State previous) {
        if (previous instanceof final Active active) {
            active.discovery().close();
        }
    }

    /**
     * The package build, where there is somewhere to give it room and its row declares how much.
     *
     * <p>Each run is given a room of its own, named so no two runs share one, and a room that
     * cannot be opened is an empty answer the handler reports under its own declared category.</p>
     */
    private static java.util.List<CommandDispatch.Registration> staged(AgentContract contract,
                                                                     CommandRegistry registry,
                                                                     java.util.List<Path> staging) {
        final Optional<RegistryRow> row = registry.rows().stream()
                .filter(held -> DownloadContentPackageCommand.WIRE_NAME.equals(held.wireName()))
                .findFirst();
        if (staging.isEmpty() || row.isEmpty()) {
            return java.util.List.of();
        }
        final Path under = staging.getFirst();
        final RegistryRow declared = row.get();
        return java.util.List.of(new CommandDispatch.Registration(
                DownloadContentPackageCommand.WIRE_NAME, new DownloadContentPackageHandler(contract,
                        () -> roomUnder(under, declared))));
    }

    private static Optional<StagingArea> roomUnder(Path under, RegistryRow row) {
        try {
            return StagingArea.forRow(under.resolve(java.util.UUID.randomUUID().toString()), row);
        } catch (final java.io.UncheckedIOException unwritable) {
            return Optional.empty();
        }
    }

    private static java.util.List<CommandDispatch.Registration> registrations(AgentContract contract,
                                                                              DiscoveryRegistry discovery) {
        return java.util.List.of(
                new CommandDispatch.Registration(FindAssetsByMetadataCommand.WIRE_NAME,
                        new FindAssetsByMetadataHandler(contract, discovery)),
                new CommandDispatch.Registration(FindAssetsReferencedByPageCommand.WIRE_NAME,
                        new FindAssetsReferencedByPageHandler(contract)),
                new CommandDispatch.Registration(FindPagesByTemplateCommand.WIRE_NAME,
                        new FindPagesByTemplateHandler(contract)),
                new CommandDispatch.Registration(FindPagesContainingPhraseCommand.WIRE_NAME,
                        new FindPagesContainingPhraseHandler(contract, discovery)),
                new CommandDispatch.Registration(FindPagesUsingComponentsCommand.WIRE_NAME,
                        new FindPagesUsingComponentsHandler(contract, discovery)),
                new CommandDispatch.Registration(ListAssetRenditionsCommand.WIRE_NAME,
                        new ListAssetRenditionsHandler(contract)),
                new CommandDispatch.Registration(ListChildNodesCommand.WIRE_NAME,
                        new ChildListingHandler(contract)),
                new CommandDispatch.Registration(ListChildNodesByTypeCommand.WIRE_NAME,
                        new ChildListingHandler(contract)),
                new CommandDispatch.Registration(ListChildPagesCommand.WIRE_NAME,
                        new ListChildPagesHandler(contract)),
                new CommandDispatch.Registration(ListPageTemplatesCommand.WIRE_NAME,
                        new AuthoringCatalogHandler(contract,
                                AuthoringCatalogHandler.Kind.PAGE_TEMPLATES)),
                new CommandDispatch.Registration(ListContentFragmentModelsCommand.WIRE_NAME,
                        new AuthoringCatalogHandler(contract,
                                AuthoringCatalogHandler.Kind.FRAGMENT_MODELS)),
                new CommandDispatch.Registration(ListComponentDefinitionsCommand.WIRE_NAME,
                        new ContentCatalogHandler(contract,
                                ContentCatalogHandler.Kind.COMPONENT_DEFINITIONS, discovery)),
                new CommandDispatch.Registration(ComponentInstancesCommand.WIRE_NAME,
                        new ContentCatalogHandler(contract, ContentCatalogHandler.Kind.COMPONENTS,
                        discovery)),
                new CommandDispatch.Registration(ListContentFragmentsCommand.WIRE_NAME,
                        new ContentCatalogHandler(contract,
                                ContentCatalogHandler.Kind.CONTENT_FRAGMENTS, discovery)),
                new CommandDispatch.Registration(ListExperienceFragmentsCommand.WIRE_NAME,
                        new ContentCatalogHandler(contract,
                                ContentCatalogHandler.Kind.EXPERIENCE_FRAGMENTS, discovery)),
                new CommandDispatch.Registration(ListResourceMappingsCommand.WIRE_NAME,
                        new ListResourceMappingsHandler(contract)),
                new CommandDispatch.Registration("load_content_as_json",
                        new LoadContentHandler()),
                new CommandDispatch.Registration(MapResourcePathCommand.WIRE_NAME,
                        new MapResourcePathHandler(contract)),
                new CommandDispatch.Registration(QueryPathsCommand.WIRE_NAME,
                        new QueryPathsHandler(contract, discovery)),
                new CommandDispatch.Registration(ReadContentFragmentCommand.WIRE_NAME,
                        new ReadContentFragmentHandler(contract)),
                new CommandDispatch.Registration(ResolveResourcePathCommand.WIRE_NAME,
                        new ResolveResourcePathHandler(contract)),
                new CommandDispatch.Registration(AddComponentCommand.WIRE_NAME,
                        new AddComponentHandler(contract)),
                new CommandDispatch.Registration(UpdateComponentCommand.WIRE_NAME,
                        new ComponentPathHandler(contract, ComponentPathCommand.Shape.UPDATE)),
                new CommandDispatch.Registration(DeleteComponentCommand.WIRE_NAME,
                        new ComponentPathHandler(contract, ComponentPathCommand.Shape.DELETE)),
                new CommandDispatch.Registration(ReorderComponentCommand.WIRE_NAME,
                        new ComponentPathHandler(contract, ComponentPathCommand.Shape.REORDER)),
                new CommandDispatch.Registration(CreateAssetFolderCommand.WIRE_NAME,
                        new AssetMutationHandler(contract, AssetMutationHandler.Kind.FOLDER)),
                new CommandDispatch.Registration(CreateAssetCommand.WIRE_NAME,
                        new AssetMutationHandler(contract, AssetMutationHandler.Kind.CREATION)),
                new CommandDispatch.Registration(UpdateAssetMetadataCommand.WIRE_NAME,
                        new AssetMutationHandler(contract, AssetMutationHandler.Kind.METADATA)),
                new CommandDispatch.Registration(DeleteAssetCommand.WIRE_NAME,
                        new AssetMutationHandler(contract, AssetMutationHandler.Kind.REMOVAL)),
                new CommandDispatch.Registration(MoveAssetCommand.WIRE_NAME,
                        new AssetMutationHandler(contract, AssetMutationHandler.Kind.MOVE)),
                new CommandDispatch.Registration(CreatePageCommand.WIRE_NAME,
                        new CreatePageHandler(contract)),
                new CommandDispatch.Registration(DeletePageCommand.WIRE_NAME,
                        new DeletePageHandler(contract)),
                new CommandDispatch.Registration(MovePageCommand.WIRE_NAME,
                        new MovePageHandler(contract)),
                new CommandDispatch.Registration(UpdatePageCommand.WIRE_NAME,
                        new UpdatePageHandler(contract)),
                new CommandDispatch.Registration(CreateContentFragmentCommand.WIRE_NAME,
                        new FragmentMutationHandler(contract, FragmentMutationHandler.Kind.CONTENT_CREATION)),
                new CommandDispatch.Registration(UpdateContentFragmentCommand.WIRE_NAME,
                        new FragmentMutationHandler(contract, FragmentMutationHandler.Kind.CONTENT_UPDATE)),
                new CommandDispatch.Registration(FragmentDeletion.CONTENT_WIRE_NAME,
                        new FragmentMutationHandler(contract, FragmentMutationHandler.Kind.CONTENT_REMOVAL)),
                new CommandDispatch.Registration(CreateExperienceFragmentCommand.WIRE_NAME,
                        new FragmentMutationHandler(contract,
                                FragmentMutationHandler.Kind.EXPERIENCE_CREATION)),
                new CommandDispatch.Registration(UpdateExperienceFragmentCommand.WIRE_NAME,
                        new FragmentMutationHandler(contract,
                                FragmentMutationHandler.Kind.EXPERIENCE_UPDATE)),
                new CommandDispatch.Registration(FragmentDeletion.EXPERIENCE_WIRE_NAME,
                        new FragmentMutationHandler(contract,
                                FragmentMutationHandler.Kind.EXPERIENCE_REMOVAL)));
    }

    /** Writes only the fail-closed state because dispatch and contract are platform objects.
     * @param output the serialization stream
     * @throws IOException if the state cannot be written
     */
    private void writeObject(ObjectOutputStream output) throws IOException {
        final State active = state.getAndSet(Missing.INSTANCE);
        final PlatformSeams bound = seams.getAndSet(PlatformSeams.NONE);
        try {
            output.defaultWriteObject();
        } finally {
            state.set(active);
            seams.set(bound);
        }
    }

    /** Clears non-serializable platform state when a servlet is deserialized.
     * @param input the serialization stream
     * @throws IOException if the state cannot be read
     * @throws ClassNotFoundException if a serialized state type is unavailable
     */
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject(); }

    @Override
    public boolean serves(String wireName) {
        return state.get() instanceof final Active active
                && active.dispatch().wireNames().contains(wireName);
    }

    @Override
    public java.util.List<CommandContractIdentity> commandContracts() {
        return state.get() instanceof final Active active
                ? active.dispatch().commandContracts(CommandContractIdentity.Bounds.from(
                        active.contract())) : java.util.List.of();
    }

    @Override
    public ExecutionOutcome.Completion run(LogicalOperation operation,
                                           DocumentValue.Mapping submission, javax.jcr.Session session) {
        return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
    }

    @Override
    public ExecutionOutcome.Completion run(LogicalOperation operation,
                                           DocumentValue.Mapping submission, javax.jcr.Session session,
                                           ResourceResolver resolver, CallerContext context) {
        if (!(state.get() instanceof final Active active)) {
            return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
        }
        final AgentContract activeContract = active.contract();
        final CommandDispatch activeDispatch = active.dispatch();
        final Optional<DocumentValue.Mapping> arguments = argumentsOf(submission, activeContract);
        if (arguments.isEmpty()) {
            return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
        }
        final CommandHandler.Answer answer = activeDispatch.run(operation.commandContract(),
                CommandContractIdentity.Bounds.from(activeContract), arguments.orElseThrow(), resolver,
                context);
        return completion(answer, operation, session, activeContract, arguments.orElseThrow());
    }

    @Override
    public ExecutionOutcome.Completion run(LogicalOperation operation,
                                           DocumentValue.Mapping submission, javax.jcr.Session session,
                                           ResourceResolver resolver) {
        if (!(state.get() instanceof final Active active)) {
            return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
        }
        final AgentContract activeContract = active.contract();
        return run(operation, submission, session, resolver, new CallerContext(
                operation.identity().identifier(),
                rs.slingshot.agent.command.Budget.discovery(activeContract),
                rs.slingshot.agent.command.Budget.time(activeContract),
                new rs.slingshot.agent.command.Budget(
                        rs.slingshot.agent.command.Budget.Kind.RESULT,
                        activeContract.value(rs.slingshot.agent.contract.ContractLimit
                                .MAXIMUM_COMMAND_RESULT_BYTES)),
                rs.slingshot.agent.command.ProgressSink.under(activeContract)));
    }

    /**
     * Continuation authority over the agent's own key ring, bound to the operation's target.
     *
     * <p>Without it every listing longer than one page is refused as a token this agent cannot
     * issue, so a runtime that can open the ring always supplies it. The ring is established by the
     * state lifecycle before discovery reports the authority ready; this only reads it.</p>
     *
     * @param operation the accepted operation, whose target and generation a token is bound to
     * @param contract the authenticated contract
     * @param stateSession the agent's own state session, which holds the key ring
     * @return the paging context, or unavailable where this runtime has no strong secure source
     */
    @Override
    public CallerContext.Paging paging(LogicalOperation operation, AgentContract contract,
                                       javax.jcr.Session stateSession) {
        if (!(DefaultContinuationKeyAuthority.open(stateSession, contract)
                instanceof final DefaultContinuationKeyAuthority.Opened opened)) {
            return CallerContext.Unavailable.INSTANCE;
        }
        return new CallerContext.Available(opened.authority(), operation.identity().targetDigest(),
                operation.identity().generation(), System.currentTimeMillis());
    }

    private Optional<DocumentValue.Mapping> argumentsOf(DocumentValue.Mapping submission,
                                                        AgentContract activeContract) {
        return submission.member(ARGUMENTS)
                .filter(DocumentValue.Text.class::isInstance)
                .map(DocumentValue.Text.class::cast)
                .map(DocumentValue.Text::value)
                .map(value -> BoundedDocumentReader.read(value.getBytes(StandardCharsets.UTF_8),
                        BoundedDocumentReader.Bounds.from(activeContract)))
                .filter(BoundedDocumentReader.Read.class::isInstance)
                .map(BoundedDocumentReader.Read.class::cast)
                .map(BoundedDocumentReader.Read::value)
                .filter(DocumentValue.Mapping.class::isInstance)
                .map(DocumentValue.Mapping.class::cast);
    }

    private static ExecutionOutcome.Completion completion(CommandHandler.Answer answer,
                                                          LogicalOperation operation,
                                                          javax.jcr.Session session,
                                                          AgentContract contract,
                                                          DocumentValue.Mapping arguments) {
        return switch (answer) {
            case CommandHandler.Produced produced -> rendered(produced.result());
            case CommandHandler.Artifact artifact -> artifact(artifact, operation, session, contract);
            case CommandHandler.Failed failed -> failed(failed, operation, arguments);
        };
    }

    /**
     * A handler's artifact answer, published into the slot it declares.
     *
     * <p>The bytes are committed first and the answer is built from what the store committed, so
     * the descriptor a client reads names the count and digest the store actually holds rather
     * than the ones this side measured before writing.</p>
     *
     * @param artifact the handler's artifact answer
     * @param operation the operation it belongs to
     * @param session the state session
     * @param contract the authenticated bounds
     * @return the completion
     */
    private static ExecutionOutcome.Completion artifact(CommandHandler.Artifact artifact,
                                                        LogicalOperation operation,
                                                        javax.jcr.Session session,
                                                        AgentContract contract) {
        final ArtifactSlot.Outcome slot = ArtifactSlot.of(artifact.slot());
        if (!(slot instanceof ArtifactSlot.Held held)) {
            return ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
        }
        final byte[] bytes = artifact.bytes();
        try {
            ArtifactStore.prepare(session, operation.caller());
            final OverflowPublication.Outcome published = OverflowPublication.publish(session,
                    new OverflowPublication.Publication(operation.caller(),
                            OperationStore.pathOf(operation.identity()), held.slot(), contract),
                    new rs.slingshot.agent.command.ResultAssembly.Overflowed(bytes.length,
                            rs.slingshot.agent.digest.Digest.of(bytes)),
                    bytes, System.currentTimeMillis());
            if (!(published instanceof final OverflowPublication.Published value)) {
                return ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
            }
            final Optional<String> document = canonical(artifact.result());
            return document.<ExecutionOutcome.Completion>map(written ->
                            new ExecutionOutcome.Succeeded(new ExecutionOutcome.Published(
                                    held.slot(), value.delivery().byteCount(),
                                    value.delivery().digest(), new ExecutionOutcome.Written(written))))
                    .orElseGet(() -> ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE);
        } catch (final javax.jcr.RepositoryException failure) {
            return ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
        }
    }

    private static ExecutionOutcome.Completion rendered(DocumentValue.Mapping result) {
        return canonical(result).map(value -> (ExecutionOutcome.Completion)
                        new ExecutionOutcome.Succeeded(new ExecutionOutcome.Inline(value)))
                .orElse(ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE);
    }

    /**
     * A declared failure as the answer the client reads.
     *
     * <p>The command's own refusal document is what a client validates an ending against, so a
     * failure that has one is carried as its canonical bytes. A failure whose row declares no
     * refusal shape of its own is carried as the shared failure document, which is what the client
     * reads a bare category out of.</p>
     *
     * @param failed the handler's declared failure
     * @return the completion
     */
    /**
     * A failure as the answer the client reads.
     *
     * <p>A handler that stated its own refusal document sends exactly that. Every other handler
     * sends its registry category, and the client authenticates an ending against the command's own
     * closed refusal type rather than against a shared category: so the category is rendered
     * through the one table that knows each command's own member names and correlation field. Only
     * a refusal the client could not read at all falls back to the shared category document, and
     * one that cannot be written in the canonical form is explicit uncertainty - never a document
     * that claims something the handler did not say.</p>
     *
     * @param failed the handler's declared failure
     * @param operation the operation it belongs to, which names the command
     * @param arguments the argument document the caller sent, which carries the correlation value
     * @return the completion
     */
    private static ExecutionOutcome.Completion failed(CommandHandler.Failed failed,
                                                      LogicalOperation operation,
                                                      DocumentValue.Mapping arguments) {
        final Optional<String> document = refusedDocument(failed.refusal())
                .flatMap(DefaultCommandRuntime::canonical)
                .or(() -> canonical(CommandRefusalDocuments.documentOf(
                        operation.commandContract().wireName(), failed.category(), arguments)))
                .or(() -> CommandFailure.Category.named(failed.category())
                        .flatMap(value -> canonical(CommandFailure.documentOf(value))));
        return document.<ExecutionOutcome.Completion>map(value ->
                        new ExecutionOutcome.Failed(new ExecutionOutcome.Inline(value)))
                .orElseGet(() -> ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE);
    }

    /**
     * The refusal document a failure names, where it names one.
     *
     * @param refusal the refusal the handler's failure carries
     * @return the document, or nothing where the handler declared none
     */
    private static Optional<DocumentValue.Mapping> refusedDocument(
            CommandHandler.RefusalDocument refusal) {
        return refusal instanceof final CommandHandler.Stated stated
                ? Optional.of(stated.document())
                : Optional.empty();
    }

    private static Optional<String> canonical(DocumentValue.Mapping value) {
        final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(value);
        return written instanceof CanonicalByteWriter.Written held
                ? Optional.of(held.rendered()) : Optional.empty();
    }
}
