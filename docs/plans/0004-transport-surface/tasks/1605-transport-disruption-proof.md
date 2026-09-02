---
id: transport-disruption-proof
title: "Transport Disruption Proof"
workstream: "0016"
kind: task
depends_on:
  - client-conformance-tier
gated: false
touches:
  - interop/src/main/java/rs/slingshot/agent/interop/harness/NetworkDisruptor.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/TransportDisruptionScenario.java
  - interop/scenarios/transport-disruption.toml
  - support/interop-harness.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Transport Disruption Proof

The interesting case is not success or refusal. It is the case where a request left the client and the answer did not come back, because that is the case where the command may or may not be running. This proves what the client is left knowing at each place that can happen.

**Steps:**

1. Enumerate the disruption points before the injector: before the first request byte, after the request head and before the body, after the body and before the response head, mid-response-head, mid-body, mid-stream, mid-artifact-transfer, and mid-intake.
2. Implement `NetworkDisruptor` to sever the connection at each named point at the container's network boundary, so nothing in either process gets an orderly close it can flush through.
3. For each point, assert what this agent's store holds afterwards and what the client concluded, and assert the two are consistent — in particular that a severance after the first request byte leaves the client not knowing rather than believing.
4. Assert convergence: after each disruption the client's own recovery path — a lookup, or a resend under the same derived key — reaches the same single operation and the same terminal answer.
5. Assert the stream, artifact, and intake cases separately, since a severed stream resumes from a cursor, a severed transfer restarts, and a severed intake leaves a slot claimable again for a whole re-upload rather than a resumed one — confusing any two of them would hide a defect in each.

**Tests:**

- Each enumerated point is exercised; the store's contents afterwards are asserted exactly and the client's conclusion is read from its own output.
- A severance after the first request byte leaves at most one operation and a client that looks up rather than resends blindly.
- Recovery after every point converges on one operation and one terminal answer, asserted from both sides independently.
- A severed stream resumes from its cursor with no event lost or repeated; a severed artifact transfer restarts and verifies; a severed intake leaves no partial slot reachable, reserves no second time on retry, and the operation is proved still not enqueued until the slot completes.
- No disruption leaves a lease held past its expiry, a subscription slot occupied, or capacity consumed beyond what an admitted manifest reserved, asserted after each point.

- **Done when:** `./mvnw verify -pl interop -Dtest=TransportDisruptionScenario` severs the connection at every enumerated point and proves an exact store state and a consistent client conclusion at each, convergence on one operation and one terminal answer from both sides, cursor-resumed streams, restarted verified transfers, a severed intake leaving no partial slot and no second reservation, and no leaked lease, subscription slot, or capacity.
