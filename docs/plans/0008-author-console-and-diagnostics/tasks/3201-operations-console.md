---
id: operations-console
title: "Operations Console"
workstream: "0032"
kind: task
depends_on:
  - data-source-foundation
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/OperationListDataSource.java
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console/operations/**"
  - aem/src/test/java/rs/slingshot/agent/aem/console/OperationListDataSourceTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/OperationsConsoleScenario.java
  - interop/scenarios/operations-console.toml
status: done
merged_as: ""
---
# Operations Console

The list somebody opens first. Filtered by state and by command rather than by free text, because a free-text filter over a store with no index is exactly the traversal this repository does not do — and a console is not a good reason to make the first exception.

**Steps:**

1. Author fixtures for a page of operations at each state, an empty store, a store with more operations than one page, and a filter combination selecting none.
2. Implement `OperationListDataSource` reading the operation store by generation and state, with the ordering the store already provides rather than a sort this data source performs.
3. Offer filters over state and command only, and refuse — at the data source rather than in the page — any parameter that would require a scan.
4. Render each row as the operation identifier, the command wire name, the state, the caller who submitted it, the request-start instant, and the physical attempt count.
5. Show retained prior generations as a separate selectable generation rather than mixed into the current one, so a reader is never looking at two stores at once without knowing.

**Tests:**

- Rows appear in the store's own order across pages, proved against a direct unbounded read of the same store.
- A filter combination selecting nothing renders an empty page rather than an error.
- A parameter that would require a scan is refused at the data source, and the page offers no control that could produce one.
- The caller shown is the caller recorded at admission, and an operation with no recorded caller is shown as unattributed rather than blank.
- On a running instance, an operation submitted through the route appears in the console with matching state and attempt count.

- **Done when:** `./mvnw verify -pl aem -Dtest=OperationListDataSourceTest && ./mvnw verify -pl interop -Dtest=OperationsConsoleScenario` proves store-ordered paging against an unbounded read, an empty page for an empty selection, a scan-requiring parameter refused with no control offering one, an explicitly unattributed caller where none was recorded, and a route-submitted operation appearing with matching state and attempts.
