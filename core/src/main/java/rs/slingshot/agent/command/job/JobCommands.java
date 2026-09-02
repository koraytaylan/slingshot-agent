// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.mutation.SingleCommit;
import rs.slingshot.agent.command.platform.JobState;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the four job commands take, and what they report under.
 *
 * <p>Three of the four arguments are one member each and the fourth is three, which is why they are
 * read here rather than in four files: what they share is every bound they are held to, and four
 * copies of the same three checks is four chances for one to drift.</p>
 */
public final class JobCommands {

    private JobCommands() {
    }

    /** The wire name of the command that lists the queues. */
    public static final String QUEUES_WIRE_NAME = "list_sling_job_queues";

    /** The wire name of the command that finds jobs. */
    public static final String JOBS_WIRE_NAME = "find_sling_jobs";

    /** The wire name of the command that reads one job. */
    public static final String INSPECT_WIRE_NAME = "inspect_sling_job";

    /** The wire name of the command that cancels one. */
    public static final String CANCEL_WIRE_NAME = "cancel_sling_job";

    /** The member a job's identifier is carried in. */
    public static final String JOB_IDENTIFIER = "job_identifier";

    /** The member the topic to search is carried in. */
    public static final String TOPIC = "topic";

    /** The member the states to include are carried in. */
    public static final String STATES = "states";

    /** What the topic says when the caller named none, which matches every topic. */
    public static final String EVERY_TOPIC = "";

    /** Every member the queue listing takes. */
    public static final List<String> QUEUE_MEMBERS = List.of(ResultWindow.ARGUMENT_MEMBER);

    /** Every member the job search takes. */
    public static final List<String> SEARCH_MEMBERS =
            List.of(ResultWindow.ARGUMENT_MEMBER, STATES, TOPIC);

    /** Every member the two identifier commands take. */
    public static final List<String> IDENTIFIER_MEMBERS = List.of(JOB_IDENTIFIER);

    /** The category a job system this side could not ask is reported under. */
    public static final String INVENTORY_FAILED = "job_inventory_failed";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category a job nothing is at is refused under. */
    public static final String JOB_NOT_FOUND = "job_not_found";

    /** The category a job the platform will not cancel is refused under. */
    public static final String JOB_NOT_CANCELLABLE = "job_not_cancellable";

    /** The category an answer larger than the contract allows is refused under. */
    public static final String RESULT_BUDGET_EXCEEDED = "result_budget_exceeded";

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
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The job's identifier is empty, or longer than one may be. */
        IDENTIFIER_REJECTED,
        /** The topic is empty, or longer than one may be. */
        TOPIC_REJECTED,
        /** A named state is not one of the six there are, or there are too many. */
        STATE_REJECTED,
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /** What reading a queue listing produced. */
    public sealed interface QueueOutcome permits QueueWindow, QueueRefused {
    }

    /**
     * A listing this command takes.
     *
     * @param window which page is wanted
     */
    public record QueueWindow(ResultWindow window) implements QueueOutcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record QueueRefused(Refusal refusal, String detail) implements QueueOutcome {
    }

    /** What reading a job search produced. */
    public sealed interface SearchOutcome permits Search, SearchRefused {
    }

    /**
     * A search this command takes.
     *
     * @param topic what kind of work, or {@link #EVERY_TOPIC}
     * @param states which states to include, which is never empty
     * @param window which page is wanted
     */
    public record Search(String topic, List<JobState> states, ResultWindow window)
            implements SearchOutcome {

        /** Holds a search whose states nothing can change afterwards. */
        public Search {
            states = List.copyOf(states);
        }

        /**
         * Which states this search includes.
         *
         * @return the states, which nothing may add to
         */
        @Override
        public List<JobState> states() {
            return states;
        }
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record SearchRefused(Refusal refusal, String detail) implements SearchOutcome {
    }

    /** What reading one job's identifier produced. */
    public sealed interface IdentifierOutcome permits Identifier, IdentifierRefused {
    }

    /**
     * One job, named.
     *
     * @param jobIdentifier what the platform calls it
     */
    public record Identifier(String jobIdentifier) implements IdentifierOutcome {
    }

    /**
     * One these commands do not take.
     *
     * @param refusal why they do not
     * @param detail what was seen
     */
    public record IdentifierRefused(Refusal refusal, String detail) implements IdentifierOutcome {
    }

    /**
     * Reads a queue listing's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the window
     * @return the listing, or the one reason there is none
     */
    public static QueueOutcome queues(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new QueueRefused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which page of the queues is wanted");
        }
        final Optional<String> unknown = unknownIn(mapping, QUEUE_MEMBERS);
        if (unknown.isPresent()) {
            return new QueueRefused(Refusal.MEMBER_UNKNOWN, unknown.get() + " is not a member of"
                    + " this command's argument. There is no filter: a deployment has a handful of"
                    + " queues and the one that matters is the one nobody thought to ask about.");
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new QueueRefused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new QueueWindow(((ResultWindow.Held) window).window());
    }

    /**
     * Reads a job search's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every member
     * @return the search, or the one reason there is none
     */
    public static SearchOutcome search(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new SearchRefused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which jobs and which page of them");
        }
        final Optional<String> unknown = unknownIn(mapping, SEARCH_MEMBERS);
        if (unknown.isPresent()) {
            return new SearchRefused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(STATES).isEmpty()) {
            return new SearchRefused(Refusal.MEMBER_ABSENT, STATES + " is required. A busy"
                    + " instance holds a great many jobs that succeeded and a few that did not,"
                    + " and every question anybody has is about the few — a search with no state"
                    + " would answer the successes first.");
        }
        return searched(mapping, contract);
    }

