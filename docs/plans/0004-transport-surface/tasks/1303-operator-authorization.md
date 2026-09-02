---
id: operator-authorization
title: "Operator Authorization"
workstream: "0013"
kind: task
depends_on:
  - request-authentication
gated: false
touches:
  - policy/repository-access.toml
  - core/src/main/java/rs/slingshot/agent/http/AuthorizationGate.java
  - core/src/main/java/rs/slingshot/agent/http/RouteAuthority.java
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/test/java/rs/slingshot/agent/http/AuthorizationGateTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/AuthorizationScenario.java
  - interop/scenarios/authorization.toml
status: done
merged_as: ""
---
# Operator Authorization

A path-bound servlet is reached before the access control that would otherwise decide the request. That is the point of binding by path and it is the hazard: without this task the agent is a way to do things the caller could not do themselves.

The gate follows the arrangement the Groovy Console established and Adobe operators already recognise: the tool is available to administrators and to nobody else until somebody deliberately says otherwise, and what widens it is an Open Service Gateway Initiative configuration naming the groups that may use it. A default of `administrators` is a default an operator can verify their install with; a default of nobody is a default they cannot tell apart from a broken install; and a default of everybody is not a default at all.

**Steps:**

1. Author fixtures for a caller in a permitted group, one authenticated and outside every permitted group, one who submitted an operation, one who did not, a configuration naming a group that does not exist, and a configuration naming no group at all.
2. Declare the permitted-group configuration in `policy/repository-access.toml` and ship it in `ui.config` with `administrators` as its only value, so a fresh install is usable by an administrator verifying it and by nobody else. An operator widens it by naming further groups in that configuration; a configuration naming no group refuses every submission rather than admitting everybody, and one naming a group that does not exist refuses naming it rather than silently admitting fewer people than the operator believes.
3. Implement `RouteAuthority` as one row per route naming what it requires — membership of a permitted group to submit, that or being the submitting caller to read an operation — as data rather than as conditions spread through servlets.
4. Implement `AuthorizationGate` to apply that table after authentication and before any store read, and record the caller on every operation at admission so the ownership test has something to compare.
5. Prove the two-session rule at this boundary: the caller's own session is what reaches the operation store for a read, so a caller who cannot see a record's tree cannot read it even if the table would allow it.
6. Decide this before a command runs rather than only before one is submitted: a command executes inside its own request, so the gate that admitted the request is the gate the execution inherits, and there is no later moment at which a stale decision could be acted on.

**Tests:**

- Every route's requirement is asserted equal to the table, and a route with no row fails.
- A caller outside every permitted group is refused submission; one inside any of them is admitted; a configuration naming no group refuses every submission, and one naming a group that does not exist refuses naming it — three distinct outcomes.
- The shipped configuration is asserted to name `administrators` and nothing else, and an administrator is proved able to submit on a fresh install while every other authenticated user is refused.
- Widening the configuration to a further group is proved to admit that group's members without a restart and without any other change.
- A caller reads their own operation and is refused another caller's, and a group member reads both.
- The submitting caller is recorded at admission, and an operation with no recorded caller is unreadable rather than readable by everyone.
- On a running instance, a caller whose repository access excludes the record's tree is refused even as a permitted-group member.
- A caller removed from every permitted group is refused on their next request, and no path exists by which an earlier admission could carry work past that, asserted over the gate's own surface.

- **Done when:** `./mvnw verify -pl core -Dtest=AuthorizationGateTest && ./mvnw verify -pl interop -Dtest=AuthorizationScenario` proves a complete route-to-requirement table, a shipped configuration naming `administrators` alone that admits an administrator and refuses every other authenticated user, three distinct outcomes for an outside caller, an empty configuration, and a configuration naming a group that does not exist, widening by configuration admitting a further group without a restart, per-caller operation ownership with permitted-group override, an unreadable record when no caller was recorded, no path by which an earlier admission carries work past a revoked one, and repository access still deciding on a running instance.
