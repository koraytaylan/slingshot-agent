// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the five replication agent commands take, and what they report under.
 *
 * <p>An agent identifier is the same identifier in all five, so it is read once. The one command
 * with a member of its own is the flush, and that member is the whole reason it is safe to
 * offer.</p>
 */
public final class AgentCommands {

    private AgentCommands() {
    }

    /** The wire name of the command that lists the agents. */
    public static final String LIST_WIRE_NAME = "list_replication_agents";

    /** The wire name of the command that reads one agent. */
    public static final String INSPECT_AGENT_WIRE_NAME = "inspect_replication_agent";

    /** The wire name of the command that reads one agent's queue. */
    public static final String INSPECT_QUEUE_WIRE_NAME = "inspect_replication_queue";

    /** The wire name of the command that empties one. */
    public static final String FLUSH_WIRE_NAME = "flush_replication_queue";

    /** The wire name of the command that offers one stuck entry again. */
    public static final String RETRY_WIRE_NAME = "retry_replication_queue_entry";

    /** The member an agent's identifier is carried in. */
    public static final String AGENT_IDENTIFIER = "agent_identifier";

    /** The member an entry's identifier is carried in. */
    public static final String ENTRY_IDENTIFIER = "entry_identifier";

    /** The member the count a caller believes is in a queue is carried in. */
    public static final String EXPECTED_ENTRY_COUNT = "expected_entry_count";

    /** Every member the agent listing takes. */
    public static final List<String> LISTING_MEMBERS = List.of(ResultWindow.ARGUMENT_MEMBER);

    /** Every member the agent inspection takes. */
    public static final List<String> AGENT_MEMBERS = List.of(AGENT_IDENTIFIER);

    /** Every member the queue inspection takes. */
    public static final List<String> QUEUE_MEMBERS =
            List.of(AGENT_IDENTIFIER, ResultWindow.ARGUMENT_MEMBER);

    /** Every member the flush takes. */
    public static final List<String> FLUSH_MEMBERS =
            List.of(AGENT_IDENTIFIER, EXPECTED_ENTRY_COUNT);

    /** Every member the retry takes. */
    public static final List<String> RETRY_MEMBERS = List.of(AGENT_IDENTIFIER, ENTRY_IDENTIFIER);

    /** The category an agent inventory this side could not ask is reported under. */
    public static final String AGENT_INVENTORY_FAILED = "agent_inventory_failed";

    /** The category a queue this side could not ask about is reported under. */
    public static final String QUEUE_INVENTORY_FAILED = "queue_inventory_failed";

    /** The category an agent nothing is called is refused under. */
    public static final String AGENT_NOT_FOUND = "agent_not_found";

    /** The category an agent the caller may not reach is refused under. */
    public static final String AGENT_ACCESS_DENIED = "agent_access_denied";

    /** The category an entry nothing is called is refused under. */
    public static final String ENTRY_NOT_FOUND = "entry_not_found";

    /** The category a queue holding something other than what was expected is refused under. */
    public static final String QUEUE_EXPECTATION_MISMATCH = "queue_expectation_mismatch";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category the platform refusing a control is reported under. */
    public static final String CONTROL_REJECTED = "platform_control_rejected";

    /** The five ways a continuation token can be refused, which every paged command declares. */
    public static final List<String> CONTINUATION_CATEGORIES = List.of(
            "continuation_token_malformed", "continuation_token_integrity_invalid",
            "continuation_token_wrong_target", "continuation_token_wrong_query",
            "continuation_token_expired");

    /** Why an argument is not one these commands take. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** The entry this command acts on is absent, which is its own refusal. */
        ENTRY_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** An identifier is empty, or longer than one may be. */
        IDENTIFIER_REJECTED,
        /** The expected count is not a whole number, or is larger than a queue holds. */
        EXPECTATION_REJECTED,
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /**
     * One refusal, said as the caller would fix it.
     *
     * @param refusal why the argument is not one this command takes
     * @param detail what was seen, which names no transport address
     */
    public record Refused(Refusal refusal, String detail) {
    }

    /** What reading an agent-and-window argument produced. */
    public sealed interface WindowedOutcome permits Windowed, WindowedRefused {
    }

    /**
     * An argument naming an agent, or none, and a page.
     *
     * @param agentIdentifier which agent, or {@link #EVERY_AGENT} where the command names none
     * @param window which page is wanted
     */
    public record Windowed(String agentIdentifier, ResultWindow window)
            implements WindowedOutcome {
    }

