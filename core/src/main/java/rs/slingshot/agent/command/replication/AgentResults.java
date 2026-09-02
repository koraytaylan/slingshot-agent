// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.replication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the five replication agent commands answer.
 *
 * <p>No transport address appears in any of them, and there is no member one could travel in. An
 * agent's transport is a URL, and a URL to a publish instance very frequently carries the
 * credential it authenticates with — a listing of agents would otherwise be one of the easiest
 * places in an author instance to collect passwords. What comes back is which agent, what kind of
 * transport it has, whether it is on, whether its queue is stuck, and where its configuration is
 * held so an operator can go and read it under the repository's own access control.</p>
 *
 * <p>Whether the agent is on and whether its queue is stuck are separate members because they are
 * the two different reasons nothing is being replicated, and they have different fixes.</p>
 */
public final class AgentResults {

    private AgentResults() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member the queue entries are carried in. */
    public static final String ENTRIES = "entries";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** The member an agent's identifier is carried in. */
    public static final String AGENT_IDENTIFIER = "agent_identifier";

    /** The member an agent's title is carried in. */
    public static final String TITLE = "title";

    /** The member where an agent's configuration is held is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member what kind of thing an agent's transport does is carried in. */
    public static final String TRANSPORT_KIND = "transport_kind";

    /** The member saying whether an agent is switched on. */
    public static final String ENABLED = "enabled";

    /** The member saying whether an agent's queue has stopped moving. */
    public static final String QUEUE_BLOCKED = "queue_blocked";

    /** The member saying whether a queue has stopped moving. */
    public static final String BLOCKED = "blocked";

    /** The member the count of waiting entries is carried in. */
    public static final String QUEUED_ENTRY_COUNT = "queued_entry_count";

    /** The member how long an agent waits before retrying is carried in. */
    public static final String RETRY_DELAY_MILLISECONDS = "retry_delay_milliseconds";

    /** The member an entry's identifier is carried in. */
    public static final String ENTRY_IDENTIFIER = "entry_identifier";

    /** The member what an entry would do to its content is carried in. */
    public static final String ACTION = "action";

    /** The member the content an entry is about is carried in. */
    public static final String CONTENT_PATH = "content_path";

    /** The member the count of attempts is carried in. */
    public static final String ATTEMPT_COUNT = "attempt_count";

    /** The member why an entry last failed is carried in, where it has. */
    public static final String LAST_FAILURE_CATEGORY = "last_failure_category";

    /** The member the count of removed entries is carried in. */
    public static final String REMOVED_ENTRY_COUNT = "removed_entry_count";

    /** The member saying whether the platform took an entry again. */
    public static final String RESUBMITTED = "resubmitted";

    /** Every member an agent listing has. */
    public static final List<String> LISTING_MEMBERS = List.of(AGENT_IDENTIFIER, ENABLED, MATCHES,
            NEXT_CONTINUATION_TOKEN, QUEUE_BLOCKED, QUEUED_ENTRY_COUNT, REPOSITORY_PATH, TITLE,
            TRANSPORT_KIND);

    /** Every member an agent inspection has. */
    public static final List<String> AGENT_MEMBERS = List.of(AGENT_IDENTIFIER, ENABLED,
            QUEUE_BLOCKED, QUEUED_ENTRY_COUNT, REPOSITORY_PATH, RETRY_DELAY_MILLISECONDS, TITLE,
            TRANSPORT_KIND);

    /** Every member a queue inspection has. */
    public static final List<String> QUEUE_MEMBERS = List.of(ACTION, ATTEMPT_COUNT, BLOCKED,
            CONTENT_PATH, ENTRIES, ENTRY_IDENTIFIER, LAST_FAILURE_CATEGORY,
            NEXT_CONTINUATION_TOKEN);

    /** Every member a flush's answer has. */
    public static final List<String> FLUSH_MEMBERS =
            List.of(AGENT_IDENTIFIER, REMOVED_ENTRY_COUNT);

    /** Every member a retry's answer has. */
    public static final List<String> RETRY_MEMBERS =
            List.of(AGENT_IDENTIFIER, ENTRY_IDENTIFIER, RESUBMITTED);

    /** What the token member says when this is the last page. */
    public static final String NO_MORE_PAGES = "";

