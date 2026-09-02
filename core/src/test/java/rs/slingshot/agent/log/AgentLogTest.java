// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The only way anything here writes a log line.
 *
 * <p>What is proved is what does not reach the line. A value the redaction corpus covers, and a
 * message longer than the contract allows — the first because a log line goes to more people than a
 * response does, and the second because half a log line is the half somebody quotes.</p>
 */
final class AgentLogTest {

    private static final AgentContract CONTRACT = contract();

    private static final long BOUND =
            CONTRACT.value(ContractLimit.MAXIMUM_LOG_MESSAGE_BYTES);

    private static final Predicate<String> SECRETS = value -> value.contains("hunter2");

    @Test
    @DisplayName("every line written during an operation carries its identifier")
    void everylineDuringAnOperationCarriesIt() {
        final String line = AgentLog.lineOf(
                LogEvent.of("the command was accepted").during("4ccf24ff28333528"),
                SECRETS, BOUND);
        assertTrue(AgentLog.carriesAnOperation(line),
                "a line written during an operation is findable by nobody: " + line);
        assertTrue(line.contains("operation=4ccf24ff28333528"), line);
        assertTrue(!AgentLog.carriesAnOperation(AgentLog.lineOf(
                        LogEvent.of("the bundle started"), SECRETS, BOUND)),
                "a line written when no operation was in scope claimed one");
    }

    @Test
    @DisplayName("a value the redaction corpus covers is withheld rather than written")
    void acoveredValueIsWithheld() {
        final String line = AgentLog.lineOf(
                LogEvent.of("the configuration was read")
                        .with("service.port", "8080")
                        .with("service.password", "hunter2"),
                SECRETS, BOUND);
        assertTrue(line.contains("service.port=8080"),
                "a value nothing covers was withheld: " + line);
        assertTrue(!line.contains("hunter2"),
                "a value the corpus covers reached the line, and a log line goes to an operator's"
                        + " console, a support bundle and whatever ships logs off the instance —"
                        + " so a secret that only ever reaches the log reaches more people, not"
                        + " fewer: " + line);
        assertTrue(line.contains("service.password=" + AgentLog.WITHHELD),
                "the line does not say that the field was withheld, so a reader cannot tell a"
                        + " withheld value from a missing field: " + line);
    }

    @Test
    @DisplayName("a message at the bound is written and one past it is refused rather than cut")
    void amessagePastTheBoundIsRefused() {
        final String at = "a".repeat((int) BOUND);
        assertTrue(AgentLog.lineOf(LogEvent.of(at), SECRETS, BOUND).startsWith(at),
                "a message at exactly the bound was not written");
        final String past = "a".repeat((int) BOUND + 1);
        final String refused = AgentLog.lineOf(LogEvent.of(past).during("4ccf"), SECRETS, BOUND);
        assertTrue(!refused.contains("aaaa"),
                "a message past the bound was truncated rather than refused, and half a log line"
                        + " is the half somebody quotes: " + refused);
        assertTrue(refused.contains(String.valueOf(BOUND)) && refused.contains("operation=4ccf"),
                "the refusal does not say what the bound is or which operation it was about: "
                        + refused);
    }

    @Test
    @DisplayName("an event carries named fields rather than a sentence somebody built")
    void aneventCarriesFieldsRatherThanASentence() {
        final LogEvent event = LogEvent.of("the command failed")
                .with("category", "page_not_found")
                .with("command", "create_page");
        assertEquals(List.of("category", "command"), List.copyOf(event.fields().keySet()),
                "the fields are not in the order they were added, and a line whose fields move"
                        + " about is a line nobody can grep");
        assertEquals(List.of("category", "command"),
                AgentLog.fieldsIn(AgentLog.lineOf(event, SECRETS, BOUND)));
        assertEquals(LogEvent.OUTSIDE_AN_OPERATION, event.operation());
    }

    @Test
    @DisplayName("an event's fields cannot be changed after it is made")
    void aneventIsFixedOnceMade() {
        final LogEvent one = LogEvent.of("something").with("a", "1");
        final LogEvent two = one.with("b", "2");
        assertEquals(1, one.fields().size(),
                "adding a field to an event changed the event somebody already had");
        assertEquals(2, two.fields().size());
        assertEquals("4ccf", one.during("4ccf").operation());
        assertEquals(LogEvent.OUTSIDE_AN_OPERATION, one.operation(),
                "saying an event was during an operation changed the one somebody already had");
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
