// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.contract;

/**
 * Every bound this agent is held to, one constant per declared bound.
 *
 * <p>A bound is reached by naming it here rather than by looking a string up in a map, so a bound
 * that does not exist is a compilation failure rather than a value that turned out to be absent
 * once something asked for it. The constant set is held equal to the contract document's key set in
 * both directions: a declared bound with no constant and a constant with no declared bound each
 * refuse the load.</p>
 *
 * <p>{@link Section#TRANSPORT} and {@link Section#COMMAND} bounds are the client's own two
 * contracts, each reproduced byte-equivalently from the sibling repository. The transport contract
 * bounds one exchange — what may travel, for how long, in how many bytes. The command contract
 * bounds one command inside that exchange — what it may be asked about and what it may answer.
 * {@link Section#AGENT} bounds are this side's, and exist because they are properties of the
 * server's environment rather than of the protocol — how long a stream may be held open before a
 * gateway would end it, how large a request body may be, how long a command may run. A bound either
 * client contract already carries is never repeated among them.</p>
 *
 * <p>A key may be declared by both client contracts with two different values, because the two
 * bound two different things: {@code maximum_sling_job_identifier_bytes} bounds an identifier
 * travelling in an envelope under one and the same identifier supplied as a command argument under
 * the other. Bounds are held by section-qualified path rather than by name, so both are carried
 * without either overwriting the other, and each constant is named for its own section so that
 * reaching for one can never silently reach the other.</p>
 */
public enum ContractLimit {
    /** How long an artifact transfer may make no progress before it is abandoned. */
    ARTIFACT_TRANSFER_IDLE_TIMEOUT_MILLISECONDS(
            Section.TRANSPORT, "artifact_transfer_idle_timeout_milliseconds"),

    /** How long one whole artifact transfer may take, however well it is progressing. */
    ARTIFACT_TRANSFER_TOTAL_TIMEOUT_MILLISECONDS(
            Section.TRANSPORT, "artifact_transfer_total_timeout_milliseconds"),

    /** How long establishing a connection to an author may take before it is given up on. */
    AUTHOR_CONNECT_TIMEOUT_MILLISECONDS(Section.TRANSPORT, "author_connect_timeout_milliseconds"),

    /** How long sending a whole request body to an author may take. */
    AUTHOR_REQUEST_BODY_TIMEOUT_MILLISECONDS(Section.TRANSPORT, "author_request_body_timeout_milliseconds"),

    /** How long an author may take to answer with its response head. */
    AUTHOR_RESPONSE_HEADER_TIMEOUT_MILLISECONDS(
            Section.TRANSPORT, "author_response_header_timeout_milliseconds"),

    /** How long the transport-security handshake with an author may take. */
    AUTHOR_TLS_TIMEOUT_MILLISECONDS(Section.TRANSPORT, "author_tls_timeout_milliseconds"),

    /** How long a call to the continuation-key authority may take before it is abandoned. */
    CONTINUATION_KEY_AUTHORITY_TIMEOUT_MILLISECONDS(
            Section.TRANSPORT, "continuation_key_authority_timeout_milliseconds"),

    /** How long a rotated-out continuation key still resolves the tokens issued under it. */
    CONTINUATION_KEY_PRIOR_RETENTION_MILLISECONDS(
            Section.TRANSPORT, "continuation_key_prior_retention_milliseconds"),

    /** How long one node holds the sole right to rotate the continuation key ring. */
    CONTINUATION_KEY_ROTATION_LEASE_MILLISECONDS(
            Section.TRANSPORT, "continuation_key_rotation_lease_milliseconds"),

    /** How long a bounded response may stall before it is abandoned. */
    FINITE_RESPONSE_IDLE_TIMEOUT_MILLISECONDS(Section.TRANSPORT, "finite_response_idle_timeout_milliseconds"),

    /** How long one whole bounded response may take, however well it is progressing. */
    FINITE_RESPONSE_TOTAL_TIMEOUT_MILLISECONDS(
            Section.TRANSPORT, "finite_response_total_timeout_milliseconds"),

    /** How often an open stream carrying nothing sends a heartbeat anyway. */
    HEARTBEAT_INTERVAL_MILLISECONDS(Section.TRANSPORT, "heartbeat_interval_milliseconds"),

    /** How long a client waits for a heartbeat before treating the stream as gone. */
    HEARTBEAT_TIMEOUT_MILLISECONDS(Section.TRANSPORT, "heartbeat_timeout_milliseconds"),

    /** The largest continuation state one paging token may carry. */
    MAXIMUM_AGENT_CONTINUATION_KEY_STATE_BYTES(
            Section.TRANSPORT, "maximum_agent_continuation_key_state_bytes"),

    /** The largest result answered inside the response rather than as an artifact. */
    MAXIMUM_AGENT_INLINE_RESULT_BYTES(Section.TRANSPORT, "maximum_agent_inline_result_bytes"),

    /** The longest operation identifier that may be accepted or produced. */
    MAXIMUM_AGENT_OPERATION_IDENTIFIER_BYTES(Section.TRANSPORT, "maximum_agent_operation_identifier_bytes"),

    /** The largest protocol document this agent will parse at all. */
    MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES(Section.TRANSPORT, "maximum_agent_protocol_document_bytes"),

    /** The largest transport contract document a client will read. */
    MAXIMUM_AUTHOR_AGENT_TRANSPORT_CONTRACT_BYTES(
            Section.TRANSPORT, "maximum_author_agent_transport_contract_bytes"),

    /** The largest response head a client will read from an author. */
    MAXIMUM_AUTHOR_RESPONSE_HEAD_BYTES(Section.TRANSPORT, "maximum_author_response_head_bytes"),

    /** The largest single response header a client will read. */
    MAXIMUM_AUTHOR_RESPONSE_HEADER_BYTES(Section.TRANSPORT, "maximum_author_response_header_bytes"),

    /** The most response headers a client will read before refusing the response. */
    MAXIMUM_AUTHOR_RESPONSE_HEADER_COUNT(Section.TRANSPORT, "maximum_author_response_header_count"),

    /** The most times a client retries on its own before it reports rather than retries. */
    MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS(Section.TRANSPORT, "maximum_automatic_retry_attempts"),

    /** The largest canonical submission that may be authenticated and executed. */
    MAXIMUM_CANONICAL_SUBMISSION_BYTES(Section.TRANSPORT, "maximum_canonical_submission_bytes"),

