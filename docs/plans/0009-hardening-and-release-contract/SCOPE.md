# Plan 0009 — Hardening and Release Contract

> Everything that has to be true before somebody installs this into an author instance they care about, and the pipeline that makes each release the same claim rather than a different one.

## Why this plan

The eight plans before this one prove that the agent does what it says. This one is about what happens when something does not go the way any of them assumed: a document nobody would write by hand, a repository that refuses a commit, a cluster that moves work mid-execution, a clock that jumped, a caller who is trying to get somewhere they should not be, and a dependency that turned out to have a problem after it was chosen.

There is also a specific thing this repository has to be careful about that most do not. It runs inside somebody else's Adobe Experience Manager, with a service user, in the same process as their content and their configuration. A defect here is not a defect in a tool somebody runs; it is a defect in their author instance. That raises the bar on the threat suites and it is the reason the privilege-escalation suite is the one written first rather than last.

And then release. A release is a claim: these bytes, built from this source, doing what this documentation says, on these deployment rows. Every part of that claim has to be either checked or explicitly not claimed. The archive is deterministic so the same source produces the same bytes; the components list is published because a package embedding nothing is a claim worth being able to verify; the deployment rows carry the evidence that actually ran against them rather than the ones somebody hoped for; and the metadata that would make it publishable stays absent until an owner supplies it, exactly as it has since Plan 0001.

Two things here are deliberately gated on somebody's decision rather than automated. Verification against a real author instance runs only when it is explicitly asked for, and reports only about the instance it ran against. And publishing anything anywhere is refused until an owner has supplied the namespace, the repository, and the acknowledgement — because a namespace is a claim to something somebody has to have verified, and no build should make it on its own.

## In scope

- **0035 — Fuzzing and Properties.** A pinned, deterministic, offline fuzzing harness; coverage-guided fuzzing of everything that parses bytes somebody else produced — the bounded document reader, the canonical writer, continuation tokens, the event encoder, and request bodies; property-based proof of the state machines that decide one effect; and generated-argument fuzzing across all sixty-four command argument shapes.
- **0036 — Chaos and Fault Injection.** Two nodes contending for one operation across a handover; a repository that refuses commits, produces conflicts, and fills up; a platform whose job system, workflow engine, and replication fail in each of the ways they can; and clocks that skew, pause, and jump, against every lease and retention decision.
- **0037 — Threat Suites.** Privilege escalation, in every direction the agent's own service user makes possible; injection and traversal through every value a caller supplies into a query, an address, or a repository name; credential exposure across every surface including logs and the console; and resource exhaustion against the request threads, the stream budget, the store, and the job queue.
- **0038 — Compatibility and Advisory Gates.** An owner-reviewed dependency advisory snapshot authenticated offline; wire compatibility snapshots so a protocol change is a visible decision; storage compatibility proved by upgrading an installed instance rather than a fresh one; and a minimum platform version gate bound to the deployment rows.
- **0039 — Pipelines and Release Contract.** The quality and interop workflows with every action pinned to a commit, least privilege declared, and no credential persisted; a deterministic release archive with published digests, sources and documentation archives, and a components list; every prerequisite the central Maven repository enforces decided offline rather than discovered from the portal; one built set published byte-identically to both distribution targets with build provenance; and the owner-supplied metadata and automation authority that gate publication.
- **0040 — Acceptance and Documentation.** Explicitly requested verification against a real author, reporting only about that instance; one acceptance matrix binding every deployment row to the evidence that actually ran against it; and the present-state documentation describing what the repository contains rather than what it intends.

## Out of scope

- Any new command, route, store, or console page. This plan hardens and releases what exists.
- Any claim about a deployment row no evidence ran against. A row stays declared and unproved rather than becoming supported because the code compiled.
- Performing a publication. This plan builds the artifact, proves every precondition both distribution targets impose, and refuses the run while any is unmet; whether to actually publish stays a decision an owner makes by supplying the metadata and the namespace verification record.
- Fixing the client repository's route spelling. Plan 0004 recorded the correction; making it is that repository's change.

## Plan dependencies

Every earlier plan. This one adds no capability and instead attacks all of them, so each workstream names what it is attacking rather than what it is building.
