---
id: capacity-and-retention-view
title: "Capacity and Retention View"
workstream: "0033"
kind: task
depends_on:
  - structured-logging
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/RetentionDataSource.java
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console/retention/**"
  - aem/src/test/java/rs/slingshot/agent/aem/console/RetentionDataSourceTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/RetentionViewScenario.java
  - interop/scenarios/retention-view.toml
status: done
merged_as: ""
---
# Capacity and Retention View

Capacity says how full the store is now. Retention says when things leave. An operator who can see one without the other cannot answer the only question that matters, which is whether it will still be full tomorrow.

**Steps:**

1. Author fixtures for a store whose oldest records are inside retention, past it, and a store whose retained bytes would not fall below a bound even after everything expired.
2. Implement `RetentionDataSource` showing, per retained kind, the declared minimum, the oldest record's retained-until instant, and how much would be released when it passes.
3. Show the case that matters most explicitly: a store where expiring everything eligible would still leave a counter above its bound is a store that needs a decision rather than patience, and the page says so.
4. Read every minimum and bound from the contract rather than from a value written here, so a change to the contract changes the page.
5. Break capacity down by kind rather than showing one total, because the fix for too many events and the fix for too many artifacts are different.

**Tests:**

- Per-kind minimums and bounds are read from the contract, and a value declared in this module is rejected by the source policy.
- The releasable amount is computed against the oldest record's retained-until instant and matches what a sweep actually releases, proved on a running instance.
- A store that would remain over a bound after full expiry is shown as needing a decision, and one that would not is not.
- Each retained kind is shown separately, with a total shown only as a sum of the parts.
- The redaction audit finds nothing on this page.

- **Done when:** `./mvnw verify -pl aem -Dtest=RetentionDataSourceTest && ./mvnw verify -pl interop -Dtest=RetentionViewScenario` proves contract-read minimums and bounds with none declared locally, a releasable amount matching what a sweep releases on a running instance, an explicit needs-a-decision state only where full expiry would not suffice, per-kind breakdown with the total a sum, and a clean redaction audit.
