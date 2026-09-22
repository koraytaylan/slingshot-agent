// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.JobState;
import rs.slingshot.agent.command.platform.SuspensionState;

/**
 * The platform's own job manager, answering the four job commands.
 *
 * <p>Nothing here decides what a caller may do: the three listings read what the job manager
 * holds, and the cancellation has already passed the deployment's control gate before it is
 * asked. What this decides is how the job manager's own vocabulary reads in the client's, and
 * that no property value of any job crosses into an answer - only the names.</p>
 */
@Component(service = JobInventory.class)
public final class DefaultJobInventory implements JobInventory {

    /** What an identifier naming no job is reported as. */
    private static final String JOB_NOT_FOUND = "job_not_found";

    /** What a job that has already ended is reported as when asked to cancel. */
    private static final String JOB_NOT_CANCELLABLE = "job_not_cancellable";

    /**
     * The most jobs one search reads, one more than a caller may examine.
     *
     * <p>A search is bounded where the job manager can bound it. Reading one past the caller's
     * examination budget is what lets the handler refuse a search that found too many rather
     * than answering with the first part of them.</p>
     */
    private final long searchLimit;

    private final JobManager jobs;

    /**
     * Holds the inventory the platform's job manager answers.
     *
     * @param jobs the platform's own job manager
     */
    @Activate
    public DefaultJobInventory(@Reference JobManager jobs) {
        this(jobs, SEARCH_LIMIT);
    }

    /**
     * Holds the inventory with an explicit search bound, which a suite states to prove it.
     *
     * @param jobs the job manager
     * @param searchLimit the most jobs one search reads
     */
    DefaultJobInventory(JobManager jobs, long searchLimit) {
        this.jobs = jobs;
        this.searchLimit = searchLimit;
    }

    /** The default bound: one more than the contract's discovery candidate limit. */
    private static final long SEARCH_LIMIT = 100_001;

    @Override
    public Outcome queues() {
        return new Queues(StreamSupport.stream(jobs.getQueues().spliterator(), false)
                .map(DefaultJobInventory::queueOf)
                .toList());
    }

    @Override
    public Outcome jobs(String topic, List<JobState> states) {
        final Map<String, org.apache.sling.event.jobs.Job> found = new LinkedHashMap<>();
        for (final JobManager.QueryType type : queriesFor(states)) {
            for (final org.apache.sling.event.jobs.Job job : search(type, topic)) {
                found.putIfAbsent(job.getId(), job);
            }
        }
        return new Jobs(found.values().stream()
                .map(DefaultJobInventory::jobOf)
                .filter(job -> states.contains(job.state()))
                .toList());
    }

    @Override
    public Outcome inspect(String jobIdentifier) {
        final org.apache.sling.event.jobs.Job job = jobs.getJobById(jobIdentifier);
        if (job == null) {
            return new Refused(JOB_NOT_FOUND, jobIdentifier + " names no job the job manager"
                    + " holds");
        }
        return new Inspected(new JobDetail(jobOf(job),
                job.getPropertyNames().stream().sorted().toList(), job.getNumberOfRetries()));
    }

    @Override
    public Outcome cancel(String jobIdentifier) {
        final org.apache.sling.event.jobs.Job job = jobs.getJobById(jobIdentifier);
        if (job == null) {
            return new Refused(JOB_NOT_FOUND, jobIdentifier + " names no job the job manager"
                    + " holds");
        }
        final JobState before = stateOf(job.getJobState());
        if (before != JobState.QUEUED && before != JobState.ACTIVE) {
            return new Refused(JOB_NOT_CANCELLABLE, jobIdentifier + " has already ended as "
                    + before.spelling() + ", so there is nothing to cancel");
        }
        if (before == JobState.ACTIVE) {
            jobs.stopJobById(jobIdentifier);
        } else {
            jobs.removeJobById(jobIdentifier);
        }
        final org.apache.sling.event.jobs.Job after = jobs.getJobById(jobIdentifier);
        return new Cancelled(after == null ? JobState.CANCELLED : stateOf(after.getJobState()));
    }

    private Collection<org.apache.sling.event.jobs.Job> search(JobManager.QueryType type, String topic) {
        // The job manager spells "every topic" as an absent one; policy/nullability.toml
        // declares this position.
        return topic.isEmpty()
                ? jobs.findJobs(type, null, searchLimit, NO_TEMPLATE)
                : jobs.findJobs(type, topic, searchLimit, NO_TEMPLATE);
    }

    /**
     * No property template at all, so a search is narrowed by its type and topic alone.
     *
     * <p>A template is a map, and an array of a generic type cannot be made without the compiler
     * warning that it cannot check it. An array of this one non-generic map type can, and it is still
     * the job manager's own template type.</p>
     */
    private static final Template[] NO_TEMPLATE = new Template[0];

    /** The job manager's property template, as one type that is not generic. */
    private interface Template extends Map<String, Object> {
    }

    /** The job manager's own queries that together hold every job in one of these states. */
    private static List<JobManager.QueryType> queriesFor(List<JobState> states) {
        final List<JobManager.QueryType> types = new ArrayList<>();
        for (final JobState state : states) {
            final List<JobManager.QueryType> held = switch (state) {
                case ACTIVE -> List.of(JobManager.QueryType.ACTIVE);
                case QUEUED -> List.of(JobManager.QueryType.QUEUED);
                case SUCCEEDED -> List.of(JobManager.QueryType.SUCCEEDED);
                case ERROR -> List.of(JobManager.QueryType.ERROR, JobManager.QueryType.GIVEN_UP);
                case CANCELLED -> List.of(JobManager.QueryType.CANCELLED,
                        JobManager.QueryType.STOPPED);
                case DROPPED -> List.of(JobManager.QueryType.DROPPED);
            };
            held.stream().filter(type -> !types.contains(type)).forEach(types::add);
        }
        return types;
    }

    private static Queue queueOf(org.apache.sling.event.jobs.Queue queue) {
        return new Queue(queue.getName(),
                queue.isSuspended() ? SuspensionState.SUSPENDED : SuspensionState.RUNNING,
                queue.getStatistics().getNumberOfActiveJobs(),
                queue.getStatistics().getNumberOfQueuedJobs());
    }

    private static Job jobOf(org.apache.sling.event.jobs.Job job) {
        return new Job(job.getId(), job.getTopic(),
                job.getQueueName() == null ? NO_QUEUE : job.getQueueName(),
                stateOf(job.getJobState()), job.getRetryCount());
    }

    /**
     * The client's word for one of the job manager's states.
     *
     * <p>A job that gave up after its retries is in error as far as a caller is concerned, and one
     * that was stopped was cancelled; the job manager's finer distinctions are not the client's.</p>
     *
     * @param state the job manager's own state
     * @return the client's word for it
     */
    static JobState stateOf(org.apache.sling.event.jobs.Job.JobState state) {
        return switch (state) {
            case QUEUED -> JobState.QUEUED;
            case ACTIVE -> JobState.ACTIVE;
            case SUCCEEDED -> JobState.SUCCEEDED;
            case STOPPED -> JobState.CANCELLED;
            case GIVEN_UP, ERROR -> JobState.ERROR;
            case DROPPED -> JobState.DROPPED;
        };
    }
}
