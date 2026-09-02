---
id: generation-rotation
title: "Generation Rotation"
workstream: "0012"
kind: task
depends_on:
  - restart-recovery
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/GenerationRotation.java
  - core/src/main/java/rs/slingshot/agent/store/RetainedGeneration.java
  - core/src/test/java/rs/slingshot/agent/store/GenerationRotationTest.java
  - "core/src/test/resources/fixtures/generation-rotation/**"
status: done
merged_as: ""
---
# Generation Rotation

Rotation is explicit and never implicit. A store that rebuilt itself quietly is a store that answered a client's lookup about a durable thing with a confident nothing.

**Steps:**

1. Author fixtures for a rotation with no prior generations, with prior generations inside the bound, at exactly the bound, one past it, and while a prior generation is still inside its retention.
2. Implement `GenerationRotation` to write the next generation by compare-and-set from the one it read, so two nodes rotating at once produce one rotation.
3. Retain prior generations up to the contract's bound, each with its own retention, and refuse a rotation that would push a generation out before its retention has ended.
4. Serve reads from retained generations and refuse writes to them, as two distinct outcomes, since a client reconciling old work needs to read and must never extend it.
5. Make the whole rotation observable: the capability document's generation changes, and a client holding rows from a retired generation is refused with a message naming both.

**Tests:**

- Concurrent rotation produces exactly one new generation.
- Retention at exactly the prior-generation bound succeeds and one past it is refused naming the generation that would be lost.
- A rotation while a prior generation is inside its retention is refused naming the retention instant.
- A read from a retained generation succeeds and a write to one is refused, as two distinct outcomes.
- After rotation the capability document reports the new generation, and an operation naming a retired generation is refused naming both.

- **Done when:** `./mvnw verify -pl core -Dtest=GenerationRotationTest` proves a single rotation under concurrency, both sides of the prior-generation bound, a refused rotation inside a prior retention window, distinct read-permitted and write-refused outcomes on retained generations, and a capability document plus a refusal that both name the generation change.
