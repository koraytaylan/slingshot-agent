---
id: java-bytecode-contract
title: "Java 21 Bytecode Contract"
workstream: "0001"
kind: task
depends_on:
  - repository-policy-toolkit
gated: false
touches:
  - pom.xml
  - development/src/main/java/rs/slingshot/agent/development/BytecodeContract.java
  - development/src/test/java/rs/slingshot/agent/development/BytecodeContractTest.java
  - "development/src/test/resources/fixtures/bytecode-contract/**"
status: done
merged_as: ""
---
# Java 21 Bytecode Contract

A compiler release level set in one place and a class file that actually carries that level are different claims. The second is the one an author instance enforces, and it is the one worth asserting.

**Steps:**

1. Author fixtures for the accepted class-file major version, for a class file one version above it, and for a module that declares its own release level.
2. Set `maven.compiler.release` to 21 once, in the aggregator, and set no `source` or `target` anywhere.
3. Implement the contract check to read the class-file major version out of every class in every built product artifact, rather than reading the property that was supposed to produce it.
4. Refuse a module that declares its own `maven.compiler.release`, `maven.compiler.source`, or `maven.compiler.target`, so a later module cannot quietly compile to something else.
5. Enable `-Xlint:all` with warnings as errors for every module, and refuse a module that disables either.

**Tests:**

- Every class in every built bundle and jar carries exactly the major version 21 corresponds to; a fixture class one version higher is rejected and one lower is rejected.
- A fixture aggregator whose module overrides the release level is rejected by name.
- A fixture module that turns warnings-as-errors off is rejected.
- The check reads built artifacts rather than the model, proved by a fixture whose model says 21 and whose class file does not.

- **Done when:** `./mvnw verify -pl development -Dtest=BytecodeContractTest` proves the exact class-file version of every built artifact, refuses a module-level release override, refuses disabled warnings-as-errors, and rejects a model that disagrees with the bytes it produced.
