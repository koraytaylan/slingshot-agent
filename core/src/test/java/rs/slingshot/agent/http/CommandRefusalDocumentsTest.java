// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.CommandRegistry;
import rs.slingshot.agent.command.RegistryRow;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What a refused command's document looks like to the client.
 *
 * <p>The client authenticates a failure against the command's own closed refusal type: the category
 * it declares, and every other member with the exact name its schema gives. A category alone is not
 * a document it will accept. So every category every row declares is rendered here and compared
 * against the one correlation member that command's family carries - which is the whole claim this
 * table makes. None of it reaches the wire until a handler actually fails, and a test that only
 * exercised a running instance would cover whichever few handlers happened to be reachable.</p>
 */
final class CommandRefusalDocumentsTest {

    /** Every registered row the registry here carries, read from its own committed rows. */
    private static final CommandRegistry REGISTRY = assertInstanceOf(CommandRegistry.Loaded.class,
            CommandRegistry.read()).registry();

    @Test
    @DisplayName("every declared category renders as the command's own refusal document")
    void everyDeclaredCategoryRendersAsTheCommandsOwnDocument() {
        for (final RegistryRow row : REGISTRY.rows()) {
            for (final String category : row.failureCategories()) {
                final DocumentValue.Mapping document = CommandRefusalDocuments.documentOf(
                        row.wireName(), category, arguments(row.wireName()));
                final SequencedMap<String, DocumentValue> members = document.members();
                assertEquals(category, text(members.get("failure")),
                        row.wireName() + " changed the category it was given");
                assertEquals(expectedMembers(row.wireName(), category).stream().sorted().toList(),
                        members.keySet().stream().sorted().toList(),
                        row.wireName() + " produced members the client's own type does not have");
            }
        }
    }

    @Test
    @DisplayName("the correlation value is the one the caller sent, never one this side invented")
    void theCorrelationValueIsTheOneTheCallerSent() {
        final DocumentValue.Mapping document = CommandRefusalDocuments.documentOf(
                "update_page", "page_not_found", mapping("page_path", "/content/site/en/report"));
        assertEquals("/content/site/en/report",
                text(document.members().get("page_path")),
                "the refusal does not echo the page the caller named, so the client cannot"
                        + " correlate it with the request it made");
    }

    @Test
    @DisplayName("an omitted correlation member is blank rather than absent or invented")
    void anOmittedCorrelationMemberIsBlank() {
        final DocumentValue.Mapping document = CommandRefusalDocuments.documentOf(
                "update_page", "page_not_found", new DocumentValue.Mapping(new LinkedHashMap<>()));
        assertEquals("", text(document.members().get("page_path")),
                "a page this side did not receive was reported as some other page");
    }

    @Test
    @DisplayName("an exhausted discovery budget carries its budget and nothing else")
    void anExhaustedDiscoveryBudgetCarriesItsBudget() {
        final DocumentValue.Mapping document = CommandRefusalDocuments.documentOf(
                "query_paths", "discovery_budget_exceeded", mapping("root_path", "/content"));
        assertEquals("candidate_nodes", text(document.members().get("budget")),
                "the client cannot read which budget ran out");
        assertEquals(List.of("budget", "failure"),
                document.members().keySet().stream().sorted().toList(),
                "a budget failure carries a partial page's correlation beside it, which the client"
                        + " refuses");
    }

    @Test
    @DisplayName("a continuation failure carries the category and nothing else")
    void aContinuationFailureCarriesNothingElse() {
        final DocumentValue.Mapping document = CommandRefusalDocuments.documentOf(
                "list_child_pages", "continuation_token_malformed", mapping("root_path", "/content"));
        assertEquals(List.of("failure"), List.copyOf(document.members().keySet()),
                "a continuation refusal named an anchor, which the client refuses as a second"
                        + " thing about a request that has one");
    }

    @Test
    @DisplayName("an unknown command falls back to the shared category document")
    void anUnknownCommandFallsBackToTheSharedCategory() {
        final DocumentValue.Mapping document = CommandRefusalDocuments.documentOf(
                "not_a_command", "not_found", new DocumentValue.Mapping(new LinkedHashMap<>()));
        assertEquals(List.of("failure"), List.copyOf(document.members().keySet()),
                "a command this table does not know produced a member its client never reads");
    }

    /** The members one command's own refusal type has, which this test states itself. */
    private static List<String> expectedMembers(String wireName, String category) {
        final List<String> members = new java.util.ArrayList<>();
        members.add("failure");
        if (category.startsWith("continuation_token_")) {
            return members;
        }
        members.addAll(shapeMembers(wireName, category));
        if (!shapeMembers(wireName, category).isEmpty()
                && !"pattern_rejected".equals(category)) {
            // A budget or reason failure is a closed shape of its own: it carries no correlation,
            // because the client's decoder reads exactly these members and nothing beside them.
            return members;
        }
        if ("download_content_package".equals(wireName)) {
            if (category.startsWith("root_")) {
                members.add("root_path");
            }
            return members;
        }
        members.addAll(correlationMembers(wireName, category));
        return members;
    }

