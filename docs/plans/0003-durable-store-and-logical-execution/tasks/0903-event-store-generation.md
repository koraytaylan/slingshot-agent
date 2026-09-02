---
id: event-store-generation
title: "Event Store Generation"
workstream: "0009"
kind: task
depends_on:
  - conditional-write-primitives
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/GenerationStore.java
  - core/src/main/java/rs/slingshot/agent/store/GenerationRecord.java
  - core/src/test/java/rs/slingshot/agent/store/GenerationStoreTest.java
  - "core/src/test/resources/fixtures/generation/**"
status: done
merged_as: ""
---
# Event Store Generation

The generation says which incarnation of the store an identifier belongs to. A client holding rows from a generation this agent no longer serves has to be told, rather than silently answered from a store that was rebuilt underneath it.

**Steps:**

1. Author fixtures for a first generation, for a generation that would repeat, for one that would decrease, and for an absent record on a tree that otherwise exists.
2. Implement `GenerationStore` to establish the first generation as one, by claim, so two instances starting together establish it once.
3. Make the generation part of every state path, and refuse any store operation whose identity names a generation this store does not serve, distinctly from one naming a generation it retains.
4. Refuse a repeat and a decrease as two distinct failures, and record every generation ever served so a repeat is detectable rather than merely unlikely.
5. Make an absent generation record on an otherwise present tree a refusal that names it, rather than an implicit creation, because a store that was never prepared and one that lost its generation need different answers.

**Tests:**

- Two instances establishing the first generation concurrently produce exactly one record, proved through the claim primitive.
- A repeat and a decrease are two distinct refusals, each naming both values.
- An operation naming a currently served generation succeeds, one naming a retained generation is refused distinctly from one naming an unknown generation.
- An absent record on a present tree is refused rather than created, naming what is missing.
- The generation appears in every derived path, asserted over the path type rather than by inspection.

- **Done when:** `./mvnw verify -pl core -Dtest=GenerationStoreTest` proves single establishment under concurrency, distinct repeat and decrease refusals, three distinct generation-membership outcomes, refusal rather than implicit creation of an absent record, and a generation present in every derived path.
