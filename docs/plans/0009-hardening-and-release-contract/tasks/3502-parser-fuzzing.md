---
id: parser-fuzzing
title: "Parser and Canonical Byte Fuzzing"
workstream: "0035"
kind: task
depends_on:
  - fuzzing-harness
gated: false
touches:
  - development/src/main/java/rs/slingshot/agent/development/fuzz/DocumentReaderTarget.java
  - development/src/main/java/rs/slingshot/agent/development/fuzz/CanonicalWriterTarget.java
  - development/src/main/java/rs/slingshot/agent/development/fuzz/RequestBodyTarget.java
  - "fuzz/corpus/document-reader/**"
  - "fuzz/corpus/canonical-writer/**"
  - "fuzz/corpus/request-body/**"
  - development/src/test/java/rs/slingshot/agent/development/fuzz/ParserFuzzTest.java
status: done
merged_as: ""
---
# Parser and Canonical Byte Fuzzing

The reader is fuzzed for what it must never do. The writer is fuzzed from the other direction, because the round trip is what the five-field identity depends on — a value where writing twice produces different bytes is a submission that would be refused in production for no visible reason.

**Steps:**

1. Seed each corpus from the committed protocol vectors, so the fuzzer starts from inputs that are already close to valid rather than from noise.
2. Implement the document-reader target with one property: every input produces either a typed value or a refusal naming a bound, and never a partial value, an unbounded allocation, or an exception a caller would see as a server fault.
3. Implement the canonical-writer target with the round-trip property: every value the reader accepts is written to bytes the reader accepts back, and writing the same value twice produces identical bytes.
4. Implement the request-body target against the bounded reader as the servlet uses it, including the framing and content-coding refusals, since those are where a hostile sender aims, and including the intake route's declared-length-and-digest stream, which is the one body that is bytes rather than a document and the one whose bound comes from a manifest a caller wrote.
5. Add every input that has ever produced a finding to the corpus, permanently.

**Tests:**

- The reader target holds its property over the whole corpus and a declared iteration count, with allocation bounded relative to input length rather than unbounded.
- The writer target holds the round-trip and idempotence properties over the whole corpus.
- No input produces an exception that would reach a caller as a server fault, asserted over every target.
- No intake stream is accepted whose length or digest disagrees with the manifest that declared it, and no such input leaves a reachable partial slot.
- Every historical finding's input is in the corpus and still passes.
- A deliberately reintroduced defect is found by the corpus alone, without new iterations, proving the corpus is a regression suite rather than decoration.

- **Done when:** `scripts/run_fuzz_target document-reader && scripts/run_fuzz_target canonical-writer && scripts/run_fuzz_target request-body && ./mvnw verify -pl development -Dtest=ParserFuzzTest` proves the value-or-bounded-refusal property with input-relative allocation, writer round-trip and idempotence, no caller-visible server fault, every historical finding retained, and a reintroduced defect caught by the corpus alone.
