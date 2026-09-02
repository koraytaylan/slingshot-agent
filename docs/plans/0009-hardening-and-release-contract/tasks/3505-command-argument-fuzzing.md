---
id: command-argument-fuzzing
title: "Command Argument Fuzzing"
workstream: "0035"
kind: task
depends_on:
  - state-machine-properties
gated: false
touches:
  - development/src/main/java/rs/slingshot/agent/development/fuzz/CommandArgumentTarget.java
  - "fuzz/corpus/command-argument/**"
  - development/src/test/java/rs/slingshot/agent/development/fuzz/CommandArgumentFuzzTest.java
status: done
merged_as: ""
---
# Command Argument Fuzzing

Sixty-four argument shapes is sixty-four places a hostile or merely confused value arrives. Deriving the targets from the registry rather than listing them is what makes the sixty-fifth command covered on the day it lands.

**Steps:**

1. Derive one fuzz target per registry row from the registry directory, so a command added later is fuzzed without editing this task's code.
2. Seed each corpus from that command's committed accepted and refused vectors.
3. Assert one property for every command: an input either constructs a valid argument value or is refused with one of that command's declared categories, and no input constructs a value the command's own validation would reject.
4. Assert a second property for every command with a bound: no input constructs a value past that bound, however the input is shaped.
5. Assert a third for every address-bearing command: no input constructs an address outside the roots the command declares.

**Tests:**

- Every registry row has a target, derived from the directory; a row with none fails naming it.
- The construct-or-declared-refusal property holds for all sixty-four over a declared iteration count.
- No input constructs a value past any declared bound, across every bounded member of every command.
- No input constructs an address outside a command's declared roots, including through separator, parent-reference, and encoding tricks.
- A deliberately weakened validator on one command is found, proving the targets exercise validation rather than construction alone.

- **Done when:** `scripts/run_fuzz_target command-argument && ./mvnw verify -pl development -Dtest=CommandArgumentFuzzTest` proves a registry-derived target for every row, construct-or-declared-refusal across all sixty-four, no value past any declared bound, no address outside declared roots including through encoding tricks, and detection of a weakened validator.
