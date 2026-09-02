---
id: repository-policy-toolkit
title: "Repository Policy Toolkit"
workstream: "0001"
kind: task
depends_on:
  - project-scaffold
gated: false
touches:
  - pom.xml
  - development/pom.xml
  - interop/pom.xml
  - development/src/main/java/rs/slingshot/agent/development/PolicyDocument.java
  - development/src/main/java/rs/slingshot/agent/development/PolicyFinding.java
  - development/src/main/java/rs/slingshot/agent/development/PolicyReport.java
  - development/src/main/java/rs/slingshot/agent/development/ReactorModel.java
  - development/src/main/java/rs/slingshot/agent/development/BuiltArtifact.java
  - development/src/main/java/rs/slingshot/agent/development/package-info.java
  - development/src/test/java/rs/slingshot/agent/development/PolicyToolkitTest.java
  - "development/src/test/resources/fixtures/policy-toolkit/**"
status: done
merged_as: ""
---
# Repository Policy Toolkit

Every check in this plan reads the same four things: a declarative policy document, the resolved build model, a built artifact, and a report somebody has to be able to read. Writing that four times, slightly differently each time, is how a repository ends up with checks that disagree about what a finding is.

Deciding the two tooling modules' whole dependency set here is the other half of the task. A later check that has to edit a manifest to reach a parser is a check whose footprint says one thing and whose change says another, and the sibling recorded four such corrections after the fact rather than before. The set includes test-scope edges to the product modules, because a policy check reads a built artifact and an interop scenario drives real types; what stays forbidden is the reverse edge, which is what the direction check is for.

**Steps:**

1. Author the toolkit fixtures first: a well-formed policy document, one with a duplicate key, one with an unknown key, one with a value outside its declared type, and a report with findings across several files.
2. Declare the complete dependency set for `development` and `interop` in one place — the declarative-document reader, the Java syntax parser, the build-model reader, the test framework, and test-scope edges to the product modules and the built artifacts these tools read — each at an exact version and each at test or build scope, managed from the aggregator.
3. Implement `PolicyDocument` as the one reader every policy and support file is loaded through, with a closed key set per document kind, so an unknown key is a failure rather than a value nobody reads.
4. Implement `PolicyFinding` and `PolicyReport` with a file, a line, a rule, and a symbol, ordered deterministically by that tuple, so two runs over the same tree produce identical bytes.
5. Implement `ReactorModel` over the resolved build model rather than the declared text, and `BuiltArtifact` over a produced jar or content package, so every later check reads what the build made rather than what it was asked to make.

**Tests:**

- The well-formed document parses to exactly its declared keys; a duplicate key, an unknown key, and an out-of-type value are each rejected distinctly and none yields a partly-populated document.
- A report over findings in several files is asserted byte-identical across two runs, and asserted ordered by file, then line, then rule.
- `ReactorModel` is proved to see a dependency that exists only through management, and `BuiltArtifact` to read an entry that exists only in the produced archive.
- The declared dependency set is asserted to be exactly what the two modules resolve, with nothing at compile scope and nothing reaching a product module.
- A later check cannot acquire a dependency without editing this module's manifest and the dependency policy, asserted by a model comparison against the declared set, so a new tooling dependency is a visible change in a footprint rather than a silent one.

- **Done when:** `./mvnw verify -pl development -Dtest=PolicyToolkitTest` proves three distinct document failures with no partial result, byte-identical deterministically ordered reports, a build model that sees managed dependencies, an artifact reader that reads produced archives, and a tooling dependency set that is exactly the declared one at test or build scope only.
