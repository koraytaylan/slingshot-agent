// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The closed refusal document one command's own client validates an ending against.
 *
 * <p>A handler fails with its registry row's category and the detail it observed. That is all this
 * side owes its own records, and it is not what the caller reads: the client authenticates the
 * failure bytes against the command's own closed refusal type - the category, the exact member
 * names, and the request correlation - before it will believe the ending. A category alone is
 * refused by that decoder, which is the difference between an operator reading "the anchor is not a
 * page" and reading that nobody knows what happened.</p>
 *
 * <p>So one place owns the mapping from a category to the client's own document. It is keyed by the
 * command's wire name and its category together, because the same command reports different
 * categories in different shapes: a discovery command's anchor refusal carries its root while its
 * exhausted budget carries a budget name and nothing else, and a continuation refusal carries
 * neither. A command this table does not know falls back to the shared category document, which is
 * what every command that declares no refusal shape of its own produces.</p>
 */
public final class CommandRefusalDocuments {

    private CommandRefusalDocuments() {
    }

    /** The member every refusal of this family carries its category in. */
    private static final String FAILURE = "failure";

    /** The member an exhausted discovery or package budget is named in. */
    private static final String BUDGET = "budget";

    /** The budget every discovery handler's single examination counter reports. */
    private static final String CANDIDATE_NODES = "candidate_nodes";

    /**
     * The refusal document the client reads for one failed command.
     *
     * @param wireName the command that failed
     * @param category the category its handler declared
     * @param arguments the argument document the caller sent
     * @return the client's own refusal document, whose members are exactly its own
     */
    public static DocumentValue.Mapping documentOf(String wireName, String category,
                                                   DocumentValue.Mapping arguments) {
        final SequencedMap<String, DocumentValue> refusal = new LinkedHashMap<>();
        refusal.put(FAILURE, new DocumentValue.Text(category));
        if (isContinuationFailure(category)) {
            // A continuation failure is the one-member shape: the request carried a token, so
            // naming an anchor beside it would be a second thing about a request that has one.
            return new DocumentValue.Mapping(refusal);
        }
        if (!budgetName(category).isEmpty()) {
            refusal.put(BUDGET, new DocumentValue.Text(budgetName(category)));
            return new DocumentValue.Mapping(refusal);
        }
        if ("configuration_value_unsupported".equals(category)
                || "configuration_value_malformed".equals(category)) {
            refusal.put("reason", new DocumentValue.Text(unsupportedReason(category)));
            return new DocumentValue.Mapping(refusal);
        }
        if ("replicate_content".equals(wireName)) {
            return replicationDocument(category, arguments, refusal);
        }
        for (final Correlation correlation : correlations(wireName, category, arguments)) {
            refusal.put(correlation.member(), new DocumentValue.Text(correlation.value()));
        }
        return new DocumentValue.Mapping(refusal);
    }

    /**
     * The replication command's two refusal families.
     *
     * <p>A preflight refusal names where it stopped and nothing else. An admission refusal is the
     * only one that reports counts: how many items were admitted, where the current one stood, and
     * how many remain, which is the shape its client's `AdmissionRefusal` type carries. The two are
     * told apart by the category, because the agent's own categories do not separate them.</p>
     */
    private static DocumentValue.Mapping replicationDocument(
            String category, DocumentValue.Mapping arguments,
            SequencedMap<String, DocumentValue> refusal) {
        final boolean preflight = switch (category) {
            case "source_not_found", "source_access_denied", "candidate_limit_exceeded" -> true;
            case "traversal_budget_exceeded" -> true;
            default -> false;
        };
        if (preflight) {
            refusal.put("path", new DocumentValue.Text(string(arguments, "path")));
            return new DocumentValue.Mapping(refusal);
        }
        refusal.put("accepted_item_count", new DocumentValue.Whole(0));
        refusal.put("current_path", new DocumentValue.Text(string(arguments, "path")));
        refusal.put("remaining_item_count", new DocumentValue.Whole(1));
        return new DocumentValue.Mapping(refusal);
    }

