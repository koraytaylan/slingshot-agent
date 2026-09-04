// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which handler runs a command, and the order in which that is decided.
 *
 * <p>The order is what the suite is really about. A handler resolved before the five-field identity
 * has been checked is a handler that can be reached by a submission naming a contract this build
 * does not hold — which is how the wrong version of a command runs and nobody finds out until the
 * result is wrong.</p>
 */
final class CommandDispatchTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/command-registry/accepted");

    private static final AgentContract CONTRACT = contract();

    @Test
    @DisplayName("a registered handler is dispatched to, and only after the identity is verified")
    void aregisteredHandlerIsDispatchedToAfterVerification() {
        final CommandDispatch dispatch = held();
        final RegistryRow row = registry().row("query_paths").orElseThrow();
        final CommandContractIdentity identity = assertInstanceOf(
                CommandContractIdentity.Held.class, row.identity(bounds()),
                "the row has no identity").identity();
        final CommandDispatch.Resolved resolved = assertInstanceOf(CommandDispatch.Resolved.class,
                dispatch.resolve(identity, bounds()), "a declared command resolved to nothing");
        assertEquals(row, resolved.row());
        assertInstanceOf(CommandHandler.Produced.class,
                resolved.handler().run(new DocumentValue.Mapping(new LinkedHashMap<>()), null,
                        null),
                "the handler that was resolved is not the one that was registered");
    }

    @Test
    @DisplayName("a submission naming a contract this build does not hold reaches no handler")
    void asubmissionUnderAnotherContractReachesNothing() {
        final RegistryRow row = registry().row("query_paths").orElseThrow();
        final CommandContractIdentity other = assertInstanceOf(CommandContractIdentity.Held.class,
                new RegistryRow(row.wireName(), "2", row.accessClass(), row.operationKey(),
                        row.resultBytes(), row.failureCategories(), row.argumentDigest(),
                        row.resultDigest(), row.limitsDigest(), row.stagingBytes(),
                        row.executionClass()).identity(bounds()),
                "the altered row has no identity").identity();
        final CommandDispatch.NotResolved refused = assertInstanceOf(
                CommandDispatch.NotResolved.class, held().resolve(other, bounds()),
                "a submission under another contract reached a handler");
        assertEquals(DispatchRefusal.IDENTITY_NOT_VERIFIED, refused.refusal());
        assertTrue(refused.detail().contains("query_paths"), refused.detail());
    }

    @Test
    @DisplayName("a row with no handler and a handler with no row are two distinct failures")
    void arowWithNoHandlerAndAhandlerWithNoRowAreDistinct() {
        final SequencedMap<String, CommandHandler> missing = new LinkedHashMap<>();
        missing.put("query_paths", answering());
        final CommandDispatch.Refused unrun = refusal(missing);
        assertEquals(DispatchRefusal.ROW_WITH_NO_HANDLER, unrun.refusal());
        assertTrue(unrun.detail().contains("list_child_pages")
                || unrun.detail().contains("download_content_package"), unrun.detail());
        final SequencedMap<String, CommandHandler> extra = everyHandler();
        extra.put("a_command_nobody_declared", answering());
        final CommandDispatch.Refused undeclared = refusal(extra);
        assertEquals(DispatchRefusal.HANDLER_WITH_NO_ROW, undeclared.refusal());
        assertTrue(undeclared.detail().contains("a_command_nobody_declared"),
                undeclared.detail());
    }

    @Test
    @DisplayName("two handlers claiming one wire name are refused, and neither is chosen")
    void twohandlersClaimingOneNameAreRefused() {
        final List<CommandDispatch.Registration> twice = new java.util.ArrayList<>();
        registry().wireNames().forEach(name ->
                twice.add(new CommandDispatch.Registration(name, answering())));
        twice.add(new CommandDispatch.Registration("query_paths", answering()));
        final CommandDispatch.Refused refused = CommandDispatch.refusalIn(
                        CommandDispatch.from(registry(), twice))
                .orElseThrow(() -> new IllegalStateException("two handlers were accepted"));
        assertEquals(DispatchRefusal.TWO_HANDLERS_FOR_ONE_NAME, refused.refusal());
        assertTrue(refused.detail().contains("query_paths"), refused.detail());
    }

    @Test
    @DisplayName("the declared categories and the producible ones correspond in both directions")
    void thecategoriesCorrespondInBothDirections() {
        final SequencedMap<String, CommandHandler> undeclared = everyHandler();
        undeclared.put("query_paths", categorised(List.of("not_found", "access_denied",
                "something_nobody_declared")));
        final CommandDispatch.Refused extra = refusal(undeclared);
        assertEquals(DispatchRefusal.CATEGORY_NO_ROW_DECLARES, extra.refusal());
        assertTrue(extra.detail().contains("something_nobody_declared"), extra.detail());
        final SequencedMap<String, CommandHandler> fewer = everyHandler();
        fewer.put("query_paths", categorised(List.of("not_found")));
        final CommandDispatch.Refused missing = refusal(fewer);
        assertEquals(DispatchRefusal.CATEGORY_NO_HANDLER_PRODUCES, missing.refusal());
        assertTrue(missing.detail().contains("access_denied"), missing.detail());
    }

    @Test
    @DisplayName("a handler answers with what it produced or with one of its own categories")
    void ahandlerAnswersWithOneOfTwoThings() {
        final CommandHandler.Answer produced = answering().run(
                new DocumentValue.Mapping(new LinkedHashMap<>()), null, null);
        assertInstanceOf(CommandHandler.Produced.class, produced);
        assertEquals(0, ((CommandHandler.Produced) produced).result().members().size(),
                "the fixture handler answered with something in it");
        final CommandHandler failing = new FailingHandler();
        final CommandHandler.Failed failed = assertInstanceOf(CommandHandler.Failed.class,
                failing.run(new DocumentValue.Mapping(new LinkedHashMap<>()), null, null),
                "a handler that failed answered with a result");
        assertEquals("not_found", failed.category());
        assertTrue(failed.detail().contains("nothing at the address"), failed.detail());
        assertEquals(List.of(), failing.categories(),
                "a handler that declares nothing was taken to declare something");
    }

    @Test
    @DisplayName("a handler is one method and nothing a handler could keep state in")
    void ahandlerIsOneMethodAndNoLifecycle() {
        final List<Method> declared = Arrays.stream(CommandHandler.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !method.isDefault())
                .toList();
        assertEquals(1, declared.size(),
                "a handler declares more than the one thing it does: " + declared);
        assertEquals("run", declared.getFirst().getName());
        assertTrue(Arrays.stream(CommandHandler.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().startsWith("activate")
                                || method.getName().startsWith("deactivate")
                                || method.getName().startsWith("modified")),
                "a handler has a lifecycle callback, which is somewhere to keep state between"
                        + " invocations");
        assertEquals(0, CommandHandler.class.getDeclaredFields().length,
                "a handler interface declares a field");
    }

    private static CommandDispatch.Refused refusal(SequencedMap<String, CommandHandler> handlers) {
        return CommandDispatch.refusalIn(CommandDispatch.of(registry(), handlers))
                .orElseThrow(() -> new IllegalStateException("the arrangement was accepted"));
    }

    private static CommandDispatch held() {
        return assertInstanceOf(CommandDispatch.Held.class,
                CommandDispatch.of(registry(), everyHandler()),
                "a well-formed arrangement was refused").dispatch();
    }

    private static SequencedMap<String, CommandHandler> everyHandler() {
        final SequencedMap<String, CommandHandler> handlers = new LinkedHashMap<>();
        registry().wireNames().forEach(name -> handlers.put(name,
                categorised(List.of("not_found", "access_denied"))));
        return handlers;
    }

    private static CommandHandler answering() {
        return categorised(List.of("not_found", "access_denied"));
    }

    private static CommandHandler categorised(List<String> categories) {
        return new CommandHandler() {

            @Override
            public Answer run(DocumentValue.Mapping arguments,
                              org.apache.sling.api.resource.ResourceResolver resolver,
                              CallerContext context) {
                return new Produced(new DocumentValue.Mapping(new LinkedHashMap<>()));
            }

            @Override
            public List<String> categories() {
                return List.copyOf(categories);
            }
        };
    }

    private static CommandContractIdentity.Bounds bounds() {
        return CommandContractIdentity.Bounds.from(CONTRACT);
    }

    private static CommandRegistry registry() {
        return assertInstanceOf(CommandRegistry.Loaded.class, CommandRegistry.read(FIXTURES),
                "the fixture registry was refused").registry();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }

    /** A handler that answers with a failure, named rather than anonymous so it keeps nothing. */
    private static final class FailingHandler implements CommandHandler {

        @Override
        public Answer run(DocumentValue.Mapping arguments,
                          org.apache.sling.api.resource.ResourceResolver resolver,
                          CallerContext context) {
            return new Failed("not_found", "nothing at the address this asked about");
        }
    }
}
