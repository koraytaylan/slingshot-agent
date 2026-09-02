---
id: submission-route
title: "Submission Route"
workstream: "0014"
kind: task
depends_on:
  - request-body-bounds
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/http/SubmissionResponse.java
  - core/src/test/java/rs/slingshot/agent/http/SubmitServletTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/SubmissionScenario.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/TierRequests.java
  - core/pom.xml
  - policy/imported-packages.toml
  - interop/scenarios/submission.toml
status: done
merged_as: ""
---
# Submission Route

The route carries the whole admission decision Plan 0003 already makes and adds one thing to it: for an immediate command — which is every command this product ships — it runs the work before it answers. That is what makes the caller's session the request's own session, and it is the reason this agent needs no power over anybody's identity.

What it must still get right is the part that is on the wire: an acknowledgement naming a different operation, generation, target, or digest is never sent, because the client is built to treat exactly that as evidence its request may or may not have run. The acknowledgement's shape does not change when the work has already finished; the operation is simply terminal by the time the client looks it up or opens its stream, which is a case the client already handles because a resend after completion produces it.

**Steps:**

1. Author fixtures for a first submission, an identical resend, a conflicting one, one whose target digest differs from the record's, a submission whose supplied key differs from the derived digest, one declaring intake slots, and one naming a generation this store does not serve.
2. Implement `SubmitServlet` to read the bounded body, validate provenance and the five-field identity, derive the submission digest, and hand the whole thing to admission — making no decision of its own about whether the work may proceed.
3. Where admission accepts and the row is immediate, advance the operation from accepted to started by compare-and-set, execute it in this request on this request's own session, and make the terminal commit, all before answering. The compare-and-set is the whole of the mutual exclusion an immediate command needs: two requests racing to start one operation both compare the same state and exactly one proceeds, with no lease to take, renew, or lose.
4. Bound how many immediate executions this instance holds at once, in total and per caller, admitting through the accounting authority rather than by counting here — an executing command holds a request thread, and a bound on how much this agent stores is not a bound on how much of somebody's author it occupies. Refuse past it with a retryable category and a capped hint, the same shape the stream bound uses.
5. Make the acknowledgement echo exactly the operation identity, generation, target digest, and derived digest the record holds, so a client comparing them finds them equal or finds a real disagreement. Where the manifest declared intake slots, the acknowledgement says which slots the agent is waiting for, because a client that has been acknowledged and does not know it still owes bytes is a client waiting on work that will never start.
6. Answer a recognised resend with the same acknowledgement the first submission produced, byte-identically, since a client that crashed and resent is comparing bytes.
7. Refuse a conflicting submission with the category that says so, and prove the existing record is unchanged afterwards.

**Tests:**

- A first submission is acknowledged and creates one record; an identical resend produces a byte-identical acknowledgement and no second record.
- The acknowledgement's four identity values are asserted equal to the record's, and a fixture servlet echoing a supplied value rather than the record's is rejected.
- A conflicting submission is refused with its category and the record is asserted byte-identical afterwards, for a differing command and for a differing target alike.
- A submission declaring intake slots is acknowledged with exactly the slots still outstanding, and the acknowledgement is proved not to claim the work has started.
- An immediate command is proved to have reached a terminal state before its acknowledgement is written, and its handler is proved to have run on the request's own session, compared against a direct login as the caller.
- Two requests racing to start one accepted operation are proved to produce exactly one execution, by the state compare-and-set alone with no lease involved.
- The execution bound is proved at exactly its limit and one past it, in total and per caller, with the refusal retryable and its hint capped, and a second caller is admitted while the first is at their share.
- A submission whose supplied key differs from the derived digest is refused, and the refusal discloses neither key.
- On a running instance, a submission followed by an immediate resend yields one operation and two recorded attempts at most.

- **Done when:** `./mvnw verify -pl core -Dtest=SubmitServletTest && ./mvnw verify -pl interop -Dtest=SubmissionScenario` proves byte-identical acknowledgements across a submission and its resend with one record, an immediate command terminal before its acknowledgement and executed on the request's own session, exactly one execution under a two-request start race decided by the state compare-and-set alone, both sides of the total and per-caller execution bounds with a retryable capped refusal, acknowledgement values taken from the record rather than the request, a conflicting command and a conflicting target each refused with the record unchanged, an intake-declaring submission acknowledged with its outstanding slots named, a key mismatch refused without disclosure, and one operation under resend on a running instance.
