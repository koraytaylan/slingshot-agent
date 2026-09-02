---
id: live-event-tail
title: "Live Event Tail"
workstream: "0032"
kind: task
depends_on:
  - operation-detail-console
gated: false
touches:
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/clientlibs/console/js/**"
  - aem/src/main/java/rs/slingshot/agent/aem/console/TailSubscription.java
  - aem/src/test/java/rs/slingshot/agent/aem/console/TailSubscriptionTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/LiveTailScenario.java
  - interop/scenarios/live-tail.toml
status: done
merged_as: ""
---
# Live Event Tail

The same event route the client uses, subscribed from the browser. Not a second stream implementation and not a polling loop — the route already resumes from a cursor and already ends its own sessions, and a console that used something else would be a second thing to keep correct.

**Steps:**

1. Author fixtures for a tail on a running operation, on a terminal one, on one whose subscription is refused, and across a session that ends at its declared bound.
2. Implement `TailSubscription` issuing a console viewer a subscription against the same event route, under the same concurrency bound the route already enforces, so console viewers and clients contend for one budget rather than two.
3. Write the client-side tail by hand: subscribe, append rows, resume from the last identifier when the session ends, and stop after the same bounded attempt count the client's own policy uses.
4. Stop the tail on a terminal event rather than reconnecting forever, and show that it stopped because the operation finished rather than because the stream failed.
5. Release the subscription when the page is left, so a viewer who navigates away does not hold a slot until it expires.

**Tests:**

- A tail on a running operation receives every event in order, proved by driving the route the way the tail does and comparing against the ledger.
- A session ending at its declared bound is resumed from the last identifier with no event lost or repeated.
- A terminal event stops the tail, and the stop is distinguishable from a stream failure in what the page shows.
- Console viewers count against the same stream concurrency bound as clients, proved by saturating with clients and asserting a console tail is refused.
- Leaving the page releases the slot, proved by asserting the count returns after navigation.

- **Done when:** `./mvnw verify -pl aem -Dtest=TailSubscriptionTest && ./mvnw verify -pl interop -Dtest=LiveTailScenario` proves ordered delivery matching the ledger, resumption across a session bound with nothing lost or repeated, a terminal stop distinguishable from a failure, one shared concurrency budget with clients, and a slot released on navigation.
