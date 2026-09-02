---
id: restart-recovery
title: "Restart and Scheduled Recovery"
workstream: "0012"
kind: task
depends_on:
  - maintenance-sweep
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/RestartRecovery.java
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/main/java/rs/slingshot/agent/execution/RecoveryDisposition.java
  - core/src/test/java/rs/slingshot/agent/execution/RestartRecoveryTest.java
  - "core/src/test/resources/fixtures/restart-recovery/**"
status: done
merged_as: ""
---
# Restart and Scheduled Recovery

On startup the store is the only witness. The job system may believe anything; what happened is what is written down, and nothing at startup may invent a durable thing the client will never ask about.

What recovery can do about an unfinished operation is limited by something it does not have: a session for the caller. An immediate command runs on its own request's session, so there is no identity here to run anything under, and recovery therefore classifies rather than executes. An operation accepted and never started is left exactly as it is — the client's own resend restarts it, on their own request — and one started and never finished is undetermined, because whether its single commit landed is a thing this side genuinely does not know.

That leaves one question a store could answer badly: whether a started operation is still running. It needs no lease to decide. An immediate command is bounded by an execution budget that is itself bounded below the request window, so a start instant older than that budget and its margin belongs to a process that is gone. And the reconciliation runs on the contract's declared interval as well as at startup, because an author instance that is never restarted is one where startup-only recovery never comes.

**Steps:**

1. Author fixtures for an accepted operation never started, a started one inside its execution budget, a started one past the budget and its margin, a deferred operation with a live lease, one with an expired lease and a non-terminal state, one whose declared intake never completed, a terminal state with a job still queued, exhausted attempts without a terminal state, and an operation from a retained prior generation.
2. Implement `RestartRecovery` to read every non-terminal operation in the served generation and assign each exactly one `RecoveryDisposition`, and register it to run both at startup and at the contract's reconciliation interval.
3. Leave a deferred operation with a live lease alone: another node may be executing it, and for that class the lease is the evidence that matters. No shipped command is deferred, so this disposition is proved against the interop tier's fake command rather than a real one.
4. Give an accepted operation that never started the restartable disposition and start nothing: there is no caller session here to start it under, and the client's resend under the same derived key is the path that does. Give a started operation whose start instant is older than the execution budget and its margin the undetermined disposition, and leave a younger one alone, because that one is somebody's request still running.
5. Give an operation whose declared intake never completed and whose retention has passed the abandoned disposition, releasing the capacity its manifest reserved, since a payload that never arrived is not work anybody is still waiting for.
6. Mark exhausted attempts without a terminal state as explicitly undetermined rather than retrying forever, because a caller told "we do not know" can act and a caller told nothing cannot.
7. Never re-identify, resubmit, or execute: recovery produces no new operation, no new identifier, no attempt, and runs no command under any identity — an exhausted or ambiguous operation is left undetermined rather than tried again.

**Tests:**

- Every fixture receives exactly one disposition, and the disposition set is asserted closed and complete over the state, start-age, lease, intake, and attempt-count matrix.
- A deferred operation with a live lease is asserted untouched, including its lease and outbox.
- An accepted operation that never started is left byte-identical by every pass and is proved startable afterwards by a resend; a started operation is undetermined past the execution budget and its margin and left alone inside it, proved at exactly that instant and one interval either side.
- A terminal operation is asserted finished whatever the job system reports, proved with a queued job present.
- An intake that never completed past its retention is abandoned with its reserved capacity released exactly, and one still inside its retention is left alone.
- Exhausted attempts without a terminal state produce the undetermined disposition and no further attempt.
- Recovery is proved to create no operation and no identifier, by comparing the store's identifier set before and after over every fixture.
- The reconciliation interval and the execution budget it compares against are proved read from the contract, with neither declared in this module.
- Recovery is proved to execute no command and to obtain no session at all, asserted over the type's surface.

- **Done when:** `./mvnw verify -pl core -Dtest=RestartRecoveryTest` proves exactly one disposition per operation across a complete state, start-age, lease, intake, and attempt matrix, an untouched live-lease deferred operation, a never-started operation left byte-identical and startable by a resend, a started operation undetermined past its execution budget and margin and untouched inside it, terminal precedence over a queued job, an abandoned expired intake with capacity released, an explicit undetermined disposition on exhaustion, an identifier set unchanged with no command executed and no session obtained, and both contract values read rather than declared.
