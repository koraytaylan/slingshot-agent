// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.Queue;
import org.apache.sling.event.jobs.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.JobState;
import rs.slingshot.agent.command.platform.SuspensionState;

/**
 * The job commands' adapter, driven over a job manager whose answers the suite scripts.
 *
 * <p>The job manager is an interface the platform implements, so what is proved here is the
 * translation: which of its queries a state asks, that a job found by two of them is reported once,
 * that no property value leaves it, and which of its methods a cancellation calls.</p>
 */
final class DefaultJobInventoryTest {

    /** How many jobs one search may read here, which is small enough to see it applied. */
    private static final long LIMIT = 7;

    @Test
    @DisplayName("a queue reads as its name, whether it moves, and its two counts kept apart")
    void aqueueReadsAsItsState() {
        final Scripted manager = new Scripted();
        manager.queues.add(queue("main", false, 2, 5));
        manager.queues.add(queue("held", true, 0, 1));
        final List<JobInventory.Queue> queues = assertInstanceOf(JobInventory.Queues.class,
                inventory(manager).queues()).queues();
        assertEquals(List.of(new JobInventory.Queue("main", SuspensionState.RUNNING, 2, 5),
                new JobInventory.Queue("held", SuspensionState.SUSPENDED, 0, 1)), queues);
    }

    @Test
    @DisplayName("a search asks the queries its states need, once each, and reports a job once")
    void asearchAsksItsQueriesOnce() {
        final Scripted manager = new Scripted();
        manager.jobs.put("gave-up", job("gave-up", "topic/a", "main", Job.JobState.GIVEN_UP, 3));
        manager.jobs.put("failed", job("failed", "topic/a", "", Job.JobState.ERROR, 1));
        manager.jobs.put("running", job("running", "topic/b", "main", Job.JobState.ACTIVE, 0));
        final List<JobInventory.Job> found = assertInstanceOf(JobInventory.Jobs.class,
                inventory(manager).jobs("", List.of(JobState.ERROR, JobState.ERROR)))
                .jobs();
        assertEquals(List.of("failed", "gave-up"), found.stream()
                .map(JobInventory.Job::jobIdentifier).toList());
        assertEquals(List.of("ERROR", "GIVEN_UP"), manager.asked,
                "a state was asked for twice, or the error state missed jobs that gave up");
        assertEquals(List.of(JobInventory.NO_QUEUE), found.stream()
                .filter(job -> "failed".equals(job.jobIdentifier()))
                .map(JobInventory.Job::queueName).toList());
        assertTrue(manager.limits.stream().allMatch(limit -> limit == LIMIT),
                "a search read without its bound");
        inventory(manager).jobs("topic/b", List.of(JobState.ACTIVE, JobState.QUEUED,
                JobState.SUCCEEDED, JobState.CANCELLED, JobState.DROPPED));
        assertEquals(List.of("topic/b"), manager.topics.stream().distinct()
                .filter(topic -> !topic.isEmpty()).toList(),
                "a search for one topic asked the job manager about every topic");
    }

    @Test
    @DisplayName("an inspection reports property names and never a value")
    void aninspectionReportsNamesOnly() {
        final Scripted manager = new Scripted();
        manager.jobs.put("one", job("one", "topic/a", "main", Job.JobState.QUEUED, 1));
        final JobInventory.JobDetail detail = assertInstanceOf(JobInventory.Inspected.class,
                inventory(manager).inspect("one")).detail();
        assertEquals(List.of("alpha", "zulu"), detail.propertyKeys(),
                "the names are not in ascending order");
        assertEquals(JobState.QUEUED, detail.job().state());
        assertEquals("job_not_found", assertInstanceOf(JobInventory.Refused.class,
                inventory(manager).inspect("none")).category());
    }

