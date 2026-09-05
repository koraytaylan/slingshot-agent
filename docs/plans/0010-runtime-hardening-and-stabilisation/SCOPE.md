# Runtime Hardening and Stabilisation

The installed agent cannot execute its declared command surface, and several underlying safety
mechanisms fail under concrete contention, interruption, and bounded-search cases. Complete runtime
integration only after repairing those mechanisms. Enabling the handlers first would expose data
loss and duplicate-execution risks that the current disconnected servlet happens to mask.

This plan records a review of commit **11d5fc9fd04614b63c959ac0369748b32e34f126** on
**2026-09-05**. The initial worktree was clean. The review changes documentation only.

## Read this bundle

- [Findings](FINDINGS.md): prioritised defects, triggers, consequences, source locations, and repair tasks.
- [Evidence](EVIDENCE.md): observed outputs, reproducible probes, and the limits of each proof.
- [Architecture and sequencing](ARCHITECTURE.md): invariants the repairs must establish.
- [Status](STATUS.md): authored task inventory and direct implementation progress.
- [Implementation evidence](EXECUTION.md): per-task review loops and validation results.
- [Tasks](tasks/): one independently verifiable target per task, with bundle-local dependencies.

## Scope

Review and stabilise the implementation that already exists: installed OSGi components and packages;
request admission, authorization and result delivery; durable operations, quotas, subscriptions,
leases and generations; mutation guards, pagination and traversal budgets; and the tests intended
to prove these behaviors. Include the missing runtime connections necessary for the already-declared
immediate commands, maintenance, continuation authority, and console to work.

The evidence includes a real pinned Apache Sling container, embedded Oak session races and injected
save failures, real Oak user/ACL checks through servlet doubles, and command execution through Sling
resource doubles. These are different proof levels. None is represented as successful AEM or sibling
client acceptance.

## Priority

**P1** means repair before enabling or releasing the affected surface. **P2** means repair before
claiming the corresponding operational bound or deployment proof. Findings explicitly distinguish
currently registered routes from foundations that are not connected to them. An unwired mutation
defect is still a release blocker for enabling that mutation; it is not a demonstrated attack on the
currently installed bundle.

## Exclusions

No style, naming, complexity, or documentation-polish findings. No new command family, deferred
caller execution, impersonation, privilege widening, or index package is proposed. Existing policies
remain the constraints: if a repair needs a rule change, update its policy, checker, and fixtures
together. Do not make a gate pass by weakening the behavior it is meant to establish.

## Completion requirements

1. Each finding has its regression case and its repair task's falsifiable acceptance evidence.
2. Identical competing requests produce one durable winner and at most one counted side effect.
3. Crashes and retries preserve quotas, retained generations, artifact validity, and operation outcome.
4. Only the owner or a configured operator can inspect or mutate operation state; content executes
   using the original request caller, while internal state uses the narrow service identity.
5. Every enabled command is discoverable, dispatchable and returns its actual result through the
   installed transport. Positive cases include content changes, paging and downloadable package bytes.
6. The full argument-free scripts/quality passes with prepared inputs. AEM/client claims additionally
   require their actual executable tiers and exact deployment evidence; absence is not a pass.

These are implementation completion requirements for this plan, not claims that the review has
already repaired the product.
