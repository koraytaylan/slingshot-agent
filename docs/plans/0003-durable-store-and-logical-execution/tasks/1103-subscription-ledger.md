---
id: subscription-ledger
title: "Subscription Ledger"
workstream: "0011"
kind: task
depends_on:
  - snapshot-materialisation
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionLedger.java
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionRecord.java
  - core/src/main/java/rs/slingshot/agent/store/HighWaterMark.java
  - core/src/test/java/rs/slingshot/agent/store/SubscriptionLedgerTest.java
  - "core/src/test/resources/fixtures/subscription-ledger/**"
status: done
merged_as: ""
---
# Subscription Ledger

A cursor is a promise about what a subscriber has already been shown. Keeping it durable is what lets a reconnection resume rather than replay, and what lets this side say honestly that it retracts nothing.

**Steps:**

1. Author fixtures for a new subscription, a resumed one, a high-water mark that would go backwards, a subscription identifier at and one past its bound, and admission at and past the live-subscription bounds.
2. Implement `SubscriptionRecord` holding the identifier, the generation, the cursor, and when it was last advanced, at a path derived from the identifier.
3. Implement `HighWaterMark` advancing only by compare-and-set and never decreasing, with a decrease refused rather than clamped, because a clamped decrease is a subscriber silently being shown something twice.
4. Admit a new live subscription through the capacity ledger, against the per-generation row and byte bounds and against the subscribing caller's own share of each, with no counter of its own, and make refusal a distinct outcome naming which bound was reached and whether it was the total or the caller's share — one caller holding every durable subscription row is one caller deciding that nobody else may subscribe.
5. Refuse a subscription naming a generation this store does not serve, and expire records whose last advance is older than the contract's retention rather than keeping them forever.

**Tests:**

- A new subscription is created once under concurrency; a resume reads the same record.
- A backwards high-water mark is refused naming both values, and the stored value is asserted unchanged.
- The identifier is accepted at exactly its byte bound and refused one past it.
- Both live-subscription bounds are proved at exactly the limit and one past it, in total and per caller, with the refusal naming the bound and which of the two it was.
- A caller at their own share is refused while a second caller is admitted.
- Admission is proved to go through the capacity ledger, asserted over the type, with no counter incremented here.
- A subscription on a foreign generation is refused, and one older than the retention is expired rather than served.

- **Done when:** `./mvnw verify -pl core -Dtest=SubscriptionLedgerTest` proves single creation under concurrency with resumption, a refused non-clamping decrease, both sides of the identifier bound and of both live-subscription bounds in total and per caller with the bound named, a second caller admitted while the first is at their share, and refusal on a foreign generation with expiry past retention.
