---
id: podman-process-harness
title: "Podman Process Harness"
workstream: "0004"
kind: task
depends_on:
  - repository-policy-toolkit
gated: false
touches:
  - interop/src/main/java/rs/slingshot/agent/interop/harness/ContainerHarness.java
  - interop/src/main/java/rs/slingshot/agent/interop/harness/ContainerHandle.java
  - interop/src/main/java/rs/slingshot/agent/interop/harness/ClusterHarness.java
  - interop/src/main/java/rs/slingshot/agent/interop/harness/ProcessRun.java
  - interop/src/main/java/rs/slingshot/agent/interop/harness/package-info.java
  - support/interop-harness.toml
  - interop/src/test/java/rs/slingshot/agent/interop/harness/ContainerHarnessTest.java
  - "development/src/test/resources/fixtures/container-harness/**"
status: done
merged_as: ""
---
# Podman Process Harness

Every interop suite depends on this behaving, so it should be code somebody here can read rather than a dependency that reaches a daemon socket and brings an ambient requirement with it. That is the same decision the sibling made about its process harness, for the same two reasons.

It starts one instance or two against one shared repository, and the second arrangement is not a later refinement. An Adobe Experience Manager as a Cloud Service author is a cluster, and every property this product has about contention — a lease two workers race for, a count two nodes advance, a key ring two nodes rotate — is a property a single instance cannot exhibit. A harness that could only start one would let every one of those pass on the tier and fail on a customer's author, so both arrangements exist from the first commit and the suites that need the second say so.

**Steps:**

1. Author fixtures for the accepted harness values, for a container that never becomes ready, for one that exits during startup, and for a suite that leaves a container behind.
2. Write `support/interop-harness.toml` holding every value the harness uses — readiness deadline, poll interval, stop grace, log capture bound, the exact container engine executable name, and the shared document store image pinned by registry, repository, exact tag, and content digest — and read them through one accessor.
3. Implement `ContainerHarness` to start a container rootlessly with no ambient network access beyond the ports it declares, capture its output to a bounded file rather than to memory, and return a `ContainerHandle` that retains the exact process it started.
4. Make readiness a condition the caller states and the harness polls under one absolute deadline, and make a container that exits during startup a distinct failure from one that never becomes ready, because the causes are different.
5. Implement `ClusterHarness` over the same wrapper, starting two instances against one shared document store so both see one repository, exposing each node separately so a suite can act on one and observe the other, and refusing to start where the pinned document store image is absent rather than pulling it.
6. Clean up through the retained handle only: observe that it already exited, or stop it through that same handle and wait. Nothing looks a container name or identifier up and acts on it, so a replacement that reused one can never be reached. A two-node arrangement cleans up both nodes and the store through their own retained handles.

**Tests:**

- A container that becomes ready is returned with its handle, and its captured output is asserted bounded and written to a file rather than held.
- A container that never becomes ready and one that exits during startup produce two distinct failures, each naming what was observed.
- The readiness deadline is proved against the declared value on the monotonic clock, and nothing sleeps for a fixed span or asserts how long something took.
- Cleanup is proved to act through the retained handle, by a fixture in which a second container has taken the same name and is asserted untouched.
- A suite that leaves a container running fails, by an assertion over the engine's own list restricted to containers this harness labelled, and a two-node arrangement is asserted to leave neither node nor the shared store behind.
- Two nodes are proved to share one repository, by writing on one and reading it back on the other, and each node is proved separately addressable.

- **Done when:** `./mvnw verify -pl interop -Dtest=ContainerHarnessTest` proves rootless startup with declared ports only, bounded output capture to a file, two distinct startup failures, a deadline proved against the declared value, cleanup through the retained handle with a same-named container untouched, a leak check over labelled containers, and a two-node arrangement sharing one repository with each node separately addressable and neither left behind.
