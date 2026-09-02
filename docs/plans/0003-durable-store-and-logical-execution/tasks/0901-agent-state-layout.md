---
id: agent-state-layout
title: "Agent State Layout"
workstream: "0009"
kind: task
depends_on: []
gated: false
touches:
  - policy/repository-layout.toml
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/main/java/rs/slingshot/agent/store/StatePath.java
  - core/src/main/java/rs/slingshot/agent/store/StateLayout.java
  - core/src/main/java/rs/slingshot/agent/store/package-info.java
  - core/src/test/java/rs/slingshot/agent/store/StatePathTest.java
  - "core/src/test/resources/fixtures/state-layout/**"
status: done
merged_as: ""
---
# Agent State Layout

Every lookup this agent makes is a path derived from an identifier, and never a query. A query needs an index, an index has to be current, and "the index had not caught up yet" is an idempotency answer that is wrong rather than slow.

**Steps:**

1. Author fixtures for the accepted layout, for an identifier that would escape the state tree, for a path derived twice from the same identifier, and for a bucket distribution over a large identifier corpus.
2. Write `policy/repository-layout.toml` declaring every node under `/var/slingshot-agent`, what it holds, and which of the two write primitives creates it — the operation subtree with its outbox, lease, events, snapshot, artifacts, and the intake slots an inbound manifest declares; the subscription records; the capacity counters both in total and per submitting caller; the generation record; and the key ring.
3. Implement `StatePath` deriving a path from an identity value alone — generation, then a two-level bucket taken from the identifier's leading characters, then the identifier — with no date, no counter, and no ambient input, so a recovering caller holding only an identifier can find its record. A caller identifier is bucketed the same way for the per-caller counters, through the same derivation rather than a second one, because a caller's own name is an ambient string until a type has constrained it.
4. Refuse any input that would produce a path outside the declared tree, before a path is built rather than after, and refuse a path built from anything but a constructed identity type.
5. Extend the repository initialisation script so the tree and its access-control entries exist at install, and assert the declared layout and the created tree agree in both directions.

**Tests:**

- The same identifier derives the same path every time and different identifiers derive different paths, over a corpus large enough to exercise the bucket.
- Bucket occupancy over that corpus is asserted to leave no node above the declared child ceiling.
- An identifier containing a path separator, a parent reference, or a leading separator is refused before a path is built, each distinctly.
- No path can be produced from a raw string, asserted over the type's surface.
- The declared layout and the tree the initialisation script creates are asserted equal in both directions, and a node created outside the declaration fails.
- A caller identifier derives its counter path through the same derivation as an operation identifier, and a caller identifier carrying a separator, a parent reference, or a leading separator is refused before a path is built.

- **Done when:** `./mvnw verify -pl core -Dtest=StatePathTest` proves deterministic collision-free derivation with bucket occupancy under the child ceiling, three distinct escape refusals before path construction, no derivation from a raw string, and two-way agreement between the declared layout and the tree the initialisation script creates.
