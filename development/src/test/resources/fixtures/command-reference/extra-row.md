<!--
SPDX-License-Identifier: MIT OR Apache-2.0
Copyright 2026 Koray Taylan Davgana
-->

# Commands

Every command this agent answers, and what its own registry row says about it. The table below is
generated from `policy/commands/` on every build and checked against it: a command that exists
appears here or the build does not pass. Nothing in the table is written by hand, and the prose
around it is written by nothing else.

Four things are worth knowing before reading it.

**Access** is `read` or `write`, and it is the client's own classification rather than a guess from
the name. It decides nothing on its own — whether a caller may run a command is decided by the group
they are in, the same way for every command here.

**Operation key** is whether a caller has to supply one. A command that requires one is not
intrinsically idempotent: running it twice is not running it once, so the key is what lets this side
answer the first attempt's own result to every resend of it. A command that refuses one is a read
nobody can repeat differently, and holding it to a single attempt would only be ceremony.

**Result bytes** is the command's declared result budget. Commands with an artifact result contract
can publish content with a byte count and a digest the caller verifies. Incremental discovery
instead refuses an oversized page; request a smaller result limit. It never silently truncates a
complete catalogue.

**Fails with** is the closed set of categories a command may fail under. A caller can handle every
one of them, and this side cannot answer any other: a category one half has never heard of is a
failure the other half cannot act on.

Two things this table cannot tell you, and neither is an oversight. It does not say which platform
controls a deployment provides — `support/deployments.toml` does, and a control command is refused
before it runs where the deployment does not keep the change. And it does not say what a command
discloses; the commands that read configurations, jobs, and replication agents each withhold
different things for different reasons, and those reasons are in the code beside the decision rather
than summarised here, where they would be believed without being read.

## Incremental discovery

The version 2 commands `query_paths`, `list_component_definitions`, `list_components`,
`list_content_fragments`, `list_experience_fragments`, `find_pages_containing_phrase`,
`find_pages_using_components` and `find_assets_by_metadata` return `matches`,
`examined_nodes` and `complete`. A partial result always carries
`next_continuation_token`; a complete result never does. Empty partial pages are
valid, including while consuming an initial offset. Continue with the same command
and unchanged query arguments until `complete` is true. The initial limit and offset govern the entire
cursor. The root must exist exactly; an ancestor search is not substituted.

`query_paths` uses version `2.0.2`. The four existing bounded discovery commands
receive patch version `2.0.3`; other commands receive patch version `1.0.4` for
the shared byte contract and limits identity changes. Their argument and result
shapes remain unchanged. The two page searches use patch version `2.0.1`, with explicit
progress and provider order in their result contracts. Asset search uses version
`2.0.0` with the same bounded progress fields and provider order.

Traversal retains depth-first provider iterators across pages and does not sort
the catalogue. With stable content and permissions, each traversal iterator is consumed once.
`query_paths`, phrase search and asset search include a matching root; catalogue commands exclude it.
Concurrent changes are live observations: removed or newly unreadable subtrees are
skipped, while inserted or moved content may be omitted or encountered again. Every
returned row is re-read through the current request's resolver. A missing or
unreadable original root ends the cursor.

Phrase search compares the full phrase exactly, without case folding or Unicode
normalization, against page title and description properties. It returns paths
and optional titles, without excerpts or descriptions. Component search groups
exact resource types by the nearest page: `any` requires one requested type and
`all` requires every requested type across that page's scoped content. Nested
pages own their components independently. An anchor inside a page can return
its containing ancestor page. Component pages are emitted after their scoped
content, preserving provider traversal order. Detached component witnesses and
readable ancestry are checked through the current request resolver before emission
and replay. These bounded proof and ancestry reads also count in `examined_nodes`;
a proof too large for a whole page's node budget refuses the cursor. Page titles
are checked against `maximum_page_title_bytes` in UTF-8 bytes. An oversized title
refuses the cursor without truncating or omitting that title.

Asset search reads original binary length without downloading binary content. Its
media format uses one usable metadata `dc:format` string, then the original MIME
type as fallback. Requested size excludes unknown lengths; omitted tag mode
requires all requested tags. Returned tags are unique in UTF-8 byte order. A
root-only search includes assets with absent optional metadata, and an asset
prunes its rendition and metadata descendants from traversal.

