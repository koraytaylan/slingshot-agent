---
id: health-checks
title: "Health Checks"
workstream: "0033"
kind: task
depends_on:
  - console-artifact-download
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/health/StateTreeHealthCheck.java
  - core/src/main/java/rs/slingshot/agent/health/ContinuationAuthorityHealthCheck.java
  - core/src/main/java/rs/slingshot/agent/health/CapacityHealthCheck.java
  - core/src/main/java/rs/slingshot/agent/health/DeploymentRowHealthCheck.java
  - core/src/main/java/rs/slingshot/agent/health/RouteRegistrationHealthCheck.java
  - core/src/main/java/rs/slingshot/agent/health/QueryCoverageHealthCheck.java
  - core/src/main/java/rs/slingshot/agent/health/package-info.java
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/test/java/rs/slingshot/agent/health/HealthCheckTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/HealthCheckScenario.java
  - interop/scenarios/health-check.toml
status: done
merged_as: ""
---
# Health Checks

The author already has a dashboard operators look at, and an agent that publishes its readiness there is one somebody finds without being told to look. Six checks rather than one, because a single aggregate tells an operator only that something is wrong.

Two of the six exist because of how this fails on somebody else's instance rather than on ours. A path-bound servlet registers only for path prefixes the servlet resolver permits, so a deployment that has narrowed them has an agent whose routes are simply absent — installed, active, and unreachable, which looks like nothing at all. And a query is only cheap while the index covering it exists, which is a property of the customer's repository rather than of this build. Both are silent, both are the operator's to fix, and neither is visible anywhere else.

**Steps:**

1. Author fixtures for each check passing, each failing for its own reason, and a deployment whose row this build does not claim.
2. Implement the state-tree check: the tree exists and its access-control entries are exactly the declared ones, reporting the first difference rather than a count.
3. Implement the continuation-authority check: a ring exists and can issue a token that validates, performed rather than inferred, because an authority that is present and unusable is the case that matters.
4. Implement the capacity check reporting each count against its bound, and the deployment-row check reporting which row this instance matches and whether the build claims it.
5. Implement the route-registration check asserting every route the table declares is registered and reachable on this instance, naming the servlet resolver's permitted path prefixes when one is not, so an operator reads the cause rather than the symptom. Implement the query-coverage check asking the platform for the plan of every declared query and reporting any that would traverse here, naming the query and the index it wants.
6. Register each with its own name and tags so they appear separately in the author's dashboard, and give each failure a message naming what to do rather than what went wrong. Bound what a check costs: the continuation-authority check issues and validates a token, which is real work, so it holds its result for a declared interval and reports when it last ran rather than repeating it for every poll — a dashboard and a monitor both polling is the ordinary case, not the exception.

**Tests:**

- Each of the six passes on a healthy instance and fails for its own reason on a fixture broken in exactly that way, with no check failing for another's reason.
- The state-tree check reports the first access-control difference by name rather than a count.
- The continuation-authority check is proved to issue and validate a token rather than to inspect the ring, by a ring that is present and unusable.
- The deployment-row check reports an unclaimed row as unclaimed rather than as a failure of something else.
- A route the servlet resolver's configuration does not permit is reported as unregistered with the prefix configuration named, on a running instance whose configuration has been narrowed.
- A declared query whose covering index has been removed from a running instance is reported as traversing, naming the query and the index.
- The authority check is proved to perform its issue-and-validate at most once per declared interval across repeated polls, and to report when it last ran.
- On a running instance, all six appear in the author's own health dashboard with their declared names and tags.

- **Done when:** `./mvnw verify -pl core -Dtest=HealthCheckTest && ./mvnw verify -pl interop -Dtest=HealthCheckScenario` proves six separately-caused checks with no cross-reporting, a named first access-control difference, an authority check that issues and validates rather than inspects and does so at most once per declared interval, an unclaimed deployment row reported as itself, an unregistered route reported with the resolver's prefix configuration named, a traversing query reported with its missing index named, and all six visible in the author's dashboard.