    /**
     * Which bounded budget one category exhausted, by command and category together.
     *
     * <p>The three budget families each have their own closed vocabulary: a discovery traversal
     * counts candidate nodes, a package build counts paths and bytes, and a configuration read
     * counts duration and matching configurations. Each command's own client reads the member its
     * type admits, so a command this table does not know reports no budget member at all rather
     * than one its decoder cannot parse.</p>
     */
    private static String budgetName(String category) {
        return switch (category) {
            case "discovery_budget_exceeded" -> CANDIDATE_NODES;
            case "evaluation_budget_exceeded" -> "candidate_paths";
            case "configuration_lookup_budget_exceeded" -> "lookup_duration";
            case "configuration_value_budget_exceeded" -> "scalar_string_bytes";
            case "configuration_result_budget_exceeded" -> "property_count";
            default -> "";
        };
    }

    /**
     * Why a configuration value could not be represented.
     *
     * <p>The agent's own refusal carries the cause in its detail; the client's closed vocabulary has
     * one spelling per reason and none for an unknown one. A malformed value is reported as a
     * key the contract will not read, which is the reason a caller can act on.</p>
     */
    private static String unsupportedReason(String category) {
        return "configuration_value_malformed".equals(category)
                ? "invalid_property_key" : "non_primary_type";
    }

    /** Whether one category is a continuation failure, which carries no correlation. */
    private static boolean isContinuationFailure(String category) {
        return switch (category) {
            case "continuation_token_malformed", "continuation_token_integrity_invalid" -> true;
            case "continuation_token_wrong_target", "continuation_token_wrong_query" -> true;
            case "continuation_token_expired" -> true;
            default -> false;
        };
    }

    /**
     * One member and the value the caller's own request gives it.
     *
     * @param member the member name the client's own refusal type declares
     * @param value the value the caller's request carried for that member
     */
    private record Correlation(String member, String value) {
    }

    /**
     * The correlation members one command's refusal carries, in the client's own names.
     *
     * <p>Almost always one member, and its value is the argument the caller sent that the refusal
     * is about. Two families carry more: a move names both ends, and a group membership names the
     * group and the member. A command whose row reports through a fieldless shared vocabulary - an
     * inventory listing, a configuration inspection, a package build - carries none, because its
     * client's own type has none.</p>
     */
    private static java.util.List<Correlation> correlations(String wireName, String category,
                                                            DocumentValue.Mapping arguments) {
        if (isFieldlessInventory(wireName)) {
            return java.util.List.of();
        }
        if ("download_content_package".equals(wireName)) {
            return packageCorrelation(category, arguments);
        }
        return addressedCorrelations(wireName, arguments);
    }

    /**
     * Whether one command's failure vocabulary is one a client reads as fieldless.
     *
     * <p>The operational listings and the configuration search report through shared inventory and
     * lookup types that carry no member a correlation could travel in, so naming one would be a
     * member their decoders refuse.</p>
     */
    private static boolean isFieldlessInventory(String wireName) {
        return switch (wireName) {
            case "list_replication_agents", "find_sling_jobs", "list_sling_job_queues" -> true;
            case "find_workflow_instances", "list_workflow_models" -> true;
            case "list_resource_mappings" -> true;
            case "list_open_service_gateway_initiative_bundles" -> true;
            case "list_open_service_gateway_initiative_components" -> true;
            case "find_open_service_gateway_initiative_configurations" -> true;
            default -> false;
        };
    }

    /** One family of commands whose refusal names the same members, chosen by wire name. */
    private enum Family {
        /** The six rooted discoveries, whose refusal names its anchor. */
        ROOTED_DISCOVERY("root_path"),
        /** One page's own address. */
        PAGE("page_path"),
        /** One component's own address. */
        COMPONENT("component_path"),
        /** One asset's own address. */
        ASSET("asset_path"),
        /** One fragment's own address. */
        FRAGMENT("fragment_path"),
        /** One variation's own address. */
        VARIATION("variation_path"),
        /** One move, naming both ends. */
        MOVE("source_path"),
        /** One group membership, naming both sides. */
        MEMBERSHIP("group_identifier"),
        /** One group's own member listing. */
        GROUP("group_identifier"),
        /** One authorizable. */
        AUTHORIZABLE("authorizable_identifier"),
        /** One replication agent's record. */
        AGENT("agent_identifier"),
        /** One Sling job. */
        JOB("job_identifier"),
        /** One workflow instance or model. */
        WORKFLOW("instance_identifier"),
        /** One configuration persistent identifier. */
        CONFIGURATION("persistent_identifier"),
        /** A read whose subject is an address, an authority, or a symbolic name. */
        SUBJECT("subject"),
        /** A creation whose refusal names the address the command computed. */
        CREATION("target_path"),
        /** A command this table does not name. */
        NONE("");

