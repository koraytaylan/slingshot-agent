---
id: artifact-intake-route
title: "Artifact Intake Route"
workstream: "0014"
kind: task
depends_on:
  - failure-status-mapping
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/ArtifactIntakeServlet.java
  - core/src/main/java/rs/slingshot/agent/http/IntakeSlotWrite.java
  - core/src/main/java/rs/slingshot/agent/store/ArtifactStore.java
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/http/IntakeRefusal.java
  - core/src/test/java/rs/slingshot/agent/http/ArtifactIntakeServletTest.java
  - "core/src/test/resources/fixtures/artifact-intake/**"
  - interop/src/test/java/rs/slingshot/agent/interop/ArtifactIntakeScenario.java
  - interop/scenarios/artifact-intake.toml
status: done
merged_as: ""
---
# Artifact Intake Route

The submitted-command digest covers an artifact manifest, and the manifest is a thing the client declares before it sends anything — none, one artifact, or several. Without a route those bytes arrive on, the manifest can only ever say none, and the one command whose payload is the thing rather than a description of it cannot exist.

The order is what makes this safe. The submission is admitted first, so the record is the claim and the manifest is what the record is waiting for; capacity for every declared byte count was reserved at admission, so a caller whose payloads will not fit learns it before sending; and the operation becomes startable only when the last declared slot completes, so a command never starts against a payload that is still arriving. A resend stays idempotent throughout, because the digest covers the declared counts and digests rather than the bytes.

**Steps:**

1. Author fixtures for a slot the manifest declares, one it does not, a slot already complete, a body at and one past the declared byte count, a body whose digest does not match what the manifest declared, a caller who did not submit the operation, and an operation that is already terminal.
2. Implement `ArtifactIntakeServlet` addressed by operation and slot, never by a repository path, refused through the same five alternative path spellings and the same single method and media type every other route is held to.
3. Implement `IntakeSlotWrite` to stream the body into the slot claimed by creation, bounded as it arrives against the byte count the manifest declared — refusing the moment the next byte would cross it rather than once the body is in hand — and digesting as it goes.
4. Refuse a slot whose written digest or written length does not match what the manifest declared, leave nothing reachable, and leave the slot claimable again, because a partial payload with a correct-looking record is the failure worth spending the most effort on. Capacity was reserved at admission and is released only by the operation's own disposition, so a retried upload does not reserve twice.
5. Make each refusal its own `IntakeRefusal` — undeclared slot, already complete, length mismatch, digest mismatch, terminal operation — and require the caller to be the one who submitted the operation or a member of a permitted group, refusing an operation they do not own with the same answer an unknown one gets.

**Tests:**

- A declared slot accepts its payload and reads back byte-identically with the declared count and digest; a slot the manifest does not declare is refused naming neither the manifest nor any other slot.
- The declared byte count is proved at exactly the limit and one byte past, with the refusal proved to happen before the whole body is read.
- A digest mismatch leaves nothing reachable and the slot claimable again, proved by a successful retry of the same slot afterwards.
- Capacity is proved reserved at admission rather than here, by a retried upload that is asserted not to reserve a second time.
- An already-complete slot, a terminal operation, and a foreign caller are three distinct refusals, and the foreign caller's response is byte-identical to an unknown operation's.
- On a running instance, a submission declaring several slots is proved not started until the last one completes, and the command then runs against the exact bytes that were sent.

- **Done when:** `./mvnw verify -pl core -Dtest=ArtifactIntakeServletTest && ./mvnw verify -pl interop -Dtest=ArtifactIntakeScenario` proves byte-identical intake into a declared slot, both sides of the declared byte count with refusal before full consumption, a digest mismatch leaving nothing reachable and the slot retryable without a second reservation, distinct refusals for an undeclared slot, a complete slot, and a terminal operation, a foreign caller answered identically to an unknown operation, and a running-instance submission started only once its last declared slot completes.
