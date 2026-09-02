---
id: data-source-foundation
title: "Data Source Foundation"
workstream: "0031"
kind: task
depends_on:
  - console-authorization
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/ConsoleDataSource.java
  - support/agent-contract.toml
  - support/agent-contract.sha256
  - aem/src/main/java/rs/slingshot/agent/aem/console/ConsolePage.java
  - aem/src/main/java/rs/slingshot/agent/aem/console/package-info.java
  - aem/src/test/java/rs/slingshot/agent/aem/console/ConsoleDataSourceTest.java
  - "development/src/test/resources/fixtures/console-data-source/**"
status: done
merged_as: ""
---
# Data Source Foundation

The data sources read the agent's stores under the service user, because that is where the stores live and no person's session can reach them. That inversion is exactly why authorization is decided first and separately: the service user is doing the reading, so the decision about whether the reading should happen cannot also be the service user's.

**Steps:**

1. Author fixtures for a page of rows, an empty page, a page at the window bound, a request past it, and a data source reached without authorization.
2. Implement `ConsoleDataSource` as the base every console data source extends: authorize, then read under the service user, then render rows, in that order and with no path that reorders them.
3. Implement `ConsolePage` as the shared paging shape — offset, window bounded by the contract, and a total where the store can produce one cheaply and an explicit unknown where it cannot.
4. Apply the redaction corpus to every rendered value, reusing Plan 0004's audit rather than a second rule set.
5. Report an empty result as an empty page rather than as an error, and a store that cannot be read as an error rather than as an empty page.

**Tests:**

- The order authorize-then-read is structural: no call sequence reaches the store without the authorization result, asserted over the types.
- The window is proved at exactly the bound and clamped past it, and an empty result renders as an empty page.
- A store that cannot be read renders an error rather than an empty page, distinctly.
- An unknown total is rendered as an explicit unknown rather than as zero.
- The redaction audit is asserted to be the very audit Plan 0004 built, and it finds nothing across every data source.

- **Done when:** `./mvnw verify -pl aem -Dtest=ConsoleDataSourceTest` proves a structurally unskippable authorize-then-read order, both sides of the window bound with clamping, an empty page distinct from an unreadable store, an explicit unknown total, and the shared redaction audit clean across every data source.
