---
id: state-machine-properties
title: "State Machine Properties"
workstream: "0035"
kind: task
depends_on:
  - token-and-stream-fuzzing
gated: false
touches:
  - development/pom.xml
  - policy/dependencies.toml
  - development/src/main/java/rs/slingshot/agent/development/property/OperationStateProperty.java
  - development/src/main/java/rs/slingshot/agent/development/property/LeaseProperty.java
  - development/src/main/java/rs/slingshot/agent/development/property/LedgerProperty.java
  - development/src/test/java/rs/slingshot/agent/development/property/StateMachinePropertyTest.java
status: done
merged_as: ""
---
# State Machine Properties

Four sentences carry the entire one-effect argument. A generated counterexample to any of them is worth more than another example that passes, which is what these are for.

**Steps:**

1. Add the property-based generator to the tooling dependency set and to the dependency policy with its reason, since it is the one tooling dependency no earlier plan could have known to declare.
2. Author the four invariants as executable predicates before the generators, so a generator cannot be written to avoid them.
3. Implement `OperationStateProperty` over generated transition sequences, asserting no sequence reaches a terminal state twice with different outcomes and every transition is from a state the actor read.
4. Implement `LeaseProperty` over generated interleavings of two workers' take, renew, lose, and expire operations, asserting no interleaving leaves both holding it and no worker writes after losing it.
5. Implement `LedgerProperty` over generated append and materialise sequences, asserting the snapshot always equals the fold of the ledger, that no reachable state has an operation record terminal without its terminal event or an event terminal without its record, and that no admission leaves a counter disagreeing with the store's contents in total or for any one caller.
6. Shrink every counterexample to a minimal sequence and record it as a permanent regression case, so a defect once found is a test rather than a memory.

**Tests:**

- Each of the four invariants holds over a declared number of generated sequences with a recorded seed, reproducible from that seed.
- A deliberately broken transition table, lease comparison, and materialisation are each found, proving the properties can fail, and so is a terminal transition split from its event into a second commit.
- Every counterexample is shrunk to a minimal sequence and added as a permanent case.
- The generators produce interleavings including the two-worker races that matter, asserted by coverage over the generated shapes rather than assumed.
- Recorded regression cases run on every build without generation, so they cost nothing and never stop running.

- **Done when:** `./mvnw verify -pl development -Dtest=StateMachinePropertyTest` proves all four invariants over seeded reproducible generation, detection of four deliberately broken mechanisms including a terminal transition split from its event, shrunk counterexamples recorded as permanent cases, generated coverage of two-worker interleavings, and regression cases running without generation.
