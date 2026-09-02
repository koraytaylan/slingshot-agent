---
id: read-content-fragment
title: "Read a Content Fragment"
workstream: "0019"
kind: task
depends_on:
  - list-asset-renditions
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/ReadContentFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/ReadContentFragmentResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/ReadContentFragmentHandler.java
  - policy/commands/read_content_fragment.toml
  - "schemas/agent-protocol/command/read_content_fragment-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/ReadContentFragmentCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ReadContentFragmentScenario.java
  - interop/scenarios/read-content-fragment.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Read a Content Fragment

A content fragment is a model, its elements, and its variations, and reading one without saying which variation is reading the one somebody happened to make first. The variation is an argument because there is no correct default.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ReadContentFragmentCommand` with a fragment address and a required variation name, so nothing inherits a default variation.
3. Implement `ReadContentFragmentResult` as the model address, the variation, and each element's name, declared type, and value, with values rendered by the same canonical mapping the content loader uses.
4. Declare exactly `fragment_not_found`, `fragment_access_denied`, `fragment_invalid`, `variation_not_found`, `result_budget_exceeded`. A variation that does not exist is a distinct refusal from a fragment that does not exist, because a caller who mistyped a variation has a different next step from one who mistyped an address.
5. Implement `ReadContentFragmentHandler` reading through the caller's read-only resolver and refusing a fragment whose model cannot be resolved rather than reporting its elements untyped.

**Tests:**

- Each element type the platform supports round-trips through the same canonical mapping the content loader uses, proved against that loader's own vectors.
- A missing variation and a missing fragment are two distinct refusals, and a fragment whose model is unresolvable is refused rather than reported untyped.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`read_content_fragment` at 262144 bytes).
- The operation-key rule is proved from the row rather than restated: `read_content_fragment` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ReadContentFragmentCommandTest && ./mvnw verify -pl aem -Dtest=ReadContentFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=ReadContentFragmentScenario` proves element values rendered by the content loader's own canonical mapping proved against its vectors, and three distinct refusals including an unresolvable model, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
