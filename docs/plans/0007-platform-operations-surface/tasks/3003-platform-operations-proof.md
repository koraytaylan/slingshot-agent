---
id: platform-operations-proof
title: "Platform Operations Proof"
workstream: "0030"
kind: task
depends_on:
  - command-reference
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/PlatformSafetyScenario.java
  - interop/scenarios/platform-safety.toml
  - development/src/main/java/rs/slingshot/agent/development/PlatformCoverage.java
  - development/src/test/java/rs/slingshot/agent/development/PlatformCoverageTest.java
status: done
merged_as: ""
---
# Platform Operations Proof

Thirty commands reach past every guard the content plans relied on. This is the scenario that proves what they all have in common, and it is derived from the registry rather than from a list, so a command added later is covered without editing it.

It selects the way Plan 0006's proof selects — by what a row declares — and it declares in its own scenario file that it claims the platform-control category, which is what lets that plan's partition check see the registry as fully covered without knowing this scenario exists.

**Steps:**

1. Enumerate every command whose registry row declares `platform_control_outcome_unknown` from the registry directory rather than from a list written here, and declare that category as this scenario's claim in `interop/scenarios/platform-safety.toml`. Every platform read that declares none of the three outcome categories is covered by its own scenario and is not this suite's subject.
2. For each, drive every declared failure category reachable without a fault injector and assert the platform's own state is unchanged afterwards, read back from the platform rather than inferred.
3. For each control command, run it on a deployment row that does not provide its capability and assert it was refused before the platform was touched, using a recording platform interface that would show any call.
4. For each, assert the unknown outcome is reachable and distinct from every rejection, by injecting a fault between the call and its answer.
5. Drive the redaction audit over all thirty, including every refusal, with credential-shaped values planted in configurations, job properties, replication transport addresses, and workflow metadata.

**Tests:**

- Every row declaring `platform_control_outcome_unknown` is covered; a row with no coverage fails naming it, and the scenario's declared claim is asserted equal to the set it actually drives.
- No covered row commits through its handler's own session, since a platform control that wrote to the repository itself would be doing something nobody declared; what the platform's own interfaces write on their own account is theirs.
- Every reachable declared failure leaves the platform's own state unchanged, read back rather than inferred.
- Every control command is refused before any platform call on a row lacking its capability, proved by a recording interface with no calls.
- The unknown outcome is produced and distinguished from a rejection for every control command.
- No planted credential-shaped value appears in any response, header, or log line across all thirty commands and their refusals.

- **Done when:** `./mvnw verify -pl interop -Dtest=PlatformSafetyScenario && ./mvnw verify -pl development -Dtest=PlatformCoverageTest` proves registry-derived coverage of every platform-control row with a declared claim equal to what it drives and no commit through any of their own sessions, unchanged platform state after every reachable failure read back rather than inferred, pre-call refusal on every row lacking a capability with a recording interface showing no calls, a reachable unknown outcome distinct from rejection for every control command, and no planted credential-shaped value anywhere in any response or log.
