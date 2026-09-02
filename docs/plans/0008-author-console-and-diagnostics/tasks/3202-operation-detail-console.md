---
id: operation-detail-console
title: "Operation Detail Console"
workstream: "0032"
kind: task
depends_on:
  - operations-console
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/OperationDetailDataSource.java
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console/operation/**"
  - aem/src/test/java/rs/slingshot/agent/aem/console/OperationDetailDataSourceTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/OperationDetailScenario.java
  - interop/scenarios/operation-detail.toml
status: done
merged_as: ""
---
# Operation Detail Console

The reason the console exists. Somebody has an identifier and a question, and the answer is spread across four stores that only this repository knows how to read. Assembling them is the difference between a diagnosable system and one where the answer is "check the logs".

**Steps:**

1. Author fixtures for an operation at each state, one with several attempts, one with artifacts, one whose lease is held, one whose lease has expired, and one from a retained generation.
2. Implement `OperationDetailDataSource` assembling the snapshot, the event ledger in sequence order, the physical attempts with the node that recorded each, the lease with its holder and expiry, and the artifacts with their sizes and digests.
3. Show the lease as held, expired, or absent with the instant that decides it, because "which node is running this and until when" is the question a stuck operation raises.
4. Show the submitted digest and the command contract identity, so a reader can tell a resend from a different command under the same identifier without leaving the page.
5. Read every part under one consistent view of the store, so the assembled page cannot show an event the snapshot has not accounted for.

**Tests:**

- Every part is present for an operation at each state, and an absent part is rendered as explicitly absent rather than empty.
- The events shown equal the ledger in sequence order, compared against a direct read.
- The lease is shown as held, expired, or absent with the deciding instant, proved for each.
- The page cannot show an event beyond its snapshot, proved by reading during an active append.
- On a running instance, a failing operation's detail page shows the failure category and, where one exists, the artifact reference with a working download.

- **Done when:** `./mvnw verify -pl aem -Dtest=OperationDetailDataSourceTest && ./mvnw verify -pl interop -Dtest=OperationDetailScenario` proves all four stores assembled with explicitly absent parts, events equal to the ledger in order, a lease shown as held, expired, or absent with its instant, no event beyond the snapshot during an active append, and a failure category with a working artifact download on a running instance.
