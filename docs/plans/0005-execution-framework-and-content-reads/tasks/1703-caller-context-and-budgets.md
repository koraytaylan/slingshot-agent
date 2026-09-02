---
id: caller-context-and-budgets
title: "Caller Context and Budgets"
workstream: "0017"
kind: task
depends_on:
  - handler-contract-and-dispatch
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/CallerContext.java
  - core/src/main/java/rs/slingshot/agent/command/Budget.java
  - core/src/main/java/rs/slingshot/agent/command/ProgressSink.java
  - core/src/main/java/rs/slingshot/agent/command/StagingArea.java
  - policy/source-policy.toml
  - policy/design-patterns.toml
  - development/src/main/java/rs/slingshot/agent/development/SourcePolicy.java
  - core/src/test/java/rs/slingshot/agent/command/CallerContextTest.java
  - development/src/test/java/rs/slingshot/agent/development/SessionAcquisitionTest.java
  - "development/src/test/resources/fixtures/session-acquisition/**"
status: done
merged_as: ""
---
# Caller Context and Budgets

The agent's own bookkeeping is on the service user, in code a handler cannot reach. Everything a handler touches is decided by the caller's own repository access — which is the difference between an agent and a privilege escalation, and it holds only if obtaining a session is unavailable rather than discouraged.

One command needs to write scratch space while it works, and that is exactly the pressure that would otherwise reopen session acquisition. So the framework hands it a place rather than the means to find one: a handle onto a directory the framework chose inside the agent's own tree, opened and released by the framework, that can write nothing else.

**Steps:**

1. Author fixtures for a context carrying each budget, a handler exceeding each budget, a handler attempting to obtain a session, a handler attempting to reach a service, a handler writing outside its staging handle, and a context for a row that declares no staging.
2. Implement `CallerContext` carrying the requesting user's own resolver — the request's, because a command executes inside the request that submitted it and there is no later moment to obtain one for — the three budgets, the operation identity, a progress sink, and, only where the registry row declares it, a staging area; and nothing that could produce a second resolver, a service reference, or a factory.
3. Implement `Budget` for the discovery, time, and result limits, each read from the registry row or the contract, each with exactly one failure category so exceeding one is reported as itself.
4. Implement `ProgressSink` so a long command's progress becomes events on the stream rather than nothing, bounded by the per-operation event count so a chatty handler cannot fill the ledger.
5. Implement `StagingArea` as a handle onto one framework-chosen directory under the agent's own tree, writing under the service user, bounded by a declared byte budget, resolving every path it is given inside its own root and refusing any that would leave it, and released by the framework on every path including every failure and every interruption. It exposes no resolver, no session, and no parent, so it is a place to write and never a way to reach anywhere else.
6. Extend the source policy: a session acquisition, a service lookup, or a bundle-context reference anywhere in a handler package is a finding, so the guarantee is enforced by parsing rather than by review.

**Tests:**

- Each budget is proved at exactly its limit and one past it, and each reports its own category.
- The context is asserted to expose no member that yields a second resolver, a service, or a factory, the staging area included.
- The resolver a handler receives is proved to be the request's own, carrying exactly the requesting user's permissions, compared against a direct login as them.
- A handler attempting a session acquisition, a service lookup, or a bundle-context reference is refused by the source policy, and a fixture naming each only in a comment passes.
- Progress events are bounded by the per-operation event count, with a handler past it refused rather than truncating the ledger.
- A staging area is present only for a row that declares one, refuses every path outside its own root including through separators, parent references, and encodings, is bounded by its declared byte budget at exactly the limit and one past, and is proved released after success, after every declared failure, and after an interruption.
- The budgets are proved read from the registry row or the contract, with none declared in the handler packages.

- **Done when:** `./mvnw verify -pl core -Dtest=CallerContextTest && ./mvnw verify -pl development -Dtest=SessionAcquisitionTest` proves both sides of all three budgets with a distinct category each, a context that yields no second resolver or service, source-policy refusal of every session-acquisition form with comment-only fixtures passing, a bounded progress sink, a staging area present only where declared that cannot escape its root or its byte budget and is released on every path, and no budget declared outside the registry or contract.
