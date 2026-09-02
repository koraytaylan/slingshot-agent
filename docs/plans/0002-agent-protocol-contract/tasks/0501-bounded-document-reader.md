---
id: bounded-document-reader
title: "Bounded Document Reader"
workstream: "0005"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/json/BoundedDocumentReader.java
  - core/src/main/java/rs/slingshot/agent/json/DocumentRefusal.java
  - core/src/main/java/rs/slingshot/agent/json/DocumentValue.java
  - core/src/main/java/rs/slingshot/agent/json/package-info.java
  - core/src/test/java/rs/slingshot/agent/json/BoundedDocumentReaderTest.java
  - "core/src/test/resources/fixtures/bounded-document/**"
status: done
merged_as: ""
---
# Bounded Document Reader

A limit applied to a document that has already been collected is a limit on nothing: the memory was spent before the check ran. So the refusal happens the moment the next byte would cross a bound, not once the document is in hand.

**Steps:**

1. Author the fixture corpus before the reader: for each of the four bounds, one document exactly at the limit and one a single unit past it; plus a duplicate member, trailing bytes after a complete document, an unterminated document, and an input that is bounded only once fully read.
2. Implement `BoundedDocumentReader` over a stream, enforcing the total document bytes, the nesting depth, the member count of one object, and the length of one member name or string value, each read from `AgentContract` and none written down here.
3. Refuse a duplicate member outright rather than letting a last writer win, because two implementations disagreeing about which value won will disagree about every digest taken over the result.
4. Refuse trailing bytes after a complete document, and refuse an input whose declared length and actual length differ, so a bound is never applied to a length nobody knows.
5. Make a refusal expose nothing: `DocumentRefusal` names the bound and the position, and no partially built `DocumentValue` is reachable from any path a caller can take.

**Tests:**

- Each of the four bounds is accepted at exactly the limit and refused one unit past it, and the refusal names the bound and the position.
- A refusal is proved to happen before the whole input is consumed, by an input whose remaining bytes would themselves be a refusal of a different kind and whose reported refusal is the first one.
- A duplicate member, trailing bytes, an unterminated document, and a length mismatch are four distinct refusals.
- No partial value is reachable after any refusal, proved by exhausting the reader's public surface for each refusal fixture.
- No bound is written down in this package, proved by the source policy's second-declaration rule over the built module.

- **Done when:** `./mvnw verify -pl core -Dtest=BoundedDocumentReaderTest` proves both sides of all four bounds, refusal before full consumption, four distinct structural refusals, no reachable partial value, and no bound declared outside the contract accessor.
