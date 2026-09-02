---
id: servlet-binding-and-method-policy
title: "Servlet Binding and Method Policy"
workstream: "0013"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/AgentServlet.java
  - core/src/main/java/rs/slingshot/agent/http/RequestShape.java
  - core/src/main/java/rs/slingshot/agent/http/ShapeRefusal.java
  - core/src/main/java/rs/slingshot/agent/http/package-info.java
  - core/src/main/java/rs/slingshot/agent/discovery/CapabilityServlet.java
  - core/src/test/java/rs/slingshot/agent/http/AgentServletTest.java
  - core/src/test/java/rs/slingshot/agent/http/RequestShapeTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/RouteReachabilityScenario.java
  - interop/scenarios/route-reachability.toml
status: done
merged_as: ""
---
# Servlet Binding and Method Policy

Sling will reach a path-bound servlet through a selector, an extension, a suffix, or a trailing segment. Each of those is a second spelling of a route, and a route with spellings nobody enumerated is a route whose policy applies to some of the ways it can be reached.

**Steps:**

1. Author the reachability corpus before the servlets: for every route, the exact path plus one request each with a selector, an extension, a suffix, a trailing segment, and a mixed case, all of which must be refused.
2. Implement `AgentServlet` as the one base every route extends, taking its path from the route table and refusing a request whose path is not exactly that path, before any parameter is read.
3. Implement the method policy from the table: exactly one method per route, with every other method refused by name rather than answered with a default.
4. Implement the media-type policy: a route that accepts a body accepts exactly one media type and refuses every other, and a route that produces one names it exactly.
5. Make each refusal a distinct `ShapeRefusal` — wrong path spelling, wrong method, wrong media type — so a caller can tell which of the three it got wrong.

**Tests:**

- Every route answers on its exact path and refuses each of the five alternative spellings, one at a time, across the whole table.
- Every route refuses every method but its own, and the refusal names the method rather than falling through to a default handler.
- A body in an unaccepted media type is refused, and a route that produces a document names its media type exactly.
- The three refusals are distinct, and every one happens before a parameter is read, proved by a request whose parameters would themselves be refused later.
- On a running instance, the same five alternative spellings are refused for every route, proving Sling's resolution does not reach past the check.

- **Done when:** `./mvnw verify -pl core -Dtest=RequestShapeTest && ./mvnw verify -pl interop -Dtest=RouteReachabilityScenario` proves exact-path-only reachability against five alternative spellings per route in both a unit suite and a running instance, one method and one media type per route with named refusals, and three distinct refusals each taken before any parameter is read.
