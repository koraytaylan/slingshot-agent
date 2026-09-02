---
id: committed-schemas-and-digests
title: "Committed Schemas and Digests"
workstream: "0008"
kind: task
depends_on:
  - result-and-failure-documents
  - continuation-key-authority-contract
gated: false
touches:
  - "schemas/agent-protocol/**"
  - schemas/agent-protocol-digests.toml
  - development/src/main/java/rs/slingshot/agent/development/SchemaCorrespondence.java
  - development/src/test/java/rs/slingshot/agent/development/SchemaCorrespondenceTest.java
  - "development/src/test/resources/fixtures/schema-correspondence/**"
status: done
merged_as: ""
---
# Committed Schemas and Digests

The schemas exist so a second implementation has something to read. They are not loaded at run time and nothing validates against them, because the typed model is the validator and a second validator with different bounds is precisely the failure this plan was built to avoid.

What keeps them honest is a check rather than a habit.

**Steps:**

1. Author fixtures for a schema with a member the model lacks, a model with a member the schema lacks, a schema whose bound differs from the model's, and a schema with no digest row.
2. Commit every document kind's schema under `schemas/agent-protocol/`, each with the identifier, the draft, and the closed-member declaration the client's own schemas use, so the two sets are directly comparable.
3. Record every schema's digest in `schemas/agent-protocol-digests.toml`, one row per schema, and make those the values a five-field identity's role-schema members are derived from.
4. Implement the correspondence check over the typed models by reflection and the schemas by parsing, comparing member names, requiredness, and every bound in both directions.
5. Assert that nothing in `core` loads a schema at run time, so the publication cannot quietly become a second validator.

**Tests:**

- Every committed schema and its typed model agree on member names, requiredness, and bounds; each of the four fixture disagreements fails naming the member.
- Every schema has exactly one digest row and every row names an existing schema.
- Every recorded digest is asserted equal to the digest of the committed bytes.
- No class in `core` reads a file under `schemas/` at run time, asserted over the built bundle.
- Where the client commits a schema for the same document kind, the two are asserted to declare the same members and bounds, from the client's committed bytes carried in as a fixture.

- **Done when:** `./mvnw verify -pl development -Dtest=SchemaCorrespondenceTest` proves two-way member, requiredness, and bound correspondence with four named disagreements, exact schema-to-digest-row correspondence with digests matching the bytes, no run-time schema loading in the bundle, and agreement with the client's own committed schemas.
