---
id: route-table-and-capability-servlet
title: "Route Table and Capability Servlet"
workstream: "0004"
kind: task
depends_on:
  - agent-contract-limits
  - module-dependency-direction
gated: false
touches:
  - policy/agent-routes.toml
  - core/src/main/java/rs/slingshot/agent/route/AgentRoute.java
  - core/src/main/java/rs/slingshot/agent/route/AgentRouteTable.java
  - core/src/main/java/rs/slingshot/agent/route/package-info.java
  - core/src/main/java/rs/slingshot/agent/discovery/CapabilityServlet.java
  - core/src/main/java/rs/slingshot/agent/discovery/AdvertisedCapabilities.java
  - core/src/main/java/rs/slingshot/agent/discovery/package-info.java
  - core/src/test/java/rs/slingshot/agent/route/AgentRouteTableTest.java
  - core/src/test/java/rs/slingshot/agent/discovery/CapabilityServletTest.java
  - "core/src/test/resources/fixtures/agent-routes/**"
status: done
merged_as: ""
---
# Route Table and Capability Servlet

Discovery is the right first route because its answer is already fully specified by the client that will call it, and answering it honestly with an empty command list is a more useful skeleton than inventing a health check nobody consumes.

The route namespace is a decision, not an inheritance. Adobe reserves `/libs`, and a third-party servlet path there is a collision waiting for an upgrade, even though the registration creates no node. The sibling repository disagrees with itself about this — its production constants say `/libs/slingshot/agent/…`, its own simulator and daemon suites say `/bin/slingshot/agent/…`, and the two spell the lookup and artifact routes differently besides. This task pins one table here; Plan 0004 owns the aliases and the cross-repository correction.

**Steps:**

1. Author the route fixtures before the table: the accepted route set with each route's method, its media type, and whether it may be reached without a body; a route outside the agent prefix; and a duplicate route.
2. Write `policy/agent-routes.toml` naming every route this agent will ever serve, under the `/bin/slingshot/agent` prefix, with `capabilities` the only one this commit implements and the rest declared with an owning plan.
3. Implement `AgentRouteTable` as the one place a route path is produced, reading the committed table, so no servlet writes its own path and no second spelling can exist.
4. Implement `CapabilityServlet` bound by path from the table, answering a bounded document naming the transport contract digest from `AgentContract`, the canonical-byte contract digest, the event-store generation, whether the continuation-key authority is ready, and the command contracts held — an empty list in this commit.
5. Refuse anything but the declared method, refuse a request carrying a body, bound the response below the contract's document limit, and answer only a request whose Sling user is authenticated, leaving what that user must additionally be permitted to Plan 0004.

**Tests:**

- The table parses into the exact declared route set; a route outside the prefix, a duplicate, and a route with no owning plan are each rejected distinctly.
- Every route path in the built bundle is asserted to come from the table, by refusing any string literal matching the agent prefix outside `AgentRouteTable`.
- The capability document is asserted field by field against the shape the sibling's discovery expects, including an empty command-contract list rendered as an empty array rather than as an absent field.
- A wrong method, a request with a body, and an unauthenticated request are each refused with distinct statuses and no capability field disclosed.
- The response is asserted below the document bound read from `AgentContract`, and the bound is not written down in this module.

- **Done when:** `./mvnw verify -pl core -Dtest='AgentRouteTableTest+CapabilityServletTest'` proves the exact route table, that no route path exists outside the table, a capability document matching the client's expected shape with an empty command list, three distinct refusals, and a response bounded by the contract accessor alone.
