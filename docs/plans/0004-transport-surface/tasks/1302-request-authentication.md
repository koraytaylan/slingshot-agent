---
id: request-authentication
title: "Request Authentication"
workstream: "0013"
kind: task
depends_on:
  - servlet-binding-and-method-policy
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/CallerIdentity.java
  - core/src/main/java/rs/slingshot/agent/http/AuthenticationGate.java
  - core/src/test/java/rs/slingshot/agent/http/AuthenticationGateTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/AuthenticationScenario.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/TierRequests.java
  - core/src/main/java/rs/slingshot/agent/discovery/CapabilityServlet.java
  - interop/scenarios/authentication.toml
status: done
merged_as: ""
---
# Request Authentication

This repository consumes the identity the platform established and never establishes one. What it must do is refuse everything that arrives without one — including discovery, which is the route somebody would most easily leave open on the grounds that it says so little.

**Steps:**

1. Author fixtures for an anonymous request, a request carrying a user, a request whose user is the service user, and a request carrying credentials the platform did not accept.
2. Implement `AuthenticationGate` to require a non-anonymous Sling user on every route in the table, with no route exempt and no configuration that could exempt one.
3. Implement `CallerIdentity` as the caller's authorizable identifier and nothing else — no credential, no token, no header value is retained past the gate.
4. Refuse a request whose established user is the agent's own service user, because a request arriving as the service user is either a misconfiguration or a confused deputy, and neither should be served.
5. Make the refusal disclose nothing about why: an anonymous request and one whose credentials the platform rejected receive the same answer, since distinguishing them tells an attacker which usernames exist.

**Tests:**

- Every route refuses an anonymous request, proved by enumerating the table rather than by listing routes here.
- No configuration exempts a route, asserted over the gate's own surface.
- A request arriving as the service user is refused, distinctly in the log and identically on the wire.
- The caller identity retains no credential, header, or token value, asserted over the type.
- On a running instance, an anonymous request and one with rejected credentials produce byte-identical responses.

- **Done when:** `./mvnw verify -pl core -Dtest=AuthenticationGateTest && ./mvnw verify -pl interop -Dtest=AuthenticationScenario` proves every table route refuses anonymous with no exemption possible, refuses a service-user request, retains no credential material, and returns byte-identical responses to anonymous and rejected-credential requests on a running instance.
