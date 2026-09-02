---
id: live-author-verification
title: "Live Author Verification"
workstream: "0040"
kind: task
depends_on:
  - central-publication-prerequisites
gated: false
touches:
  - scripts/verify_live_author
  - support/live-author.toml
  - interop/src/main/java/rs/slingshot/agent/interop/tier/LiveAuthorTier.java
  - interop/src/test/java/rs/slingshot/agent/interop/LiveAuthorTierTest.java
  - docs/INTEROP.md
status: done
merged_as: ""
---
# Live Author Verification

One run against one author is evidence about that author and about nothing else. Saying so in the report is what stops it being quoted later as evidence about a deployment row.

**Steps:**

1. Author fixtures for a run without the explicit enabling flag, one against an instance the operator has not acknowledged, and an accepted arrangement.
2. Write `scripts/verify_live_author` refusing before a byte of configuration is read unless the enabling flag is given, so reaching a real instance is never something that happens by default.
3. Determine what it may run from the registry itself: exactly the rows the registry classifies as replacing nothing, never a list written here, so a command reclassified later changes what runs without editing this.
4. Implement `LiveAuthorTier` running those commands against the acknowledged instance under a supplied identity, and asserting each one's contract behaviour rather than its content — the shapes, the bounds, and the failure categories.
5. Report the result labelled as an observation of one unattested instance, naming the instance's platform version and the exact commands run, and — for every row that declares a staging budget — that it wrote inside the agent's own tree and that the staging was released, since an operator handing over a real author is owed the difference between a command that touched nothing at all and one that touched only what this agent owns. Refuse to aggregate the report with any tier's evidence.

**Tests:**

- Without the enabling flag the command refuses before reading configuration, proved by a configuration that would fail to parse.
- The commands run are exactly the registry's read rows, proved by reclassifying a fixture row and expecting the set to change with no code edit.
- No command that replaces anything is reachable through this tier, asserted over the derived set.
- The report is labelled as a single-instance observation and names the platform version and the exact commands, and a fixture report aggregating it with tier evidence is rejected.
- Every staging-declaring row that ran is reported as having written and released inside the agent's own tree, and the tree is asserted byte-identical to its pre-run state afterwards.
- A run against an unacknowledged instance is refused naming what an operator must acknowledge.

- **Done when:** `scripts/verify_live_author` refuses before reading configuration without its enabling flag and against an unacknowledged instance, and with both present runs exactly the registry's read rows — proved to change when a fixture row is reclassified — reaching no replacing command, and reports a labelled single-instance observation naming the platform version, the commands, and every staging-declaring row's write-and-release, with the agent's tree byte-identical afterwards and the report unable to be aggregated with tier evidence.