        private final String member;

        Family(String member) {
            this.member = member;
        }

        private String member() {
            return member;
        }
    }

    /** Which family one wire name belongs to. */
    private static Family familyOf(String wireName) {
        return switch (wireName) {
            case "list_child_pages", "find_pages_by_template", "find_pages_containing_phrase" ->
                    Family.ROOTED_DISCOVERY;
            case "find_pages_using_components", "find_assets_by_metadata", "query_paths" ->
                    Family.ROOTED_DISCOVERY;
            case "find_assets_referenced_by_page", "update_page", "delete_page" -> Family.PAGE;
            case "update_component", "delete_component", "reorder_component" -> Family.COMPONENT;
            case "list_asset_renditions", "update_asset_metadata", "delete_asset" -> Family.ASSET;
            case "read_content_fragment", "update_content_fragment" -> Family.FRAGMENT;
            case "delete_content_fragment", "delete_experience_fragment" -> Family.FRAGMENT;
            case "update_experience_fragment" -> Family.VARIATION;
            case "move_asset", "move_page" -> Family.MOVE;
            case "add_group_member", "remove_group_member" -> Family.MEMBERSHIP;
            case "list_group_members" -> Family.GROUP;
            case "delete_authorizable", "set_user_disabled", "update_user_profile" ->
                    Family.AUTHORIZABLE;
            case "create_group", "create_user" -> Family.AUTHORIZABLE;
            case "inspect_replication_agent", "inspect_replication_queue" -> Family.AGENT;
            case "flush_replication_queue", "retry_replication_queue_entry" -> Family.AGENT;
            case "inspect_sling_job", "cancel_sling_job" -> Family.JOB;
            case "inspect_workflow_instance", "set_workflow_instance_suspension" -> Family.WORKFLOW;
            case "terminate_workflow_instance", "start_workflow" -> Family.WORKFLOW;
            case "update_open_service_gateway_initiative_configuration" -> Family.CONFIGURATION;
            case "delete_open_service_gateway_initiative_configuration" -> Family.CONFIGURATION;
            case "load_content_as_json", "resolve_resource_path", "map_resource_path" ->
                    Family.SUBJECT;
            case "set_open_service_gateway_initiative_bundle_state" -> Family.SUBJECT;
            case "create_asset", "create_asset_folder", "create_content_fragment" -> Family.CREATION;
            case "create_experience_fragment", "create_page", "add_component" -> Family.CREATION;
            default -> Family.NONE;
        };
    }

    /**
     * The correlation members every other command's refusal carries.
     *
     * <p>One family per wire name, and the members each family's client type declares. A family
     * whose value comes from a different argument than the member it fills - a page move, a
     * component addition, a workflow start - is answered by the small helpers below rather than
     * here, so this stays a lookup rather than a second inventory of what each command means.</p>
     */
    private static java.util.List<Correlation> addressedCorrelations(
            String wireName, DocumentValue.Mapping arguments) {
        final Family family = familyOf(wireName);
        return switch (family) {
            case ROOTED_DISCOVERY, PAGE, COMPONENT, ASSET, FRAGMENT, VARIATION,
                    AUTHORIZABLE, CONFIGURATION ->
                    java.util.List.of(text(arguments, family.member(), family.member()));
            case MOVE -> java.util.List.of(
                    text(arguments, "source_path", "source_path"),
                    text(arguments, "destination_path", "destination_path"));
            case MEMBERSHIP -> java.util.List.of(
                    text(arguments, "group_identifier", "group_identifier"),
                    text(arguments, "member_identifier", "member_identifier"));
            case GROUP -> java.util.List.of(text(arguments, "group_identifier", "group_identifier"));
            case AGENT -> agentCorrelations(wireName, arguments);
            case JOB -> java.util.List.of(text(arguments, "job_identifier", "job_identifier"));
            case WORKFLOW -> workflowCorrelations(wireName, arguments);
            case SUBJECT -> readCorrelations(wireName, arguments);
            case CREATION -> creationCorrelations(wireName, arguments);
            case NONE -> java.util.List.of();
        };
    }

    /** The package build's correlated categories, which are not shared with any other family. */
    private static java.util.List<Correlation> packageCorrelation(
            String category, DocumentValue.Mapping arguments) {
        return switch (category) {
            case "root_not_found", "root_access_denied" -> {
                final String root = firstRoot(arguments);
                yield java.util.List.of(new Correlation("root_path", root));
            }
            case "pattern_rejected" -> java.util.List.of(
                    new Correlation("collection", "inclusion"),
                    new Correlation("expression_index", "0"));
            default -> java.util.List.of();
        };
    }

