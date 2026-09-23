// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.List;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.command.platform.ConfigurationCatalogues;
import rs.slingshot.agent.command.platform.ContentAdmission;
import rs.slingshot.agent.command.platform.JobInventory;
import rs.slingshot.agent.command.platform.ReplicationInventory;
import rs.slingshot.agent.command.platform.WorkflowService;

/**
 * The platform adapters another bundle provides, as they are bound.
 *
 * <p>Each is a list of at most one rather than a value that may be absent: a seam nobody bound is
 * an empty list, and the commands it answers are then not registered at all rather than
 * registered and failing on every call.</p>
 *
 * @param jobs what answers the four job commands
 * @param admissions what offers content to the replication service
 * @param workflows what answers the six workflow commands
 * @param bundles what answers the three bundle and component commands
 * @param agents what answers the five replication agent and queue commands
 * @param configurations what answers the four configuration commands
 */
record PlatformSeams(List<JobInventory> jobs, List<ContentAdmission> admissions,
                     List<WorkflowService> workflows, List<BundleInventory> bundles,
                     List<ReplicationInventory> agents,
                     List<ConfigurationCatalogues> configurations)
        implements java.io.Serializable {

    /** No adapter bound, which is what a Sling runtime without the platform bundle has. */
    static final PlatformSeams NONE = new PlatformSeams(List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());

    /** Holds the lists apart from whatever produced them. */
    PlatformSeams {
        jobs = List.copyOf(jobs);
        admissions = List.copyOf(admissions);
        workflows = List.copyOf(workflows);
        bundles = List.copyOf(bundles);
        agents = List.copyOf(agents);
        configurations = List.copyOf(configurations);
    }

    /**
     * These seams with the job inventory replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withJobs(List<JobInventory> bound) {
        return new PlatformSeams(bound, admissions, workflows, bundles, agents,
                configurations);
    }

    /**
     * These seams with the content admission replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withAdmissions(List<ContentAdmission> bound) {
        return new PlatformSeams(jobs, bound, workflows, bundles, agents, configurations);
    }

    /**
     * These seams with the workflow service replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withWorkflows(List<WorkflowService> bound) {
        return new PlatformSeams(jobs, admissions, bound, bundles, agents, configurations);
    }

    /**
     * These seams with the bundle inventory replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withBundles(List<BundleInventory> bound) {
        return new PlatformSeams(jobs, admissions, workflows, bound, agents, configurations);
    }

    /**
     * These seams with the replication agent inventory replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withAgents(List<ReplicationInventory> bound) {
        return new PlatformSeams(jobs, admissions, workflows, bundles, bound, configurations);
    }

    /**
     * These seams with the configuration catalogues replaced.
     *
     * @param bound what is bound now, which is empty where it went away
     * @return the seams
     */
    PlatformSeams withConfigurations(List<ConfigurationCatalogues> bound) {
        return new PlatformSeams(jobs, admissions, workflows, bundles, agents, bound);
    }
}
