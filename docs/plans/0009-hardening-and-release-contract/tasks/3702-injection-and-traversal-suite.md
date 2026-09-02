---
id: injection-and-traversal-suite
title: "Injection and Traversal Suite"
workstream: "0037"
kind: task
depends_on:
  - privilege-escalation-suite
gated: false
touches:
  - policy/injection-corpus.toml
  - interop/src/test/java/rs/slingshot/agent/interop/InjectionScenario.java
  - interop/scenarios/injection.toml
  - development/src/main/java/rs/slingshot/agent/development/InjectionAudit.java
  - development/src/test/java/rs/slingshot/agent/development/InjectionAuditTest.java
status: done
merged_as: ""
---
# Injection and Traversal Suite

Every value a caller supplies eventually becomes part of a query, an address, or a repository name. Those are three different grammars with three different escapes, and a value that is safe in one is frequently not safe in another.

**Steps:**

1. Write `policy/injection-corpus.toml` as the closed set of attack shapes: query grammar breaks, path separators and parent references, encoded and doubly-encoded separators, expression and template delimiters, characters the repository reserves in names, and characters that are legal in a name and illegal in a query.
2. Drive every corpus value through every caller-supplied member of all sixty-four commands, every route parameter, and every console parameter.
3. Assert three properties: no value changes which rows a query returns beyond the rows the same value would select literally; no value produces an address outside the roots the command declared; and no value reaches a repository name unescaped.
4. Implement `InjectionAudit` proving that every query in the build is constructed with bound parameters rather than by concatenation, over the parsed source rather than by matching text.
5. Assert the corpus is closed and covered: an attack shape with no driven member fails, and a member with no attack shape driven through it fails.

**Tests:**

- No corpus value changes a query's selection beyond its literal interpretation, proved against a fixture corpus whose literal matches are known.
- No corpus value produces an address outside a command's declared roots, including through encoded and doubly-encoded separators.
- No corpus value reaches a repository name unescaped, proved by reading the resulting names back.
- Every query in the build is constructed with bound parameters, and a fixture concatenation is detected by the parsed-source audit.
- Two-way corpus coverage: an undriven attack shape and an unattacked member are two distinct findings.

- **Done when:** `./mvnw verify -pl interop -Dtest=InjectionScenario && ./mvnw verify -pl development -Dtest=InjectionAuditTest` proves no corpus value alters query selection beyond its literal meaning, escapes a declared root through any encoding, or reaches a repository name unescaped, every query bound rather than concatenated with a fixture concatenation detected, and two-way corpus-to-member coverage.
