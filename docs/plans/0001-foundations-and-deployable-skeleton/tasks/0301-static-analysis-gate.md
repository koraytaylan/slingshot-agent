---
id: static-analysis-gate
title: "Static Analysis Gate"
workstream: "0003"
kind: task
depends_on:
  - java-bytecode-contract
  - publication-metadata-boundary
gated: false
touches:
  - pom.xml
  - policy/checkstyle.xml
  - policy/pmd-ruleset.xml
  - policy/spotbugs-exclude.xml
  - policy/static-analysis.toml
  - development/src/main/java/rs/slingshot/agent/development/StaticAnalysisConfiguration.java
  - development/src/test/java/rs/slingshot/agent/development/StaticAnalysisConfigurationTest.java
  - "development/src/test/resources/fixtures/static-analysis/**"
status: done
merged_as: ""
---
# Static Analysis Gate

The goal is a scan that finds nothing left to report, and the way to reach it is to fail the build on the same rules the scan would raise, before the scan ever runs. A finding that only a server can see is a finding nobody fixes on the day they wrote it.

**Steps:**

1. Author the configuration fixtures before enabling anything: the exact expected rule categories, the exact severity that fails a build, and a configuration with a disabled category.
2. Configure Checkstyle, the source-pattern rule set, and the bug-pattern analyser with its security plugin, each bound to the build and each failing on its first finding rather than producing a report.
3. Choose the enabled rules so that every category a Sonar Java profile raises — bug, vulnerability, security hotspot, and maintainability smell — has a build-time counterpart, and record that correspondence in `policy/static-analysis.toml` as one row per category naming which analyser covers it.
4. Enable the analyser rules that overlap this repository's own code doctrine — dereference of a possibly-absent value, a redundant condition, an unused declaration, a resource never closed, and equality on a type with no value equality — so the doctrine's own checkers are a second opinion on those rather than the only one.
5. Set the bug-pattern exclusion file to empty and refuse a non-empty one, so a finding is fixed rather than filtered.
6. Refuse `@SuppressWarnings` and every analyser-specific suppression annotation or comment anywhere in repository-owned Java, and prove the refusal against a fixture that uses each form.

**Tests:**

- The configured rule set is asserted equal to the declared categories, and a fixture disabling one is rejected naming it.
- Each analyser is asserted to fail the build at its first finding, proved by a fixture source triggering one finding per analyser.
- A non-empty exclusion file is rejected, and so is a suppression annotation, a suppression comment, and a rule-level severity lowered below failing.
- Every Sonar category row names a covering analyser, and a row with none is rejected.
- The three analysers run over every module including tests, proved by a finding planted in a test source.

- **Done when:** `./mvnw verify -pl development -Dtest=StaticAnalysisConfigurationTest` proves the exact enabled rule set, build failure at the first finding for each analyser over both main and test sources, an empty and unwidenable exclusion file, complete Sonar-category coverage, and refusal of every suppression form.
