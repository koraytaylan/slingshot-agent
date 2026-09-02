---
id: source-policy-checker
title: "Source Policy Checker"
workstream: "0003"
kind: task
depends_on:
  - agent-contract-limits
  - static-analysis-gate
gated: false
touches:
  - policy/source-policy.toml
  - policy/abbreviated-identifiers.txt
  - development/src/main/java/rs/slingshot/agent/development/SourcePolicy.java
  - development/src/main/java/rs/slingshot/agent/development/SourceFinding.java
  - development/src/test/java/rs/slingshot/agent/development/SourcePolicyTest.java
  - "development/src/test/resources/fixtures/source-policy/**"
status: done
merged_as: ""
---
# Source Policy Checker

Some rules no off-the-shelf analyser has, because they are about this repository: that a bound declared in the contract file is not declared a second time in Java, that a name spells its words out, that a file has not grown past the point where anybody reads all of it. Those are decided by parsing, not by matching text, so a file naming a forbidden thing in a comment passes.

**Steps:**

1. Author the fixture corpus before the checker: an accepted file per rule, a violating file per rule, and for each rule one file that names the forbidden thing only inside a comment or a string literal.
2. Write `policy/source-policy.toml` holding the file-length ceiling of one thousand physical lines, the directory that owns contract values, and the paths excluded with a reason each; write `policy/abbreviated-identifiers.txt` as the closed list of shortened forms a declared name may not use. The complexity and nesting ceilings belong to the method-shape policy and are deliberately not declared here as well.
3. Implement the checker over a parsed syntax tree, classifying by node kind rather than by text, and report a file, a line, a rule, and a symbol, ordered deterministically so two runs produce identical output.
4. Implement the second-declaration rule: a numeric literal equal to a bound the contract file declares, or a constant named after one, anywhere outside the contract accessor, is a finding naming both the constant and the bound it duplicates.
5. Implement the remaining rules — every declared name spelled in full with no abbreviation and no single-character name anywhere, a named constant for every meaningful numeric value so no magic number reaches the source, and the file-length ceiling — and record what the checker deliberately does not decide as a closed review checklist rather than as a rule it pretends to have.

**Tests:**

- Each rule accepts its accepted fixture, rejects its violating fixture with the exact file, line, rule, and symbol, and accepts the comment-and-string fixture.
- The second-declaration rule catches both the literal and the named-constant form, and does not fire inside the contract accessor itself.
- The file-length ceiling is proved at exactly one thousand lines and at one thousand and one, and no complexity ceiling is declared in this policy.
- An abbreviated name and a single-character name are refused wherever declared, and a magic number is refused with its named-constant alternative reported.
- Output ordering is asserted identical across two runs over a corpus with findings in several files.
- The checklist entries are asserted to be exactly the questions the checker does not answer, and a fixture checker that restates one as a rule is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=SourcePolicyTest` proves every rule against its accepted, violating, and comment-only fixture, both sides of the one-thousand-line ceiling with no complexity ceiling declared here, abbreviations and single-character names refused wherever declared, a magic number refused with its named-constant alternative, the second-declaration rule in both forms, deterministic ordering, and a review checklist the checker does not pretend to decide.