    /** The largest record the continuation-key authority holds for one key. */
    MAXIMUM_CONTINUATION_KEY_AUTHORITY_RECORD_BYTES(
            Section.TRANSPORT, "maximum_continuation_key_authority_record_bytes"),

    /** The bytes every live subscription in the current generation may occupy together. */
    MAXIMUM_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_BYTES(
            Section.TRANSPORT, "maximum_current_generation_active_subscription_bytes"),

    /** The live subscriptions the current generation may hold at once. */
    MAXIMUM_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_ROWS(
            Section.TRANSPORT, "maximum_current_generation_active_subscription_rows"),

    /** The artifact bytes the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_ARTIFACT_BYTES(Section.TRANSPORT, "maximum_current_generation_artifact_bytes"),

    /** The artifacts the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_ARTIFACT_ROWS(Section.TRANSPORT, "maximum_current_generation_artifact_rows"),

    /** The event bytes the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_EVENT_BYTES(Section.TRANSPORT, "maximum_current_generation_event_bytes"),

    /** The events the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_EVENT_ROWS(Section.TRANSPORT, "maximum_current_generation_event_rows"),

    /** The operation-detail bytes the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_OPERATION_DETAIL_BYTES(
            Section.TRANSPORT, "maximum_current_generation_operation_detail_bytes"),

    /** The operation details the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_OPERATION_DETAIL_ROWS(
            Section.TRANSPORT, "maximum_current_generation_operation_detail_rows"),

    /** The reservation bytes the current generation may hold while submissions are in flight. */
    MAXIMUM_CURRENT_GENERATION_OPERATION_RESERVATION_BYTES(
            Section.TRANSPORT, "maximum_current_generation_operation_reservation_bytes"),

    /** The submission reservations the current generation may hold at once. */
    MAXIMUM_CURRENT_GENERATION_OPERATION_RESERVATION_ROWS(
            Section.TRANSPORT, "maximum_current_generation_operation_reservation_rows"),

    /** The result bytes the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_RESULT_BYTES(Section.TRANSPORT, "maximum_current_generation_result_bytes"),

    /** The results the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_RESULT_ROWS(Section.TRANSPORT, "maximum_current_generation_result_rows"),

    /** The snapshot bytes the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_SNAPSHOT_BYTES(Section.TRANSPORT, "maximum_current_generation_snapshot_bytes"),

    /** The snapshots the current generation may hold. */
    MAXIMUM_CURRENT_GENERATION_SNAPSHOT_ROWS(Section.TRANSPORT, "maximum_current_generation_snapshot_rows"),

    /** The longest subscription identifier a following daemon may present. */
    MAXIMUM_DAEMON_SUBSCRIPTION_IDENTIFIER_BYTES(
            Section.TRANSPORT, "maximum_daemon_subscription_identifier_bytes"),

    /** The largest bounded response body that may be read in full. */
    MAXIMUM_FINITE_RESPONSE_BODY_BYTES(Section.TRANSPORT, "maximum_finite_response_body_bytes"),

    /** The most physical attempts one logical operation's outbox may make before it stops. */
    MAXIMUM_LOGICAL_OUTBOX_ATTEMPTS(Section.TRANSPORT, "maximum_logical_outbox_attempts"),

    /** The event bytes one operation may produce before it is refused more. */
    MAXIMUM_OPERATION_EVENT_BYTES(Section.TRANSPORT, "maximum_operation_event_bytes"),

    /** The events one operation may produce before it is refused more. */
    MAXIMUM_OPERATION_EVENT_ROWS(Section.TRANSPORT, "maximum_operation_event_rows"),

    /** The longest remaining retention that may be persisted for anything stored. */
    MAXIMUM_PERSISTED_REMAINING_RETENTION_MILLISECONDS(
            Section.TRANSPORT, "maximum_persisted_remaining_retention_milliseconds"),

    /** The longest identifier one physical attempt may carry. */
    MAXIMUM_PHYSICAL_ATTEMPT_IDENTIFIER_BYTES(Section.TRANSPORT, "maximum_physical_attempt_identifier_bytes"),

    /** The most Sling jobs a physical lookup may match before it refuses to guess. */
    MAXIMUM_PHYSICAL_SLING_JOB_MATCHES(Section.TRANSPORT, "maximum_physical_sling_job_matches"),

    /** The artifact bytes every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_ARTIFACT_BYTES(Section.TRANSPORT, "maximum_prior_generation_artifact_bytes"),

    /** The artifacts every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_ARTIFACT_ROWS(Section.TRANSPORT, "maximum_prior_generation_artifact_rows"),

    /** The operation-detail bytes every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_OPERATION_DETAIL_BYTES(
            Section.TRANSPORT, "maximum_prior_generation_operation_detail_bytes"),

    /** The operation details every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_OPERATION_DETAIL_ROWS(
            Section.TRANSPORT, "maximum_prior_generation_operation_detail_rows"),

    /** The result bytes every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_RESULT_BYTES(Section.TRANSPORT, "maximum_prior_generation_result_bytes"),

    /** The results every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_RESULT_ROWS(Section.TRANSPORT, "maximum_prior_generation_result_rows"),

    /** The snapshot bytes every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_SNAPSHOT_BYTES(Section.TRANSPORT, "maximum_prior_generation_snapshot_bytes"),

    /** The snapshots every retained prior generation may hold together. */
    MAXIMUM_PRIOR_GENERATION_SNAPSHOT_ROWS(Section.TRANSPORT, "maximum_prior_generation_snapshot_rows"),

    /** How many superseded event-store generations are kept before the oldest is collected. */
    MAXIMUM_PRIOR_GENERATIONS(Section.TRANSPORT, "maximum_prior_generations"),

    /** The largest query string a route will read before refusing the request. */
    MAXIMUM_ROUTE_QUERY_BYTES(Section.TRANSPORT, "maximum_route_query_bytes"),

    /** The bytes one stream may hold buffered for a client that is not reading. */
    MAXIMUM_SERVER_SENT_EVENT_BUFFER_BYTES(Section.TRANSPORT, "maximum_server_sent_event_buffer_bytes"),

    /** The largest single event that may be written to a stream. */
    MAXIMUM_SERVER_SENT_EVENT_BYTES(Section.TRANSPORT, "maximum_server_sent_event_bytes"),

