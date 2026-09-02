// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.WorkflowInstanceState;
import rs.slingshot.agent.command.platform.WorkflowService;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the six workflow commands answer.
 *
 * <p>No workflow variable appears anywhere here, and that is a rule rather than an omission. A
 * workflow's variables belong to whatever created it: they routinely hold content, repository
 * addresses, and — in the ones people write themselves — occasionally a token. What an operator
 * actually needs is which model is running, on what, in what state, and who it is waiting for, and
 * all four of those are here.</p>
 */
public final class WorkflowResults {

    private WorkflowResults() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** The member a model's identifier is carried in. */
    public static final String MODEL_IDENTIFIER = "model_identifier";

    /** The member a model's title is carried in. */
    public static final String TITLE = "title";

    /** The member a model's version is carried in, where it has one. */
    public static final String VERSION = "version";

    /** The member an instance's identifier is carried in. */
    public static final String INSTANCE_IDENTIFIER = "instance_identifier";

    /** The member an instance's payload is carried in. */
    public static final String PAYLOAD_PATH = "payload_path";

    /** The member a state is carried in. */
    public static final String STATE = "state";

    /** The member the state an instance ended up in is carried in. */
    public static final String OBSERVED_STATE = "observed_state";

    /** The member when an instance started is carried in, where that was recorded. */
    public static final String STARTED_AT = "started_at";

    /** The member the outstanding work items are carried in. */
    public static final String WORK_ITEMS = "work_items";

    /** The member a work item's identifier is carried in. */
    public static final String WORK_ITEM_IDENTIFIER = "work_item_identifier";

    /** The member the step a work item sits at is carried in. */
    public static final String NODE_TITLE = "node_title";

    /** The member who a work item is with is carried in, where somebody has it. */
    public static final String ASSIGNEE = "assignee";

    /** Every member a model listing has. */
    public static final List<String> MODEL_MEMBERS =
            List.of(MATCHES, MODEL_IDENTIFIER, NEXT_CONTINUATION_TOKEN, TITLE, VERSION);

    /** Every member an instance search has. */
    public static final List<String> INSTANCE_MEMBERS = List.of(INSTANCE_IDENTIFIER, MATCHES,
            MODEL_IDENTIFIER, NEXT_CONTINUATION_TOKEN, PAYLOAD_PATH, STARTED_AT, STATE);

    /** Every member an instance inspection has. */
    public static final List<String> DETAIL_MEMBERS = List.of(ASSIGNEE, INSTANCE_IDENTIFIER,
            MODEL_IDENTIFIER, NODE_TITLE, PAYLOAD_PATH, STATE, WORK_ITEMS, WORK_ITEM_IDENTIFIER);

    /** Every member a start's answer has. */
    public static final List<String> START_MEMBERS =
            List.of(INSTANCE_IDENTIFIER, MODEL_IDENTIFIER, STATE);

    /** Every member a control's answer has. */
    public static final List<String> CONTROL_MEMBERS =
            List.of(INSTANCE_IDENTIFIER, OBSERVED_STATE);

    /** What the token member says when this is the last page. */
    public static final String NO_MORE_PAGES = "";

    /**
     * The result one model listing produces.
     *
     * @param models what it found, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping modelsOf(List<WorkflowService.Model> models,
                                                 String nextContinuationToken) {
        return paged(models.stream().map(WorkflowResults::modelOf).toList(),
                nextContinuationToken);
    }

    /**
     * The result one instance search produces.
     *
     * @param instances what it found, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping instancesOf(List<WorkflowService.Instance> instances,
                                                    String nextContinuationToken) {
        return paged(instances.stream().map(WorkflowResults::instanceOf).toList(),
                nextContinuationToken);
    }

    /**
     * The result one inspection produces.
     *
     * @param detail what the instance is
     * @return the result document
     */
    public static DocumentValue.Mapping detailOf(WorkflowService.Detail detail) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(INSTANCE_IDENTIFIER,
                new DocumentValue.Text(detail.instance().instanceIdentifier()));
        result.put(MODEL_IDENTIFIER, new DocumentValue.Text(detail.instance().modelIdentifier()));
        result.put(PAYLOAD_PATH, new DocumentValue.Text(detail.instance().payloadPath()));
        result.put(STATE, new DocumentValue.Text(detail.instance().state().spelling()));
        result.put(WORK_ITEMS, new DocumentValue.Sequence(detail.workItems().stream()
                .map(WorkflowResults::workItemOf)
                .toList()));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one start produces.
     *
     * @param instance what started
     * @return the result document
     */
    public static DocumentValue.Mapping startedOf(WorkflowService.Instance instance) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(INSTANCE_IDENTIFIER, new DocumentValue.Text(instance.instanceIdentifier()));
        result.put(MODEL_IDENTIFIER, new DocumentValue.Text(instance.modelIdentifier()));
        result.put(STATE, new DocumentValue.Text(instance.state().spelling()));
        return new DocumentValue.Mapping(result);
    }

    /**
     * The result one control produces.
     *
     * @param instanceIdentifier which instance it was
     * @param observed what state it is in now, which is reported rather than assumed
     * @return the result document
     */
    public static DocumentValue.Mapping controlledOf(String instanceIdentifier,
                                                     WorkflowInstanceState observed) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(INSTANCE_IDENTIFIER, new DocumentValue.Text(instanceIdentifier));
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

    private static DocumentValue modelOf(WorkflowService.Model model) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(MODEL_IDENTIFIER, new DocumentValue.Text(model.modelIdentifier()));
        match.put(TITLE, new DocumentValue.Text(model.title()));
        if (!WorkflowService.UNVERSIONED.equals(model.version())) {
            match.put(VERSION, new DocumentValue.Text(model.version()));
        }
        return new DocumentValue.Mapping(match);
    }

    private static DocumentValue instanceOf(WorkflowService.Instance instance) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(INSTANCE_IDENTIFIER, new DocumentValue.Text(instance.instanceIdentifier()));
        match.put(MODEL_IDENTIFIER, new DocumentValue.Text(instance.modelIdentifier()));
        match.put(PAYLOAD_PATH, new DocumentValue.Text(instance.payloadPath()));
        match.put(STATE, new DocumentValue.Text(instance.state().spelling()));
        if (!WorkflowService.NOT_RECORDED.equals(instance.startedAt())) {
            match.put(STARTED_AT, new DocumentValue.Text(instance.startedAt()));
        }
        return new DocumentValue.Mapping(match);
    }

    private static DocumentValue workItemOf(WorkflowService.WorkItem item) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(WORK_ITEM_IDENTIFIER, new DocumentValue.Text(item.workItemIdentifier()));
        held.put(NODE_TITLE, new DocumentValue.Text(item.nodeTitle()));
        if (!WorkflowService.UNASSIGNED.equals(item.assignee())) {
            held.put(ASSIGNEE, new DocumentValue.Text(item.assignee()));
        }
        return new DocumentValue.Mapping(held);
    }
}
