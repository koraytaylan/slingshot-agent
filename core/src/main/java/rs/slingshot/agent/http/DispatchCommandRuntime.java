// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.wire.CommandFailure;

/** Adapts a verified command dispatch to the submission servlet's execution contract. */
public final class DispatchCommandRuntime implements CommandRuntime {

    private static final String ARGUMENTS = SubmitServlet.ARGUMENTS;
    private static final long serialVersionUID = 1L;
    private transient Optional<CommandDispatch> dispatch;
    private transient Optional<AgentContract> contract;

    /**
     * Holds a dispatch and the contract used to bound its argument reader.
     * @param dispatch the validated registry/handler dispatch
     * @param contract the authenticated contract
     */
    public DispatchCommandRuntime(CommandDispatch dispatch, AgentContract contract) {
        this.dispatch = Optional.of(java.util.Objects.requireNonNull(dispatch, "dispatch"));
        this.contract = Optional.of(java.util.Objects.requireNonNull(contract, "contract"));
    }

    /** Clears non-serializable platform state when a servlet is deserialized. */
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        dispatch = Optional.empty();
        contract = Optional.empty();
    }

    @Override
    public boolean serves(String wireName) {
        return dispatch.filter(value -> value.wireNames().contains(wireName)).isPresent();
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
        if (dispatch.isEmpty() || contract.isEmpty()) {
            return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
        }
        final AgentContract activeContract = contract.orElseThrow();
        final CommandDispatch activeDispatch = dispatch.orElseThrow();
        final Optional<DocumentValue.Mapping> arguments = argumentsOf(submission, activeContract);
        if (arguments.isEmpty()) {
            return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
        }
        final CommandHandler.Answer answer = activeDispatch.run(operation.commandContract(),
                CommandContractIdentity.Bounds.from(activeContract), arguments.orElseThrow(), resolver,
                context);
        return completion(answer);
    }

    @Override
    public ExecutionOutcome.Completion run(LogicalOperation operation,
                                           DocumentValue.Mapping submission, javax.jcr.Session session,
                                           ResourceResolver resolver) {
        if (contract.isEmpty()) {
            return ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED;
        }
        final AgentContract activeContract = contract.orElseThrow();
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

    private static ExecutionOutcome.Completion completion(CommandHandler.Answer answer) {
        return switch (answer) {
            case CommandHandler.Produced produced -> rendered(produced.result());
            case CommandHandler.Failed failed -> failed(failed.category());
        };
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
