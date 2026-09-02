---
id: stream-concurrency-bound
title: "Stream Concurrency Bound"
workstream: "0015"
kind: task
depends_on:
  - resumption-and-reset
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/stream/StreamAdmission.java
  - core/src/test/java/rs/slingshot/agent/stream/StreamAdmissionTest.java
status: done
merged_as: ""
---
# Stream Concurrency Bound

The store's subscription bounds are about durable rows. This bound is about the instance: how many streams this process will hold open at once, which is a different number and a much smaller one, because past it the author stops serving anything at all.

**Steps:**

1. Author fixtures for admission below the bound, at it, past it, and for a slot released by each of the four stream endings.
2. Implement `StreamAdmission` against the contract's maximum concurrent stream count, admitting by an atomic count rather than by checking and then incrementing.
3. Refuse admission past the bound with a retryable category and a capped hint, because the honest answer is that there is room for a bounded number of subscribers and this caller is past it.
4. Release the slot on every ending through the same single path the route already uses, so a leaked slot is impossible rather than unlikely.
5. Bound per caller as well as in total, so one caller cannot occupy every slot and lock every other out.

**Tests:**

- Admission succeeds at exactly the bound and is refused one past it, with the refusal retryable and its hint capped.
- Each of the four endings releases the slot, proved by admitting again immediately afterwards.
- Concurrent admission at the boundary admits exactly the bound and no more, proved on a running instance.
- The per-caller bound is proved at exactly its limit and one past it, and a second caller is admitted while the first is at their limit.
- After saturation and full release, the count returns to zero exactly.

- **Done when:** `./mvnw verify -pl core -Dtest=StreamAdmissionTest && ./mvnw verify -pl interop -Dtest=StreamSaturationScenario` proves both sides of the total and per-caller bounds, a retryable capped refusal past the bound, slot release on all four endings, exactly-bound admission under a running-instance race, and a count returning to zero after full release.
