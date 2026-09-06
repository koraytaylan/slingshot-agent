// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.OverflowPublication;
import rs.slingshot.agent.command.component.AddComponentCommand;
import rs.slingshot.agent.command.component.AddComponentHandler;
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
import rs.slingshot.agent.command.content.ListChildPagesCommand;
import rs.slingshot.agent.command.content.ListChildPagesHandler;
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
import rs.slingshot.agent.command.page.CreatePageCommand;
import rs.slingshot.agent.command.page.CreatePageHandler;
import rs.slingshot.agent.command.page.DeletePageCommand;
import rs.slingshot.agent.command.page.DeletePageHandler;
import rs.slingshot.agent.command.page.MovePageCommand;
import rs.slingshot.agent.command.page.MovePageHandler;
import rs.slingshot.agent.command.page.UpdatePageCommand;
import rs.slingshot.agent.command.page.UpdatePageHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.wire.CommandFailure;

/** Adapts a verified command dispatch to the submission servlet's execution contract. */
@Component(service = CommandRuntime.class, immediate = true)
public final class DefaultCommandRuntime implements CommandRuntime {

    private static final String ARGUMENTS = SubmitServlet.ARGUMENTS;
    private static final long serialVersionUID = 1L;
    /** Runtime state, which is temporarily replaced by the fail-closed state during serialization. */
    private final AtomicReference<State> state = new AtomicReference<>(Missing.INSTANCE);

    private sealed interface State extends Serializable permits Active, Missing {
    }