    /** The largest single line an event may be written as. */
    MAXIMUM_SERVER_SENT_EVENT_LINE_BYTES(Section.TRANSPORT, "maximum_server_sent_event_line_bytes"),

    /** The longest Sling job identifier that may be recorded or looked up. */
    TRANSPORT_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES(
            Section.TRANSPORT, "maximum_sling_job_identifier_bytes"),

    /** The shortest time an artifact is kept before collection may take it. */
    MINIMUM_ARTIFACT_RETENTION_MILLISECONDS(Section.TRANSPORT, "minimum_artifact_retention_milliseconds"),

    /** The shortest time an operation's detail is kept before collection may take it. */
    MINIMUM_OPERATION_DETAIL_RETENTION_MILLISECONDS(
            Section.TRANSPORT, "minimum_operation_detail_retention_milliseconds"),

    /** The shortest time a result is kept before collection may take it. */
    MINIMUM_RESULT_RETENTION_MILLISECONDS(Section.TRANSPORT, "minimum_result_retention_milliseconds"),

    /** The shortest time a snapshot is kept before collection may take it. */
    MINIMUM_SNAPSHOT_RETENTION_MILLISECONDS(Section.TRANSPORT, "minimum_snapshot_retention_milliseconds"),

    /** How long an operation nobody can find is treated as still arriving rather than absent. */
    MISSING_OPERATION_GRACE_MILLISECONDS(Section.TRANSPORT, "missing_operation_grace_milliseconds"),

    /** How long looking a physical job up may take before the answer is unknown rather than late. */
    PHYSICAL_JOB_LOOKUP_TIMEOUT_MILLISECONDS(Section.TRANSPORT, "physical_job_lookup_timeout_milliseconds"),

    /** The longest retry delay this agent will ever ask a client to wait. */
    RETRY_AFTER_CAP_MILLISECONDS(Section.TRANSPORT, "retry_after_cap_milliseconds"),

    /** The delay the client's own backoff is computed from. */
    RETRY_BASE_MILLISECONDS(Section.TRANSPORT, "retry_base_milliseconds"),

    /** The most jitter the client's own backoff may add to a delay. */
    RETRY_JITTER_CAP_MILLISECONDS(Section.TRANSPORT, "retry_jitter_cap_milliseconds"),

    /** How long a worker holds its execution fence before the lease is lost. */
    WORKER_EXECUTION_LEASE_MILLISECONDS(Section.TRANSPORT, "worker_execution_lease_milliseconds"),

    /** How often a worker renews the execution fence it holds. */
    WORKER_EXECUTION_LEASE_RENEWAL_MILLISECONDS(
            Section.TRANSPORT, "worker_execution_lease_renewal_milliseconds"),

    /** The disagreement between two clocks every two-instant comparison is decided under. */
    CLOCK_SKEW_ALLOWANCE_MILLISECONDS(Section.AGENT, "clock_skew_allowance_milliseconds"),

    /** How long a health check that does real work holds its answer before doing it again. */
    HEALTH_CHECK_INTERVAL_MILLISECONDS(Section.AGENT, "health_check_interval_milliseconds"),

    /** The rows one maintenance sweep may examine before it stops and resumes later. */
    MAINTENANCE_SWEEP_WORK_BOUND_ROWS(Section.AGENT, "maintenance_sweep_work_bound_rows"),

    /** The commands one caller may have running at once, which are request threads it holds. */
    MAXIMUM_CALLER_CONCURRENT_COMMAND_EXECUTIONS(
            Section.AGENT, "maximum_caller_concurrent_command_executions"),

    /** The event streams one caller may hold open at once. */
    MAXIMUM_CALLER_CONCURRENT_EVENT_STREAMS(Section.AGENT, "maximum_caller_concurrent_event_streams"),

    /** The live-subscription bytes one caller may occupy in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_BYTES(
            Section.AGENT, "maximum_caller_current_generation_active_subscription_bytes"),

    /** The live subscriptions one caller may hold in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_ACTIVE_SUBSCRIPTION_ROWS(
            Section.AGENT, "maximum_caller_current_generation_active_subscription_rows"),

    /** The artifact bytes one caller may occupy in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_ARTIFACT_BYTES(
            Section.AGENT, "maximum_caller_current_generation_artifact_bytes"),

    /** The artifacts one caller may hold in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_ARTIFACT_ROWS(
            Section.AGENT, "maximum_caller_current_generation_artifact_rows"),

    /** The event bytes one caller may occupy in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_EVENT_BYTES(
            Section.AGENT, "maximum_caller_current_generation_event_bytes"),

    /** The events one caller may hold in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_EVENT_ROWS(
            Section.AGENT, "maximum_caller_current_generation_event_rows"),

    /** The operation-detail bytes one caller may occupy in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_DETAIL_BYTES(
            Section.AGENT, "maximum_caller_current_generation_operation_detail_bytes"),

    /** The operation details one caller may hold in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_DETAIL_ROWS(
            Section.AGENT, "maximum_caller_current_generation_operation_detail_rows"),

    /** The reservation bytes one caller may occupy while its submissions are in flight. */
    MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_RESERVATION_BYTES(
            Section.AGENT, "maximum_caller_current_generation_operation_reservation_bytes"),

    /** The submission reservations one caller may hold at once. */
    MAXIMUM_CALLER_CURRENT_GENERATION_OPERATION_RESERVATION_ROWS(
            Section.AGENT, "maximum_caller_current_generation_operation_reservation_rows"),

    /** The result bytes one caller may occupy in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_RESULT_BYTES(
            Section.AGENT, "maximum_caller_current_generation_result_bytes"),

    /** The results one caller may hold in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_RESULT_ROWS(
            Section.AGENT, "maximum_caller_current_generation_result_rows"),

    /** The snapshot bytes one caller may occupy in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_SNAPSHOT_BYTES(
            Section.AGENT, "maximum_caller_current_generation_snapshot_bytes"),

    /** The snapshots one caller may hold in the current generation. */
    MAXIMUM_CALLER_CURRENT_GENERATION_SNAPSHOT_ROWS(
            Section.AGENT, "maximum_caller_current_generation_snapshot_rows"),

    /**
     * How long one command may execute before its budget is spent, kept below every supported
     * deployment's request window.
     */
    MAXIMUM_COMMAND_EXECUTION_MILLISECONDS(Section.AGENT, "maximum_command_execution_milliseconds"),

