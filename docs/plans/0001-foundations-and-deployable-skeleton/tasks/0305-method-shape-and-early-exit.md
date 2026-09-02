---
id: method-shape-and-early-exit
title: "Method Shape and Early Exit"
workstream: "0003"
kind: task
depends_on:
  - api-shape-and-naming
gated: false
touches:
  - policy/method-shape.toml
  - development/src/main/java/rs/slingshot/agent/development/MethodShapePolicy.java
  - development/src/test/java/rs/slingshot/agent/development/MethodShapePolicyTest.java
  - "development/src/test/resources/fixtures/method-shape/**"
status: done
merged_as: ""
---
# Method Shape and Early Exit

Complexity in a method is almost always nesting, and nesting is almost always a refusal that was written as an `else` instead of as a return. A method that answers every question it cannot proceed with, immediately, at the top, has one indentation level and reads as a list of conditions followed by the work. One that nests has the work at the bottom, four levels in, where nobody looks.

So the ceilings here are on nesting rather than only on a complexity number, because the number is a symptom and the nesting is the cause.

**Steps:**

1. Author fixtures at exactly each ceiling and one step past it, plus a method whose complexity is acceptable and whose nesting is not, a returning block followed by an `else`, a boolean parameter, and a method rewritten from nested to guarded that must pass in the second form and fail in the first.
2. Write `policy/method-shape.toml` holding every ceiling once: cyclomatic complexity, cognitive complexity, nesting depth, method length, and parameter count, each with a reason for the value chosen rather than a number somebody picked.
3. Implement the nesting rule as the primary one — a block nested past the declared depth is a finding whose message names the guard clause that would remove it, so the tool says what to do rather than only what is wrong.
4. Refuse an `else` branch attached to a block whose every path returns or throws, because that `else` is exactly the nesting the guard-clause idiom removes and it is mechanically detectable.
5. Refuse a boolean wherever it carries a choice somebody made, and permit it wherever it carries a fact the agent observed. A choice — a method parameter, a command argument, a configuration value — becomes a named two-valued type, because a call site reading `apply(true)` tells a reader nothing and `apply(ReferencePolicy.REFUSE)` tells them everything, and because neither answer was ever a safe default. A fact the agent reports about something it looked at stays a boolean, since there is no decision for a name to record. Where a named type already exists for a concept, a result reporting that concept uses the type rather than a second representation of it.

**Tests:**

- Every ceiling is proved at exactly its value and one step past it, and the value is proved read from the policy with none declared in the checker.
- A method within the complexity ceiling and past the nesting ceiling is rejected, proving nesting is enforced independently rather than inferred from complexity.
- An `else` after an exhaustively returning block is rejected, and the same logic written with a guard clause passes.
- A boolean is rejected in every choice position — method parameter, command argument, configuration value — and permitted in a reported fact, two outcomes from one rule.
- A result reporting a concept for which a named two-valued type exists is rejected for using a boolean instead, naming the type it should have used.
- Each finding names the guard clause that would remove the nesting, checked against a fixture expectation rather than by matching prose.

- **Done when:** `./mvnw verify -pl development -Dtest=MethodShapePolicyTest` proves both sides of all five ceilings read from the policy, nesting enforced independently of complexity, a returning-block `else` rejected while its guarded rewrite passes, booleans rejected in every choice position and permitted for reported facts with a named type required where one already exists for the concept, and findings that name the guard clause which would remove the nesting.
