---
id: crash-consistency-proof
title: "Crash Consistency Proof"
workstream: "0012"
kind: task
depends_on:
  - generation-rotation
gated: false
touches:
  - interop/src/main/java/rs/slingshot/agent/interop/harness/CrashInjector.java
  - interop/src/test/java/rs/slingshot/agent/interop/CrashConsistencyScenario.java
  - interop/src/test/java/rs/slingshot/agent/interop/DuplicateDeliveryScenario.java
  - interop/scenarios/crash-consistency.toml
  - interop/scenarios/duplicate-delivery.toml
  - support/interop-harness.toml
status: done
merged_as: ""
---
# Crash Consistency Proof

Every claim in this plan is about what survives a process ending between two steps. A unit test can prove the shape of a commit; only killing the instance proves the shape was the one that mattered.

At most one effect is only half of what has to hold. An operation that was accepted and then produced no effect at all is a client waiting on an answer that will never come, which is a different failure from a double effect and a worse one to discover in production. So each crash point is held to both halves, with the second stated exactly as it is true: never two effects, and after the client's own recovery path — a lookup that finds a terminal answer, or a resend that starts an operation nothing had started — either one effect or an outcome that says plainly nobody knows.

**Steps:**

1. Enumerate the crash points before writing the injector: after admission and before the start transition, after the start transition and before the command's own commit, after the command's own commit and before the terminal commit, during intake and before the manifest is complete, after artifact bytes and before the reference, after the terminal commit and before the acknowledgement, and during a sweep.
2. Implement `CrashInjector` to stop the container at a named point without a graceful shutdown, so no orderly path can flush anything the store did not already hold.
3. Restart against the same durable state, let recovery run, replay the client's own recovery path, and assert the invariant that point is about: never two effects, one logical operation, no reference to a missing artifact, a snapshot equal to its ledger fold, no terminal record without its terminal event, and capacity equal to contents.
4. Run the duplicate-delivery scenario separately: deliver the same physical job several times, across a restart, and assert one effect and several recorded attempts.
5. Register both scenarios in the inventory against the features they cover, so the coverage gate sees them.

**Tests:**

- Each enumerated crash point is exercised and, after restart and recovery, its invariant holds; a fixture with the invariant deliberately broken is detected.
- No crash point produces two effects, counted by a marker the execution writes exactly once.
- A crash before the start transition leaves an operation a resend starts exactly once, producing one effect; a crash after it leaves an operation recovery calls undetermined, and the client is told so rather than told nothing.
- No restart leaves an operation in a state from which neither a lookup nor a resend produces an answer, asserted after recovery at every crash point.
- No restart produces a reference to an artifact that does not exist, no terminal record without its terminal event, and no unreferenced artifact survives the next sweep.
- Duplicate delivery across a restart produces one effect and several recorded attempts, with the attempt count asserted exactly.
- Capacity equals contents and every snapshot equals its ledger fold after every crash point.

- **Done when:** `./mvnw verify -pl interop -Dtest='CrashConsistencyScenario+DuplicateDeliveryScenario'` kills the container at every enumerated point without a graceful shutdown and proves after each restart and recovery never two effects and either one effect or an explicit undetermined outcome once the client's own recovery path has run, no operation from which neither a lookup nor a resend produces an answer, no dangling artifact reference, no terminal record without its event, snapshot-equals-fold, capacity-equals-contents, and one effect with an exact attempt count under duplicate delivery.