    /** The commands this agent will have running at once, across every caller. */
    MAXIMUM_CONCURRENT_COMMAND_EXECUTIONS(Section.AGENT, "maximum_concurrent_command_executions"),

    /** The event streams this agent will hold open at once, across every caller. */
    MAXIMUM_CONCURRENT_EVENT_STREAMS(Section.AGENT, "maximum_concurrent_event_streams"),

    /** The most rows one console page carries, which a request past it is clamped to. */
    MAXIMUM_CONSOLE_PAGE_ROWS(Section.AGENT, "maximum_console_page_rows"),

    /** The most one log message may be, past which it is refused rather than truncated. */
    MAXIMUM_LOG_MESSAGE_BYTES(Section.AGENT, "maximum_log_message_bytes"),

    /** How long the code of one refusal this agent reports may be, in bytes. */
    MAXIMUM_AGENT_ERROR_CODE_BYTES(Section.AGENT, "maximum_agent_error_code_bytes"),

    /** How long the message of one refusal this agent reports may be, in bytes. */
    MAXIMUM_AGENT_ERROR_MESSAGE_BYTES(Section.AGENT, "maximum_agent_error_message_bytes"),

    /** How deep one document may nest before a reader stops rather than recurses. */
    MAXIMUM_DOCUMENT_NESTING_DEPTH(Section.AGENT, "maximum_document_nesting_depth"),

    /** How many members one object in a document may carry. */
    MAXIMUM_DOCUMENT_OBJECT_MEMBERS(Section.AGENT, "maximum_document_object_members"),

    /** How long one member name or one string value in a document may be, in bytes. */
    MAXIMUM_DOCUMENT_STRING_BYTES(Section.AGENT, "maximum_document_string_bytes"),

    /**
     * How long one event-stream session is held before the agent ends it first, so the gateway never
     * does.
     */
    MAXIMUM_EVENT_STREAM_SESSION_MILLISECONDS(Section.AGENT, "maximum_event_stream_session_milliseconds"),

    /** The largest request body this agent will read before refusing it as it arrives. */
    MAXIMUM_REQUEST_BODY_BYTES(Section.AGENT, "maximum_request_body_bytes"),

    /** How far a submitted request-start instant may sit from this side's own clock. */
    MAXIMUM_REQUEST_START_SKEW_MILLISECONDS(Section.AGENT, "maximum_request_start_skew_milliseconds"),

    /** How long the environment revision one operation was selected against may be, in bytes. */
    MAXIMUM_SELECTED_ENVIRONMENT_REVISION_BYTES(
            Section.AGENT, "maximum_selected_environment_revision_bytes"),

    /** How long a call into the platform may take before it is abandoned rather than waited on. */
    PLATFORM_CALL_DEADLINE_MILLISECONDS(Section.AGENT, "platform_call_deadline_milliseconds"),

    /** How often recovery reconciles the store, rather than only at startup. */
    RECOVERY_RECONCILIATION_INTERVAL_MILLISECONDS(
            Section.AGENT, "recovery_reconciliation_interval_milliseconds"),

    /** What recovery adds to an execution budget before it calls a started operation undetermined. */
    RECOVERY_UNDETERMINED_MARGIN_MILLISECONDS(Section.AGENT, "recovery_undetermined_margin_milliseconds"),

    /** How long a continuation token stays resumable after it is issued. */
    CONTINUATION_TOKEN_LIFETIME_MILLISECONDS(
            Section.COMMAND, "continuation_token_lifetime_milliseconds"),

    /** How deep a load descends when the caller asks for no particular depth. */
    DEFAULT_LOAD_DEPTH(Section.COMMAND, "default_load_depth"),

    /** How many matches a page carries when the caller asks for no particular number. */
    DEFAULT_RESULT_LIMIT(Section.COMMAND, "default_result_limit"),

    /** How many references one move or rename may repoint before it refuses. */
    MAXIMUM_ADJUSTED_REFERENCES(Section.COMMAND, "maximum_adjusted_references"),

    /** How large a loaded document may be before it is served as an artifact instead. */
    MAXIMUM_AGENT_INLINE_LOADED_DOCUMENT_BYTES(
            Section.COMMAND, "maximum_agent_inline_loaded_document_bytes"),

    /** How long an artifact's identifier may be. */
    MAXIMUM_ARTIFACT_IDENTIFIER_BYTES(Section.COMMAND, "maximum_artifact_identifier_bytes"),

    /** How long the media type declared for an artifact may be. */
    MAXIMUM_ARTIFACT_MEDIA_TYPE_BYTES(Section.COMMAND, "maximum_artifact_media_type_bytes"),

    /** How long the name of the slot an artifact occupies may be. */
    MAXIMUM_ARTIFACT_SLOT_BYTES(Section.COMMAND, "maximum_artifact_slot_bytes"),

    /** How long the file name an artifact suggests to whoever saves it may be. */
    MAXIMUM_ARTIFACT_SUGGESTED_FILE_NAME_BYTES(
            Section.COMMAND, "maximum_artifact_suggested_file_name_bytes"),

    /** How large an asset's own bytes may be, which is as large as a count can be. */
    MAXIMUM_ASSET_BYTE_LENGTH(Section.COMMAND, "maximum_asset_byte_length"),

    /** How many paths one asset lookup may report as referring to it. */
    MAXIMUM_ASSET_REFERENCE_PATHS(Section.COMMAND, "maximum_asset_reference_paths"),

    /** How long one tag applied to an asset may be. */
    MAXIMUM_ASSET_TAG_BYTES(Section.COMMAND, "maximum_asset_tag_bytes"),

    /** How long the reason recorded for disabling a user may be. */
    MAXIMUM_AUTHORIZABLE_DISABLED_REASON_BYTES(
            Section.COMMAND, "maximum_authorizable_disabled_reason_bytes"),

    /** How long a user or group identifier may be. */
    MAXIMUM_AUTHORIZABLE_IDENTIFIER_BYTES(Section.COMMAND, "maximum_authorizable_identifier_bytes"),

    /** How long the path a new user or group is created under may be. */
    MAXIMUM_AUTHORIZABLE_INTERMEDIATE_PATH_BYTES(
            Section.COMMAND, "maximum_authorizable_intermediate_path_bytes"),