    private static SearchOutcome searched(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<List<JobState>> states = statesIn(mapping, contract);
        if (states.isEmpty()) {
            return new SearchRefused(Refusal.STATE_REJECTED, STATES + " is a list of the states to"
                    + " include, from " + JobState.spellings() + ", within the "
                    + contract.value(ContractLimit.MAXIMUM_SLING_JOB_STATES)
                    + " one search may name");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_SLING_JOB_TOPIC_BYTES);
        final Optional<DocumentValue> asked = mapping.member(TOPIC);
        if (asked.isPresent()
                && (!(asked.orElseThrow() instanceof final DocumentValue.Text text)
                        || text.value().isEmpty() || text.value().length() > bound)) {
            return new SearchRefused(Refusal.TOPIC_REJECTED, TOPIC + " is what kind of work a job"
                    + " is: not empty, and within the " + bound + " a topic may be. Leave it out"
                    + " to search every topic.");
        }
        final String topic = asked
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(EVERY_TOPIC);
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new SearchRefused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Search(topic, states.orElseThrow(), ((ResultWindow.Held) window).window());
    }

    /**
     * Reads an argument naming one job.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier
     * @return the job, or the one reason there is none
     */
    public static IdentifierOutcome identifier(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new IdentifierRefused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming one job");
        }
        final Optional<String> unknown = unknownIn(mapping, IDENTIFIER_MEMBERS);
        if (unknown.isPresent()) {
            return new IdentifierRefused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(JOB_IDENTIFIER).isEmpty()) {
            return new IdentifierRefused(Refusal.MEMBER_ABSENT,
                    JOB_IDENTIFIER + " is required; this command chooses no job");
        }
        final long bound = contract.value(ContractLimit.COMMAND_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES);
        if (!(mapping.member(JOB_IDENTIFIER).orElseThrow()
                instanceof final DocumentValue.Text identifier)
                || identifier.value().isEmpty() || identifier.value().length() > bound) {
            return new IdentifierRefused(Refusal.IDENTIFIER_REJECTED, JOB_IDENTIFIER + " is what"
                    + " the platform calls one job: not empty, and within the " + bound
                    + " an identifier may be");
        }
        return new Identifier(identifier.value());
    }

    private static Optional<String> unknownIn(DocumentValue.Mapping mapping, List<String> members) {
        return mapping.members().keySet().stream()
                .filter(member -> !members.contains(member))
                .findFirst();
    }

    private static Optional<List<JobState>> statesIn(DocumentValue.Mapping mapping,
                                                     AgentContract contract) {
        if (!(mapping.member(STATES).orElseThrow() instanceof final DocumentValue.Sequence items)
                || items.items().isEmpty()
                || items.items().size()
                        > contract.value(ContractLimit.MAXIMUM_SLING_JOB_STATES)) {
            return Optional.empty();
        }
        final List<JobState> states = items.items().stream()
                .filter(DocumentValue.Text.class::isInstance)
                .map(item -> JobState.named(((DocumentValue.Text) item).value()))
                .flatMap(Optional::stream)
                .toList();
        return states.size() == items.items().size() ? Optional.of(states) : Optional.empty();
    }

    /**
     * Everything one job listing can fail with, of either kind.
     *
     * @return the categories
     */
    public static List<String> listingCategories() {
        final List<String> categories = new ArrayList<>(List.of(DISCOVERY_BUDGET_EXCEEDED));
        categories.addAll(CONTINUATION_CATEGORIES);
        categories.add(INVENTORY_FAILED);
        return List.copyOf(categories);
    }

    /**
     * Everything one job inspection can fail with.
     *
     * @return the categories
     */
    public static List<String> inspectionCategories() {
        return List.of(JOB_NOT_FOUND, RESULT_BUDGET_EXCEEDED, INVENTORY_FAILED);
    }

    /**
     * Everything one cancellation can fail with.
     *
     * @return the categories
     */
    public static List<String> cancellationCategories() {
        return List.of(JOB_NOT_FOUND, JOB_NOT_CANCELLABLE, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }
}
