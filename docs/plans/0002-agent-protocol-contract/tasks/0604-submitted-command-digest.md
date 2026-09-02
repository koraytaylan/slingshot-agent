---
id: submitted-command-digest
title: "Submitted Command Digest"
workstream: "0006"
kind: task
depends_on:
  - document-provenance
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/identity/SubmittedCommandDigest.java
  - core/src/main/java/rs/slingshot/agent/identity/ArtifactManifestKind.java
  - core/src/main/java/rs/slingshot/agent/identity/SubmissionBinding.java
  - core/src/test/java/rs/slingshot/agent/identity/SubmittedCommandDigestTest.java
  - "core/src/test/resources/fixtures/submitted-command-digest/**"
status: done
merged_as: ""
---
# Submitted Command Digest

This is the idempotency key, and it is derived rather than allocated. A client that crashed between writing a request and recording its outcome arrives at the same value when it restarts, and this side recognises the resend as the same submission instead of as a second piece of work.

It is also derived *here*, independently, and compared. Reading the key a client sent and trusting it is letting a caller assert what its own request means.

**Steps:**

1. Author vectors before the derivation: one per input member changed in isolation, one where two members are swapped between fields, and one where a field's bytes contain the separator value.
2. Implement the derivation under the exact binding version the client uses, over the transport contract digest, the five identity fields, the canonical contract digest, the complete canonical argument bytes, and the artifact manifest, joined by a separator that cannot occur inside any field.
3. Implement `ArtifactManifestKind` as the closed set the client declares — none, one artifact, several — spelled inside the digest exactly as the client spells it.
4. Make every derivation input required: there is no default manifest, no absent argument body treated as empty, and no field permitted to be skipped.
5. Compare a client-supplied key against the derived one, and make a mismatch a refusal that names neither key in full, because a refusal that echoes a key is a refusal that helps somebody guess one.
6. Document on the type what the derivation does not cover — the target identity digest and the environment revision, which the client's binding version leaves out — and that they are compared where the durable record holds them rather than folded in here, so nobody reading this type concludes the digest alone decides that two submissions are the same work.

**Tests:**

- Changing any single input member changes the digest, proved one member at a time across every member.
- Swapping the values of two members produces a different digest from the unswapped pair, proving the separator is doing its job.
- A field whose bytes contain the separator value cannot collide with a different field arrangement, proved by a constructed pair.
- Each manifest kind produces a distinct digest for otherwise identical input, and an absent kind is refused rather than defaulted.
- A mismatching client key is refused, and the refusal is asserted to disclose neither key in full.
- Two submissions differing only in target identity digest or only in environment revision are asserted to produce the same digest, which is what makes comparing them separately necessary rather than optional, and the type's documentation is asserted to say so.

- **Done when:** `./mvnw verify -pl core -Dtest=SubmittedCommandDigestTest` proves per-member sensitivity across every member, separator-proof field ordering including a field containing the separator, three distinct manifest kinds with no default, an identical digest for submissions differing only in target or environment revision with the type documenting why, and a mismatch refusal that discloses neither key.