    /** How many bundle states one listing may be filtered by. */
    MAXIMUM_BUNDLE_STATES(Section.COMMAND, "maximum_bundle_states"),

    /** How long a bundle's symbolic name may be. */
    MAXIMUM_BUNDLE_SYMBOLIC_NAME_BYTES(Section.COMMAND, "maximum_bundle_symbolic_name_bytes"),

    /** How long a bundle's version may be. */
    MAXIMUM_BUNDLE_VERSION_BYTES(Section.COMMAND, "maximum_bundle_version_bytes"),

    /** How large one command's whole argument document may be. */
    MAXIMUM_COMMAND_ARGUMENT_BYTES(Section.COMMAND, "maximum_command_argument_bytes"),

    /** How long a command's description may be in the published registry. */
    MAXIMUM_COMMAND_DESCRIPTION_BYTES(Section.COMMAND, "maximum_command_description_bytes"),

    /** How many failure categories one command may declare. */
    MAXIMUM_COMMAND_FAILURE_CATEGORIES(Section.COMMAND, "maximum_command_failure_categories"),

    /** How large one command's whole result document may be. */
    MAXIMUM_COMMAND_RESULT_BYTES(Section.COMMAND, "maximum_command_result_bytes"),

    /** How large one command's argument or result schema may be. */
    MAXIMUM_COMMAND_SCHEMA_BYTES(Section.COMMAND, "maximum_command_schema_bytes"),

    /** How large the manifest naming every command schema may be. */
    MAXIMUM_COMMAND_SCHEMA_MANIFEST_BYTES(Section.COMMAND, "maximum_command_schema_manifest_bytes"),

    /** How long a command's semantic contract version may be. */
    MAXIMUM_COMMAND_SEMANTIC_CONTRACT_VERSION_BYTES(
            Section.COMMAND, "maximum_command_semantic_contract_version_bytes"),

    /** How many dot-separated identifiers that version may carry. */
    MAXIMUM_COMMAND_SEMANTIC_CONTRACT_VERSION_IDENTIFIERS(
            Section.COMMAND, "maximum_command_semantic_contract_version_identifiers"),

    /** How many digits one numeric identifier in that version may have. */
    MAXIMUM_COMMAND_SEMANTIC_CONTRACT_VERSION_NUMERIC_DIGITS(
            Section.COMMAND, "maximum_command_semantic_contract_version_numeric_digits"),

    /** How long a command's title may be in the published registry. */
    MAXIMUM_COMMAND_TITLE_BYTES(Section.COMMAND, "maximum_command_title_bytes"),

    /** How long a command's wire name may be. */
    MAXIMUM_COMMAND_WIRE_NAME_BYTES(Section.COMMAND, "maximum_command_wire_name_bytes"),

    /** How long the node name of a component may be. */
    MAXIMUM_COMPONENT_NAME_BYTES(Section.COMMAND, "maximum_component_name_bytes"),

    /** How long a component's resource type may be. */
    MAXIMUM_COMPONENT_RESOURCE_TYPE_BYTES(Section.COMMAND, "maximum_component_resource_type_bytes"),

    /** How many path segments a component's resource type may have. */
    MAXIMUM_COMPONENT_RESOURCE_TYPE_SEGMENTS(
            Section.COMMAND, "maximum_component_resource_type_segments"),

    /** How many component states one listing may be filtered by. */
    MAXIMUM_COMPONENT_STATES(Section.COMMAND, "maximum_component_states"),

    /** How long looking one configuration up may take before it is abandoned. */
    MAXIMUM_CONFIGURATION_LOOKUP_DURATION_MILLISECONDS(
            Section.COMMAND, "maximum_configuration_lookup_duration_milliseconds"),

    /** How long the filter selecting a configuration may be. */
    MAXIMUM_CONFIGURATION_LOOKUP_FILTER_BYTES(
            Section.COMMAND, "maximum_configuration_lookup_filter_bytes"),

    /** How many configurations a filter may match before the answer is ambiguous. */
    MAXIMUM_CONFIGURATION_LOOKUP_MATCHES(Section.COMMAND, "maximum_configuration_lookup_matches"),

    /** How long a configuration's persistent identifier may be. */
    MAXIMUM_CONFIGURATION_PERSISTENT_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_configuration_persistent_identifier_bytes"),

    /** How long one configuration property's key may be. */
    MAXIMUM_CONFIGURATION_PROPERTY_KEY_BYTES(
            Section.COMMAND, "maximum_configuration_property_key_bytes"),

    /** How long one string a configuration property carries may be. */
    MAXIMUM_CONFIGURATION_SCALAR_STRING_BYTES(
            Section.COMMAND, "maximum_configuration_scalar_string_bytes"),

    /** How large the canonical bytes of one configuration sequence may be. */
    MAXIMUM_CONFIGURATION_SEQUENCE_CANONICAL_BYTES(
            Section.COMMAND, "maximum_configuration_sequence_canonical_bytes"),

    /** How many items one configuration sequence may carry. */
    MAXIMUM_CONFIGURATION_SEQUENCE_ITEMS(Section.COMMAND, "maximum_configuration_sequence_items"),

    /** How long one content fragment element's name may be. */
    MAXIMUM_CONTENT_FRAGMENT_ELEMENT_NAME_BYTES(
            Section.COMMAND, "maximum_content_fragment_element_name_bytes"),

    /** How many values one content fragment element may carry. */
    MAXIMUM_CONTENT_FRAGMENT_ELEMENT_VALUES(
            Section.COMMAND, "maximum_content_fragment_element_values"),

    /** How many elements one content fragment may carry. */
    MAXIMUM_CONTENT_FRAGMENT_ELEMENTS(Section.COMMAND, "maximum_content_fragment_elements"),

    /** How long one content fragment variation's name may be. */
    MAXIMUM_CONTENT_FRAGMENT_VARIATION_NAME_BYTES(
            Section.COMMAND, "maximum_content_fragment_variation_name_bytes"),

    /** How long the key identifier a continuation token names may be. */
    MAXIMUM_CONTINUATION_KEY_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_continuation_key_identifier_bytes"),

    /** How large the canonical resume key inside a continuation token may be. */
    MAXIMUM_CONTINUATION_RESUME_KEY_CANONICAL_BYTES(
            Section.COMMAND, "maximum_continuation_resume_key_canonical_bytes"),

    /** How large a whole continuation token may be. */
    MAXIMUM_CONTINUATION_TOKEN_BYTES(Section.COMMAND, "maximum_continuation_token_bytes"),

