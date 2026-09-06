# Plan 0010 — Runtime Hardening and Stabilisation — Unregistered

This is an authored plan, not a registered Makina run. Direct implementation is tracked in
[EXECUTION.md](EXECUTION.md), with review and validation evidence for each task. The authored
Makina lifecycle fields remain pending with no landing OIDs;
subsequent registration, progress, integration evidence and root roll-up updates belong to the
coordinator under [the authoring contract](../README.md). Existing plans' completion records have
not been rewritten by this review.

- Authored against: 11d5fc9fd04614b63c959ac0369748b32e34f126.
- Review date: 2026-09-05.
- Direct implementation: 7/27 tasks complete (4101, 4102, 4103, 4104, 4106, 4107, 4108). See [EXECUTION.md](EXECUTION.md)
  for the review loops and validation evidence. The full gate passed after task 4108; the
  owner-supplied AEM and client tiers remain unproved.
- Registration/integration: not attempted; no validated Phase R base or integration OID claimed.
- Review evidence: [EVIDENCE.md](EVIDENCE.md).
- Full gate baseline: refused in core tests, 857 run / 2 failures; see R16.

| Task | Target | Dependencies |
|---|---|---|
| [4101](tasks/4101-deterministic-subscription-time.md) | Make Subscription Expiry Tests Deterministic | None |
| [4102](tasks/4102-exclusive-store-transitions.md) | Establish Exclusive Oak Store Transitions | None |
| [4103](tasks/4103-consistent-capacity-accounting.md) | Make Capacity Reservations and Releases Consistent | exclusive-store-transitions |
| [4104](tasks/4104-atomic-retention-cleanup.md) | Make Retention Cleanup Atomic and Idempotent | consistent-capacity-accounting |
| [4105](tasks/4105-bounded-maintenance-pages.md) | Enforce Maintenance Bounds Inside Buckets | atomic-retention-cleanup |
| [4106](tasks/4106-atomic-execution-fences.md) | Persist Execution Fence Authority Atomically | exclusive-store-transitions |
| [4107](tasks/4107-fenced-continuation-authority.md) | Fence Continuation Key Writes with Persisted Ownership | atomic-execution-fences |
| [4108](tasks/4108-atomic-generation-rotation.md) | Publish Generation Rotation Atomically | exclusive-store-transitions, consistent-capacity-accounting |
| [4201](tasks/4201-live-operator-configuration.md) | Apply Operator Group Configuration at Runtime | None |
| [4202](tasks/4202-state-access-and-ownership.md) | Separate State Sessions and Enforce Ownership | live-operator-configuration |
| [4301](tasks/4301-resumable-request-admission.md) | Make Accepted Requests Progress After Saturation | consistent-capacity-accounting, state-access-and-ownership |
| [4302](tasks/4302-reliable-terminal-finalization.md) | Preserve Truthful Terminal Outcomes | resumable-request-admission |
| [4303](tasks/4303-atomic-intake-publication.md) | Validate Intake Before Publishing a Completed Slot | consistent-capacity-accounting, state-access-and-ownership |
| [4304](tasks/4304-intake-completion-execution.md) | Execute Fully Received Intake Operations Once | atomic-intake-publication, reliable-terminal-finalization |
| [4305](tasks/4305-durable-stream-progress.md) | Record Subscription Progress from Actual Delivery | resumable-request-admission, atomic-generation-rotation |
| [4306](tasks/4306-terminal-result-delivery.md) | Expose Durable Command Results Through the Contract | reliable-terminal-finalization |
| [4307](tasks/4307-enforceable-transfer-deadlines.md) | Enforce Transfer Deadlines During Blocked I/O | durable-stream-progress, atomic-intake-publication |
| [4401](tasks/4401-bounded-repository-traversal.md) | Bound Repository Reads and Retained Traversal Work | None |
| [4402](tasks/4402-complete-reference-guards.md) | Refuse Mutations After Incomplete Reference Discovery | bounded-repository-traversal |
| [4403](tasks/4403-bounded-component-deletion.md) | Restrict Component Deletion to Valid Bounded Components | bounded-repository-traversal |
| [4404](tasks/4404-correct-handler-pagination.md) | Apply Verified Paging on Every Handler Run Path | bounded-repository-traversal, fenced-continuation-authority |
| [4405](tasks/4405-real-package-publication.md) | Build and Publish Actual Content Package Bytes | bounded-repository-traversal, atomic-intake-publication |
| [4501](tasks/4501-runtime-lifecycle-services.md) | Activate Durable State Lifecycle Services | bounded-maintenance-pages, atomic-generation-rotation, fenced-continuation-authority, state-access-and-ownership |
| [4502](tasks/4502-runtime-command-assembly.md) | Connect the Packaged Immediate Command Runtime | runtime-lifecycle-services, intake-completion-execution, terminal-result-delivery, correct-handler-pagination, complete-reference-guards, bounded-component-deletion, real-package-publication |
| [4503](tasks/4503-console-runtime-assembly.md) | Connect the Installed Console Data Sources | runtime-command-assembly, state-access-and-ownership |
| [4601](tasks/4601-positive-runtime-acceptance.md) | Require Successful Runtime Behavior in Acceptance Tests | deterministic-subscription-time, console-runtime-assembly, enforceable-transfer-deadlines |
| [4602](tasks/4602-executable-owner-tiers.md) | Make Acknowledged AEM and Client Tiers Execute | positive-runtime-acceptance |
