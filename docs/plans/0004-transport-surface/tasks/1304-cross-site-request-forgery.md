---
id: cross-site-request-forgery
title: "Forgery and Referrer Prerequisites"
workstream: "0013"
kind: task
depends_on:
  - operator-authorization
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/ForgeryProtection.java
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - docs/DEPLOYMENT.md
  - core/src/test/java/rs/slingshot/agent/http/ForgeryProtectionTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ForgeryProtectionScenario.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/TierRequests.java
  - interop/scenarios/forgery-protection.toml
status: done
merged_as: ""
---
# Forgery and Referrer Prerequisites

The client already fetches a short-lived token immediately before every submission and sends it, because Adobe documents that as the requirement for an authenticated author POST. What this side has to do is make sure that requirement actually applies to these routes rather than being quietly excluded.

There is a second filter in front of every state-changing request and it is the one that surprises people. Sling's referrer filter refuses a POST whose `Referer` is absent or names a host the deployment does not allow, and it refuses it before any servlet is reached — which is exactly the shape of a command-line client that sends no `Referer` at all. Discovering that as an unexplained refusal from a deployment nobody has changed is the most expensive way to learn it, so this task establishes what the client must send, what an operator must allow, and proves both against a real instance. What it does not do is suggest relaxing the filter: a deployment that accepts an empty referrer accepts it for everything, and this agent is not worth that.

**Steps:**

1. Author fixtures for a submission with a valid token, with none, with another user's token, with an expired one, one with a matching `Referer`, one with none, one naming a foreign host, and for a read route which requires neither.
2. Implement `ForgeryProtection` so the state-changing routes are inside both platform protections rather than beside them, and assert their paths are on no exclusion list the deployment carries for either filter.
3. Refuse a submission with an absent, foreign, or expired token as three distinct refusals, none of which discloses whether a token existed.
4. Leave read routes outside the requirement, deliberately and in writing, because a token on a read is a prerequisite that buys nothing and one more thing to get wrong.
5. Document in `docs/DEPLOYMENT.md` exactly which platform configurations these requirements depend on — the forgery filter, the referrer filter and its allowed hosts, and the servlet resolver's permitted path prefixes, without which a path-bound servlet registers nowhere and the agent is simply absent — naming each by its configuration identifier so an operator who has changed one knows what they changed. Record that a client must send a `Referer` naming the instance, or its host must be allowed, and that relaxing the filter to accept an empty referrer is not the answer.

**Tests:**

- A submission with a valid token succeeds; absent, foreign, and expired tokens are three distinct refusals.
- No refusal discloses whether a token was present, proved by comparing response bytes across the three.
- The submission path is asserted absent from every exclusion the deployment configures for either filter, on a running instance.
- A submission with no `Referer` and one naming a foreign host are each refused by the platform before the servlet is reached, proved by a servlet that would record any call, and one with a matching `Referer` succeeds — so the requirement is established as the platform's rather than assumed away.
- Every documented configuration identifier is asserted to name a configuration the platform actually declares, and a fixture naming one that does not exist is rejected.
- Read routes accept a request with no token and do not require one, asserted across the table.
- The documented configuration dependency is asserted to name a real configuration, compared against the shipped configuration set.

- **Done when:** `./mvnw verify -pl core -Dtest=ForgeryProtectionTest && ./mvnw verify -pl interop -Dtest=ForgeryProtectionScenario` proves a token-bearing submission with a matching referrer accepted, three distinct token refusals with byte-identical non-disclosure, an absent and a foreign referrer each refused by the platform before the servlet is reached, the submission path absent from every exclusion of either filter on a running instance, every documented configuration identifier proved real, and read routes requiring neither.
