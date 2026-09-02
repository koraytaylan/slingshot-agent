---
id: capability-document
title: "Capability Document"
workstream: "0007"
kind: task
depends_on:
  - job-snapshot-document
gated: false
touches:
  - schemas/agent-protocol/discovery/capabilities.json
  - core/src/main/java/rs/slingshot/agent/discovery/AdvertisedCapabilities.java
  - core/src/main/java/rs/slingshot/agent/discovery/CapabilityDocument.java
  - core/src/main/java/rs/slingshot/agent/discovery/CapabilityServlet.java
  - core/src/test/java/rs/slingshot/agent/discovery/CapabilityServletTest.java
  - core/src/test/java/rs/slingshot/agent/discovery/CapabilityDocumentTest.java
  - "core/src/test/resources/fixtures/capability-document/**"
status: done
merged_as: ""
---
# Capability Document

Discovery exists so that a disagreement is found while it is still cheap. Everything the client compares before it submits is in this one document, and each thing that can differ has to be visible separately, because a transport disagreement, a command-contract disagreement, and a store that was rebuilt have three different fixes.

**Steps:**

1. Author fixtures for a document with no contracts, with one, with contracts out of wire order, with a duplicate wire name, and with the authority both ready and not.
2. Replace Plan 0001's skeleton `AdvertisedCapabilities` with the full document: the event-store generation, the canonical contract digest, the command contracts in wire order, whether the continuation-key authority is ready, and the transport contract digest.
3. Order the contract list by wire name and refuse a duplicate, so two builds comparing the same set compare it in the same order and a repeated name cannot shadow another.
4. Report the authority's readiness as an observed value rather than a constant, since an agent whose continuation-key authority is not ready cannot issue tokens that will still validate, and the client refuses it before a paged query rather than after.
5. Bound the whole document below the protocol document limit and refuse to emit one that would exceed it, naming the contract count that did, and update the servlet Plan 0001 landed so it emits this document rather than the skeleton.

**Tests:**

- An empty contract list renders as an empty array rather than an absent member, and a single-contract list renders in wire order.
- Contracts supplied out of order are emitted in wire order, and a duplicate wire name is refused naming it.
- Readiness is proved to be read rather than constant, by a fixture authority that reports each value in turn.
- The document is accepted at exactly the protocol document bound and refused one past it, with the refusal naming the contract count.
- The committed schema and the typed model are asserted equal in both directions, and the document is asserted to satisfy the shape the client's discovery expects, field by field.

- **Done when:** `./mvnw verify -pl core -Dtest=CapabilityDocumentTest` proves an empty list rendered as an array, wire ordering with duplicates refused, readiness read from an authority rather than fixed, both sides of the document bound, and a document matching both the committed schema and the client's expected shape field by field.
