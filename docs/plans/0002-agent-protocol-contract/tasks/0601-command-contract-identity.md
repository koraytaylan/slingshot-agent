---
id: command-contract-identity
title: "Five-Field Command Contract Identity"
workstream: "0006"
kind: task
depends_on:
  - canonical-contract-authentication
gated: false
touches:
  - schemas/agent-protocol/identity/command-contract.json
  - core/src/main/java/rs/slingshot/agent/identity/CommandContractIdentity.java
  - core/src/main/java/rs/slingshot/agent/identity/IdentityRefusal.java
  - core/src/main/java/rs/slingshot/agent/identity/package-info.java
  - core/src/test/java/rs/slingshot/agent/identity/CommandContractIdentityTest.java
  - "core/src/test/resources/fixtures/command-contract-identity/**"
status: done
merged_as: ""
---
# Five-Field Command Contract Identity

A command is not identified by its name. Two builds can both call something `query_paths` and disagree about what its arguments are, what its result looks like, or how large either may be — so all five fields match or the submission is refused, and there is no sixth.

**Steps:**

1. Author fixtures for a complete identity, for each of the five fields absent, for each of the five differing, for a sixth member, and for a wire name at and one past its bound.
2. Implement `CommandContractIdentity` holding exactly the wire name, the semantic contract version, the limits digest, the argument schema digest, and the result schema digest, each constrained at construction.
3. Make comparison total: two identities are equal only when all five members are, and no comparison that ignores a member or falls back to the wire name exists on the type.
4. Refuse a document carrying a sixth member rather than ignoring it, because an ignored member is a caller believing something is being honoured.
5. Commit the schema for this document and derive nothing from it at run time; it exists so a second implementation reads the same shape.

**Tests:**

- A complete identity constructs; each of the five absent fields is refused distinctly and each of the five differing values makes two identities unequal.
- A sixth member is refused naming it, and an unknown member inside a nested value is refused too.
- The wire name is accepted at exactly its bound and refused one past it, and the same for the semantic version.
- Every digest member is refused for uppercase, short, long, and non-hexadecimal input.
- The type is proved to expose no partial comparison, asserted over its own surface.

- **Done when:** `./mvnw verify -pl core -Dtest=CommandContractIdentityTest` proves construction of a complete identity, five distinct absence refusals, five distinct inequalities, refusal of a sixth member, both sides of both length bounds, four digest-shape refusals per digest member, and no partial comparison on the type.
