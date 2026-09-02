---
id: result-and-failure-documents
title: "Result and Failure Documents"
workstream: "0007"
kind: task
depends_on:
  - capability-document
gated: false
touches:
  - schemas/agent-protocol/job/result.json
  - schemas/agent-protocol/job/failure.json
  - core/src/main/java/rs/slingshot/agent/wire/CommandResult.java
  - core/src/main/java/rs/slingshot/agent/wire/CommandFailure.java
  - core/src/main/java/rs/slingshot/agent/wire/ResultDelivery.java
  - core/src/test/java/rs/slingshot/agent/wire/CommandResultTest.java
  - "core/src/test/resources/fixtures/command-result/**"
status: done
merged_as: ""
---
# Result and Failure Documents

An answer larger than the envelope allows is not truncated. It is published as an artifact and the document says where to fetch it, because a truncated answer is not a smaller answer but an unparseable one.

**Steps:**

1. Author fixtures for an inline result at and one past the inline bound, for a result delivered as an artifact, for a failure carrying its category, and for a failure that also carries a result.
2. Implement `ResultDelivery` as a closed choice — inline bytes, or an artifact reference with its byte count and digest — with no third case and no state where both are present.
3. Implement `CommandResult` bounded by the inline limit the contract declares, and make crossing that bound a switch to artifact delivery rather than a refusal, since the client expects the larger answer to exist somewhere.
4. Implement `CommandFailure` carrying exactly a category and the fields that category declares, and refuse a failure that also carries a result, because a command either produced an answer or did not.
5. Make every failure state whether the command had an effect: a category whose effect is unknown says so as its own value, since "we do not know" is the case a caller most needs to distinguish and the one most often flattened.

**Tests:**

- An inline result at exactly the bound stays inline and one byte past becomes an artifact reference carrying a byte count and digest.
- A delivery carrying both inline bytes and an artifact reference is refused, and so is one carrying neither.
- A failure carrying a result is refused, and a result carrying a category is refused.
- Every category's effect disposition is one of the three declared values, and a category with none is refused.
- The two committed schemas and the typed models are asserted equal in both directions.

- **Done when:** `./mvnw verify -pl core -Dtest=CommandResultTest` proves the inline bound at and one past with a switch to artifact delivery, refusal of both-and-neither deliveries, mutual exclusion of result and failure, a declared effect disposition on every category, and two-way schema-to-model correspondence for both documents.
