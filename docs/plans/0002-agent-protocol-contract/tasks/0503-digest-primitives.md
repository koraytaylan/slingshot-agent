---
id: digest-primitives
title: "Digest Primitives"
workstream: "0005"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/digest/Digest.java
  - core/src/main/java/rs/slingshot/agent/digest/DigestValue.java
  - core/src/main/java/rs/slingshot/agent/digest/CommittedResource.java
  - core/src/main/java/rs/slingshot/agent/digest/package-info.java
  - core/src/test/java/rs/slingshot/agent/digest/DigestTest.java
  - "core/src/test/resources/fixtures/digest/**"
status: done
merged_as: ""
---
# Digest Primitives

Every authentication in this repository ends in a comparison of two digests, and a comparison that returns early on the first differing byte tells an attacker how many bytes they got right. Doing this once, correctly, is cheaper than auditing it at each of the fourteen places it happens.

**Steps:**

1. Author fixtures for the empty input, a known vector, a value differing in its first byte, a value differing in its last, and a rendering in the wrong case.
2. Implement `DigestValue` as a type that can only hold sixty-four lowercase hexadecimal characters, refusing an uppercase, short, long, or non-hexadecimal value at construction, so an invalid digest cannot exist to be compared.
3. Implement `Digest` over byte input and over a stream, so a large artifact is digested without being held.
4. Implement comparison that examines every byte regardless of where the first difference is, and make it the only comparison available — an equality that returns early must not be reachable for this type.
5. Implement `CommittedResource` to load a resource embedded in the bundle together with its committed digest, authenticate one against the other, and refuse to return bytes that did not authenticate.

**Tests:**

- Known vectors match, including the empty input, and the rendering is asserted lowercase.
- Construction refuses uppercase, short, long, and non-hexadecimal values distinctly.
- Comparison is proved to examine every byte, by an assertion over the implementation's structure rather than by timing it, and the type is proved to expose no early-returning equality.
- Streamed and whole-input digests of the same content are equal, proved on an input larger than any single buffer.
- `CommittedResource` returns bytes only after authentication, and a resource whose digest does not match is refused with nothing returned.

- **Done when:** `./mvnw verify -pl core -Dtest=DigestTest` proves known vectors including the empty input, four distinct construction refusals, a comparison that cannot return early and no alternative equality, streamed and whole-input agreement, and a committed resource that yields no bytes without authentication.
