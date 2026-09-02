# Plan 0002 — Agent Protocol Contract — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending. Every task is complete and verified; the final integration commit is not made.
- **Goal:** produce every document this agent reads or writes as a typed model with exact canonical bytes, derive the five-field command-contract identity and the submitted-command digest independently of anything a client asserts, and publish the schemas and vectors a second implementation is held to.
- **Root cause:** the client identifies a command by five fields rather than by its name, and every one of those fields is a digest over bytes. If this side canonicalises differently, authenticates in a different order, or believes a role schema before the contract it was written under, the two halves agree on a name and disagree on everything the name is supposed to guarantee — and the disagreement surfaces as a refused submission in production rather than as a failing vector at build time.
- **Approach:** enforce every bound incrementally as bytes arrive rather than after a document is collected, and refuse duplicate members and trailing bytes so that two implementations cannot disagree about which bytes a digest covered; reproduce the canonical-byte contract as committed bytes proved against the client's own vectors rather than as an implementation written from its description; authenticate in one fixed order — contract bytes, then schema annotation, then role digest, then identity — with a distinct failure at each step; treat the five-field identity as complete or refused with no sixth member and no partial match; derive the submitted-command digest here and compare rather than trusting the key a client sent; close the event, snapshot, and failure kind sets so an unknown one is a refusal rather than a guess; bind a continuation token to its own query and hold every deployment to the same linearizable key-ring contract with prior-key retention; and keep the committed schemas as publication rather than as a second validator, with a two-way check that they and the typed model still describe the same documents.
- **Progress:** 17/17 tasks done; 0 blocked; 0 dropped. All four workstreams are complete: bounded and canonical bytes, identity and provenance, the job and result documents, and the continuation tokens with their published schemas and vectors.
- **Integration:** `in progress`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `pending`; mode `sequential`; final integration `pending`.
- **Exceptions:** three, each recorded where it was made.
  - Six bounds this plan needed are declared in the `[agent]` section of `support/agent-contract.toml`
    rather than read from the client's contract: the three document bounds a reader needs (nesting
    depth, object members, and one name or string), the two identity lengths, the environment
    revision, and the two error lengths. The client never needed them — it writes the documents this
    agent reads, and a writer bounds what it produces by producing it. Each is the number the
    client's own committed schema states, and a suite compares the two so they cannot drift; nothing
    reads a schema at run time, because a bound read from a document is a bound whoever sent the
    document could choose.
  - This repository's copy of the error schema adds the closed code set to the client's own bytes.
    The client's schema bounds the code and does not enumerate it, so every code here satisfies it;
    what the addition buys is that an unknown code is refused before it reaches a caller rather than
    passed through. The schema correspondence check compares members with the client's copy and not
    enumerations, and records why.
  - Task 0803's correspondence check compares members and stated lengths rather than every keyword a
    schema can carry. Requiredness is implied here — every document this plan models is all of its
    members or none of them — and a check claiming to compare "every bound" would be claiming more
    than it does.
- **Outcome:** every document this agent reads or writes is a typed model with exact canonical
  bytes. A reader enforces four bounds as the bytes arrive and refuses a duplicate member, trailing
  bytes, and an unterminated input distinctly; a writer produces the client's own
  `slingshot.command-canonical-json/1` form, proved against the client's thirty-eight committed
  vectors and twenty-one of this side's own; a role schema is believed in four steps whose order is
  the compiler's rather than a convention; the five-field identity is complete or refused with no
  sixth member; the submitted-command digest is derived here under the client's own binding version
  and compared rather than trusted, with sixteen vectors computed from the written derivation by
  something that is not this code; the event, snapshot, failure, and error kind sets are closed; a
  continuation token is bound to its own query and to a key ring every deployment holds identically;
  and twelve committed schemas are compared with their typed models in both directions, with
  thirty-four conformance vectors that a document kind cannot exist without.

_Last updated: 2026-09-01, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
