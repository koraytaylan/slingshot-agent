// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.AgentOperationIdentifier;

/**
 * Everything a handler may reach, and the fact that there is nothing else on it.
 *
 * <p>The interesting assertion is the negative one: no member of the context yields a second
 * resolver, a service, or a factory. That is what makes "a command runs as its caller" a property
 * of the machinery rather than a promise — a handler cannot obtain a session because there is
 * nothing to obtain one from, not because obtaining one is discouraged.</p>
 *
 * <p>The staging area is the pressure that would otherwise reopen exactly that. It is a place to
 * write, bounded, released by the framework, and unable to name anything outside itself however the
 * name is spelled.</p>
 */
final class CallerContextTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/command-registry/accepted");

    private static final AgentContract CONTRACT = contract();

    @Test
    @DisplayName("each budget is a number the row or the contract chose, and none is unbounded")
    void eachbudgetIsAnumberSomebodyElseChose() {
        final RegistryRow row = row("query_paths");
        assertEquals(row.resultBytes(), Budget.result(row).limit(),
                "a result bound is not the row's own");
        assertEquals(CONTRACT.value(
                        rs.slingshot.agent.contract.ContractLimit
                                .MAXIMUM_COMMAND_EXECUTION_MILLISECONDS),
                Budget.time(CONTRACT).limit(), "a time budget is not the contract's own");
        assertTrue(Budget.discovery(CONTRACT).limit() > 0);
        assertThrows(IllegalArgumentException.class,
                () -> new Budget(Budget.Kind.RESULT, 0),
                "an unbounded budget was accepted, which is a command deciding for itself");
    }

    @Test
    @DisplayName("each budget is proved at its limit and one past it, and reports its own category")
    void eachbudgetIsProvedAtItsLimitAndOnePastIt() {
        final CallerContext context = context();
        assertTrue(context.exceeded(context.discovery().limit(), 0, 0).isEmpty(),
                "a spend at exactly the discovery bound was refused");
        assertEquals(Budget.Kind.DISCOVERY,
                context.exceeded(context.discovery().limit() + 1, 0, 0).orElseThrow().kind());
        assertTrue(context.exceeded(0, context.time().limit(), 0).isEmpty());
        assertEquals(Budget.Kind.TIME,
                context.exceeded(0, context.time().limit() + 1, 0).orElseThrow().kind());
        assertTrue(context.exceeded(0, 0, context.result().limit()).isEmpty());
        assertEquals(Budget.Kind.RESULT,
                context.exceeded(0, 0, context.result().limit() + 1).orElseThrow().kind());
        assertEquals(3, Budget.Kind.values().length, "a budget was added or lost");
        assertEquals(3, Arrays.stream(Budget.Kind.values()).map(Budget.Kind::category).distinct()
                        .count(),
                "two budgets are reported as the same thing, so a caller cannot tell which ran out");
    }

    @Test
    @DisplayName("the context yields no second resolver, no service, and no factory")
    void thecontextYieldsNothingToReachAnythingElseThrough() {
        final List<String> reachable = Arrays.stream(CallerContext.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .filter(name -> name.contains("service") || name.contains("Service")
                        || name.contains("factory") || name.contains("Factory")
                        || name.contains("session") || name.contains("Session")
                        || name.contains("resolver") || name.contains("Resolver")
                        || name.contains("bundle") || name.contains("Bundle"))
                .toList();
        assertEquals(List.of(), reachable,
                "a handler can reach something other than what it was given: " + reachable);
        final List<String> onStaging = Arrays.stream(StagingArea.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .filter(name -> name.contains("resolver") || name.contains("session")
                        || name.contains("parent") || name.contains("root"))
                .toList();
        assertEquals(List.of(), onStaging,
                "a staging area is a way to reach somewhere else: " + onStaging);
    }

    @Test
    @DisplayName("a staging area exists only where the row declared room for one")
    void astagingAreaExistsOnlyWhereTheRowDeclaredIt(@TempDir Path scratch) {
        assertTrue(StagingArea.forRow(scratch.resolve("none"), row("query_paths")).isEmpty(),
                "a command declaring no room was given some");
        try (StagingArea area = StagingArea
                .forRow(scratch.resolve("some"), row("download_content_package")).orElseThrow()) {
            assertTrue(area.isOpen(), "a command declaring room was given none");
            assertEquals(row("download_content_package").stagingBytes(), area.remaining());
        }
    }

    @Test
    @DisplayName("a staging area refuses every name that would leave it, however it is spelled")
    void astagingAreaRefusesEveryNameThatWouldLeaveIt(@TempDir Path scratch) {
        try (StagingArea area = area(scratch)) {
            for (final String outside : List.of("../escaped", "../../escaped",
                    "held/../../escaped", "/absolute", "./../escaped")) {
                final StagingArea.Outcome refused = area.write(outside, "anything");
                assertEquals(StagingArea.Refusal.OUTSIDE_ITS_OWN_ROOT,
                        assertInstanceOf(StagingArea.Refused.class, refused,
                                outside + " was written").refusal(),
                        outside + " was resolved into something that looked fine");
            }
            assertInstanceOf(StagingArea.Written.class, area.write("held/inside.txt", "anything"),
                    "a name inside the area was refused");
        }
    }

    @Test
    @DisplayName("a staging area is bounded at exactly its budget and one byte past it")
    void astagingAreaIsBoundedAtItsBudget(@TempDir Path scratch) {
        try (StagingArea area = area(scratch)) {
            final long room = area.remaining();
            assertInstanceOf(StagingArea.Written.class,
                    area.write("all-of-it", new byte[(int) room]),
                    "a write of exactly the declared room was refused");
            assertEquals(0, area.remaining());
            assertEquals(StagingArea.Refusal.PAST_ITS_BYTE_BUDGET,
                    assertInstanceOf(StagingArea.Refused.class,
                            area.write("one-more", new byte[1]),
                            "a write past the declared room was taken").refusal());
        }
    }

    @Test
    @DisplayName("a staging area is given back however the command ended")
    void astagingAreaIsGivenBackHoweverTheCommandEnded(@TempDir Path scratch) {
        assertFalse(afterEnding(scratch.resolve("after-success"), Ending.SUCCEEDED).isOpen(),
                "an area survived a command that succeeded");
        assertFalse(afterEnding(scratch.resolve("after-failure"), Ending.FAILED).isOpen(),
                "an area survived a command that failed");
        assertFalse(afterEnding(scratch.resolve("after-interruption"), Ending.INTERRUPTED)
                        .isOpen(),
                "an area survived an interruption");
    }

    /** The three ways a command ends, all of which give the place back. */
    private enum Ending {
        /** It produced a result. */
        SUCCEEDED,
        /** It failed with one of its declared categories. */
        FAILED,
        /** Something nobody planned for happened. */
        INTERRUPTED
    }

    private static StagingArea afterEnding(Path scratch, Ending ending) {
        final StagingArea area = area(scratch);
        if (ending == Ending.INTERRUPTED) {
            assertThrows(IllegalStateException.class, () -> {
                try (StagingArea held = area) {
                    held.write("kept", "anything");
                    throw new IllegalStateException("something nobody planned for");
                }
            });
            return area;
        }
        try (StagingArea held = area) {
            assertInstanceOf(StagingArea.Written.class, held.write("kept", "anything"));
        }
        return area;
    }

    @Test
    @DisplayName("a place that cannot be written to answers that rather than pretending")
    void aplaceThatCannotBeWrittenToAnswersThat(@TempDir Path scratch) {
        try (StagingArea held = area(scratch.resolve("unwritable"))) {
            assertInstanceOf(StagingArea.Written.class, held.write("held/one.txt", "anything"));
            assertEquals(StagingArea.Refusal.UNWRITABLE,
                    assertInstanceOf(StagingArea.Refused.class,
                            held.write("held/one.txt/under-a-file", "anything"),
                            "a name under a file was written").refusal(),
                    "a place that cannot hold what was asked for said something else");
        }
    }

    @Test
    @DisplayName("progress is bounded by what one operation's ledger may hold")
    void progressIsBoundedByWhatAledgerMayHold() {
        final ProgressSink sink = ProgressSink.under(CONTRACT);
        final long bound = sink.remaining();
        for (long reported = 0; reported < bound; reported = reported + 1) {
            assertEquals(ProgressSink.Taken.REPORTED, sink.report("still going"),
                    "a report inside the bound was refused");
        }
        assertEquals(ProgressSink.Taken.PAST_THE_EVENT_BOUND, sink.report("one more"),
                "a chatty handler was allowed to fill the ledger");
        assertEquals(bound, sink.reported().size(),
                "the ledger holds a different number of reports from what was taken");
    }

    @Test
    @DisplayName("no budget is declared anywhere but the registry row or the contract")
    void nobudgetIsDeclaredAnywhereElse() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/command/Budget.java"));
        assertTrue(source.contains("ContractLimit."),
                "the budgets are not read from the contract at all");
        assertTrue(source.contains("row.resultBytes()"),
                "the result bound is not the row's own");
        assertFalse(source.matches("(?s).*=\\s*[0-9]{3,}.*"),
                "a budget is written down here rather than read from what declares it");
    }

    private static StagingArea area(Path scratch) {
        return StagingArea.forRow(scratch, row("download_content_package")).orElseThrow();
    }

    private static CallerContext context() {
        return new CallerContext(operation(), Budget.discovery(CONTRACT), Budget.time(CONTRACT),
                Budget.result(row("query_paths")), ProgressSink.under(CONTRACT));
    }

    private static AgentOperationIdentifier operation() {
        return assertInstanceOf(AgentOperationIdentifier.Held.class,
                AgentOperationIdentifier.of(
                        "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8",
                        CONTRACT), "the operation identifier was refused").identifier();
    }

    private static RegistryRow row(String wireName) {
        return assertInstanceOf(CommandRegistry.Loaded.class, CommandRegistry.read(FIXTURES),
                "the fixture registry was refused").registry().row(wireName).orElseThrow();
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