    /** One replication agent's record, which a retry pairs with its own entry. */
    private static java.util.List<Correlation> agentCorrelations(
            String wireName, DocumentValue.Mapping arguments) {
        if ("retry_replication_queue_entry".equals(wireName)) {
            return java.util.List.of(
                    text(arguments, "agent_identifier", "agent_identifier"),
                    text(arguments, "entry_identifier", "entry_identifier"));
        }
        return java.util.List.of(text(arguments, "agent_identifier", "agent_identifier"));
    }

    /** One workflow record, whose start names the model rather than an instance. */
    private static java.util.List<Correlation> workflowCorrelations(
            String wireName, DocumentValue.Mapping arguments) {
        if ("start_workflow".equals(wireName)) {
            return java.util.List.of(text(arguments, "model_identifier", "model_identifier"));
        }
        return java.util.List.of(text(arguments, "instance_identifier", "instance_identifier"));
    }

    /** A read whose subject is an address, an authority, or a symbolic name. */
    private static java.util.List<Correlation> readCorrelations(
            String wireName, DocumentValue.Mapping arguments) {
        return switch (wireName) {
            case "load_content_as_json" -> java.util.List.of(text(arguments, "path", "path"));
            case "resolve_resource_path" ->
                    java.util.List.of(text(arguments, "request_address", "subject"));
            case "map_resource_path" ->
                    java.util.List.of(text(arguments, "repository_path", "subject"));
            default -> java.util.List.of(text(arguments, "symbolic_name", "symbolic_name"));
        };
    }

    /** A creation whose refusal names the address the command computed from its arguments. */
    private static java.util.List<Correlation> creationCorrelations(
            String wireName, DocumentValue.Mapping arguments) {
        if ("create_page".equals(wireName)) {
            return java.util.List.of(joined(arguments, "parent_path", "page_name", "target_path"));
        }
        if ("add_component".equals(wireName)) {
            return java.util.List.of(new Correlation("target_path", componentTarget(arguments)));
        }
        return java.util.List.of(joined(arguments, "parent_path", "name", "target_path"));
    }

    /** The first root a package request named, which is what a root refusal is about. */
    private static String firstRoot(DocumentValue.Mapping arguments) {
        final Optional<DocumentValue> roots = arguments.member("roots");
        if (roots.isPresent() && roots.get() instanceof DocumentValue.Sequence sequence
                && !sequence.items().isEmpty()
                && sequence.items().getFirst() instanceof DocumentValue.Text first) {
            return first.value();
        }
        return "";
    }

    /** One member whose value is an argument value, as the client spells both. */
    private static Correlation text(DocumentValue.Mapping arguments, String held, String member) {
        final Optional<DocumentValue> stated = arguments.member(held);
        if (stated.isPresent() && stated.get() instanceof DocumentValue.Text value) {
            return new Correlation(member, value.value());
        }
        return new Correlation(member, "");
    }

    /** One member whose value is the address a creation computed from two arguments. */
    private static Correlation joined(DocumentValue.Mapping arguments, String parentMember,
                                      String nameMember, String member) {
        final String parent = string(arguments, parentMember);
        final String name = string(arguments, nameMember);
        return new Correlation(member, parent.isEmpty() || name.isEmpty()
                ? "" : parent + "/" + name);
    }

    /**
     * The address an addition computed: the page's content resource, the relative parent, and the
     * component's own name.
     */
    private static String componentTarget(DocumentValue.Mapping arguments) {
        final String page = string(arguments, "page_path");
        final String name = string(arguments, "component_name");
        if (page.isEmpty() || name.isEmpty()) {
            return "";
        }
        final String parent = string(arguments, "content_parent");
        final String content = page + "/jcr:content";
        return parent.isEmpty() || "content_root".equals(parent)
                ? content + "/" + name : content + "/" + parent + "/" + name;
    }

    /** One argument's text, or empty where it is absent or not text. */
    private static String string(DocumentValue.Mapping arguments, String held) {
        final Optional<DocumentValue> stated = arguments.member(held);
        return stated.isPresent() && stated.get() instanceof DocumentValue.Text text
                ? text.value() : "";
    }
}