    private record Active(CommandDispatch dispatch, AgentContract contract) implements State {
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
                java.util.Objects.requireNonNull(contract, "contract")));
    }

    /** Creates the fail-closed runtime before declarative-services activation. */
    public DefaultCommandRuntime() {
    }

    /** Loads and activates the complete stateless handler subset available in this bundle. */
    @Activate
    public void activate() {
        final AgentContract.Outcome loaded = AgentContract.load();
        if (!(loaded instanceof final AgentContract.Loaded present)) {
            return;
        }
        final CommandRegistry.Outcome rows = CommandRegistry.read(
                Thread.currentThread().getContextClassLoader());
        if (!(rows instanceof final CommandRegistry.Loaded embedded)) {
            return;
        }
        final java.util.List<CommandDispatch.Registration> registrations = registrations(
                present.contract());
        final CommandRegistry.Outcome active = embedded.registry().active(registrations.stream()
                .map(CommandDispatch.Registration::wireName).toList());
        if (!(active instanceof final CommandRegistry.Loaded selected)) {
            return;
        }
        final CommandDispatch.Outcome dispatch = CommandDispatch.from(selected.registry(), registrations);
        if (dispatch instanceof final CommandDispatch.Held ready) {
            state.set(new Active(ready.dispatch(), present.contract()));
        }
    }

    /** Revokes the runtime before the DS component is released. */
    @Deactivate
    public void deactivate() {
        state.set(Missing.INSTANCE);
    }

    private static java.util.List<CommandDispatch.Registration> registrations(AgentContract contract) {
        return java.util.List.of(
                new CommandDispatch.Registration(FindAssetsByMetadataCommand.WIRE_NAME,
                        new FindAssetsByMetadataHandler(contract)),
                new CommandDispatch.Registration(FindAssetsReferencedByPageCommand.WIRE_NAME,
                        new FindAssetsReferencedByPageHandler(contract)),
                new CommandDispatch.Registration(FindPagesByTemplateCommand.WIRE_NAME,
                        new FindPagesByTemplateHandler(contract)),
                new CommandDispatch.Registration(FindPagesContainingPhraseCommand.WIRE_NAME,
                        new FindPagesContainingPhraseHandler(contract)),
                new CommandDispatch.Registration(FindPagesUsingComponentsCommand.WIRE_NAME,
                        new FindPagesUsingComponentsHandler(contract)),
                new CommandDispatch.Registration(ListAssetRenditionsCommand.WIRE_NAME,
                        new ListAssetRenditionsHandler(contract)),
                new CommandDispatch.Registration(ListChildPagesCommand.WIRE_NAME,
                        new ListChildPagesHandler(contract)),
                new CommandDispatch.Registration(ListResourceMappingsCommand.WIRE_NAME,
                        new ListResourceMappingsHandler(contract)),
                new CommandDispatch.Registration("load_content_as_json",
                        new LoadContentHandler()),
                new CommandDispatch.Registration(MapResourcePathCommand.WIRE_NAME,
                        new MapResourcePathHandler(contract)),
                new CommandDispatch.Registration(QueryPathsCommand.WIRE_NAME,
                        new QueryPathsHandler(contract)),
                new CommandDispatch.Registration(ReadContentFragmentCommand.WIRE_NAME,
                        new ReadContentFragmentHandler(contract)),
                new CommandDispatch.Registration(ResolveResourcePathCommand.WIRE_NAME,
                        new ResolveResourcePathHandler(contract)),
                new CommandDispatch.Registration(AddComponentCommand.WIRE_NAME,
                        new AddComponentHandler(contract)),
                new CommandDispatch.Registration(CreatePageCommand.WIRE_NAME,
                        new CreatePageHandler(contract)),
                new CommandDispatch.Registration(DeletePageCommand.WIRE_NAME,
                        new DeletePageHandler(contract)),
                new CommandDispatch.Registration(MovePageCommand.WIRE_NAME,
                        new MovePageHandler(contract)),
                new CommandDispatch.Registration(UpdatePageCommand.WIRE_NAME,
                        new UpdatePageHandler(contract)));
    }

    /** Writes only the fail-closed state because dispatch and contract are platform objects.
     * @param output the serialization stream
     * @throws IOException if the state cannot be written
     */
    private void writeObject(ObjectOutputStream output) throws IOException {
        final State active = state.getAndSet(Missing.INSTANCE);
        try {
            output.defaultWriteObject();
        } finally {
            state.set(active);
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
        return completion(answer, operation, session, activeContract);
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

    @Override
    public CallerContext.Paging paging(LogicalOperation operation, AgentContract ignored) {
        return CallerContext.Unavailable.INSTANCE;
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
                                                          AgentContract contract) {
        return switch (answer) {
            case CommandHandler.Produced produced -> rendered(produced.result());
            case CommandHandler.Artifact artifact -> artifact(artifact, operation, session, contract);
            case CommandHandler.Failed failed -> failed(failed.category());
        };
    }

    private static ExecutionOutcome.Completion artifact(CommandHandler.Artifact artifact,
                                                        LogicalOperation operation,
                                                        javax.jcr.Session session,
                                                        AgentContract contract) {
        final ArtifactSlot.Outcome slot = ArtifactSlot.of(artifact.slot());
        if (!(slot instanceof ArtifactSlot.Held held)) {
            return ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
        }
        try {
            final OverflowPublication.Outcome published = OverflowPublication.publish(session,
                    operation.caller(), OperationStore.pathOf(operation.identity()),
                    new rs.slingshot.agent.command.ResultAssembly.Overflowed(
                            artifact.bytes().length,
                            rs.slingshot.agent.digest.Digest.of(artifact.bytes())),
                    artifact.bytes(), System.currentTimeMillis(), contract);
            return published instanceof OverflowPublication.Published value
                    ? new ExecutionOutcome.Succeeded(new ExecutionOutcome.Published(held.slot(),
                            value.delivery().byteCount(), value.delivery().digest()))
                    : ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
        } catch (final javax.jcr.RepositoryException failure) {
            return ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
        }
    }

    private static ExecutionOutcome.Completion rendered(DocumentValue.Mapping result) {
        return canonical(result).map(value -> (ExecutionOutcome.Completion)
                        new ExecutionOutcome.Succeeded(new ExecutionOutcome.Inline(value)))
                .orElse(ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE);
    }

    private static ExecutionOutcome.Completion failed(String category) {
        return CommandFailure.Category.named(category)
                .<ExecutionOutcome.Completion>map(value -> new ExecutionOutcome.Failed(
                        new ExecutionOutcome.Inline(canonical(CommandFailure.documentOf(value))
                                .orElse("{}"))))
                .orElseGet(() -> ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE);
    }

    private static Optional<String> canonical(DocumentValue.Mapping value) {
        final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(value);
        return written instanceof CanonicalByteWriter.Written held
                ? Optional.of(held.rendered()) : Optional.empty();
    }
}
