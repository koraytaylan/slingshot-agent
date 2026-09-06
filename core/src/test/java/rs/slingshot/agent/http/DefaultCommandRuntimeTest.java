// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandDispatch;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.ExecutionOutcome;
import rs.slingshot.agent.json.DocumentValue;

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
    void serializationLeavesASeparateRuntimeFailClosed() throws Exception {
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
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(runtime);
        }
        final DefaultCommandRuntime restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = assertInstanceOf(DefaultCommandRuntime.class, input.readObject());
        }
        assertFalse(restored.serves(registry.wireNames().getFirst()));
    }
}
