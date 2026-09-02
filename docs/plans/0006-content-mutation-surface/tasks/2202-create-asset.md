---
id: create-asset
title: "Create an Asset"
workstream: "0022"
kind: task
depends_on:
  - create-asset-folder
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/asset/CreateAssetCommand.java
  - core/src/main/java/rs/slingshot/agent/command/asset/CreateAssetResult.java
  - core/src/main/resources/registry/create_asset.toml
  - "schemas/commands/create_asset/**"
  - core/src/test/java/rs/slingshot/agent/command/asset/CreateAssetCommandTest.java
  - "core/src/test/resources/fixtures/commands/create_asset/**"
  - aem/src/main/java/rs/slingshot/agent/aem/asset/CreateAssetHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/asset/CreateAssetHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CreateAssetScenario.java
  - interop/scenarios/create-asset.toml
status: done
merged_as: ""
---
# Create an Asset

The only mutation whose payload is the thing rather than a description of it. Sniffing a media type is guessing, and a guess written into a repository is a guess everything downstream inherits — so the type is declared, checked against a closed set, and refused when it is not one this build supports.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CreateAssetCommand` with a parent address, a name, a declared media type, the payload's declared byte count, and the payload reference itself.
3. Implement `CreateAssetResult` as the created asset's address, its stored byte count, and its digest, and claim nothing about renditions, which the platform makes later and this command cannot observe.
4. Declare exactly `parent_not_found`, `parent_access_denied`, `target_already_exists`, `payload_rejected`, `payload_too_large`, `media_type_unsupported`, `repository_commit_failed`, `mutation_outcome_unknown`. A payload past its bound and a payload whose declared and actual size differ are two distinct refusals, because one is a caller sending too much and the other is a transfer that went wrong.
5. Implement `CreateAssetHandler` streaming the payload without holding it, verifying the declared size as it goes, and writing the asset in one commit under the caller's session.

**Tests:**

- A declared media type outside the supported set is refused at construction; the type is never inferred from the payload's own bytes, proved by a payload whose content contradicts its declaration.
- A payload whose actual size differs from its declared size is refused with nothing written, in both directions, and the payload bound is proved at exactly its limit and one byte past.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`create_asset` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `create_asset` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CreateAssetCommandTest && ./mvnw verify -pl aem -Dtest=CreateAssetHandlerTest && ./mvnw verify -pl interop -Dtest=CreateAssetScenario` proves a declared media type checked against a closed set and never inferred, a size disagreement refused in both directions with nothing written, streaming without holding the payload, and no claim about renditions, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
