// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.execution.LogicalOperation;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.StatePath;

/** Covers the servlet runtime's active and fail-closed lifecycle. */
final class DefaultCommandRuntimeTest {

    private static final Path FIXTURES = repositoryRoot()
            .resolve("core/src/test/resources/fixtures/command-registry/accepted");
    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "test is not inside repository");
    }

    @Test
    void activeRuntimeDispatchesOnlyDeclaredNamesAndFailsClosedOnMalformedSubmission() {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                if ("query_paths".equals(row.wireName())) {
                    return new Failed(row.failureCategories().getFirst(), "test failure");
                }
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final CommandDispatch dispatch = assertInstanceOf(CommandDispatch.Held.class,
                CommandDispatch.of(registry, handlers)).dispatch();
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(dispatch, CONTRACT);
        assertTrue(runtime.serves(registry.wireNames().getFirst()));
        assertFalse(runtime.serves("not-a-command"));
        final DocumentValue.Mapping empty = new DocumentValue.Mapping(new LinkedHashMap<>());
        assertInstanceOf(ExecutionOutcome.Uncertain.class, runtime.run(null, empty, null));
        assertInstanceOf(ExecutionOutcome.Uncertain.class,
                runtime.run(null, empty, null, null, null));
        assertInstanceOf(CallerContext.Unavailable.class, runtime.paging(null, CONTRACT));
    }

    @Test
    void activeRuntimeRunsAValidSubmissionThroughTheDispatch() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        final AtomicBoolean fail = new AtomicBoolean(true);
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                if ("query_paths".equals(row.wireName()) && fail.getAndSet(false)) {
                    return new Failed(row.failureCategories().getFirst(), "test failure");
                }
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        final CommandHandler.Artifact offered = new CommandHandler.Artifact(
                new DocumentValue.Mapping(new LinkedHashMap<>()), "bad/slot",
                new byte[] {1, 2, 3});
        assertTrue(java.util.Arrays.equals(new byte[] {1, 2, 3}, offered.bytes()));
        final LogicalOperation operation = operation();
        final SequencedMap<String, DocumentValue> submissionMembers = new LinkedHashMap<>();
        submissionMembers.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{}"));
        final DocumentValue.Mapping submission = new DocumentValue.Mapping(submissionMembers);
        assertInstanceOf(ExecutionOutcome.Completion.class,
                runtime.run(operation, submission, null, null, context()));
        assertInstanceOf(ExecutionOutcome.Succeeded.class,
                runtime.run(operation, submission, null, null));
        final SequencedMap<String, DocumentValue> malformedMembers = new LinkedHashMap<>();
        malformedMembers.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{"));
        assertInstanceOf(ExecutionOutcome.Uncertain.class,
                runtime.run(operation, new DocumentValue.Mapping(malformedMembers), null, null,
                        context()));
    }

    @Test
    void artifactWithAnInvalidSlotFailsClosedBeforeRepositoryAccess() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return "query_paths".equals(row.wireName())
                        ? new Artifact(new DocumentValue.Mapping(new LinkedHashMap<>()), "bad/slot",
                                new byte[] {1, 2, 3})
                        : new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        final LogicalOperation operation = operation();
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(SubmitServlet.ARGUMENTS, new DocumentValue.Text("{}"));
        assertInstanceOf(ExecutionOutcome.Uncertain.class,
                runtime.run(operation, new DocumentValue.Mapping(members), null, null, context()));
    }

    @Test
    void serializationTemporarilyRemovesPlatformRuntimeState() throws java.io.IOException {
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry.rows().forEach(row -> handlers.put(row.wireName(), new CommandHandler() {
            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return row.failureCategories();
            }
        }));
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime(
                assertInstanceOf(CommandDispatch.Held.class,
                        CommandDispatch.of(registry, handlers)).dispatch(), CONTRACT);
        try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
             java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(runtime);
        }
        assertTrue(runtime.serves(registry.wireNames().getFirst()));
    }

    @Test
    void dsActivationAdvertisesOnlyThePackagedStatelessSubset() {
        final DefaultCommandRuntime runtime = new DefaultCommandRuntime();
        runtime.activate();
        assertTrue(runtime.serves("query_paths"));
        assertFalse(runtime.serves("download_content_package"));
        runtime.deactivate();
        assertFalse(runtime.serves("query_paths"));
    }

    private static LogicalOperation operation() throws java.io.IOException {
        final Path fixture = repositoryRoot().resolve(
                "core/src/test/resources/fixtures/submit-servlet/a-submission.json");
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(Files.readAllBytes(fixture),
                        BoundedDocumentReader.Bounds.from(CONTRACT))).value());
        final OperationIdentity identity = assertInstanceOf(OperationIdentity.Held.class,
                OperationIdentity.of(document.member("operation").orElseThrow(), CONTRACT)).identity();
        final CommandRegistry registry = assertInstanceOf(CommandRegistry.Loaded.class,
                CommandRegistry.read(FIXTURES)).registry();
        final rs.slingshot.agent.identity.CommandContractIdentity contract =
                assertInstanceOf(rs.slingshot.agent.identity.CommandContractIdentity.Held.class,
                        registry.row("query_paths").orElseThrow().identity(
                                rs.slingshot.agent.identity.CommandContractIdentity.Bounds.from(CONTRACT)))
                        .identity();
        final StatePath.Caller caller = assertInstanceOf(StatePath.Held.class,
                StatePath.caller("admin")).caller();
        return assertInstanceOf(LogicalOperation.Held.class, LogicalOperation.accepted(identity,
                Digest.of("submission".getBytes(StandardCharsets.UTF_8)), contract, caller,
                1_000L, 1_000L, CONTRACT)).operation();
    }

    private static CallerContext context() throws java.io.IOException {
        return new CallerContext(operation().identity().identifier(),
                rs.slingshot.agent.command.Budget.discovery(CONTRACT),
                rs.slingshot.agent.command.Budget.time(CONTRACT),
                rs.slingshot.agent.command.Budget.result(
                        assertInstanceOf(CommandRegistry.Loaded.class,
                                CommandRegistry.read(FIXTURES)).registry().row("query_paths")
                                .orElseThrow()),
                rs.slingshot.agent.command.ProgressSink.under(CONTRACT));
    }
}