    /** How far ahead of validation a token may claim to have been issued. */
    MAXIMUM_CONTINUATION_TOKEN_CLOCK_SKEW_MILLISECONDS(
            Section.COMMAND, "maximum_continuation_token_clock_skew_milliseconds"),

    /** How many fractional-second digits a date and time may carry. */
    MAXIMUM_DATE_TIME_FRACTION_DIGITS(Section.COMMAND, "maximum_date_time_fraction_digits"),

    /** How long a decimal number's written form may be. */
    MAXIMUM_DECIMAL_BYTES(Section.COMMAND, "maximum_decimal_bytes"),

    /** How many digits a decimal may carry after the point. */
    MAXIMUM_DECIMAL_FRACTION_DIGITS(Section.COMMAND, "maximum_decimal_fraction_digits"),

    /** How many digits a decimal may carry before the point. */
    MAXIMUM_DECIMAL_INTEGER_DIGITS(Section.COMMAND, "maximum_decimal_integer_digits"),

    /** How long a declarative-services component's name may be. */
    MAXIMUM_DECLARATIVE_SERVICE_COMPONENT_NAME_BYTES(
            Section.COMMAND, "maximum_declarative_service_component_name_bytes"),

    /** How many nodes one deletion may remove before it refuses. */
    MAXIMUM_DELETED_NODES(Section.COMMAND, "maximum_deleted_nodes"),

    /** How many nodes a discovery command may consider. */
    MAXIMUM_DISCOVERY_CANDIDATE_NODES(Section.COMMAND, "maximum_discovery_candidate_nodes"),

    /** How many criterion evaluations one discovery command may perform. */
    MAXIMUM_DISCOVERY_CRITERION_EVALUATIONS(
            Section.COMMAND, "maximum_discovery_criterion_evaluations"),

    /** How long one discovery command may run before it stops. */
    MAXIMUM_DISCOVERY_EXECUTION_DURATION_MILLISECONDS(
            Section.COMMAND, "maximum_discovery_execution_duration_milliseconds"),

    /** How many property bytes one discovery command may read. */
    MAXIMUM_DISCOVERY_PROPERTY_BYTES(Section.COMMAND, "maximum_discovery_property_bytes"),

    /** How many property values one discovery command may read. */
    MAXIMUM_DISCOVERY_PROPERTY_VALUES(Section.COMMAND, "maximum_discovery_property_values"),

    /** How large one discovery command's result may be. */
    MAXIMUM_DISCOVERY_RESULT_BYTES(Section.COMMAND, "maximum_discovery_result_bytes"),

    /** How long one experience fragment variation's name may be. */
    MAXIMUM_EXPERIENCE_FRAGMENT_VARIATION_NAME_BYTES(
            Section.COMMAND, "maximum_experience_fragment_variation_name_bytes"),

    /** How large a binary supplied inline may be once decoded. */
    MAXIMUM_INLINE_BINARY_DECODED_BYTES(Section.COMMAND, "maximum_inline_binary_decoded_bytes"),

    /** How large a binary supplied inline may be as it arrives encoded. */
    MAXIMUM_INLINE_BINARY_ENCODED_BYTES(Section.COMMAND, "maximum_inline_binary_encoded_bytes"),

    /** How long the media type declared for an inline binary may be. */
    MAXIMUM_INLINE_BINARY_MEDIA_TYPE_BYTES(
            Section.COMMAND, "maximum_inline_binary_media_type_bytes"),

    /** How many properties one configuration inspection may report. */
    MAXIMUM_INSPECTED_CONFIGURATION_PROPERTIES(
            Section.COMMAND, "maximum_inspected_configuration_properties"),

    /** How large one configuration inspection's result may be. */
    MAXIMUM_INSPECTED_CONFIGURATION_RESULT_BYTES(
            Section.COMMAND, "maximum_inspected_configuration_result_bytes"),

    /** How deep a load may be asked to descend. */
    MAXIMUM_LOAD_DEPTH(Section.COMMAND, "maximum_load_depth"),

    /** How large a loaded document may be. */
    MAXIMUM_LOAD_DOCUMENT_BYTES(Section.COMMAND, "maximum_load_document_bytes"),

    /** How many property bytes one load may read. */
    MAXIMUM_LOAD_PROPERTY_BYTES(Section.COMMAND, "maximum_load_property_bytes"),

    /** How many property values one load may read. */
    MAXIMUM_LOAD_PROPERTY_VALUES(Section.COMMAND, "maximum_load_property_values"),

    /** How many nodes one load may read. */
    MAXIMUM_LOAD_RESOURCE_NODES(Section.COMMAND, "maximum_load_resource_nodes"),

    /** How long one load's traversal may take before it stops. */
    MAXIMUM_LOAD_TRAVERSAL_DURATION_MILLISECONDS(
            Section.COMMAND, "maximum_load_traversal_duration_milliseconds"),

    /** How large the artifact a load overflows into may be. */
    MAXIMUM_LOADED_CONTENT_ARTIFACT_BYTES(Section.COMMAND, "maximum_loaded_content_artifact_bytes"),

    /** How long one media format's name may be. */
    MAXIMUM_MEDIA_FORMAT_BYTES(Section.COMMAND, "maximum_media_format_bytes"),

    /** How many properties one mutation may set or remove. */
    MAXIMUM_MUTATION_PROPERTIES(Section.COMMAND, "maximum_mutation_properties"),

    /** How large the result of a mutation that succeeded may be. */
    MAXIMUM_MUTATION_SUCCESS_RESULT_BYTES(Section.COMMAND, "maximum_mutation_success_result_bytes"),

    /** How many platform records one operational command may consider. */
    MAXIMUM_OPERATIONAL_CANDIDATE_RECORDS(Section.COMMAND, "maximum_operational_candidate_records"),

    /** How large one operational inspection's result may be. */
    MAXIMUM_OPERATIONAL_INSPECTION_RESULT_BYTES(
            Section.COMMAND, "maximum_operational_inspection_result_bytes"),

    /** How large one operational listing's result may be. */
    MAXIMUM_OPERATIONAL_LISTING_RESULT_BYTES(
            Section.COMMAND, "maximum_operational_listing_result_bytes"),

