---
id: command-conformance-gate
title: "Command Conformance Gate"
workstream: "0017"
kind: task
depends_on:
  - result-bounds-and-overflow
gated: false
touches:
  - development/src/main/java/rs/slingshot/agent/development/CommandConformance.java
  - development/src/test/java/rs/slingshot/agent/development/CommandConformanceTest.java
  - "development/src/test/resources/fixtures/command-conformance/**"
  - scripts/quality
  - policy/quality-gate.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Command Conformance Gate

A command exists when six things are true. Checking them once, here, is what keeps the sixty-fourth command as complete as the first — including the interop scenario, which is the one somebody would otherwise leave for later.

**Steps:**

1. Author fixtures for a command missing each of the six facts in turn, and one that has all six.
2. Implement `CommandConformance` to check, for every registry row: a committed argument and result schema whose digests match the row; a typed argument and result agreeing with those schemas in both directions; a declared failure set equal to what its handler can produce in both directions; conformance vectors including one at and one past every bound it declares; and an interop scenario naming it on a tier that can run it.
3. Compare the whole registry against the client's own published command table, carried in as a fixture, so a command this side holds and the client does not — or the reverse — is visible rather than discovered by a refused submission.
4. Add the check to `scripts/quality` so a command missing any of the six fails the gate rather than shipping.
5. Report every failure together rather than the first, so somebody adding a command sees the whole list of what it still needs.

**Tests:**

- A complete command passes; each of the six missing facts fails distinctly naming the command and the fact.
- A command in this registry but not in the client's table, and the reverse, are two distinct findings.
- A bound with no vector at it or past it fails naming both the bound and the missing side.
- The gate reports all failures for a command at once, proved by a fixture missing three facts.
- The check is proved to read the registry directory rather than any list written here, by adding a fixture row and expecting the failure with no change to the check.

- **Done when:** `./mvnw verify -pl development -Dtest=CommandConformanceTest && scripts/quality` proves six distinct per-fact failures, two distinct registry-to-client-table divergences, a missing vector at either side of a bound, all failures reported together, and a check driven by the registry directory rather than a written list.
