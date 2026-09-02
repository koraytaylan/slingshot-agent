---
id: fuzzing-harness
title: "Fuzzing Harness"
workstream: "0035"
kind: task
depends_on: []
gated: false
touches:
  - support/fuzzing-tool.toml
  - scripts/run_fuzz_target
  - scripts/verify_fuzzing_tool
  - development/src/main/java/rs/slingshot/agent/development/FuzzTargetInventory.java
  - development/src/test/java/rs/slingshot/agent/development/FuzzTargetInventoryTest.java
  - "fuzz/corpus/**"
status: done
merged_as: ""
---
# Fuzzing Harness

A fuzzer that fetches is a gate whose result depends on a remote server, and a corpus that regenerates is a gate that finds different things on different days. Pinning both is what makes a passing run mean the same thing twice.

**Steps:**

1. Author fixtures for a tool whose digest does not match, an absent tool, a target with no corpus, and a corpus entry that is not reachable by its target.
2. Write `support/fuzzing-tool.toml` pinning the coverage-guided fuzzing tool by exact version and content digest, and `scripts/verify_fuzzing_tool` authenticating it offline before any target runs.
3. Write `scripts/run_fuzz_target` taking exactly one target name, running it with a deterministic seed and a declared iteration count, and fetching nothing.
4. Commit a corpus per target under `fuzz/corpus/`, including every input that has ever produced a finding, so a fixed defect stays fixed.
5. Implement `FuzzTargetInventory` comparing declared targets against the targets that exist, in both directions, so a target with no corpus and a corpus with no target both fail.

**Tests:**

- A tool digest mismatch and an absent tool are two distinct refusals, and neither runs a target.
- Two runs of one target with the same seed produce identical output, proved by comparing them.
- Every declared target exists and every target is declared, and a target with no corpus fails naming it.
- The harness fetches nothing, proved by a run with no reachable network.
- A corpus entry that its target cannot consume is rejected, so the corpus cannot rot silently.

- **Done when:** `scripts/verify_fuzzing_tool && ./mvnw verify -pl development -Dtest=FuzzTargetInventoryTest` proves distinct digest-mismatch and absent-tool refusals with no target run, identical output across two same-seed runs, two-way target-to-corpus correspondence, a run with no reachable network, and rejection of an unconsumable corpus entry.
