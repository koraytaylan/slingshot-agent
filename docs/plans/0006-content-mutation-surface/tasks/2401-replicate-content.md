---
id: replicate-content
title: "Replicate Content"
workstream: "0024"
kind: task
depends_on:
  - delete-experience-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/replication/ReplicateContentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/replication/SubtreeScope.java
  - core/src/main/java/rs/slingshot/agent/command/replication/ReplicateContentResult.java
  - core/src/main/resources/registry/replicate_content.toml
  - "schemas/commands/replicate_content/**"
  - core/src/test/java/rs/slingshot/agent/command/replication/ReplicateContentCommandTest.java
  - "core/src/test/resources/fixtures/commands/replicate_content/**"
  - aem/src/main/java/rs/slingshot/agent/aem/replication/ReplicateContentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/replication/ReplicateContentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ReplicateContentScenario.java
  - interop/scenarios/replicate-content.toml
status: done
merged_as: ""
---
# Replicate Content

An author cannot observe a publish instance. A command that reported content as published would be claiming something it has no way to know, so this one reports an admission — what was considered, what was offered, and what the platform said about the offer — and leaves finding out what happened next to the queue commands.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ReplicateContentCommand` with a source address, a required `SubtreeScope` naming whether the subtree travels with the source, a required candidate limit, and a required traversal budget — the scope a named type rather than a boolean, because offering one node and offering ten thousand are not a flag apart.
3. Implement `ReplicateContentResult` as the number of candidates considered, the number offered, and the platform's admission answer for each, and never a claim that anything was published.
4. Declare exactly `source_not_found`, `source_access_denied`, `candidate_limit_exceeded`, `traversal_budget_exceeded`, `admission_rejected`, `admission_budget_exceeded`, `admission_outcome_unknown`. `admission_outcome_unknown` exists for the same reason the mutation unknown does: an offer that left this process without an answer may have been accepted, and reporting a failure there would be false.
5. Implement `ReplicateContentHandler` walking candidates under the traversal budget with the caller's session, so a caller can only offer what they can read, and offering through the platform's own replication interface.

**Tests:**

- A caller who cannot read part of the subtree offers only the readable part, and the considered and offered counts differ accordingly.
- No result member claims publication, asserted over the result type, and the unknown admission outcome is reachable and distinct from every refusal.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`replicate_content` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `replicate_content` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ReplicateContentCommandTest && ./mvnw verify -pl aem -Dtest=ReplicateContentHandlerTest && ./mvnw verify -pl interop -Dtest=ReplicateContentScenario` proves an admission reported as considered, offered, and answered with no claim of publication anywhere in the type, only readable candidates offered under the caller's session, and both sides of the candidate limit and traversal budget, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
