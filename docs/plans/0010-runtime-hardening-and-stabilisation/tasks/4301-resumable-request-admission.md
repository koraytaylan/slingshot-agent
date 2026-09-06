---
id: resumable-request-admission
title: "Make Accepted Requests Progress After Saturation"
workstream: "0043"
kind: task
depends_on:
  - consistent-capacity-accounting
  - state-access-and-ownership
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/execution/SubmissionAdmission.java
  - core/src/main/java/rs/slingshot/agent/execution/LogicalOperation.java
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionLedger.java
  - core/src/test/java/rs/slingshot/agent/http/SubmitServletTest.java
  - core/src/test/java/rs/slingshot/agent/execution/SubmissionAdmissionTest.java
status: pending
merged_as: ""
---
# Make Accepted Requests Progress After Saturation

Finding(s): R04; R06 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce first=503/retry=202/executions=0 after freeing execution capacity.
2. Reserve required execution resources before accepting work or persist a waiting state with a safe resubmission path under a live authorized caller request.
3. Register the owned subscription with admission and test cancellation, response loss and identical competing resends without obtaining another caller's identity.

- **Done when:** A capacity-refused submission retried after capacity returns either completes once or returns a truthful explicit refusal; it cannot remain permanently ACCEPTED with no executable progress path.

Direct implementation is complete; saturation/resend reproduction, start contention and interruption,
response-loss and competing-effect proof, and full-gate verification are recorded in
[EXECUTION.md](../EXECUTION.md#4301--resumable-request-admission).