    /** How many entries one content package archive may hold. */
    MAXIMUM_PACKAGE_ARCHIVE_ENTRIES(Section.COMMAND, "maximum_package_archive_entries"),

    /** How many paths one package build may consider. */
    MAXIMUM_PACKAGE_CANDIDATE_PATHS(Section.COMMAND, "maximum_package_candidate_paths"),

    /** How many exclusion expressions one package filter may carry. */
    MAXIMUM_PACKAGE_EXCLUSION_EXPRESSIONS(Section.COMMAND, "maximum_package_exclusion_expressions"),

    /** How large one package's filter document may be. */
    MAXIMUM_PACKAGE_FILTER_DOCUMENT_BYTES(Section.COMMAND, "maximum_package_filter_document_bytes"),

    /** How many inclusion expressions one package filter may carry. */
    MAXIMUM_PACKAGE_INCLUSION_EXPRESSIONS(Section.COMMAND, "maximum_package_inclusion_expressions"),

    /** How large one package's manifest may be. */
    MAXIMUM_PACKAGE_MANIFEST_BYTES(Section.COMMAND, "maximum_package_manifest_bytes"),

    /** How many cells one package pattern match may fill, which bounds the match itself. */
    MAXIMUM_PACKAGE_MATCHER_CELLS(Section.COMMAND, "maximum_package_matcher_cells"),

    /** How long a content package's name may be. */
    MAXIMUM_PACKAGE_NAME_BYTES(Section.COMMAND, "maximum_package_name_bytes"),

    /** How large the package one build produces may be. */
    MAXIMUM_PACKAGE_OUTPUT_BYTES(Section.COMMAND, "maximum_package_output_bytes"),

    /** How many pattern evaluations one package build may perform. */
    MAXIMUM_PACKAGE_PATTERN_EVALUATIONS(Section.COMMAND, "maximum_package_pattern_evaluations"),

    /** How many roots one package filter may declare. */
    MAXIMUM_PACKAGE_ROOTS(Section.COMMAND, "maximum_package_roots"),

    /** How many paths one package build may select. */
    MAXIMUM_PACKAGE_SELECTED_PATHS(Section.COMMAND, "maximum_package_selected_paths"),

    /** How long one package selection expression may be. */
    MAXIMUM_PACKAGE_SELECTION_EXPRESSION_BYTES(
            Section.COMMAND, "maximum_package_selection_expression_bytes"),

    /** How many tokens one package selection expression may carry. */
    MAXIMUM_PACKAGE_SELECTION_EXPRESSION_TOKENS(
            Section.COMMAND, "maximum_package_selection_expression_tokens"),

    /** How long the file name a built package suggests may be. */
    MAXIMUM_PACKAGE_SUGGESTED_FILE_NAME_BYTES(
            Section.COMMAND, "maximum_package_suggested_file_name_bytes"),

    /** How large a package's input may be before compression. */
    MAXIMUM_PACKAGE_UNCOMPRESSED_INPUT_BYTES(
            Section.COMMAND, "maximum_package_uncompressed_input_bytes"),

    /** How long a page's node name may be. */
    MAXIMUM_PAGE_NAME_BYTES(Section.COMMAND, "maximum_page_name_bytes"),

    /** How long a page's title may be. */
    MAXIMUM_PAGE_TITLE_BYTES(Section.COMMAND, "maximum_page_title_bytes"),

    /** How long a primary node type's name may be. */
    MAXIMUM_PRIMARY_NODE_TYPE_NAME_BYTES(Section.COMMAND, "maximum_primary_node_type_name_bytes"),

    /** How long one property's name may be. */
    MAXIMUM_PROPERTY_NAME_BYTES(Section.COMMAND, "maximum_property_name_bytes"),

    /** How many values one property predicate may test against. */
    MAXIMUM_PROPERTY_PREDICATE_VALUES(Section.COMMAND, "maximum_property_predicate_values"),

    /** How many property predicates one query may carry. */
    MAXIMUM_PROPERTY_PREDICATES(Section.COMMAND, "maximum_property_predicates"),

    /** How long one string a property carries may be. */
    MAXIMUM_PROPERTY_STRING_BYTES(Section.COMMAND, "maximum_property_string_bytes"),

    /** How many items one multi-valued property may carry. */
    MAXIMUM_PROPERTY_VALUE_ITEMS(Section.COMMAND, "maximum_property_value_items"),

    /** How long a property path relative to a node may be. */
    MAXIMUM_RELATIVE_PROPERTY_PATH_BYTES(Section.COMMAND, "maximum_relative_property_path_bytes"),

    /** How many property names one mutation may remove. */
    MAXIMUM_REMOVED_PROPERTY_NAMES(Section.COMMAND, "maximum_removed_property_names"),

    /** How long one asset rendition's name may be. */
    MAXIMUM_RENDITION_NAME_BYTES(Section.COMMAND, "maximum_rendition_name_bytes"),

    /** How long admitting content for replication may take before it stops. */
    MAXIMUM_REPLICATION_ADMISSION_DURATION_MILLISECONDS(
            Section.COMMAND, "maximum_replication_admission_duration_milliseconds"),

    /** How long a replication agent's identifier may be. */
    MAXIMUM_REPLICATION_AGENT_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_replication_agent_identifier_bytes"),

    /** How many paths one replication may consider. */
    MAXIMUM_REPLICATION_CANDIDATE_PATHS(Section.COMMAND, "maximum_replication_candidate_paths"),

    /** How many entries one replication queue listing may report. */
    MAXIMUM_REPLICATION_QUEUE_ENTRIES(Section.COMMAND, "maximum_replication_queue_entries"),

    /** How long one replication queue entry's identifier may be. */
    MAXIMUM_REPLICATION_QUEUE_ENTRY_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_replication_queue_entry_identifier_bytes"),

    /** How large one replication command's result may be. */
    MAXIMUM_REPLICATION_RESULT_BYTES(Section.COMMAND, "maximum_replication_result_bytes"),

    /** How long one replication's traversal may take before it stops. */
    MAXIMUM_REPLICATION_TRAVERSAL_DURATION_MILLISECONDS(
            Section.COMMAND, "maximum_replication_traversal_duration_milliseconds"),

    /** How long one repository node's own name may be. */
    MAXIMUM_REPOSITORY_NAME_BYTES(Section.COMMAND, "maximum_repository_name_bytes"),

