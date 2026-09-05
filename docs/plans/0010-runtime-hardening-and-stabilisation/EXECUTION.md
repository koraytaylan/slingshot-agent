# Plan 0010 implementation evidence

This records direct implementation under the user's instruction to review and commit each task.
It does not claim Makina registration, Phase R validation, or final integration. The authored task
frontmatter remains the Makina lifecycle record; the evidence below tracks the direct work.

## 4101 — deterministic subscription time

The production no-argument servlet constructor uses `Clock.systemUTC()`. A second constructor accepts
an explicit clock, and subscription expiry uses that clock. Tests create subscription timestamps
and advance time from the same controlled source. The existing retention comparison stays inclusive:
subscriptions are live one millisecond before and exactly at the retention boundary, and expired one
millisecond after it. Cases cover 2020-01-01, 2026-09-05, and 2040-12-31.

Review and correction loop:

1. Checked the expiry predicate in `SubscriptionLedger` and preserved its strict greater-than
   comparison rather than changing the production boundary to accommodate the test.
2. Compilation found that a servlet cannot hold a non-serializable `Clock` as a normal field.
   The final clock reference is transient; `readResolve` restores a production servlet.
3. Static analysis rejected a mutable restoration field. The final implementation retains no
   mutable request state. A deserialization test was removed because the repository's security
   analyzer forbids object deserialization, including in tests; no suppression was added.
4. Updated the servlet's pattern registration from `stateless-policy` to the existing `accessor`
   pattern, because the class now holds its clock. The pattern rules and checker are unchanged.
5. Javadoc generation required documentation on the serialization hook; added it.
6. Re-read the final diff against all three task steps and checked whitespace.

Validation on 2026-09-05:

- Focused `HighWaterServletTest`: 8 tests, zero failures/errors/skips.
- Full argument-free `scripts/quality`: core completed 860 tests with zero failures/errors/skips,
  and its coverage thresholds passed. Formatting, compilation, static analysis, and the repository
  policy stages preceding the reactor verification passed. `HighWaterScenario` passed all three
  running-instance cases. The interop module finished with 455 tests, zero failures, one error.
- Independent remaining gate failure: `ArtifactTransferScenario.install` failed during public-tier
  setup because `/system/userManager/group/administrators.update.html` returned HTTP 500 while
  adding the test caller to the administrators group. No artifact request was executed by that
  scenario. This does not establish that the full gate passes; later stages were not reached.
- Task 4101 acceptance is satisfied: controlled-date expiry regressions pass, production time is
  retained, and the full gate's remaining failure is recorded. The full-plan gate remains open.
- Full gate output for this work session: `/tmp/plan10-4101-quality.log`.

The task 4101 commit is `2a716c5`. Its gate refusal above records that run; later task results
below supersede the current gate status.

## 4102 — exclusive store transitions

`CompareAndSet` stages a unique `transition_revision` before reading its expected value and saves
both together. `OperationStore` uses the same ordering before checking the start predicate.
`ClaimByCreation` fences the existing parent before rechecking absence, then commits the new node's
revision with its initial data. Competing writers cannot succeed by merging identical business
values. Refused predicates discard their staged revisions. A conflict whose winner is not yet
visible returns contention, not claimed ownership. Sibling creations can contend on their bucket;
this is an explicit tradeoff of protecting creation against session refresh.

Review and correction loop:

1. Converted the preserved save interleavings into deterministic embedded Oak regressions.
   The original implementation failed four assertions: both identical claims, counter updates,
   admissions and starts reported success. The start test counts distinct, independently saved
   content nodes and resends after treating the winning response as lost.
2. The initial revision-marker repair passed those regressions. Review then inspected Oak's actual
   `SessionDelegate.prePerform` bytecode from the prepared dependency cache: it can call
   `refresh(true)` before a write. Three additional deterministic regressions reproduced adoption
   of a competing update, start, and newly created node during that refresh. They failed the first
   repair. Staging the revision before the predicate, and fencing the parent during creation,
   made all three pass. This is why merely adding a marker after writing the business value is
   insufficient.
3. Covered both pre-existing revision markers and legacy counter records without them, and checked
   that a refused claim leaves no staged data when no winner is visible.
4. Added a test-only servlet and bundle assembly for the shared-store proof. The assembly copies
   classes and resources from the built core jar, omits its component descriptors, and adds only
   the test driver. The product bundles, exported API, route registrations, dependencies and
   customer packages gain no test endpoint. The proxy uses the OSGi bundle loader and declares
   its JCR interface packages; the launcher loader cannot see them.
5. Extended `CrashConsistencyScenario` to pause both nodes before save, release the first through
   its commit, then release the second's stale save. It checks one counter/admission/start winner
   and reads distinct content effects through Sling's own JSON servlet. A further case blocks a
   response after the effect, kills that node, requires an actual transport exception, and resends
   through the survivor without a second effect. The recorded crash point is after content commit
   and before terminal bookkeeping; this does not claim the later terminal-finalization tasks.
6. Fixed the observer's JSON depth and made early contender failures surface immediately instead
   of being hidden by a barrier timeout. Barrier cleanup releases paused requests before joining
   their executor. Static-analysis and formatting findings in the test driver were corrected
   without rule changes or suppressions.

Validation:

- Original save-race regression run: 19 tests, four expected failures.
- First repair plus the refresh regressions: 24 tests, three expected failures.
- Final focused store/admission suite: 24 tests, zero failures/errors/skips.
- Final shared-store proof: `CrashConsistencyScenario` passed both tests, including forced
  competing counter/admission/start saves, independently observed effects, actual response loss
  after a node kill, and a resend through the survivor without another effect.
- Full argument-free `scripts/quality`: passed on 2026-09-05 at 17:38 CEST; 868 core tests and
  459 interop tests passed with no failures/errors/skips, and all declared coverage and policy
  stages passed. The owner-supplied AEM and sibling-client tiers were not run by this gate.
- Compared all 1,269 production class files in the proof bundle byte-for-byte with the built
  core jar; all matched. Scanned the customer container and nested bundles/packages: neither the
  test servlet nor the interleaving helper is shipped.
- Re-read the final implementation against each of the three task steps and the done criterion;
  task 4102 is complete. No admission/terminal/runtime-assembly task is implied complete by it.
- Current logs are `interop/target/plan10-4102-core.log`,
  `interop/target/plan10-4102-refresh-red.log`, and `interop/target/plan10-4102-quality.log`.
  The JVM's temporary directory is set to `interop/target/runtime-tmp` because the host's `/tmp`
  quota was exhausted. The gate still takes no arguments and uses the existing prepared inputs.