The runtime owns a bounded registry and clones the caller resolver with unchanged
authentication. It closes that clone at completion, expiry, cancellation, refusal
or shutdown. The most recent continuation has one bounded replay answer, revalidated
against the current root and rows before delivery; changed rows expire that replay.
Older tokens expire after another page advances. Replays retain the original work
count. Tokens are bound to caller, query, target, generation and runtime, and their
fixed lifetime is never renewed. A restart or runtime replacement requires a fresh
enumeration. Completed continuation replay answers retain no resolver.

`maximum_discovery_candidate_nodes`, `maximum_discovery_execution_duration_milliseconds` and
the result limit bound each page; exhaustion returns partial progress. Iterator
housekeeping also consumes the node-work budget, so `examined_nodes` may be smaller
than the work limit. Cursor count, depth and idle collection interval are declared
in `support/agent-contract.toml`. Cancellation, excessive depth or result bytes
refuse the cursor. The time and cancellation checks run between provider calls;
they cannot preempt a blocked repository provider.

Legacy content and platform listings authenticate a continuation before reading
repository content or inventories. Their signed state retains the initial page size and fixed
expiry through every successor. Invalid shape, unavailable authority, tampering,
wrong query or target, changed generation and expiry retain their declared
refusal categories. The version-two token requires `initial_result_limit`;
version-one tokens require a fresh initial request. This changes the opaque token
format while preserving command argument/result shapes and numeric bounds.

Workflow start preserves nonempty `title` and `comment` arguments as the platform
metadata entries `workflowTitle` and `startComment`. Explicit descriptions take
precedence over caller metadata with the same names; omitted descriptions preserve
those entries. Other metadata remains unchanged, and the existing caller metadata
entry allowance and separate title/comment bounds still apply.

<!-- generated: command-table -->

Commands: 67.

