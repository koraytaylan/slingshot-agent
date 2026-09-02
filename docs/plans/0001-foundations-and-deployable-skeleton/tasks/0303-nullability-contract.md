---
id: nullability-contract
title: "Nullability Contract"
workstream: "0003"
kind: task
depends_on:
  - source-policy-checker
gated: false
touches:
  - pom.xml
  - policy/nullability.toml
  - development/src/main/java/rs/slingshot/agent/development/NullabilityPolicy.java
  - development/src/test/java/rs/slingshot/agent/development/NullabilityPolicyTest.java
  - "development/src/test/resources/fixtures/nullability/**"
status: done
merged_as: ""
---
# Nullability Contract

No method in this repository accepts a null argument or returns a null value. That is not a convention to be careful about; it is a property a checker decides, and once it holds, an entire category of defect and an entire category of defensive code both stop existing.

Absence is modelled by a type. This repository already does that everywhere it matters — an admission is an outcome, a fence attempt is an outcome, a write is an outcome — and the rule here simply makes it universal: a closed result type, an empty collection, or a return-only `Optional`. Never a null, and never an `Optional` in a parameter position, which converts a caller's simple decision into a wrapper they have to construct.

**Steps:**

1. Author the fixture corpus before the checker: a method with an unannotated parameter, an unannotated return, a `@Nullable` parameter, a `@Nullable` return, an `Optional` parameter, an `Optional` field, a null literal returned, a null literal passed, and an accepted file exercising every permitted form.
2. Add the JetBrains annotations at provided scope, and assert they never become a runtime import of either bundle — the annotations are retained in the class file and not at runtime, so a bundle importing their package would be a bundle that fails to resolve on an instance that does not carry them.
3. Write `policy/nullability.toml` declaring the annotation package, the permitted forms, and the paths exempt from annotation because the language already decides them — primitives, which cannot be null, and private members, which the enclosing type's own contract covers.
4. Implement `NullabilityPolicy` over the parsed source: every non-private method's parameters and return carry the non-null annotation or are primitive; the nullable annotation appears nowhere on a parameter or a return; `Optional` appears only as a return type; and a null literal appears in no argument position and no return expression.
5. Require the contract annotation wherever a method's nullness or purity is expressible in it, so a caller's static analysis is told what the documentation says.

**Tests:**

- Each of the eight violating fixtures is rejected distinctly, naming the file, the line, and the member; the accepted fixture passes.
- Primitives and private members are exempt, and a fixture annotating a primitive is rejected as redundant rather than accepted.
- `Optional` is accepted as a return type and rejected as a parameter type and as a field, three distinct outcomes.
- Neither built bundle imports the annotation package, asserted from the manifest rather than the classpath.
- A null literal reaching an argument position through a local variable is caught, proving the check follows assignment rather than matching the token.

- **Done when:** `./mvnw verify -pl development -Dtest=NullabilityPolicyTest` proves eight distinct violation findings with an accepted file passing, exemption for primitives and private members with redundant annotation rejected, `Optional` permitted only as a return type, no annotation package imported by either bundle, and a null literal traced through assignment rather than matched as a token.
