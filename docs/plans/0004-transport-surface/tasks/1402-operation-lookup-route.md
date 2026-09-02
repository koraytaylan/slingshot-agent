---
id: operation-lookup-route
title: "Operation Lookup Route"
workstream: "0014"
kind: task
depends_on:
  - submission-route
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/OperationLookupServlet.java
  - core/src/test/java/rs/slingshot/agent/http/OperationLookupServletTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/OperationLookupScenario.java
  - interop/scenarios/operation-lookup.toml
status: done
merged_as: ""
---
# Operation Lookup Route

This is the route the client's whole ambiguity story depends on. Not knowing prompts a lookup; believing something incorrect does not. So the lookup has to answer from the store and has to distinguish "no such operation" from "not yet" — because the client waits on one and gives up on the other.

**Steps:**

1. Author fixtures for a known operation at each state, an unknown one, one inside the missing-operation grace window, one from a retained generation, and one belonging to another caller.
2. Implement `OperationLookupServlet` to answer with the snapshot Plan 0003 materialised, plus the terminal result reference where there is one, and nothing the store does not hold.
3. Distinguish an operation this store never had from one that may not have been written yet: inside the contract's missing-operation grace window from a submission this caller made, answer with the not-yet outcome rather than with absence.
4. Serve a retained prior generation as a read and refuse a write to it, so a client reconciling old work can finish reconciling.
5. Refuse another caller's operation with the same answer as an unknown one, because distinguishing them tells a caller which identifiers exist.

**Tests:**

- Each state answers with the snapshot and, where terminal, the result reference; a fixture answering with a state the store does not hold is rejected.
- An unknown operation and one inside the grace window are two distinct outcomes, and the grace bound is proved at exactly the contract value and one past it.
- A retained-generation lookup succeeds and a write to it is refused, distinctly.
- Another caller's operation produces a response byte-identical to an unknown one.
- On a running instance, a lookup during execution and a lookup after it agree with the events the stream delivered.

- **Done when:** `./mvnw verify -pl core -Dtest=OperationLookupServletTest && ./mvnw verify -pl interop -Dtest=OperationLookupScenario` proves store-only answers at every state, distinct unknown and not-yet outcomes at both sides of the grace bound, readable retained generations with writes refused, byte-identical responses for foreign and unknown operations, and agreement with the event stream on a running instance.
