---
id: download-content-package
title: "Download a Content Package"
workstream: "0019"
kind: task
depends_on:
  - read-content-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/RootDisposition.java
  - core/src/main/java/rs/slingshot/agent/command/content/DownloadContentPackageCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/DownloadContentPackageResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/DownloadContentPackageHandler.java
  - policy/commands/download_content_package.toml
  - "schemas/agent-protocol/command/download_content_package-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/DownloadContentPackageCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/DownloadContentPackageScenario.java
  - interop/scenarios/download-content-package.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Download a Content Package

The only read that produces something large enough to always be an artifact, the only one that needs scratch space while it works, and the one that comes closest to not fitting in the time it has. It runs inside its caller's request like every other command, so it is bounded by the same execution budget, and a filter wide enough to exceed it is refused rather than run — a caller who wants more narrows their filter, which is a better answer than an author holding a request thread for a quarter of an hour. It stays a read because it replaces nothing the caller owns: the staging is inside the agent's own tree, written through the framework's staging area under the service user, and the handler still reaches the caller's repository through the read-only resolver and nothing else. Cleaning that up on every path, including the ones that failed, is most of the work.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DownloadContentPackageCommand` with the filter patterns, the package profile, and a `RootDisposition` per root rather than a boolean, since including and excluding a root are two choices a caller makes and neither is a default. A pattern that would widen beyond the declared roots is refused at construction.
3. Implement `DownloadContentPackageResult` as the artifact reference with its byte count and digest, the filter the package was actually built with, and the node count it covered.
4. Declare an evaluation budget in the registry row sized so a package that reaches it still answers inside the execution budget, count every node the filter selects against it before building anything, and refuse over it rather than starting a build that cannot finish. Declare exactly `pattern_rejected`, `filevault_profile_unsupported`, `filevault_filter_unrepresentable`, `root_not_found`, `root_access_denied`, `repository_read_failed`, `filevault_package_failed`, `staging_cleanup_failed`, `artifact_publication_failed`, `artifact_publication_outcome_unknown`, `evaluation_budget_exceeded`. Staging cleanup has its own category because a package that built successfully and left its staging behind is a repository that fills up quietly, and it is a different problem from a package that failed to build.
5. Implement `DownloadContentPackageHandler` reading through the caller's read-only resolver so the package can contain only what the caller could read, and writing every staged byte through the caller context's staging area — the framework's bounded handle onto the agent's own tree — since a handler has no way to obtain a session and this one is not the exception. Declare the staging byte budget in the registry row, and let the framework release the staging on every path including every failure and every interruption; `staging_cleanup_failed` reports the case where that release itself did not succeed.

**Tests:**

- A package built by a caller who cannot read part of the requested tree contains only the readable part, and says so in the filter it reports.
- Staging is removed on success, on every declared failure, and after an interruption, proved by asserting the agent's tree is byte-identical afterwards in all three cases.
- The handler is proved to obtain no session and to write nothing through the caller's resolver, and every staged byte is proved to have gone through the staging area, so the command's `Read` class means what the registry says it means.
- The staging byte budget is proved at exactly the registry row's value and one past it, refusing rather than filling the agent's tree.
- The evaluation budget is proved at exactly its value and one past it, with the over-budget case refused before any staging is written, and the budget is asserted to leave the whole command inside the execution budget on every deployment row.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`download_content_package` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `download_content_package` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DownloadContentPackageCommandTest && ./mvnw verify -pl aem -Dtest=DownloadContentPackageHandlerTest && ./mvnw verify -pl interop -Dtest=DownloadContentPackageScenario` proves a package containing only what the caller could read with the actual filter reported, a handler that obtains no session and writes nothing through the caller's resolver, both sides of the staging byte budget and of the evaluation budget with an over-budget filter refused before any build starts, and staging removed on success, on every failure, and after an interruption, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
