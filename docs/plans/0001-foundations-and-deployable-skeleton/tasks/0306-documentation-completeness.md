---
id: documentation-completeness
title: "Documentation Completeness"
workstream: "0003"
kind: task
depends_on:
  - method-shape-and-early-exit
gated: false
touches:
  - policy/javadoc.toml
  - development/src/main/java/rs/slingshot/agent/development/JavadocPolicy.java
  - development/src/test/java/rs/slingshot/agent/development/JavadocPolicyTest.java
  - "development/src/test/resources/fixtures/javadoc/**"
status: done
merged_as: ""
---
# Documentation Completeness

Self-explanatory code and thorough documentation are not alternatives. The code says what happens; the documentation says what is guaranteed, what is refused, what the caller must hold, and why the thing exists at all — and none of those is inferable from an implementation, because an implementation is one way of satisfying a contract rather than the contract.

The rule is therefore about the falsifiable half. Whether prose is accurate or worth reading is a reader's judgement and is recorded as a review checklist; whether it exists, covers every parameter, names every failure, and does not merely restate the member's own name is decidable, and is decided.

**Steps:**

1. Author fixtures for a type with no documentation, a method with an undocumented parameter, a non-void method with no return description, a declared exception with no description, a summary that restates the member name, an empty or placeholder comment, a package with no package documentation, and an accepted file.
2. Write `policy/javadoc.toml` declaring which members require documentation, the required tags per member kind, and the paths exempt with a reason.
3. Implement the completeness rules: every type and every non-private member is documented; every parameter, type parameter, and declared exception has its own description; every non-void method describes what it returns; and every method that can refuse says what makes it refuse.
4. Refuse a summary that is the member's name with the spaces put back, since that is the most common way documentation exists without saying anything, and refuse an empty or placeholder body outright.
5. Require a `package-info.java` for every package stating what the package is responsible for and what it depends on, and permit inherited documentation only where the overriding member's contract is genuinely identical rather than merely similar.

**Tests:**

- Each of the eight fixtures is rejected distinctly, naming the member and the missing element; the accepted file passes.
- A summary that restates the member name is rejected across a corpus of naming shapes, and a summary that genuinely differs passes.
- Every package has package documentation, and a package with none is rejected naming it.
- Inherited documentation is accepted where the contract is unchanged and rejected where the overriding member declares a different exception or a narrowed return.
- Documentation generation over the whole reactor produces no warning, and a fixture module with one fails the build.

- **Done when:** `./mvnw verify -pl development -Dtest=JavadocPolicyTest` proves eight distinct completeness findings with an accepted file passing, rejection of name-restating summaries across a naming corpus, package documentation everywhere, inherited documentation permitted only on unchanged contracts, and warning-free generation over the whole reactor.