    /**
     * The result one agent listing produces.
     *
     * @param agents what it found, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping agentsOf(List<ReplicationInventory.Agent> agents,
                                                 String nextContinuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(agents.stream()
                .map(agent -> (DocumentValue) new DocumentValue.Mapping(agentMembers(agent)))
                .toList()));
        if (!NO_MORE_PAGES.equals(nextContinuationToken)) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(nextContinuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one agent inspection produces.
     *
     * @param inspected what the agent is
     * @return the result document
     */
    public static DocumentValue.Mapping agentOf(ReplicationInventory.Inspected inspected) {
        final SequencedMap<String, DocumentValue> result =
                new LinkedHashMap<>(agentMembers(inspected.agent()));
        result.put(RETRY_DELAY_MILLISECONDS,
                new DocumentValue.Whole(inspected.retryDelayMilliseconds()));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one queue inspection produces.
     *
     * @param flow whether the queue is moving
     * @param entries what is waiting, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping queueOf(ReplicationInventory.Flow flow,
                                                List<ReplicationInventory.Entry> entries,
                                                String nextContinuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(BLOCKED, flag(flow == ReplicationInventory.Flow.BLOCKED
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        result.put(ENTRIES, new DocumentValue.Sequence(entries.stream()
                .map(AgentResults::entryOf)
                .toList()));
        if (!NO_MORE_PAGES.equals(nextContinuationToken)) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(nextContinuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one flush produces.
     *
     * @param agentIdentifier which agent it was
     * @param removedEntryCount how much went
     * @return the result document
     */
    public static DocumentValue.Mapping flushedOf(String agentIdentifier, long removedEntryCount) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(AGENT_IDENTIFIER, new DocumentValue.Text(agentIdentifier));
        result.put(REMOVED_ENTRY_COUNT, new DocumentValue.Whole(removedEntryCount));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one retry produces.
     *
     * @param agentIdentifier which agent it was
     * @param entryIdentifier which entry it was
     * @param resubmission whether the platform took it
     * @return the result document
     */
    public static DocumentValue.Mapping retriedOf(String agentIdentifier, String entryIdentifier,
                                                  ReplicationInventory.Resubmission resubmission) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(AGENT_IDENTIFIER, new DocumentValue.Text(agentIdentifier));
        result.put(ENTRY_IDENTIFIER, new DocumentValue.Text(entryIdentifier));
        result.put(RESUBMITTED, flag(resubmission == ReplicationInventory.Resubmission.TAKEN
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(result);
    }

    private static SequencedMap<String, DocumentValue> agentMembers(
            ReplicationInventory.Agent agent) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(AGENT_IDENTIFIER, new DocumentValue.Text(agent.agentIdentifier()));
        match.put(TITLE, new DocumentValue.Text(agent.title()));
        match.put(REPOSITORY_PATH, new DocumentValue.Text(agent.repositoryPath()));
        match.put(TRANSPORT_KIND, new DocumentValue.Text(agent.transportKind().spelling()));
        match.put(ENABLED, flag(agent.switched() == ReplicationInventory.Switch.ENABLED
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        match.put(QUEUE_BLOCKED, flag(agent.flow() == ReplicationInventory.Flow.BLOCKED
                ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        match.put(QUEUED_ENTRY_COUNT, new DocumentValue.Whole(agent.queuedEntryCount()));
        return match;
    }

    private static DocumentValue entryOf(ReplicationInventory.Entry entry) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(ENTRY_IDENTIFIER, new DocumentValue.Text(entry.entryIdentifier()));
        held.put(ACTION, new DocumentValue.Text(entry.action().spelling()));
        held.put(CONTENT_PATH, new DocumentValue.Text(entry.contentPath()));
        held.put(ATTEMPT_COUNT, new DocumentValue.Whole(entry.attemptCount()));
        if (!ReplicationInventory.NEVER_FAILED.equals(entry.lastFailureCategory())) {
            held.put(LAST_FAILURE_CATEGORY,
                    new DocumentValue.Text(entry.lastFailureCategory()));
        }
        return new DocumentValue.Mapping(held);
    }

    private static DocumentValue.Flag flag(DocumentValue.Truth held) {
        return new DocumentValue.Flag(held);
    }
}
