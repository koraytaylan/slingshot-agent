---
id: handler-contract-and-dispatch
title: "Handler Contract and Dispatch"
workstream: "0017"
kind: task
depends_on:
  - command-registry
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/CommandHandler.java
  - core/src/main/java/rs/slingshot/agent/command/CommandDispatch.java
  - core/src/main/java/rs/slingshot/agent/command/DispatchRefusal.java
  - core/src/test/java/rs/slingshot/agent/command/CommandDispatchTest.java
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Handler Contract and Dispatch

A handler receives everything it may use and can obtain nothing itself. Everything else in this plan follows from that one sentence being true rather than intended.

**Steps:**

1. Author fixtures for a registered handler, a row with no handler, a handler with no row, two handlers for one wire name, and a handler registered under a name that is not a wire name.
2. Implement `CommandHandler` as one method taking typed arguments and a caller context and returning a typed result, with no other member and no lifecycle callback a handler could use to keep state between invocations.
3. Implement `CommandDispatch` to resolve a handler by wire name only after the five-field identity has been verified, so a handler is never reached by a submission whose contract this build does not hold.
4. Refuse a row with no handler and a handler with no row as two distinct build-time failures, and refuse two handlers claiming one wire name at registration rather than picking one.
5. Make the handler's declared failure set the row's set: a handler that produces a category the row does not declare is refused, and a declared category no handler can produce is refused too.

**Tests:**

- A registered handler is dispatched to after identity verification, and a fixture dispatching before verification is rejected.
- A row with no handler and a handler with no row are two distinct failures naming the wire name.
- Two handlers for one wire name are refused at registration, and neither is chosen.
- A handler producing an undeclared category is refused, and a declared category no handler produces is refused, both naming the category.
- The handler interface is asserted to expose exactly one method and no lifecycle member, over its own type.

- **Done when:** `./mvnw verify -pl core -Dtest=CommandDispatchTest` proves dispatch only after identity verification, two distinct row-and-handler correspondence failures, refusal of duplicate registration, two-way failure-category correspondence, and a handler interface with exactly one method and no lifecycle member.
