---
id: deterministic-subscription-time
title: "Make Subscription Expiry Tests Deterministic"
workstream: "0041"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/HighWaterServlet.java
  - core/src/test/java/rs/slingshot/agent/http/HighWaterServletTest.java
status: complete
merged_as: 2a716c5
---
# Make Subscription Expiry Tests Deterministic

Finding(s): R16 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Provide an explicit wall-clock source for subscription expiry while retaining the production default.
2. Set subscription timestamps and the servlet clock from the same controlled source; test just before, at and after expiry.
3. Run the affected tests at multiple simulated calendar dates and record the full gate's independent remaining failures.

- **Done when:** The live and expired subscription cases pass at every simulated date and distinguish the expiry boundary without depending on the execution day's clock.

Direct implementation and review evidence: [EXECUTION.md](../EXECUTION.md#4101--deterministic-subscription-time).
