---
id: mutation-vocabulary
title: "Mutation Vocabulary"
workstream: "0020"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/mutation/ReferencePolicy.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/PropertyValue.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/ComponentPlacement.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/CountingResolver.java
  - core/src/main/java/rs/slingshot/agent/command/property/PropertyScalar.java
  - core/src/main/java/rs/slingshot/agent/command/property/ScalarKind.java
  - core/src/main/java/rs/slingshot/agent/command/property/package-info.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/PropertyChange.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/MutationOutcome.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/DeletedResourceResult.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/package-info.java
  - core/src/main/java/rs/slingshot/agent/command/mutation/SingleCommit.java
  - core/src/test/java/rs/slingshot/agent/command/mutation/MutationVocabularyTest.java
  - "core/src/test/resources/fixtures/mutation-vocabulary/**"
status: done
merged_as: ""
---
# Mutation Vocabulary

Twenty commands share five decisions, and making them once is the difference between twenty commands that behave alike and twenty that mostly do. The one worth the most argument is the third answer: a commit whose outcome nobody knows is not a failure, and reporting it as one tells a caller something false about their own repository.

**Steps:**

1. Author fixtures for each vocabulary type at its boundaries, for a mutation making two commits, for a result carrying a failure, and for an unknown outcome carrying a claim of change.
2. Implement `ReferencePolicy` as a closed two-valued choice with no default constructor and no inferred value, so neither refusing nor ignoring an incoming reference is something a caller inherits.
3. Implement `PropertyChange` as two explicit lists — properties to set with their values, and properties to remove by name — with an absent property meaning neither, and refuse a name appearing in both lists.
4. Implement `DeletionBudget` and the shared `DeletedResourceResult`, and implement `MutationOutcome` as three mutually exclusive answers where the unknown one carries neither a claim of change nor a claim of no change.
5. Implement the two guards the other two share their shape with: `ExpectedSibling`, naming the node a reordered one should end up before with a distinguished last-position value rather than an absence, and `StateExpectation`, a required expectation of the value a caller was looking at with a mismatch reported as its own outcome carrying both values. Four guards are named in this plan's design and all four are types here, so the next plan's bundle transitions, workflow terminations, job cancellations, and queue flushes reuse this vocabulary rather than growing a second one each.
6. Implement `SingleCommit` as the wrapper every handler in this plan and the next runs inside, counting commits and failing on a second, so one-commit atomicity is enforced rather than reviewed. How many commits are expected is read from the registry row's own declared categories rather than assumed: a command declaring `mutation_outcome_unknown` changes the repository and must make exactly one, and one declaring `admission_outcome_unknown` or `platform_control_outcome_unknown` changes something that is not the repository and must make none. What the wrapper counts is commits the handler's own session made, so a platform interface that writes somewhere on its own account is the platform's business and not a commit this rule is about. Offering content to replication and disabling an account are not mutations that happen to commit nothing; they are a different kind of act, and a wrapper that demanded a commit from them would be demanding a write nobody asked for.

**Tests:**

- A reference policy cannot be constructed without a value, and no default exists, asserted over the type, and the same is asserted of every other guard.
- A state expectation whose value differs from what was read reports the mismatch with both values and no write, and one that matches proceeds; an expected sibling expressed by absence rather than by the distinguished last-position value is refused at construction.
- A property named in both the set and the remove list is refused; an absent property is proved to leave the stored value unchanged.
- The deletion budget is proved at exactly its limit and one past it, with the over-budget case leaving nothing removed.
- The three outcomes are proved mutually exclusive: no result carries a failure, no failure carries a result, and the unknown outcome carries neither claim.
- A handler making two commits is refused by the wrapper, whatever its declared categories.
- A handler whose row declares `mutation_outcome_unknown` and makes zero commits on a successful path is refused; one whose row declares `admission_outcome_unknown` or `platform_control_outcome_unknown` and makes zero commits is accepted, and the same handler making one is refused.
- The expectation is proved read from the row rather than from the handler's package or name, by a fixture row whose categories change and whose expectation changes with them.

- **Done when:** `./mvnw verify -pl core -Dtest=MutationVocabularyTest` proves a reference policy with no default, a property change where an absent name changes nothing and a doubly-named one is refused, both sides of the deletion budget with nothing removed past it, three mutually exclusive outcomes with an unknown that claims neither, four guards that cannot be constructed without a value including a state expectation reporting both values on a mismatch, and a commit counter whose expectation is read from the row — exactly one for a repository mutation, none for an admission or a platform control, and never two for either.
