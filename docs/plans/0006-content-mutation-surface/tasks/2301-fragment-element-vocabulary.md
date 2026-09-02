---
id: fragment-element-vocabulary
title: "Fragment Element Vocabulary"
workstream: "0023"
kind: task
depends_on:
  - move-asset
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/FragmentElement.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/ElementType.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/VariationName.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/package-info.java
  - core/src/test/java/rs/slingshot/agent/command/fragment/FragmentElementTest.java
  - "core/src/test/resources/fixtures/fragment-element/**"
status: done
merged_as: ""
---
# Fragment Element Vocabulary

An element the model has never heard of, written as a loose property, produces a fragment that reads back differently through every tool that opens it. Refusing it by name is the only answer that leaves the repository describable.

**Steps:**

1. Author fixtures for each element type the platform supports, for a type it does not, for an element the model does not declare, for a value outside its element's declared constraints, and for variation names at and one past their bound.
2. Implement `ElementType` as the closed set of element types this build supports, refusing an unknown type at construction rather than at write time.
3. Implement `FragmentElement` binding a name to a typed value, with values rendered by the same canonical mapping the content loader uses, so a fragment read and a subtree read agree about what a value is.
4. Implement `VariationName` bounded and validated, with the master variation named explicitly rather than represented by absence, because absence is how a caller ends up editing a variation they did not mean to.
5. Refuse an element the model does not declare by name, and make that refusal distinct from a value the element's own constraints reject.

**Tests:**

- Every supported element type round-trips through the canonical mapping identically to the content loader, proved against that loader's own vectors.
- An unsupported element type is refused at construction, naming the type.
- An undeclared element and a constraint-rejected value are two distinct refusals, each naming the element.
- The master variation is represented by an explicit name, and a fixture representing it by absence is refused.
- Variation names are accepted at exactly their bound and refused one past it.

- **Done when:** `./mvnw verify -pl core -Dtest=FragmentElementTest` proves every supported element type agreeing with the content loader's own vectors, an unsupported type refused at construction, distinct undeclared-element and rejected-value refusals, an explicitly named master variation with absence refused, and both sides of the variation-name bound.
