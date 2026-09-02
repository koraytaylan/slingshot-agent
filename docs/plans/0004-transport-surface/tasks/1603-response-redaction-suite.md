---
id: response-redaction-suite
title: "Response Redaction Suite"
workstream: "0016"
kind: task
depends_on:
  - route-aliases-and-reconciliation
gated: false
touches:
  - policy/redaction-corpus.toml
  - policy/design-patterns.toml
  - CONTRIBUTING.md
  - development/src/main/java/rs/slingshot/agent/development/RedactionAudit.java
  - development/src/test/java/rs/slingshot/agent/development/RedactionAuditTest.java
  - "development/src/test/resources/fixtures/redaction-audit/**"
  - interop/src/main/java/rs/slingshot/agent/interop/tier/InteropTier.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/PublicSlingTier.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/QuickstartTier.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/RedactionScenario.java
  - interop/scenarios/redaction.toml
status: done
merged_as: ""
---
# Response Redaction Suite

Every route, every refusal, every log line, and every stream error is a place a secret can leave. Auditing them one at a time as they are written is how one gets missed; auditing all of them from one corpus is how none does.

**Steps:**

1. Write `policy/redaction-corpus.toml` as the closed set of things that must never appear in a response or a log: a credential, a token, a key, a repository path, an internal class or package name, a queue or topic name, a transport address, and a configuration value.
2. Plant a distinctive value of each kind wherever the agent could hold one — configuration, key ring, request headers, repository content — and drive every route and every refusal.
3. Implement `RedactionAudit` to scan every response body, every header, and every log line produced during the drive, and fail on any corpus hit naming the route and the kind.
4. Include the stream: a stream error, a reset, and a heartbeat are responses too, and an error emitted mid-stream is the easiest one to forget.
5. Prove the audit is complete rather than merely passing: every route in the table and every category in the mapping is asserted to have been driven.

**Tests:**

- Every route and every refusal category is driven, asserted against the route table and the status mapping.
- No corpus value appears in any response body, header, or log line; a fixture that leaks one is detected naming the route and the kind.
- Stream errors, resets, and heartbeats are included in the scan, proved by a fixture leak in each.
- The corpus is asserted closed: a kind with no planted value fails, and a planted value of no declared kind fails.
- The audit runs against a running instance as well as in a unit suite, so a leak that only exists in a real container is caught.

- **Done when:** `./mvnw verify -pl development -Dtest=RedactionAuditTest && ./mvnw verify -pl interop -Dtest=RedactionScenario` proves every table route and every mapping category driven with no corpus value in any body, header, log line, stream error, reset, or heartbeat, a closed corpus with planted values for every kind, and the same audit passing against a running instance.