| Command | Access | Operation key | Result bytes | Fails with |
|---|---|---|---|---|
| `add_component` | write | required | 16384 | `mutation_outcome_unknown`, `page_invalid`, `page_not_found`, `parent_access_denied`, `parent_not_found`, `parent_not_orderable`, `property_rejected`, `repository_commit_failed`, `target_already_exists` |
| `add_group_member` | write | required | 16384 | `authorizable_access_denied`, `authorizable_kind_mismatch`, `group_not_found`, `member_not_found`, `membership_cycle_refused`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `cancel_sling_job` | write | required | 16384 | `job_not_cancellable`, `job_not_found`, `platform_control_outcome_unknown`, `platform_control_rejected` |
| `create_asset` | write | required | 16384 | `media_type_unsupported`, `mutation_outcome_unknown`, `parent_access_denied`, `parent_not_found`, `payload_rejected`, `payload_too_large`, `repository_commit_failed`, `target_already_exists` |
| `create_asset_folder` | write | required | 16384 | `mutation_outcome_unknown`, `parent_access_denied`, `parent_not_found`, `property_rejected`, `repository_commit_failed`, `target_already_exists` |
| `create_content_fragment` | write | required | 16384 | `element_unknown`, `element_value_rejected`, `model_invalid`, `model_not_found`, `mutation_outcome_unknown`, `parent_access_denied`, `parent_not_found`, `repository_commit_failed`, `target_already_exists` |
| `create_experience_fragment` | write | required | 16384 | `mutation_outcome_unknown`, `parent_access_denied`, `parent_not_found`, `repository_commit_failed`, `target_already_exists`, `template_invalid`, `template_not_found` |
| `create_group` | write | required | 16384 | `authorizable_access_denied`, `authorizable_already_exists`, `identifier_rejected`, `intermediate_path_rejected`, `mutation_outcome_unknown`, `property_rejected`, `repository_commit_failed` |
| `create_page` | write | required | 16384 | `mutation_outcome_unknown`, `parent_access_denied`, `parent_not_found`, `property_rejected`, `repository_commit_failed`, `target_already_exists`, `template_invalid`, `template_not_found` |
| `create_user` | write | required | 16384 | `authorizable_access_denied`, `authorizable_already_exists`, `identifier_rejected`, `intermediate_path_rejected`, `mutation_outcome_unknown`, `property_rejected`, `repository_commit_failed` |
| `delete_asset` | write | required | 16384 | `asset_access_denied`, `asset_invalid`, `asset_is_referenced`, `asset_not_found`, `deletion_budget_exceeded`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `delete_authorizable` | write | required | 16384 | `authorizable_access_denied`, `authorizable_kind_mismatch`, `authorizable_not_found`, `group_has_members`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `delete_component` | write | required | 16384 | `component_access_denied`, `component_invalid`, `component_not_found`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `delete_content_fragment` | write | required | 16384 | `deletion_budget_exceeded`, `fragment_access_denied`, `fragment_invalid`, `fragment_is_referenced`, `fragment_not_found`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `delete_experience_fragment` | write | required | 16384 | `deletion_budget_exceeded`, `fragment_access_denied`, `fragment_invalid`, `fragment_is_referenced`, `fragment_not_found`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `delete_page` | write | required | 16384 | `deletion_budget_exceeded`, `mutation_outcome_unknown`, `repository_commit_failed`, `target_access_denied`, `target_is_referenced`, `target_not_a_page`, `target_not_found` |
| `download_content_package` | read | required | 1048576 | `artifact_publication_failed`, `artifact_publication_outcome_unknown`, `evaluation_budget_exceeded`, `filevault_filter_unrepresentable`, `filevault_package_failed`, `filevault_profile_unsupported`, `pattern_rejected`, `repository_read_failed`, `root_access_denied`, `root_not_found`, `staging_cleanup_failed` |
| `find_assets_by_metadata` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `find_assets_referenced_by_page` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `page_access_denied`, `page_invalid`, `page_not_found` |
| `find_open_service_gateway_initiative_configurations` | read | refused | 1048576 | `configuration_lookup_budget_exceeded`, `configuration_lookup_failed`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded` |
| `find_pages_by_template` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `find_pages_containing_phrase` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `find_pages_using_components` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `find_sling_jobs` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `job_inventory_failed` |
| `find_workflow_instances` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `workflow_inventory_failed` |
| `flush_replication_queue` | write | required | 16384 | `agent_access_denied`, `agent_not_found`, `platform_control_outcome_unknown`, `platform_control_rejected`, `queue_expectation_mismatch` |
| `inspect_open_service_gateway_initiative_configuration` | read | refused | 1048576 | `configuration_lookup_ambiguous`, `configuration_lookup_budget_exceeded`, `configuration_lookup_failed`, `configuration_lookup_mismatch`, `configuration_result_budget_exceeded`, `configuration_value_budget_exceeded`, `configuration_value_malformed`, `configuration_value_unsupported` |
| `inspect_replication_agent` | read | refused | 262144 | `agent_access_denied`, `agent_inventory_failed`, `agent_not_found` |
| `inspect_replication_queue` | read | refused | 1048576 | `agent_access_denied`, `agent_not_found`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `queue_inventory_failed` |
| `inspect_sling_job` | read | refused | 262144 | `job_inventory_failed`, `job_not_found`, `result_budget_exceeded` |
| `inspect_workflow_instance` | read | refused | 262144 | `instance_access_denied`, `instance_not_found`, `result_budget_exceeded`, `workflow_inventory_failed` |
| `list_asset_renditions` | read | refused | 1048576 | `asset_access_denied`, `asset_invalid`, `asset_not_found`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded` |
| `list_child_nodes` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_child_nodes_by_type` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_child_pages` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_component_definitions` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_components` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_content_fragment_models` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_content_fragments` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_experience_fragments` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_group_members` | read | refused | 1048576 | `authorizable_access_denied`, `authorizable_kind_mismatch`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `group_not_found` |
| `list_open_service_gateway_initiative_bundles` | read | refused | 1048576 | `bundle_inventory_failed`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded` |
| `list_open_service_gateway_initiative_components` | read | refused | 1048576 | `component_inventory_failed`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded` |
| `list_page_templates` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `list_replication_agents` | read | refused | 1048576 | `agent_inventory_failed`, `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded` |
| `list_resource_mappings` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `mapping_inventory_failed` |
| `list_sling_job_queues` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `job_inventory_failed` |
| `list_workflow_models` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `workflow_inventory_failed` |
| `load_content_as_json` | read | required | 1048576 | `access_denied`, `load_budget_exceeded`, `not_found`, `unsupported_repository_value` |
| `map_resource_path` | read | refused | 262144 | `resolution_budget_exceeded`, `resolution_failed` |
| `move_page` | write | required | 16384 | `destination_already_exists`, `destination_inside_source`, `destination_parent_not_found`, `mutation_outcome_unknown`, `reference_adjustment_budget_exceeded`, `repository_commit_failed`, `source_access_denied`, `source_not_found` |
| `query_paths` | read | refused | 1048576 | `continuation_token_expired`, `continuation_token_integrity_invalid`, `continuation_token_malformed`, `continuation_token_wrong_query`, `continuation_token_wrong_target`, `discovery_budget_exceeded`, `root_access_denied`, `root_not_found` |
| `read_content_fragment` | read | refused | 262144 | `fragment_access_denied`, `fragment_invalid`, `fragment_not_found`, `result_budget_exceeded`, `variation_not_found` |
| `remove_group_member` | write | required | 16384 | `authorizable_access_denied`, `authorizable_kind_mismatch`, `group_not_found`, `member_not_found`, `membership_cycle_refused`, `mutation_outcome_unknown`, `repository_commit_failed` |
| `reorder_component` | write | required | 16384 | `component_access_denied`, `component_not_found`, `mutation_outcome_unknown`, `parent_not_orderable`, `repository_commit_failed`, `sibling_not_found` |
| `replicate_content` | write | required | 16384 | `admission_budget_exceeded`, `admission_outcome_unknown`, `admission_rejected`, `candidate_limit_exceeded`, `source_access_denied`, `source_not_found`, `traversal_budget_exceeded` |
| `resolve_resource_path` | read | refused | 262144 | `request_address_rejected`, `resolution_budget_exceeded`, `resolution_failed` |
| `set_user_disabled` | write | required | 16384 | `authorizable_access_denied`, `authorizable_kind_mismatch`, `authorizable_not_found`, `platform_control_outcome_unknown`, `platform_control_rejected` |
| `set_workflow_instance_suspension` | write | required | 16384 | `instance_access_denied`, `instance_not_found`, `instance_not_suspendable`, `platform_control_outcome_unknown`, `platform_control_rejected` |
| `start_workflow` | write | required | 16384 | `metadata_rejected`, `model_invalid`, `model_not_found`, `payload_access_denied`, `payload_not_found`, `platform_control_outcome_unknown`, `platform_control_rejected` |
| `teleport_page` | write | required | 16384 | `nothing` |
| `terminate_workflow_instance` | write | required | 16384 | `instance_access_denied`, `instance_not_found`, `instance_not_terminable`, `platform_control_outcome_unknown`, `platform_control_rejected` |
| `update_asset_metadata` | write | required | 16384 | `asset_access_denied`, `asset_invalid`, `asset_not_found`, `mutation_outcome_unknown`, `property_not_removable`, `property_rejected`, `repository_commit_failed` |
| `update_component` | write | required | 16384 | `component_access_denied`, `component_invalid`, `component_not_found`, `mutation_outcome_unknown`, `property_not_removable`, `property_rejected`, `repository_commit_failed` |
| `update_content_fragment` | write | required | 16384 | `element_unknown`, `element_value_rejected`, `fragment_access_denied`, `fragment_invalid`, `fragment_not_found`, `mutation_outcome_unknown`, `repository_commit_failed`, `variation_not_found` |
| `update_experience_fragment` | write | required | 16384 | `mutation_outcome_unknown`, `property_not_removable`, `property_rejected`, `repository_commit_failed`, `variation_access_denied`, `variation_invalid`, `variation_not_found` |
| `update_page` | write | required | 16384 | `mutation_outcome_unknown`, `page_access_denied`, `page_invalid`, `page_not_found`, `property_not_removable`, `property_rejected`, `repository_commit_failed` |
| `update_user_profile` | write | required | 16384 | `authorizable_access_denied`, `authorizable_kind_mismatch`, `authorizable_not_found`, `mutation_outcome_unknown`, `property_not_removable`, `property_rejected`, `repository_commit_failed` |

<!-- end generated: command-table -->
