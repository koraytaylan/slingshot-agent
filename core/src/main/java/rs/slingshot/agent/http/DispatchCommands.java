// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;

/** Bridges verified command dispatch to the servlet's request-scoped execution contract. */
public final class DispatchCommands implements SubmitServlet.Commands {

    private static final long serialVersionUID = 1L;

    private final transient CommandDispatch dispatch;
    private final transient ContinuationKeyAuthority authority;

    /** Creates a runtime command bridge. */
    public DispatchCommands(CommandDispatch dispatch, ContinuationKeyAuthority authority) {
        this.dispatch = dispatch;
        this.authority = authority;
    }

    @Override
    public boolean serves(String wireName) {
        return dispatch.wireNames().contains(wireName);
    }

    @Override
    public Optional<CallerContext.Paging> paging(LogicalOperation operation,
                                                  rs.slingshot.agent.contract.AgentContract contract) {
        return Optional.of(new CallerContext.Paging(authority,
                operation.identity().targetDigest(), operation.identity().generation(),
                System.currentTimeMillis()));
    }

    @Override
    public ExecutionOutcome.Completion run(LogicalOperation operation,
                                           DocumentValue.Mapping submission,
                                           javax.jcr.Session session,
                                           ResourceResolver resolver,
                                           CallerContext context) {
        final CommandContractIdentity identity = operation.commandContract();
        final var loaded = rs.slingshot.agent.contract.AgentContract.load();
        if (!(loaded instanceof rs.slingshot.agent.contract.AgentContract.Loaded held)) {
            return ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE;
        }
        final rs.slingshot.agent.contract.AgentContract contract = held.contract();
        final CommandDispatch.Resolution resolved = dispatch.resolve(identity,
                CommandContractIdentity.Bounds.from(contract));
        if (!(resolved instanceof CommandDispatch.Resolved)) {
            return new ExecutionOutcome.Failed(new ExecutionOutcome.Inline(
                    "{\"outcome\":\"failed\",\"reason\":\"command_not_registered\"}"));
        }
        final var answer = dispatch.run(identity, CommandContractIdentity.Bounds.from(contract),
                submission, resolver, context);
        return switch (answer) {
            case rs.slingshot.agent.command.CommandHandler.Produced produced ->
                    new ExecutionOutcome.Succeeded(new ExecutionOutcome.Inline(rendered(
                            produced.result())));
            case rs.slingshot.agent.command.CommandHandler.Failed failed ->
                    new ExecutionOutcome.Failed(new ExecutionOutcome.Inline(failure(failed)));
        };
    }

    private static String failure(rs.slingshot.agent.command.CommandHandler.Failed failed) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put("category", new DocumentValue.Text(failed.category()));
        members.put("detail", new DocumentValue.Text(failed.detail()));
        return rendered(new DocumentValue.Mapping(members));
    }

    private static String rendered(DocumentValue.Mapping document) {
        final CanonicalByteWriter.Outcome written = CanonicalByteWriter.write(document);
        return ((CanonicalByteWriter.Written) written).rendered();
    }
}
