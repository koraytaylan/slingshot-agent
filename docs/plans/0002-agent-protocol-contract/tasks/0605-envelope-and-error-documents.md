---
id: envelope-and-error-documents
title: "Envelope and Error Documents"
workstream: "0006"
kind: task
depends_on:
  - submitted-command-digest
gated: false
touches:
  - schemas/agent-protocol/common/envelope.json
  - schemas/agent-protocol/common/error.json
  - core/src/main/java/rs/slingshot/agent/wire/AgentEnvelope.java
  - core/src/main/java/rs/slingshot/agent/wire/AgentError.java
  - core/src/main/java/rs/slingshot/agent/wire/ErrorCode.java
  - core/src/main/java/rs/slingshot/agent/wire/package-info.java
  - core/src/test/java/rs/slingshot/agent/wire/AgentEnvelopeTest.java
  - "core/src/test/resources/fixtures/envelope-and-error/**"
status: done
merged_as: ""
---
# Envelope and Error Documents

One document: its provenance, the operation it is about, and its body. And one refusal shape with a stable code, because a caller that has to read prose to find out what went wrong is a caller that will match on prose.

**Steps:**

1. Author fixtures for an accepted envelope, for an envelope missing provenance, for one missing the operation, for one carrying an unknown member, and for error messages at and one past their bound.
2. Implement `AgentEnvelope` requiring both provenance and operation identity, with the body typed by the caller rather than free-form, and refuse an unknown member outright.
3. Implement `ErrorCode` as a closed set this build knows, with each code's meaning documented on it, and refuse an unknown code at construction rather than passing it through.
4. Implement `AgentError` with the code and a bounded message, and make the message a description of what to do rather than a place to put a stack trace: a rendered message that names an internal path, a class, or a repository address is refused by an assertion over the code set.
5. Prove no error carries a secret: every code's rendered message is checked against a corpus of secret-shaped values that must never appear.

**Tests:**

- An accepted envelope constructs; absent provenance, absent operation, and an unknown member are three distinct refusals.
- The error message is accepted at exactly its bound and refused one past it, and the code is refused at and past its own bound.
- An unknown error code is refused at construction, and the closed set is asserted equal to the committed schema's.
- No code's rendered message contains an internal path, a class name, or a repository address, asserted over the whole code set.
- A secret-shaped value passed as a message parameter does not appear in any rendered message, asserted over the whole code set.

- **Done when:** `./mvnw verify -pl core -Dtest=AgentEnvelopeTest` proves three distinct envelope refusals, both sides of the message and code bounds, a closed code set equal to the committed schema's with unknown codes refused, and no rendered message disclosing an internal path, class, repository address, or secret-shaped parameter.
