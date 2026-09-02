---
id: adobe-practice-policy
title: "Adobe Experience Manager Practice Policy"
workstream: "0003"
kind: task
depends_on:
  - allocation-and-stream-discipline
gated: false
touches:
  - policy/adobe-practice.toml
  - development/src/main/java/rs/slingshot/agent/development/AdobePracticePolicy.java
  - development/src/test/java/rs/slingshot/agent/development/AdobePracticePolicyTest.java
  - "development/src/test/resources/fixtures/adobe-practice/**"
status: done
merged_as: ""
---
# Adobe Experience Manager Practice Policy

Durability inside somebody else's author is mostly a matter of not doing the handful of things that work today and stop working at the next upgrade. Each of these is a real practice Adobe documents, each has a version of this code that would pass every other check in this repository and still be wrong, and each is structurally decidable.

**Steps:**

1. Author fixtures for a resolver obtained and not closed on every path, direct repository access where the resource abstraction would serve, a component holding mutable instance state, a manual service lookup, a deprecated platform interface, and an accepted file exercising the permitted form of each.
2. Write `policy/adobe-practice.toml` declaring each rule, the permitted exceptions with a reason, and the deprecated interfaces this build refuses with the replacement named for each.
3. Refuse a resolver or session that is not closed on every path, requiring the language's own resource management rather than a close in a trailing block, because the trailing block is the one an early return skips.
4. Prefer the resource abstraction over direct repository access, permitting the lower interface only where a row records why the higher one cannot express the operation — and require the same of a query, so an operation drops a level deliberately rather than by habit.
5. Refuse mutable instance state on a declarative-services component, refuse a manual service lookup or bundle-context reference where a declared reference would serve, refuse synchronization on a component instance, and refuse every interface on the deprecation list, naming its replacement.

**Tests:**

- A resolver not closed on every path is rejected, including one closed in a trailing block that an early return skips.
- Direct repository access with no recorded reason is rejected and with one is accepted, and a reason that names no operation is rejected.
- Mutable instance state on a component, a manual service lookup, and synchronization on a component are three distinct rejections.
- Every deprecated interface on the list is rejected naming its replacement, and the list is asserted to name only interfaces the platform actually declares deprecated for the supported deployment rows.
- Every rule is proved to parse structure rather than match text, by a fixture naming each forbidden construct inside a comment and a string literal.

- **Done when:** `./mvnw verify -pl development -Dtest=AdobePracticePolicyTest` proves an unclosed resolver caught including the early-return case, direct repository access permitted only with a recorded operation-naming reason, three distinct component-lifecycle rejections, every deprecated interface refused with its replacement named and the list validated against the supported rows, and every rule deciding on parsed structure rather than text.
