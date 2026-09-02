---
id: allocation-and-stream-discipline
title: "Allocation and Stream Discipline"
workstream: "0003"
kind: task
depends_on:
  - documentation-completeness
gated: false
touches:
  - policy/allocation.toml
  - development/src/main/java/rs/slingshot/agent/development/AllocationPolicy.java
  - development/src/test/java/rs/slingshot/agent/development/AllocationPolicyTest.java
  - "development/src/test/resources/fixtures/allocation/**"
status: done
merged_as: ""
---
# Allocation and Stream Discipline

Garbage collection makes allocation cheap, not free, and this code runs inside somebody else's author instance sharing a heap with their content. Waste here is not a benchmark number; it is a customer's instance pausing.

Two rules that look opposed and are not. Streams and lambdas are the right way to express a transformation over a collection, and a hand-rolled indexed loop doing the same thing is worse in every respect. But a stream in a path that runs per byte or per event allocates a pipeline and a lambda capture each time round, and there a loop is correct. So the policy declares which paths are allocation-sensitive, and the rule inverts inside them.

**Steps:**

1. Author fixtures for an indexed loop that a stream expresses, a stream inside a declared allocation-sensitive path, string concatenation in a loop, boxing in a declared sensitive path, a defensive copy of an already-immutable input, and a returned copy where an unmodifiable view would do.
2. Write `policy/allocation.toml` naming the allocation-sensitive paths — the document reader, the canonical writer, the event encoder, the digest input — each with the reason it is sensitive, and the rules that apply inside and outside them.
3. Outside sensitive paths, refuse a manual indexed loop over a collection where a stream expresses the same transformation, and refuse string concatenation inside any loop.
4. Inside sensitive paths, refuse stream pipelines and boxing, and require a bounded reusable buffer rather than a fresh allocation per unit of input.
5. Refuse a defensive copy of an input the type system already proves immutable, and require an unmodifiable view rather than a copy where the source is owned and stable, annotated so a caller knows not to modify it.

**Tests:**

- Each of the six fixtures is rejected distinctly, naming the file, the line, and the rule.
- The stream rule inverts correctly: an indexed loop is rejected outside a sensitive path and required inside one, from one policy file.
- Every declared sensitive path exists and every sensitive path is declared, in both directions, so a new hot path cannot appear unlisted.
- A sensitive path is proved to allocate independently of input length, by driving it over inputs differing by three orders of magnitude and asserting the allocation shape rather than a timing.
- An unmodifiable view is returned rather than a copy wherever the source is stable, and a fixture returning a copy is rejected naming the alternative.

- **Done when:** `./mvnw verify -pl development -Dtest=AllocationPolicyTest` proves six distinct findings, a stream rule that inverts inside declared sensitive paths from one policy, two-way correspondence between declared and actual sensitive paths, input-length-independent allocation across three orders of magnitude, and unmodifiable views preferred to copies where the source is stable.