    /**
     * One these commands do not take.
     *
     * @param refusal why they do not
     */
    public record WindowedRefused(Refused refusal) implements WindowedOutcome {
    }

    /** What the agent member says on the listing, which names no agent. */
    public static final String EVERY_AGENT = "";

    /** What reading a flush argument produced. */
    public sealed interface FlushOutcome permits Flush, FlushRefused {
    }

    /**
     * A flush this command takes.
     *
     * @param agentIdentifier which agent
     * @param expectation how many entries the caller believes are in it, or the any-count sentinel
     */
    public record Flush(String agentIdentifier, long expectation) implements FlushOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record FlushRefused(Refused refusal) implements FlushOutcome {
    }

    /** What reading a retry argument produced. */
    public sealed interface RetryOutcome permits Retry, RetryRefused {
    }

    /**
     * A retry this command takes.
     *
     * @param agentIdentifier which agent
     * @param entryIdentifier which entry
     */
    public record Retry(String agentIdentifier, String entryIdentifier) implements RetryOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     */
    public record RetryRefused(Refused refusal) implements RetryOutcome {
    }

    /**
     * Reads an argument naming a page and, on the queue inspection, an agent.
     *
     * @param arguments the argument document
     * @param members which members this command takes
     * @param contract the authenticated contract, which bounds the identifier and the window
     * @return the argument, or the one reason there is none
     */
    public static WindowedOutcome windowed(DocumentValue arguments, List<String> members,
                                           AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new WindowedRefused(new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which page is wanted"));
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !members.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new WindowedRefused(new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument"));
        }
        final boolean named = members.contains(AGENT_IDENTIFIER);
        if (named && mapping.member(AGENT_IDENTIFIER).isEmpty()) {
            return new WindowedRefused(new Refused(Refusal.MEMBER_ABSENT,
                    AGENT_IDENTIFIER + " is required; this command chooses no agent"));
        }
        final Optional<String> agent = named
                ? identifier(mapping, AGENT_IDENTIFIER,
                        contract.value(ContractLimit.MAXIMUM_REPLICATION_AGENT_IDENTIFIER_BYTES))
                : Optional.of(EVERY_AGENT);
        if (agent.isEmpty()) {
            return new WindowedRefused(agentRefusal(contract));
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new WindowedRefused(new Refused(Refusal.WINDOW_REFUSED,
                        refused.refusal().toString()))
                : new Windowed(agent.orElseThrow(), ((ResultWindow.Held) window).window());
    }

    /**
     * Reads a flush's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier and the count
     * @return the flush, or the one reason there is none
     */
    public static FlushOutcome flush(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, FLUSH_MEMBERS, contract);
        if (shape.isPresent()) {
            return new FlushRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final Optional<DocumentValue> expected = mapping.member(EXPECTED_ENTRY_COUNT);
        if (expected.isEmpty()) {
            return new Flush(identifierIn(mapping), ReplicationInventory.ANY_COUNT);
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_REPLICATION_QUEUE_ENTRIES);
        if (!(expected.orElseThrow() instanceof final DocumentValue.Whole count)
                || count.value() < 0 || count.value() > bound) {
            return new FlushRefused(new Refused(Refusal.EXPECTATION_REJECTED, EXPECTED_ENTRY_COUNT
                    + " is how many entries you believe are in the queue: a whole number within"
                    + " the " + bound + " one holds. Leave it out to empty whatever is there."));
        }
        return new Flush(identifierIn(mapping), count.value());
    }

    /**
     * Reads a retry's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds both identifiers
     * @return the retry, or the one reason there is none
     */
    public static RetryOutcome retry(DocumentValue arguments, AgentContract contract) {
        final Optional<Refused> shape = shapeOf(arguments, RETRY_MEMBERS, contract);
        if (shape.isPresent()) {
            return new RetryRefused(shape.orElseThrow());
        }
        final DocumentValue.Mapping mapping = held(arguments);
        final long bound =
                contract.value(ContractLimit.MAXIMUM_REPLICATION_QUEUE_ENTRY_IDENTIFIER_BYTES);
        if (mapping.member(ENTRY_IDENTIFIER).isEmpty()) {
            return new RetryRefused(new Refused(Refusal.ENTRY_ABSENT,
                    ENTRY_IDENTIFIER + " is required; this command chooses no entry"));
        }
        final Optional<String> entry = identifier(mapping, ENTRY_IDENTIFIER, bound);
        return entry.isEmpty()
                ? new RetryRefused(new Refused(Refusal.ENTRY_ABSENT, ENTRY_IDENTIFIER + " is what"
                        + " the platform calls one queue entry: not empty, and within the " + bound
                        + " an identifier may be"))
                : new Retry(identifierIn(mapping), entry.orElseThrow());
    }

    /**
     * The argument as the document its shape has already been checked against.
     *
     * <p>Re-tested rather than cast, because a cast here would be a promise about what
     * {@code shapeOf} did rather than a fact the compiler can see.</p>
     *
     * @param arguments the argument document
     * @return it as a mapping, which its shape check has already proved it is
     */
    private static DocumentValue.Mapping held(DocumentValue arguments) {
        if (arguments instanceof final DocumentValue.Mapping mapping) {
            return mapping;
        }
        throw new IllegalStateException("the shape check accepted something that is not a"
                + " document, which would be a defect in it rather than in the caller's argument");
    }

    private static Optional<Refused> shapeOf(DocumentValue arguments, List<String> members,
                                             AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return Optional.of(new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming one replication agent"));
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !members.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return Optional.of(new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument"));
        }
        if (mapping.member(AGENT_IDENTIFIER).isEmpty()) {
            return Optional.of(new Refused(Refusal.MEMBER_ABSENT,
                    AGENT_IDENTIFIER + " is required; this command chooses no agent"));
        }
        return identifier(mapping, AGENT_IDENTIFIER,
                        contract.value(ContractLimit.MAXIMUM_REPLICATION_AGENT_IDENTIFIER_BYTES))
                .isEmpty() ? Optional.of(agentRefusal(contract)) : Optional.empty();
    }

    private static Refused agentRefusal(AgentContract contract) {
        return new Refused(Refusal.IDENTIFIER_REJECTED, AGENT_IDENTIFIER + " is what the platform"
                + " calls one replication agent: not empty, and within the "
                + contract.value(ContractLimit.MAXIMUM_REPLICATION_AGENT_IDENTIFIER_BYTES)
                + " an identifier may be");
    }

    private static Optional<String> identifier(DocumentValue.Mapping mapping, String member,
                                               long bound) {
        final Optional<DocumentValue> asked = mapping.member(member);
        if (asked.isEmpty() || !(asked.orElseThrow() instanceof final DocumentValue.Text text)
                || text.value().isEmpty() || text.value().length() > bound) {
            return Optional.empty();
        }
        return Optional.of(text.value());
    }

    private static String identifierIn(DocumentValue.Mapping mapping) {
        return ((DocumentValue.Text) mapping.member(AGENT_IDENTIFIER).orElseThrow()).value();
    }

    /**
     * Everything the agent listing can fail with.
     *
     * @return the categories
     */
    public static List<String> listingCategories() {
        final List<String> categories =
                new ArrayList<>(List.of(AGENT_INVENTORY_FAILED, DISCOVERY_BUDGET_EXCEEDED));
        categories.addAll(CONTINUATION_CATEGORIES);
        return List.copyOf(categories);
    }

    /**
     * Everything one agent inspection can fail with.
     *
     * @return the categories
     */
    public static List<String> agentCategories() {
        return List.of(AGENT_ACCESS_DENIED, AGENT_INVENTORY_FAILED, AGENT_NOT_FOUND);
    }

    /**
     * Everything one queue inspection can fail with.
     *
     * @return the categories
     */
    public static List<String> queueCategories() {
        final List<String> categories = new ArrayList<>(List.of(AGENT_ACCESS_DENIED,
                AGENT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED, QUEUE_INVENTORY_FAILED));
        categories.addAll(CONTINUATION_CATEGORIES);
        return List.copyOf(categories);
    }

    /**
     * Everything one flush can fail with.
     *
     * @return the categories
     */
    public static List<String> flushCategories() {
        return List.of(AGENT_ACCESS_DENIED, AGENT_NOT_FOUND, QUEUE_EXPECTATION_MISMATCH,
                CONTROL_REJECTED, SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }

    /**
     * Everything one retry can fail with.
     *
     * @return the categories
     */
    public static List<String> retryCategories() {
        return List.of(AGENT_ACCESS_DENIED, AGENT_NOT_FOUND, ENTRY_NOT_FOUND, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }
}
