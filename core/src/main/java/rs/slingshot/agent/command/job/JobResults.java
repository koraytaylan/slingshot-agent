// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.JobState;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the four job commands answer.
 *
 * <p>The inspection carries property <em>names</em> and no values, and that asymmetry is the whole
 * design. The names tell an operator what kind of work a stuck job is — a job carrying
 * {@code path} and {@code agentId} is a replication; one carrying {@code model} is a workflow — and
 * that is enough to know where to look. The values are somebody else's content and occasionally
 * somebody else's credential, and a command about a queue is not where either belongs.</p>
 */
public final class JobResults {

    private JobResults() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** The member a queue's name is carried in. */
    public static final String QUEUE_NAME = "queue_name";

    /** The member the count of running jobs is carried in. */
    public static final String ACTIVE_JOB_COUNT = "active_job_count";

    /** The member the count of waiting jobs is carried in. */
    public static final String QUEUED_JOB_COUNT = "queued_job_count";

    /** The member a state is carried in. */
    public static final String STATE = "state";

    /** The member a job's identifier is carried in. */
    public static final String JOB_IDENTIFIER = "job_identifier";

    /** The member a job's topic is carried in. */
    public static final String TOPIC = "topic";

    /** The member the count of attempts is carried in. */
    public static final String RETRY_COUNT = "retry_count";

    /** The member the count of attempts the platform will make is carried in. */
    public static final String MAXIMUM_RETRY_COUNT = "maximum_retry_count";

    /** The member the names of what a job carries are held in, and never their values. */
    public static final String PROPERTY_KEYS = "property_keys";

    /** The member the state a job ended up in is carried in. */
    public static final String OBSERVED_STATE = "observed_state";

    /** Every member a queue listing has. */
    public static final List<String> QUEUE_MEMBERS = List.of(ACTIVE_JOB_COUNT, MATCHES,
            NEXT_CONTINUATION_TOKEN, QUEUE_NAME, QUEUED_JOB_COUNT, STATE);

    /** Every member a job search has. */
    public static final List<String> SEARCH_MEMBERS = List.of(JOB_IDENTIFIER, MATCHES,
            NEXT_CONTINUATION_TOKEN, QUEUE_NAME, RETRY_COUNT, STATE, TOPIC);

    /** Every member a job inspection has. */
    public static final List<String> DETAIL_MEMBERS = List.of(JOB_IDENTIFIER, MAXIMUM_RETRY_COUNT,
            PROPERTY_KEYS, QUEUE_NAME, RETRY_COUNT, STATE, TOPIC);

    /** Every member a cancellation's answer has. */
    public static final List<String> CANCELLATION_MEMBERS =
            List.of(JOB_IDENTIFIER, OBSERVED_STATE);

    /** What the token member says when this is the last page. */
    public static final String NO_MORE_PAGES = "";

    /**
     * The result one queue listing produces.
     *
     * @param queues what it found, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping queuesOf(List<JobInventory.Queue> queues,
                                                 String nextContinuationToken) {
        return paged(queues.stream().map(JobResults::queueOf).toList(), nextContinuationToken);
    }

    /**
     * The result one job search produces.
     *
     * @param jobs what it found, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping jobsOf(List<JobInventory.Job> jobs,
                                               String nextContinuationToken) {
        return paged(jobs.stream().map(JobResults::jobOf).toList(), nextContinuationToken);
    }

    /**
     * The result one inspection produces.
     *
     * @param detail what the job is
     * @return the result document
     */
    public static DocumentValue.Mapping detailOf(JobInventory.JobDetail detail) {
        final SequencedMap<String, DocumentValue> result =
                new LinkedHashMap<>(jobMembers(detail.job()));
        result.put(MAXIMUM_RETRY_COUNT, new DocumentValue.Whole(detail.maximumRetryCount()));
        result.put(PROPERTY_KEYS, new DocumentValue.Sequence(detail.propertyKeys().stream()
                .map(name -> (DocumentValue) new DocumentValue.Text(name))
                .toList()));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one cancellation produces.
     *
     * @param jobIdentifier which job it was
     * @param observed what state it is in now, which is reported rather than assumed
     * @return the result document
     */
    public static DocumentValue.Mapping cancelledOf(String jobIdentifier, JobState observed) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(JOB_IDENTIFIER, new DocumentValue.Text(jobIdentifier));
        result.put(OBSERVED_STATE, new DocumentValue.Text(observed.spelling()));
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue.Mapping paged(List<DocumentValue> matches,
                                               String nextContinuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(matches));
        if (!NO_MORE_PAGES.equals(nextContinuationToken)) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(nextContinuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue queueOf(JobInventory.Queue queue) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(QUEUE_NAME, new DocumentValue.Text(queue.queueName()));
        match.put(STATE, new DocumentValue.Text(queue.state().spelling()));
        match.put(ACTIVE_JOB_COUNT, new DocumentValue.Whole(queue.activeJobCount()));
        match.put(QUEUED_JOB_COUNT, new DocumentValue.Whole(queue.queuedJobCount()));
        return new DocumentValue.Mapping(match);
    }

    private static DocumentValue jobOf(JobInventory.Job job) {
        return new DocumentValue.Mapping(jobMembers(job));
    }

    private static SequencedMap<String, DocumentValue> jobMembers(JobInventory.Job job) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(JOB_IDENTIFIER, new DocumentValue.Text(job.jobIdentifier()));
        match.put(TOPIC, new DocumentValue.Text(job.topic()));
        if (!JobInventory.NO_QUEUE.equals(job.queueName())) {
            match.put(QUEUE_NAME, new DocumentValue.Text(job.queueName()));
        }
        match.put(STATE, new DocumentValue.Text(job.state().spelling()));
        match.put(RETRY_COUNT, new DocumentValue.Whole(job.retryCount()));
        return match;
    }
}
