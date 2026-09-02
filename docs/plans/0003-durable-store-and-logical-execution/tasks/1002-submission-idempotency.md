---
id: submission-idempotency
title: "Submission Idempotency"
workstream: "0010"
kind: task
depends_on:
  - logical-operation-record
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/SubmissionAdmission.java
  - core/src/main/java/rs/slingshot/agent/execution/AdmissionOutcome.java
  - core/src/test/java/rs/slingshot/agent/execution/SubmissionAdmissionTest.java
  - "core/src/test/resources/fixtures/submission-admission/**"
status: done
merged_as: ""
---
# Submission Idempotency

This is the property the client's whole recovery story rests on. A request that left the client and never produced an answer prompts a lookup rather than a resend, and a resend that does happen has to converge on the same durable thing rather than create a second one.

**Steps:**

1. Author fixtures for a first submission, a resend carrying the same derived digest, a different command under the same identifier, a submission whose target identity digest differs from the record's, one whose environment revision differs, a submission naming a generation this store does not serve, a resend arriving while the first is still executing, and a submission whose artifact manifest declares intake slots.
2. Implement `SubmissionAdmission` to derive the digest from the request itself and compare it against any existing record, never against the key the caller supplied, since a key nobody recomputed is a caller asserting what its own request means.
3. Compare the target identity digest and the environment revision against the record's alongside the digest, because the derivation does not cover them and an identifier reused against another target or another revision of the caller's environment is a different piece of work wearing the same name. A difference in either is a conflict reported as itself, naming which member disagreed.
4. Make the three outcomes explicit in `AdmissionOutcome`: accepted as new, recognised as the same submission, or refused as a conflicting one. A conflicting submission neither overwrites the record nor executes.
5. Answer a recognised resend from the record wherever the record has something to say — a terminal outcome, an execution already started, an intake still outstanding — without starting anything a second time.
6. Where the record is merely accepted and nothing has started it, let the resend start it. That is the liveness path an immediate command has instead of a redelivery: a process that stopped between admitting a submission and starting its work leaves a record nothing will move, and the client's own resend under the same derived key is what moves it — on that resend's own request, on that caller's own session. Starting is a compare-and-set from accepted, so a resend that races the original loses harmlessly rather than producing a second execution.
7. Accept a submission whose artifact manifest declares intake slots without starting it, reserving capacity for every declared slot's byte count at admission rather than at upload, so a submission whose payloads will not fit is refused before a caller sends the first byte. The record is the claim; the manifest is what the record is waiting for.
8. Refuse a submission naming a generation this store does not serve before any record is read, distinctly from one naming a retained generation.

**Tests:**

- A first submission creates one record; an identical resend creates none and returns the recognised outcome with the same record.
- A different command under the same identifier is refused as a conflict, and the existing record is asserted byte-identical afterwards.
- A differing target identity digest and a differing environment revision are each refused as conflicts naming the disagreeing member, one at a time, with the record unchanged.
- A resend during execution returns the recognised outcome and is proved not to start a second execution; a resend of a record that is merely accepted starts it exactly once, and two such resends racing produce exactly one execution.
- The comparison is proved to use the derived digest rather than the supplied key, by a submission whose supplied key is another submission's.
- A submission declaring intake slots is accepted and proved not started, with capacity reserved for every declared byte count, and one whose declared total exceeds remaining capacity is refused before any record is created.
- A foreign generation is refused before any record is read, distinctly from a retained one.

- **Done when:** `./mvnw verify -pl core -Dtest=SubmissionAdmissionTest` proves one record across a submission and its resend, a conflicting command, target digest, or environment revision each refused with the record unchanged and the member named, a mid-execution resend that starts nothing, a resend of a never-started record that starts it exactly once even against a racing resend, comparison against the derived digest rather than the supplied key, an intake-declaring submission accepted with capacity reserved and nothing started, and two distinct generation refusals taken before any record is read.