    /** The shape members one command's own refusal type has beyond its correlation. */
    private static List<String> shapeMembers(String wireName, String category) {
        if ("replicate_content".equals(wireName)) {
            return switch (category) {
                case "source_not_found", "source_access_denied", "candidate_limit_exceeded",
                        "traversal_budget_exceeded" -> List.of("path");
                default -> List.of("accepted_item_count", "current_path", "remaining_item_count");
            };
        }
        if (budgetCategory(category)) {
            return List.of("budget");
        }
        return reasonMembers(category);
    }

    /** The shape members the non-budget, non-correlated categories carry. */
    private static List<String> reasonMembers(String category) {
        return switch (category) {
            case "configuration_value_unsupported", "configuration_value_malformed" ->
                    List.of("reason");
            case "pattern_rejected" -> List.of("collection", "expression_index");
            default -> List.of();
        };
    }

    /** Whether one category is a budget failure, which every budget family carries a name in. */
    private static boolean budgetCategory(String category) {
        return switch (category) {
            case "discovery_budget_exceeded", "evaluation_budget_exceeded" -> true;
            case "configuration_lookup_budget_exceeded" -> true;
            case "configuration_value_budget_exceeded" -> true;
            case "configuration_result_budget_exceeded" -> true;
            default -> false;
        };
    }

    /** The correlation members one command's family carries, which this test states itself. */
    private static List<String> correlationMembers(String wireName, String category) {
        if ("download_content_package".equals(wireName)) {
            return switch (category) {
                case "root_not_found", "root_access_denied" -> List.of("root_path");
                default -> List.of();
            };
        }
        return switch (wireName) {
            case "list_child_pages", "find_pages_by_template", "list_page_templates",
                    "list_content_fragment_models", "list_component_definitions", "list_components",
                    "list_content_fragments", "list_experience_fragments" -> List.of("root_path");
            case "find_pages_containing_phrase", "find_pages_using_components" -> List.of("root_path");
            case "find_assets_by_metadata", "query_paths" -> List.of("root_path");
            case "find_assets_referenced_by_page" -> List.of("page_path");
            case "load_content_as_json" -> List.of("path");
            case "read_content_fragment", "update_content_fragment" -> List.of("fragment_path");
            case "delete_content_fragment", "delete_experience_fragment" -> List.of("fragment_path");
            case "update_experience_fragment" -> List.of("variation_path");
            case "list_asset_renditions", "update_asset_metadata", "delete_asset" ->
                    List.of("asset_path");
            case "update_page", "delete_page" -> List.of("page_path");
            case "update_component", "delete_component", "reorder_component" ->
                    List.of("component_path");
            case "move_asset", "move_page" -> List.of("source_path", "destination_path");
            case "list_group_members" -> List.of("group_identifier");
            case "add_group_member", "remove_group_member" ->
                    List.of("group_identifier", "member_identifier");
            case "resolve_resource_path", "map_resource_path" -> List.of("subject");
            case "inspect_replication_agent", "inspect_replication_queue" ->
                    List.of("agent_identifier");
            case "flush_replication_queue" -> List.of("agent_identifier");
            case "retry_replication_queue_entry" ->
                    List.of("agent_identifier", "entry_identifier");
            case "inspect_sling_job", "cancel_sling_job" -> List.of("job_identifier");
            case "inspect_workflow_instance", "set_workflow_instance_suspension" ->
                    List.of("instance_identifier");
            case "terminate_workflow_instance" -> List.of("instance_identifier");
            case "start_workflow" -> List.of("model_identifier");
            case "set_open_service_gateway_initiative_bundle_state" -> List.of("symbolic_name");
            case "delete_authorizable", "set_user_disabled", "update_user_profile" ->
                    List.of("authorizable_identifier");
            case "create_group", "create_user" -> List.of("authorizable_identifier");
            case "update_open_service_gateway_initiative_configuration" ->
                    List.of("persistent_identifier");
            case "delete_open_service_gateway_initiative_configuration" ->
                    List.of("persistent_identifier");
            case "create_asset", "create_asset_folder", "create_content_fragment" ->
                    List.of("target_path");
            case "create_experience_fragment", "create_page" -> List.of("target_path");
            case "add_component" -> List.of("target_path");
            default -> List.of();
        };
    }

    /** One argument document carrying the correlation member a command's request would send. */
    private static DocumentValue.Mapping arguments(String wireName) {
        if ("download_content_package".equals(wireName)) {
            final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>();
            arguments.put("roots", new DocumentValue.Sequence(
                    List.of(new DocumentValue.Text("/content/example"))));
            return new DocumentValue.Mapping(arguments);
        }
        final List<String> members = correlationMembers(wireName, "root_not_found");
        if (members.isEmpty()) {
            return new DocumentValue.Mapping(new LinkedHashMap<>());
        }
        final SequencedMap<String, DocumentValue> arguments = new LinkedHashMap<>();
        for (final String member : members) {
            arguments.put(member, new DocumentValue.Text("/content/example"));
        }
        return new DocumentValue.Mapping(arguments);
    }

    private static DocumentValue.Mapping mapping(String name, String value) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(name, new DocumentValue.Text(value));
        return new DocumentValue.Mapping(members);
    }

    private static String text(DocumentValue value) {
        return assertInstanceOf(DocumentValue.Text.class, value).value();
    }
}
