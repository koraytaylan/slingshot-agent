---
id: storage-upgrade-compatibility
title: "Storage Upgrade Compatibility"
workstream: "0038"
kind: task
depends_on:
  - wire-compatibility-snapshots
gated: false
touches:
  - support/previous-release.toml
  - interop/src/main/java/rs/slingshot/agent/interop/tier/UpgradeTier.java
  - interop/src/test/java/rs/slingshot/agent/interop/StorageUpgradeScenario.java
  - interop/scenarios/storage-upgrade.toml
status: done
merged_as: ""
---
# Storage Upgrade Compatibility

A fresh install proves nothing about an upgrade, and an upgrade is what every real deployment does. So this is proved by installing over a populated instance rather than beside one.

**Steps:**

1. Pin the previous release in `support/previous-release.toml` by version and artifact digest, with an explicit absent value for the first release rather than a special case in the code.
2. Implement `UpgradeTier` installing the previous release, populating its store with operations at every state including one still awaiting its declared intake, events, artifacts, partly-filled intake slots, subscriptions, per-caller capacity counters, and a key ring, then installing the current release over it.
3. Assert every populated record is still readable after the upgrade, with the same values it had, compared record by record.
4. Assert every invariant still holds after the upgrade: snapshot equal to fold, capacity equal to contents, no dangling artifact reference, and every lease either live or expired rather than malformed.
5. Assert continuity across the upgrade: a token issued before it still validates after, an operation in flight before it reaches a disposition after, and the generation is unchanged unless a rotation was explicitly recorded.

**Tests:**

- Every populated record is readable after the upgrade with identical values, compared record by record.
- Every store invariant holds after the upgrade, using the same verification pass the sweep uses.
- A token issued under the previous release validates under the current one until its own expiry.
- An operation in flight across the upgrade reaches a disposition rather than remaining stuck, and one awaiting intake accepts its remaining slots afterwards and is then startable.
- With no previous release pinned, the tier reports that explicitly rather than passing vacuously.

- **Done when:** `./mvnw verify -pl interop -Dtest=StorageUpgradeScenario` installs the pinned previous release over a populated store and proves record-by-record readability with identical values, every invariant holding, a pre-upgrade token still validating, an in-flight operation reaching a disposition and an intake-awaiting one completing across the upgrade, and an explicit report rather than a vacuous pass when no previous release is pinned.