    /** How long an absolute repository path may be. */
    MAXIMUM_REPOSITORY_PATH_BYTES(Section.COMMAND, "maximum_repository_path_bytes"),

    /** How many segments an absolute repository path may have. */
    MAXIMUM_REPOSITORY_PATH_SEGMENTS(Section.COMMAND, "maximum_repository_path_segments"),

    /** How long a path naming a property may be. */
    MAXIMUM_REPOSITORY_PROPERTY_PATH_BYTES(
            Section.COMMAND, "maximum_repository_property_path_bytes"),

    /** How long one reference to another node may be. */
    MAXIMUM_REPOSITORY_REFERENCE_BYTES(Section.COMMAND, "maximum_repository_reference_bytes"),

    /** How long a path relative to another node may be. */
    MAXIMUM_REPOSITORY_RELATIVE_PATH_BYTES(
            Section.COMMAND, "maximum_repository_relative_path_bytes"),

    /** How long a repository identifier written as a uniform resource identifier may be. */
    MAXIMUM_REPOSITORY_UNIFORM_RESOURCE_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_repository_uniform_resource_identifier_bytes"),

    /** How long an address supplied as a command argument may be. */
    MAXIMUM_REQUEST_ADDRESS_BYTES(Section.COMMAND, "maximum_request_address_bytes"),

    /** How many asset tags one command may be asked about. */
    MAXIMUM_REQUESTED_ASSET_TAGS(Section.COMMAND, "maximum_requested_asset_tags"),

    /** How many component resource types one command may be asked about. */
    MAXIMUM_REQUESTED_COMPONENT_RESOURCE_TYPES(
            Section.COMMAND, "maximum_requested_component_resource_types"),

    /** How many media formats one command may be asked about. */
    MAXIMUM_REQUESTED_MEDIA_FORMATS(Section.COMMAND, "maximum_requested_media_formats"),

    /** How many steps a path resolution may report having taken. */
    MAXIMUM_RESOLUTION_TRACE_ENTRIES(Section.COMMAND, "maximum_resolution_trace_entries"),

    /** How long one resource mapping's pattern may be. */
    MAXIMUM_RESOURCE_MAPPING_PATTERN_BYTES(
            Section.COMMAND, "maximum_resource_mapping_pattern_bytes"),

    /** How many replacements one resource mapping may apply. */
    MAXIMUM_RESOURCE_MAPPING_REPLACEMENTS(Section.COMMAND, "maximum_resource_mapping_replacements"),

    /** How many matches one page may be asked for. */
    MAXIMUM_RESULT_LIMIT(Section.COMMAND, "maximum_result_limit"),

    /** How many matches an enumeration may be asked to skip. */
    MAXIMUM_RESULT_OFFSET(Section.COMMAND, "maximum_result_offset"),

    /** How high a same-name sibling's index may go. */
    MAXIMUM_SAME_NAME_SIBLING_INDEX(Section.COMMAND, "maximum_same_name_sibling_index"),

    /** How long a phrase searched for in content may be. */
    MAXIMUM_SEARCH_PHRASE_BYTES(Section.COMMAND, "maximum_search_phrase_bytes"),

    /** How long a platform job's identifier may be when it is a command argument. */
    COMMAND_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_sling_job_identifier_bytes"),

    /** How many property keys one platform job may be reported with. */
    MAXIMUM_SLING_JOB_PROPERTY_KEYS(Section.COMMAND, "maximum_sling_job_property_keys"),

    /** How long a platform job queue's name may be. */
    MAXIMUM_SLING_JOB_QUEUE_NAME_BYTES(Section.COMMAND, "maximum_sling_job_queue_name_bytes"),

    /** How many job states one listing may be filtered by. */
    MAXIMUM_SLING_JOB_STATES(Section.COMMAND, "maximum_sling_job_states"),

    /** How long a platform job's topic may be. */
    MAXIMUM_SLING_JOB_TOPIC_BYTES(Section.COMMAND, "maximum_sling_job_topic_bytes"),

    /** How long one workflow work item's identifier may be. */
    MAXIMUM_WORK_ITEM_IDENTIFIER_BYTES(Section.COMMAND, "maximum_work_item_identifier_bytes"),

    /** How long a comment recorded against a workflow may be. */
    MAXIMUM_WORKFLOW_COMMENT_BYTES(Section.COMMAND, "maximum_workflow_comment_bytes"),

    /** How long a workflow instance's identifier may be. */
    MAXIMUM_WORKFLOW_INSTANCE_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_workflow_instance_identifier_bytes"),

    /** How many workflow states one listing may be filtered by. */
    MAXIMUM_WORKFLOW_INSTANCE_STATES(Section.COMMAND, "maximum_workflow_instance_states"),

    /** How many metadata entries one workflow may be started with. */
    MAXIMUM_WORKFLOW_METADATA_ENTRIES(Section.COMMAND, "maximum_workflow_metadata_entries"),

    /** How long a workflow model's identifier may be. */
    MAXIMUM_WORKFLOW_MODEL_IDENTIFIER_BYTES(
            Section.COMMAND, "maximum_workflow_model_identifier_bytes"),

    /** How many work items one workflow instance may be reported with. */
    MAXIMUM_WORKFLOW_WORK_ITEMS(Section.COMMAND, "maximum_workflow_work_items");

    /** Which half of the contract a bound belongs to. */
    public enum Section {

        /** The client's own transport contract, reproduced here byte-equivalently. */
        TRANSPORT("transport"),

        /** This side's own bounds, which neither client contract does or can give. */
        AGENT("agent"),

        /** The client's own command contract, reproduced here byte-equivalently. */
        COMMAND("command");

        private final String table;

        Section(String table) {
            this.table = table;
        }

        /**
         * The table this section's bounds are declared under.
         *
         * @return the table name, exactly as the contract document spells it
         */
        public String table() {
            return table;
        }
    }

    private final Section section;
    private final String key;

    ContractLimit(Section section, String key) {
        this.section = section;
        this.key = key;
    }

    /**
     * Which half of the contract this bound belongs to.
     *
     * @return the bound's section
     */
    public Section section() {
        return section;
    }

    /**
     * The key this bound is declared under, exactly as the contract document spells it.
     *
     * @return the declared key
     */
    public String key() {
        return key;
    }

    /**
     * The dotted path this bound sits at in the contract document.
     *
     * @return the table and the key, joined
     */
    public String path() {
        return section.table() + "." + key;
    }
}