    @Test
    @DisplayName("a running job is stopped, a waiting one removed, and an ended one refused")
    void acancellationActsOnWhatTheJobIs() {
        final Scripted manager = new Scripted();
        manager.jobs.put("running", job("running", "t", "q", Job.JobState.ACTIVE, 0));
        manager.jobs.put("waiting", job("waiting", "t", "q", Job.JobState.QUEUED, 0));
        manager.jobs.put("done", job("done", "t", "q", Job.JobState.SUCCEEDED, 0));
        assertEquals(JobState.CANCELLED, assertInstanceOf(JobInventory.Cancelled.class,
                inventory(manager).cancel("running")).observed());
        assertEquals(JobState.CANCELLED, assertInstanceOf(JobInventory.Cancelled.class,
                inventory(manager).cancel("waiting")).observed());
        assertEquals(List.of("stop:running", "remove:waiting"), manager.controls);
        assertEquals("job_not_cancellable", assertInstanceOf(JobInventory.Refused.class,
                inventory(manager).cancel("done")).category());
        assertEquals("job_not_found", assertInstanceOf(JobInventory.Refused.class,
                inventory(manager).cancel("none")).category());
    }

    @Test
    @DisplayName("every one of the job manager's states reads as one of the client's")
    void everyStateReadsAsTheClients() {
        final Map<Job.JobState, JobState> expected = new LinkedHashMap<>();
        expected.put(Job.JobState.QUEUED, JobState.QUEUED);
        expected.put(Job.JobState.ACTIVE, JobState.ACTIVE);
        expected.put(Job.JobState.SUCCEEDED, JobState.SUCCEEDED);
        expected.put(Job.JobState.STOPPED, JobState.CANCELLED);
        expected.put(Job.JobState.GIVEN_UP, JobState.ERROR);
        expected.put(Job.JobState.ERROR, JobState.ERROR);
        expected.put(Job.JobState.DROPPED, JobState.DROPPED);
        expected.forEach((platform, client) ->
                assertEquals(client, DefaultJobInventory.stateOf(platform), platform.name()));
        assertEquals(Set.of(Job.JobState.values()), expected.keySet());
    }

    private static DefaultJobInventory inventory(Scripted manager) {
        return new DefaultJobInventory(manager.service(), LIMIT);
    }

    /** A job manager whose queues and jobs the suite holds, recording what it was asked. */
    private static final class Scripted {

        private final List<Queue> queues = new ArrayList<>();
        private final Map<String, Job> jobs = new LinkedHashMap<>();
        private final List<String> asked = new ArrayList<>();
        private final List<String> topics = new ArrayList<>();
        private final List<Long> limits = new ArrayList<>();
        private final List<String> controls = new ArrayList<>();

        JobManager service() {
            return (JobManager) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {JobManager.class}, (proxy, method, arguments) ->
                            switch (method.getName()) {
                                case "getQueues" -> queues;
                                case "getJobById" -> jobs.get((String) arguments[0]);
                                case "findJobs" -> found((JobManager.QueryType) arguments[0],
                                        (String) arguments[1], (Long) arguments[2]);
                                case "stopJobById" -> controlled("stop", (String) arguments[0]);
                                case "removeJobById" -> controlled("remove",
                                        (String) arguments[0]) != null;
                                default -> throw new UnsupportedOperationException(
                                        method.getName());
                            });
        }

        private List<Job> found(JobManager.QueryType type, String topic, long limit) {
            asked.add(type.name());
            topics.add(topic == null ? "" : topic);
            limits.add(limit);
            return jobs.values().stream()
                    .filter(job -> topic == null || topic.equals(job.getTopic()))
                    .filter(job -> matches(type, job.getJobState()))
                    .toList();
        }

        private Object controlled(String control, String identifier) {
            controls.add(control + ":" + identifier);
            jobs.remove(identifier);
            return control;
        }

        private static boolean matches(JobManager.QueryType type, Job.JobState state) {
            return type.name().equals(state.name());
        }
    }

    private static Queue queue(String name, boolean suspended, long active, long queued) {
        final Statistics statistics = (Statistics) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Statistics.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getNumberOfActiveJobs" -> active;
                            case "getNumberOfQueuedJobs" -> queued;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        return (Queue) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Queue.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getName" -> name;
                            case "isSuspended" -> suspended;
                            case "getStatistics" -> statistics;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static Job job(String identifier, String topic, String queue, Job.JobState state,
                           int retries) {
        return (Job) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Job.class}, (proxy, method, arguments) ->
                        switch (method.getName()) {
                            case "getId" -> identifier;
                            case "getTopic" -> topic;
                            case "getQueueName" -> queue.isEmpty() ? null : queue;
                            case "getJobState" -> state;
                            case "getRetryCount" -> retries;
                            case "getNumberOfRetries" -> retries + 2;
                            case "getPropertyNames" -> Set.of("zulu", "alpha");
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
    }
}
