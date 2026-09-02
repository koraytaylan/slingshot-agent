---
id: sixty-four-row-registry
title: "Sixty-Four Row Registry"
workstream: "0030"
kind: task
depends_on:
  - retry-replication-queue-entry
gated: false
touches:
  - development/src/main/java/rs/slingshot/agent/development/RegistryCompleteness.java
  - development/src/test/java/rs/slingshot/agent/development/RegistryCompletenessTest.java
  - "development/src/test/resources/fixtures/registry-completeness/**"
  - policy/client-command-table.toml
status: done
merged_as: ""
---
# Sixty-Four Row Registry

A command one side holds and the other does not is a refused submission in production. Comparing the two tables is the cheapest possible place to find that, and it only becomes possible once the last row exists.

**Steps:**

1. Record the client's own published command table in `policy/client-command-table.toml` — wire name, access class, operation-key requirement, and result bound per row — taken from its rendered reference rather than from recollection.
2. Implement `RegistryCompleteness` comparing this repository's registry directory against that table in both directions, so a command either side lacks is a finding naming which side.
3. Compare every row's access class, operation-key requirement, and result bound against the client's, and report a disagreement per field rather than per row, because the fixes differ.
4. Assert the registry is exactly sixty-four rows and that the rows are in ascending wire-name order, with ordering a property of the check over the directory rather than of any file.
5. Assert every row's five-field contract identity is derivable and distinct, so no two commands can be confused for one another however similar their names.

**Tests:**

- The registry is exactly sixty-four rows, and a fixture with sixty-three or sixty-five fails naming the difference.
- A command in this registry and not in the client's table, and the reverse, are two distinct findings naming the side.
- An access class, key requirement, or result bound differing from the client's is a finding naming the field, proved one field at a time.
- Ascending wire-name order is asserted over the directory, and a fixture directory whose enumeration order differs still passes.
- All sixty-four contract identities are distinct, and a fixture with two identical identities fails naming both.

- **Done when:** `./mvnw verify -pl development -Dtest=RegistryCompletenessTest` proves exactly sixty-four rows in ascending wire-name order independent of enumeration, two-way correspondence with the client's published table naming the divergent side, per-field disagreement findings for access class, key requirement, and result bound, and sixty-four distinct contract identities.
