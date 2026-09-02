// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;

/**
 * What answers questions about the platform's job queues and cancels work in them.
 *
 * <p>A job's properties never cross this seam as values. They belong to whatever created the job —
 * a replication agent, a workflow step, somebody's own service — and they routinely carry content
 * addresses and occasionally a credential. What crosses is the <em>names</em>, which is enough to
 * tell an operator what kind of work a stuck job is, and no more.</p>
 */
public interface JobInventory {

    /**
     * One queue as a listing names it.
     *
     * @param queueName what the platform calls it
     * @param state whether it is taking work
     * @param activeJobCount how many jobs are running in it now
     * @param queuedJobCount how many are waiting
     */
    record Queue(String queueName, SuspensionState state, long activeJobCount,
                 long queuedJobCount) {
    }

    /**
     * One job as a listing names it.
     *
     * @param jobIdentifier what the platform calls it
     * @param topic what kind of work it is
     * @param queueName which queue holds it, or {@link #NO_QUEUE}
     * @param state what state it is in
     * @param retryCount how many times it has been tried
     */
    record Job(String jobIdentifier, String topic, String queueName, JobState state,
               long retryCount) {
    }

    /** What a listing says when a job is in no queue the platform will name. */
    String NO_QUEUE = "";

    /**
     * What one job is, in full, with the names of its properties and none of their values.
     *
     * @param job the job itself
     * @param propertyKeys the names of what it carries, and never what they hold
     * @param maximumRetryCount how many times the platform will try it
     */
    record JobDetail(Job job, List<String> propertyKeys, long maximumRetryCount) {

        /** Holds the property names apart from whatever produced them. */
        public JobDetail {
            propertyKeys = List.copyOf(propertyKeys);
        }
    }

    /** What one job call produced. */
    sealed interface Outcome permits Queues, Jobs, Inspected, Cancelled, Refused {
    }

    /**
     * The queues a listing found.
     *
     * @param queues what it found, in the platform's own order
     */
    record Queues(List<Queue> queues) implements Outcome {

        /** Holds the queues apart from whatever produced them. */
        public Queues {
            queues = List.copyOf(queues);
        }
    }

    /**
     * The jobs a search found.
     *
     * @param jobs what it found, in the platform's own order
     */
    record Jobs(List<Job> jobs) implements Outcome {

        /** Holds the jobs apart from whatever produced them. */
        public Jobs {
            jobs = List.copyOf(jobs);
        }
    }

    /**
     * One job in full.
     *
     * @param detail what it is
     */
    record Inspected(JobDetail detail) implements Outcome {
    }

    /**
     * What state a job ended up in.
     *
     * @param observed what state it is in now, which is reported rather than assumed
     */
    record Cancelled(JobState observed) implements Outcome {
    }

    /**
     * The platform would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said, carrying no job property value
     */
    record Refused(String category, String detail) implements Outcome {
    }

    /**
     * Every queue the platform holds.
     *
     * @return what it found, or the reason there is nothing
     */
    Outcome queues();

    /**
     * The jobs on one topic whose state is one of a set.
     *
     * @param topic what kind of work, or empty for every topic
     * @param states which states to include, which is never empty
     * @return what it found, or the reason there is nothing
     */
    Outcome jobs(String topic, List<JobState> states);

    /**
     * One job in full.
     *
     * @param jobIdentifier which job
     * @return what it is, or the reason there is nothing
     */
    Outcome inspect(String jobIdentifier);

    /**
     * Cancels one job and reports where it ended up.
     *
     * @param jobIdentifier which job
     * @return the state it is in now, or the reason nothing happened
     */
    Outcome cancel(String jobIdentifier);
}
