---
id: agent-contract-limits
title: "One Source For Every Bound"
workstream: "0001"
kind: task
depends_on:
  - project-scaffold
gated: false
touches:
  - support/agent-contract.toml
  - support/agent-contract.sha256
  - core/pom.xml
  - core/src/main/java/rs/slingshot/agent/contract/AgentContract.java
  - core/src/main/java/rs/slingshot/agent/contract/ContractLimit.java
  - core/src/main/java/rs/slingshot/agent/contract/package-info.java
  - core/src/test/java/rs/slingshot/agent/contract/AgentContractTest.java
  - "core/src/test/resources/fixtures/agent-contract/**"
status: done
merged_as: ""
---
# One Source For Every Bound

The sibling repository learned this rule by breaking it: a limit written down twice is two things that can disagree quietly, for as long as nobody compares them. Its transport bounds arrive here as bytes, and this side's own bounds join them in the same file rather than beside it.

**Steps:**

1. Author fixtures for the exact expected limit set, for a document missing one shared bound, for a document whose shared bound differs from the sibling's, and for a value outside its declared type.
2. Write `support/agent-contract.toml` carrying every limit and formula from `policy/author-agent-transport-contract-1.json` byte-equivalently, plus this side's own bounds — every one of them, because a later plan that needs a bound this file does not carry has nowhere to put it and will write it in the module that reads it. Those are: the maximum event-stream session duration, the maximum concurrent event streams in total and the maximum one caller may hold, the maximum accepted request-body bytes, the command execution lease and its renewal interval, the maximum bounded document this agent will parse, the continuation key-ring rotation-lease duration, the clock-skew allowance every two-instant comparison is decided under, the maximum a submitted request-start instant may differ from this side's own clock, the retained prior-generation bound, the maximum a command may execute for and the margin recovery adds to it before calling a started operation undetermined, the maintenance sweep's work bound and the recovery reconciliation interval, the per-caller share of every accounted capacity quantity, and the deadline a platform call is abandoned at.
3. Commit `support/agent-contract.sha256` beside it, and embed both into `slingshot-agent-core` as resources at build time rather than copying their values into Java.
4. Implement `AgentContract` as the one typed accessor: it authenticates the embedded bytes against the embedded digest before exposing anything, and every bound is reached through a named method rather than a map lookup by string.
5. Make a missing bound, an unknown bound, a digest mismatch, and a value outside its type four distinct failures at load, none of which leaves a partly-populated contract reachable.

**Tests:**

- The shared limits and formulas are asserted equal, name by name and value by value, to the sibling's committed contract carried in as a fixture; a fixture differing in one value is rejected and the message names the bound.
- Loading rejects a digest mismatch before parsing a single bound, proved by a fixture whose bytes parse cleanly and whose digest does not match.
- A missing bound, an unknown bound, and an out-of-type value are each rejected distinctly, and none produces a usable contract.
- Every accessor returns the value the file declares, and the accessor set is asserted equal to the file's key set, so a bound with no accessor and an accessor with no bound both fail.
- The event-stream session bound is asserted strictly below the heartbeat timeout multiplied by the retry attempt count, so a session that ends on schedule is always resumable within the client's own policy.
- Every per-caller bound is asserted at or below its own total, and a fixture whose per-caller share exceeds the total is rejected naming both, since a per-caller bound above the total is a bound that never applies.
- The maximum command execution budget is asserted strictly below the smallest request window any row in `support/deployments.toml` declares, so a command that runs to its budget still answers inside the window every supported deployment gives it, and a fixture matrix whose smallest window drops below the budget fails naming both.

- **Done when:** `./mvnw verify -pl core -Dtest=AgentContractTest` proves byte-equivalence with the sibling's shared bounds, digest authentication before parsing, four distinct load failures, exact accessor-to-key correspondence, the resumability relation between the session bound and the client's retry policy, every per-caller bound at or below its own total, and an execution budget strictly below the smallest declared request window.
