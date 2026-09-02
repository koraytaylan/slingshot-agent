---
id: canonical-byte-writer
title: "Canonical Byte Writer"
workstream: "0005"
kind: task
depends_on:
  - bounded-document-reader
gated: false
touches:
  - schemas/command-canonical-json-1.json
  - schemas/command-canonical-json-1.sha256
  - schemas/command-canonical-json-vectors.json
  - core/src/main/java/rs/slingshot/agent/json/CanonicalByteWriter.java
  - core/src/main/java/rs/slingshot/agent/json/CanonicalRefusal.java
  - core/src/test/java/rs/slingshot/agent/json/CanonicalByteWriterTest.java
  - "core/src/test/resources/fixtures/canonical-bytes/**"
status: done
merged_as: ""
---
# Canonical Byte Writer

The client derives four of its five identity fields from digests over canonical bytes. Writing an implementation from the description of a canonicalisation is how two systems end up producing different bytes for the same value and discovering it as a refused submission rather than as a failing vector.

**Steps:**

1. Carry the client's committed `slingshot.command-canonical-json/1` contract and its vector file into this repository unchanged, with the contract's digest committed beside it.
2. Author the additional vectors this side needs and the client does not, each one a line carrying an input, the exact expected bytes, and a note saying what it proves.
3. Implement `CanonicalByteWriter` producing exactly that form: members ordered as the contract says, numbers in the single form it permits, strings escaped exactly as it requires, and no insignificant whitespace anywhere.
4. Refuse rather than approximate: a value the canonical form cannot represent — a number outside its permitted range, a string that is not well-formed, a member name that is not — is a `CanonicalRefusal` naming the value's position, not a silently coerced output.
5. Prove the round trip closes: bytes the reader accepts, converted to a value and written back, are byte-identical to the input when the input was already canonical, and are the vector's expected bytes when it was not.

**Tests:**

- Every vector in the shared file produces byte-identical expected output, and a fixture whose expected bytes differ by one character fails naming the vector.
- Member ordering, number form, and string escaping are each proved by a vector pair that differs only in that respect.
- Each unrepresentable value is refused distinctly with its position, and none produces partial output.
- Canonical input round-trips byte-identically, and non-canonical input converges on the same bytes as its already-canonical equivalent.
- The contract bytes are asserted equal to the digest committed beside them before any vector is run.

- **Done when:** `./mvnw verify -pl core -Dtest=CanonicalByteWriterTest` proves every shared and local vector byte-identically, ordering, number form, and escaping each isolated by a vector pair, distinct refusals with positions and no partial output, convergent round trips, and contract authentication before the first vector.
