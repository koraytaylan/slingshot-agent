// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;
import java.util.SequencedMap;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * What answers questions about workflows and starts, ends, or holds them.
 *
 * <p>Every call takes the caller's own session. A workflow started through this agent runs as work
 * the platform carries out afterwards, and it must only ever be started on a payload the caller
 * could have changed themselves — otherwise this command is a way to make the platform do, in the
 * background and under its own identity, exactly what the caller was refused in the foreground.
 * Passing the session across the seam is what makes that checkable rather than intended.</p>
 */
public interface WorkflowService {

    /**
     * One workflow model as a listing names it.
     *
     * @param modelIdentifier what the platform calls it
     * @param title what a person calls it
     * @param version which version of it this is, or {@link #UNVERSIONED}
     */
    record Model(String modelIdentifier, String title, String version) {
    }

    /** What a listing says when a model carries no version of its own. */
    String UNVERSIONED = "";

    /** What a listing says when nothing recorded when an instance started. */
    String NOT_RECORDED = "";

    /** What an instance says when nobody is assigned to a work item. */
    String UNASSIGNED = "";

    /**
     * One workflow instance as a listing names it.
     *
     * @param instanceIdentifier what the platform calls it
     * @param modelIdentifier which model it is running
     * @param payloadPath what it is running on
     * @param state what state it is in
     * @param startedAt when it started, or {@link #NOT_RECORDED}
     */
    record Instance(String instanceIdentifier, String modelIdentifier, String payloadPath,
                    WorkflowInstanceState state, String startedAt) {
    }

    /**
     * One outstanding work item.
     *
     * @param workItemIdentifier what the platform calls it
     * @param nodeTitle what step of the model it is sitting at
     * @param assignee who it is with, or {@link #UNASSIGNED}
     */
    record WorkItem(String workItemIdentifier, String nodeTitle, String assignee) {
    }

    /**
     * What one instance is, in full.
     *
     * @param instance the instance itself
     * @param workItems what is outstanding on it
     */
    record Detail(Instance instance, List<WorkItem> workItems) {

        /** Holds the work items apart from whatever produced them. */
        public Detail {
            workItems = List.copyOf(workItems);
        }
    }

    /** What one workflow call produced. */
    sealed interface Outcome permits Models, Instances, Inspected, Moved, Started, Refused {
    }

    /**
     * The models a listing found.
     *
     * @param models what it found, in the platform's own order
     */
    record Models(List<Model> models) implements Outcome {

        /** Holds the models apart from whatever produced them. */
        public Models {
            models = List.copyOf(models);
        }
    }

    /**
     * The instances a search found.
     *
     * @param instances what it found, in the platform's own order
     */
    record Instances(List<Instance> instances) implements Outcome {

        /** Holds the instances apart from whatever produced them. */
        public Instances {
            instances = List.copyOf(instances);
        }
    }

    /**
     * One instance in full.
     *
     * @param detail what it is
     */
    record Inspected(Detail detail) implements Outcome {
    }

    /**
     * What state an instance ended up in.
     *
     * <p>Reported rather than assumed, and it is very often not what was asked for: an instance
     * asked to suspend may have completed a moment earlier, which is not a failure and is not what
     * the caller wanted either.</p>
     *
     * @param observed what state it is in now
     */
    record Moved(WorkflowInstanceState observed) implements Outcome {
    }

    /**
     * A workflow that started.
     *
     * @param instance what started
     */
    record Started(Instance instance) implements Outcome {
    }

    /**
     * The platform would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said
     */
    record Refused(String category, String detail) implements Outcome {
    }

    /**
     * The models whose title begins with one prefix.
     *
     * @param titlePrefix what a title begins with, which is empty for every model
     * @param session the caller's own session, so a model they cannot see is one that is not there
     * @return what it found, or the reason there is nothing
     */
    Outcome models(String titlePrefix, ResourceResolver session);

    /**
     * Starts one model on one payload.
     *
     * @param modelIdentifier which model
     * @param payloadPath what to run it on, which the caller must be able to change themselves
     * @param metadata what to record on it, by name
     * @param session the caller's own session
     * @return what started, or the reason nothing did
     */
    Outcome start(String modelIdentifier, String payloadPath,
                  SequencedMap<String, String> metadata, ResourceResolver session);

    /**
     * The instances matching a model, a payload prefix, and a set of states.
     *
     * @param query what to match
     * @param session the caller's own session
     * @return what it found, or the reason there is nothing
     */
    Outcome instances(InstanceQuery query, ResourceResolver session);

    /**
     * What to match when searching for instances.
     *
     * @param modelIdentifier which model, or empty for every model
     * @param payloadPrefix what a payload path begins with, or empty for every payload
     * @param states which states to include, which is never empty
     */
    record InstanceQuery(String modelIdentifier, String payloadPrefix,
                         List<WorkflowInstanceState> states) {

        /** Holds a query whose states nothing can change afterwards. */
        public InstanceQuery {
            states = List.copyOf(states);
        }
    }

    /**
     * One instance in full.
     *
     * @param instanceIdentifier which instance
     * @param session the caller's own session
     * @return what it is, or the reason there is nothing
     */
    Outcome inspect(String instanceIdentifier, ResourceResolver session);

    /**
     * Ends one instance before it finishes.
     *
     * @param instanceIdentifier which instance
     * @param session the caller's own session
     * @return the state it is in now, or the reason nothing happened
     */
    Outcome terminate(String instanceIdentifier, ResourceResolver session);

    /**
     * Holds one instance or lets it go again.
     *
     * @param instanceIdentifier which instance
     * @param requested which of the two states to put it in
     * @param session the caller's own session
     * @return the state it is in now, or the reason nothing happened
     */
    Outcome suspend(String instanceIdentifier, SuspensionState requested,
                    ResourceResolver session);
}
