---
id: terminal-commit
title: "Terminal Commit"
workstream: "0011"
kind: task
depends_on:
  - artifact-store
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/TerminalCommit.java
  - core/src/main/java/rs/slingshot/agent/execution/ExecutionOutcome.java
  - core/src/main/java/rs/slingshot/agent/store/SnapshotStore.java
  - core/src/test/java/rs/slingshot/agent/execution/TerminalCommitTest.java
  - "core/src/test/resources/fixtures/terminal-commit/**"
status: done
merged_as: ""
---
# Terminal Commit

An outcome, its result, the event that announces it, and the snapshot that records it land in one commit or none of them do. A state saying `succeeded` beside a result that was not written is a client fetching an answer that does not exist; a result written beside a state that still says `progress` is an answer nobody will ever fetch; and a terminal state with no terminal event in the ledger is a lookup that says finished to a subscriber who will wait forever.

That last one is why this task sits after the ledger and the snapshot rather than before them. The operation record's state and the snapshot's kind are two representations of one fact, and two commits are two moments in which they disagree. It sits after the artifact store for a smaller reason with the same shape: naming an artifact is not writing one, and a terminal commit that wrote artifact bytes itself would be a second artifact writer.

**Steps:**

1. Author fixtures for an inline terminal commit, an artifact-backed one, a commit interrupted between the artifact and the reference, one interrupted between the state and the result, one interrupted between the state and its event, and a second terminal commit after the first.
2. Implement `TerminalCommit` to write the state, the outcome, an inline result, the terminal event at its next sequence, and the materialised snapshot as one commit, so no observer can see any of them without the rest.
3. Where the result is an artifact, name one the artifact store has already committed with its byte count and digest, and refuse to name one that is not yet committed — this task writes no artifact byte of its own. An unreferenced artifact is garbage the sweep collects, while a reference to a missing artifact is a broken answer, so the refusal is what makes the ordering a property rather than a convention.
4. Advance by compare-and-set from a non-terminal state, so a worker that ran past its lease still cannot write an outcome over one another worker already wrote.
5. Make a repeated terminal commit with the identical outcome idempotent and one with a different outcome a refusal, naming both.

**Tests:**

- An inline commit is proved atomic by a reader that sees either the previous state with no result, no terminal event, and the prior snapshot, or the terminal state with all three, never a mixture, across an injected interruption.
- A terminal state is proved unreachable without its terminal event and its snapshot, structurally over the type's surface, so no path advances the state alone.
- Naming an artifact that is not yet committed is refused with nothing written, and an interruption between the artifact's own commit and this one leaves an unreferenced artifact and a non-terminal state, with the operation proved still executable.
- No artifact byte is written by this type, asserted over its surface, so the store remains the only artifact writer.
- A terminal commit from an already-terminal state with a different outcome is refused naming both; with the identical outcome it is accepted and changes nothing.
- A worker whose lease expired mid-execution cannot write a terminal outcome over another's, proved by a compare-and-set against the state it read.

- **Done when:** `./mvnw verify -pl core -Dtest=TerminalCommitTest` proves state, outcome, result, terminal event, and snapshot visible together or not at all under injected interruption, structural impossibility of a terminal state without its event and snapshot, a refused reference to an uncommitted artifact with no artifact byte written here, a recoverable unreferenced artifact after an interrupted artifact commit, idempotent repeat and refused divergent re-commit, and refusal of a stale worker's terminal write.
