---
id: dual-licence-and-source-headers
title: "Dual Licence and Source Headers"
workstream: "0002"
kind: task
depends_on:
  - java-bytecode-contract
gated: false
touches:
  - LICENSE
  - LICENSE-MIT
  - LICENSE-APACHE
  - NOTICE
  - pom.xml
  - policy/licence-headers.toml
  - development/src/main/java/rs/slingshot/agent/development/LicenceHeaders.java
  - development/src/test/java/rs/slingshot/agent/development/LicenceHeadersTest.java
  - "development/src/test/resources/fixtures/licence-headers/**"
status: done
merged_as: ""
---
# Dual Licence and Source Headers

The sibling is offered under `MIT OR Apache-2.0`, and the two halves of one product being offered under different terms would be a defect nobody discovers until somebody tries to use both.

**Steps:**

1. Author the header fixtures first: an accepted file of each repository-owned kind, a file with no header, a file with the wrong expression, and a generated file the rule does not apply to.
2. Reproduce the sibling's licence exactly: `LICENSE` carrying the combined statement, the choice, the copyright line, and both texts in full, with `LICENSE-MIT` and `LICENSE-APACHE` beside it as the two separate texts.
3. Declare `MIT OR Apache-2.0` once in the aggregator's licence block and inherit it everywhere; a module declaring its own is refused.
4. Write `policy/licence-headers.toml` naming which file kinds carry a header, the exact `SPDX-License-Identifier` expression, and the exact copyright line, and give generated and third-party paths an explicit exclusion each with a reason.
5. Implement the header check over parsed files rather than over the first bytes, so a header inside a string literal is not a header, and add the `NOTICE` file stating that the built artifacts embed nothing and therefore carry no third-party terms.

**Tests:**

- Every repository-owned source, manifest, script, and content file carries the exact expression and copyright line; a fixture missing one, carrying a different expression, or carrying a different year is rejected distinctly.
- The aggregator's licence block is asserted to be the single declaration, and a fixture module declaring its own is rejected.
- Both licence texts are asserted present and unmodified apart from the copyright line, byte-compared against fixtures.
- An excluded path is accepted only when the policy names it, and an exclusion with no reason is rejected.
- The `NOTICE` claim is checked rather than trusted: no built artifact contains a class or resource from another party.

- **Done when:** `./mvnw verify -pl development -Dtest=LicenceHeadersTest` proves the exact expression and copyright on every repository-owned file, single-point licence declaration, unmodified licence texts, reasoned exclusions only, and that the built artifacts contain nothing from another party.
