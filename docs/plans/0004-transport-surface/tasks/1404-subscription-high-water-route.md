---
id: subscription-high-water-route
title: "Subscription High-Water Route"
workstream: "0014"
kind: task
depends_on:
  - physical-job-lookup-route
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/HighWaterServlet.java
  - core/src/test/java/rs/slingshot/agent/http/HighWaterServletTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/HighWaterScenario.java
  - interop/scenarios/high-water.toml
status: done
merged_as: ""
---
# Subscription High-Water Route

How far a subscription has been served, asked for by identifier and generation. A client reconciling after a disconnection needs the number this side actually has, not the one it last saw, and the difference between those two is the whole reason the route exists.

**Steps:**

1. Author fixtures for a live subscription, an expired one, an unknown one, one from a foreign generation, and one belonging to another caller.
2. Implement `HighWaterServlet` to answer the subscription's durable cursor and the generation it belongs to, and nothing about any other subscription.
3. Answer an expired subscription distinctly from an unknown one, because a subscriber whose record has been swept needs to start again rather than to keep asking.
4. Refuse a foreign generation with the reset outcome and the current generation, so a client can rebuild rather than guess.
5. Refuse another caller's subscription with the same answer as an unknown one.

**Tests:**

- A live subscription answers its exact cursor and generation; an expired one and an unknown one are two distinct outcomes.
- A foreign generation produces the reset outcome naming the current generation.
- Another caller's subscription produces a response byte-identical to an unknown one.
- No response names another subscription, asserted over a store holding several.
- On a running instance, the answer after a stream disconnection equals the last event the subscriber actually received.

- **Done when:** `./mvnw verify -pl core -Dtest=HighWaterServletTest && ./mvnw verify -pl interop -Dtest=HighWaterScenario` proves an exact cursor for a live subscription, distinct expired and unknown outcomes, a reset naming the current generation on a foreign one, byte-identical foreign and unknown responses with no cross-subscription disclosure, and agreement with the last delivered event after a disconnection.
