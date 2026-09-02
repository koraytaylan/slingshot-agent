---
id: artifact-transfer-route
title: "Artifact Transfer Route"
workstream: "0016"
kind: task
depends_on:
  - stream-concurrency-bound
  - artifact-intake-route
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/ArtifactServlet.java
  - core/src/main/java/rs/slingshot/agent/http/TransferDeadlines.java
  - core/src/main/java/rs/slingshot/agent/http/ArtifactIntakeServlet.java
  - core/src/main/java/rs/slingshot/agent/http/AgentServlet.java
  - core/src/test/java/rs/slingshot/agent/http/ArtifactServletTest.java
  - "core/src/test/resources/fixtures/artifact-transfer/**"
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ArtifactTransferScenario.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ArtifactIntakeScenario.java
  - interop/scenarios/artifact-transfer.toml
  - interop/scenarios/artifact-intake.toml
  - policy/design-patterns.toml
  - policy/allocation.toml
  - README.md
status: done
merged_as: ""
---
# Artifact Transfer Route

A reader verifies rather than trusting what the store says about itself, so the byte count and the digest travel with the bytes. And a large download that is still moving is not a stalled one, which is why the deadlines here are a separate pair from a finite response's.

**Steps:**

1. Author fixtures for a small artifact, one larger than any single buffer, an unknown slot, an unreferenced artifact, an artifact whose stored digest does not match its bytes, and an artifact belonging to another caller's operation.
2. Implement `ArtifactServlet` addressed by operation and slot, never by a repository path, and prove no response discloses one.
3. Send the recorded byte count and digest as headers before the body, so a reader that gets a short body knows it and a reader that gets the whole body can verify it.
4. Implement `TransferDeadlines` as the contract's idle and total transfer bounds, separate from the finite-response pair, and end a transfer that stalls without ending one that is merely large.
5. Refuse an unknown slot, an unreferenced artifact, and an artifact failing its own digest check as three distinct refusals, and never send bytes for the third.

**Tests:**

- A small and a large artifact both transfer byte-identically, with the received bytes matching the sent digest and count.
- No response contains a repository path, asserted over a corpus including error responses.
- A stalled transfer ends at the idle bound and a large but moving one is not ended, both proved against the contract values on a monotonic clock.
- The three refusals are distinct, and the digest-mismatch case is proved to send no body byte.
- Another caller's artifact produces a response byte-identical to an unknown slot's, and a group member fetches both, so the route decides ownership exactly as the operation lookup does.
- On a running instance, an artifact produced by an execution is fetched and verified independently of the store's own claims.

- **Done when:** `./mvnw verify -pl core -Dtest=ArtifactServletTest && ./mvnw verify -pl interop -Dtest=ArtifactTransferScenario` proves byte-identical transfer with verifiable count and digest, no repository path in any response, idle and total deadlines proved on a monotonic clock without ending a moving transfer, three distinct refusals with no body on a digest mismatch, byte-identical foreign and unknown responses with a group member reading both, and independent verification on a running instance.
