---
id: document-provenance
title: "Document Provenance"
workstream: "0006"
kind: task
depends_on:
  - operation-identity
gated: false
touches:
  - schemas/agent-protocol/common/provenance.json
  - core/src/main/java/rs/slingshot/agent/identity/DocumentProvenance.java
  - core/src/test/java/rs/slingshot/agent/identity/DocumentProvenanceTest.java
  - "core/src/test/resources/fixtures/document-provenance/**"
status: done
merged_as: ""
---
# Document Provenance

What every operation-bearing document says about which contracts it means. Nothing here proves the bytes are canonical; that was checked before this is consulted, and keeping the two separate is what stops a provenance block from looking like an authentication.

**Steps:**

1. Author fixtures for accepted provenance, for a format value other than the exact constant, for each digest member absent, and for a document whose provenance is present and whose command contract is not.
2. Implement `DocumentProvenance` holding the exact format constant, the transport contract digest, the canonical contract digest, and the command contract identity, all four required.
3. Refuse a format value that is anything but the exact constant, without a version-range comparison, because a range is a way to accept a document written under a contract nobody checked.
4. Compare the transport and canonical digests against this build's own, from `AgentContract` and the canonical authority, and make each mismatch a distinct refusal naming which contract disagreed and both values.
5. Document plainly, on the type, that provenance is a claim the document makes and not evidence about its bytes.

**Tests:**

- Accepted provenance constructs; a different format constant is refused, including one that differs only in its version suffix.
- Each absent member is refused distinctly, and a present provenance with an absent command contract is refused.
- A transport digest mismatch and a canonical digest mismatch are two distinct refusals, each naming which contract and both values.
- No range or prefix comparison exists on the format member, asserted over the type.
- The committed schema and the typed model are asserted equal in both directions.

- **Done when:** `./mvnw verify -pl core -Dtest=DocumentProvenanceTest` proves exact-constant format matching with no range comparison, four distinct absence refusals, two distinct digest-mismatch refusals naming both values, and two-way schema-to-model correspondence.
