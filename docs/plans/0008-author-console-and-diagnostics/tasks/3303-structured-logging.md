---
id: structured-logging
title: "Structured Logging"
workstream: "0033"
kind: task
depends_on:
  - build-identity-console
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/log/AgentLog.java
  - support/agent-contract.toml
  - support/agent-contract.sha256
  - core/src/main/java/rs/slingshot/agent/log/LogEvent.java
  - core/src/main/java/rs/slingshot/agent/log/package-info.java
  - policy/source-policy.toml
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/test/java/rs/slingshot/agent/log/AgentLogTest.java
  - development/src/test/java/rs/slingshot/agent/development/LogStatementPolicyTest.java
status: done
merged_as: ""
---
# Structured Logging

An operator with a console row should be able to find the logs, and an operator with a log line should be able to find the console row. That only works if every line carries the operation identifier and nothing carries anything it should not.

**Steps:**

1. Author fixtures for a line with an operation identifier, one without, a line interpolating a value the redaction corpus covers, and a line at and past the message bound.
2. Implement `AgentLog` as the only logging interface this repository uses, taking a `LogEvent` with named fields rather than a formatted string, so a value is a field rather than text somebody concatenated.
3. Carry the operation identifier on every event produced while one is in scope, from the caller context rather than passed by hand, so it cannot be forgotten.
4. Apply the redaction corpus to every field before it is written, and bound every message, refusing rather than truncating.
5. Extend the source policy: a direct logger call, a formatted-string log statement, and a log statement interpolating a corpus-covered value are three findings, so the interface cannot be bypassed.

**Tests:**

- Every event produced during an operation carries its identifier, proved by driving a command and asserting over every line.
- A corpus-covered value passed as a field is redacted before writing, across every corpus kind.
- The message bound is proved at exactly its limit and one past it, refused rather than truncated.
- A direct logger call, a formatted-string statement, and a corpus interpolation are three distinct source-policy findings, each with a comment-only fixture that passes.
- A console row and its log lines are joinable by operation identifier, proved on a running instance.

- **Done when:** `./mvnw verify -pl core -Dtest=AgentLogTest && ./mvnw verify -pl development -Dtest=LogStatementPolicyTest` proves an operation identifier on every in-scope line, redaction of every corpus kind before writing, both sides of the message bound with refusal rather than truncation, three distinct source-policy findings with comment-only fixtures passing, and console-to-log joinability on a running instance.
