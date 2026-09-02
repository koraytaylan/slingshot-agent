---
id: coverage-floor
title: "Coverage Floor"
workstream: "0003"
kind: task
depends_on:
  - adobe-practice-policy
gated: false
touches:
  - pom.xml
  - policy/coverage.toml
  - development/src/main/java/rs/slingshot/agent/development/CoverageFloor.java
  - development/src/test/java/rs/slingshot/agent/development/CoverageFloorTest.java
  - "development/src/test/resources/fixtures/coverage-floor/**"
status: done
merged_as: ""
---
# Coverage Floor

A coverage report nobody fails on is a number that drifts downward one change at a time. This makes it a build failure, and makes the excluded classes a list somebody wrote reasons into.

**Steps:**

1. Author fixtures for a module at exactly the floor, one a single branch below it, and an exclusion with and without a reason.
2. Configure the coverage agent for `core`, `aem`, and `interop`, with line and branch minimums both enforced per module and per class.
3. Write `policy/coverage.toml` holding the two minimums once and the excluded classes, each with a reason; refuse an exclusion without one and refuse a package-level exclusion, so nothing is excluded in bulk.
4. Bind the check to the build so a shortfall fails it, and make the failure name the module, the class, the measure, the floor, and the actual value.
5. Exclude nothing by default: generated sources, if any exist, are excluded by an explicit row like everything else.

**Tests:**

- A module at exactly the floor passes and one a single branch below fails, with the message naming module, class, measure, floor, and actual.
- Both line and branch measures are enforced independently, proved by a fixture that meets one and not the other.
- An exclusion with no reason and a package-level exclusion are rejected distinctly.
- The two minimums are asserted to be declared once, and a module overriding either is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=CoverageFloorTest` proves both measures enforced per module and per class at exactly the floor and one step below, reasoned class-level exclusions only, and a single declaration of both minimums.
