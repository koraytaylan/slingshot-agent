// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.List;
import rs.slingshot.agent.command.platform.ContentAdmission;
import rs.slingshot.agent.command.platform.JobInventory;

/**
 * The platform adapters another bundle provides, as they are bound.
 *
 * <p>Each is a list of at most one rather than a value that may be absent: a seam nobody bound is
 * an empty list, and the commands it answers are then not registered at all rather than
 * registered and failing on every call.</p>
 *
 * @param jobs what answers the four job commands
 * @param admissions what offers content to the replication service
 */
record PlatformSeams(List<JobInventory> jobs, List<ContentAdmission> admissions)
        implements java.io.Serializable {

    /** No adapter bound, which is what a Sling runtime without the platform bundle has. */
    static final PlatformSeams NONE = new PlatformSeams(List.of(), List.of());

    /** Holds the lists apart from whatever produced them. */
    PlatformSeams {
        jobs = List.copyOf(jobs);
        admissions = List.copyOf(admissions);
    }

    /**
     * These seams with the job inventory replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withJobs(List<JobInventory> bound) {
        return new PlatformSeams(bound, admissions);
    }

    /**
     * These seams with the content admission replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withAdmissions(List<ContentAdmission> bound) {
        return new PlatformSeams(jobs, bound);
    }
}
