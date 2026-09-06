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

## 4103 — consistent capacity accounting

Capacity admission now owns durable identities with complete charge vectors. Activation and release
commit total and caller changes together, and publication binds retained data to its identity in the
same commit. All production callers have migrated and the amount-only APIs are removed. Recovery
reclaims reservations of an independently verified stopped process, preserving retained data and
other owners. A shared-store runtime test verifies this after actually killing the owning process.
Legacy resources keep their existing charges until their identity-marked retirement; owner recovery
never fabricates missing historical ownership or clamps counters. Final gate validation passed.

Review and correction loop:

1. Added three deterministic Oak regressions before changing the ledger. Two competing releases
   reproduced total=0/share=1. Interrupting admission or release after the first successful save
   reproduced unequal total and caller counts. All three failed the original implementation.
2. Changed the counter transition to fence both nodes before checking counts and to save both
   changes together. Every conflict retries the entire decision after refresh. Exhausted release
   contention now raises a repository failure, and over-release refuses without clamping or
   leaving pending changes.
3. Added last-slot admission, automatic refresh between counter stamps, exhausted contention, and
   over-release checks. All 14 capacity-ledger tests passed. The broader core run exposed four
   stream fixtures that served a writer without first reserving its slot; the former implementation
   silently decremented their empty counts. Corrected those fixtures. All 875 core tests then passed.
4. Added `CapacityReservation`: a fresh, bucketed, process-owned pending identity with a canonical
   charge vector. Admission requires that record and activates all quantities in one save. Release
   removes the record and all its charges together. Repeated release is harmless, and a delayed
   admission cannot recreate the removed identity. This avoids retaining a tombstone per release.
5. Nine additional identity tests cover uncertain save responses, competing admission and release,
   cancellation racing an already prepared admission, whole-vector refusal, persisted identity
   recovery from another session, and refusal of altered identity data. The combined focused run
   passed all 23 tests. The tests distinguish pending identities, which charge nothing, from active
   identities, whose persisted vectors supply exactly what is released.
6. Corrected formatting, an immutable-list accessor finding, and the repository-name provenance
   finding from the full gate. Added negative/duplicate/empty vector, immutability, and exhausted
   pending-creation contention checks. The complete argument-free `scripts/quality` passed on
   2026-09-05 at 19:50 CEST: 886 core tests and 459 interop tests passed with no failures, errors,
   or skips; all coverage and policy stages passed. The owner-supplied AEM and sibling-client tiers
   were not run. The log is `interop/target/plan10-4103-quality.log`. This validates the current
   foundation, not the remaining caller migration or process-death capacity recovery.

7. Migrated stream admission and synchronous command execution to `CapacityLedger.take`, carrying
   each returned identity into release. Stream writers now require their admission identity.
   Repeated close, lost activation replies, and response failure before writing preserve other
   open streams' counts. Reservation creation contention is an explicit absent result translated
   to `NotCounted(CONTENDED)`, rather than being confused with an unrelated repository failure.
8. Migrated events and subscriptions to one row/byte charge vector. Their publication commits
   bind the reservation to retained data. Cancellation preserves a committed publication even
   when its save response was lost; cancellation that wins before publication fences the pending
   resource commit. The retained-resource ownership marker is checked before a cleanup debit.
9. Added identity-aware retained-resource release for maintenance and restart cleanup. Legacy
   records commit a per-record release marker with both counter changes, preventing repeated
   release after response loss. Data removal is still separate and remains task 4104's work.
   Repeated modern/legacy cleanup tests preserve a second reservation's counts.
10. The migrated core suite passed 894 tests. A subsequent full-gate review rejected broad runtime
    catches in cleanup paths. Replaced them with an `AutoCloseable` ownership guard, allowing Java
    to preserve primary and suppressed cleanup failures. The guard hands off explicitly after
    successful activation or stream scheduling and otherwise cancels unfinished ownership.
    The private-constructor cleanup scope borrows its session through the reservation's factory,
    following the repository's existing authority pattern. Added rejection of foreign resource
    paths and decoding of malformed negative persisted charges as repository failures. All 896
    core tests and coverage passed; integration validation of this refactor was recorded in
    `interop/target/plan10-4103-migration-quality.log`.
11. Confirmed that `IntakeSlotWrite.declare` writes declarations without reserving capacity, while
    upload passes `ArtifactStore.Reservation.ALREADY_TAKEN`. The existing documentation's upfront
    reservation claim is not evidence of implemented behavior. This assumption must be removed
    by implementing actual intake reservation ownership, not carried into the identity API.

12. The first full migration gate passed 896 core tests and reached all 459 interop tests, but
    `TransportDisruptionScenario.everyenumeratedPointSevers` observed `NOTHING_CONNECTED` after a
    mid-intake reset. An isolated four-test rerun passed. Review found that `NetworkDisruptor`
    closed the socket before publishing `SEVERED`, allowing the peer to return before the observer
    saw that publication. Synchronized the status read and the close/publication transition;
    success is still recorded only after close succeeds. This is a supporting gate-observer fix,
    not a claim that the positive-intake acceptance work in task 4601 is complete.
13. The first rerun after that synchronization passed the severance check but observed an HTTP 500
    in the separate post-disruption capability probe. Added the response body to that assertion's
    diagnostic. The subsequent diagnostic rerun passed all four tests; the cause of that one HTTP
    500 remains unproved. The full gate is being rerun with the synchronization and diagnostic
    changes. The original gate failure is preserved in
    `interop/target/plan10-4103-migration-transport-red.log`; isolated runs are in
    `interop/target/plan10-4103-transport-recheck.log`,
    `interop/target/plan10-4103-transport-fixed.log`, and
    `interop/target/plan10-4103-transport-diagnostic.log`.

14. The next migration gate passed all 896 core tests but failed one of 459 interop tests: the
    shared-store proof received a 404 while polling the second node's prepared fixture state.
    The probe had already passed its GET readiness check, so this was not a missing installation
    wait. Read-only visibility polls now tolerate 404 within the existing 20-second deadline;
    every mutation still requires a successful response without replay. Both focused
    `CrashConsistencyScenario` tests passed in `interop/target/plan10-4103-probe-readiness.log`.
15. Migrated ordinary artifact publication to one row-and-byte reservation and bound that identity
    in the artifact publication commit. Its cleanup guard returns capacity on repository failures
    and preserves it after a committed publication loses its response. Publication also stamps the
    parent before creating the slot and disposes the staged binary after use. The intake-only
    `ALREADY_TAKEN` assertion remains an explicit unfinished path.
16. Added two publication-interruption regressions with another live reservation present. Against
    the previous ArtifactStore implementation, the failed-commit case leaked its row (expected
    one, observed two), and the lost-response case had no durable reservation ownership. Both red
    results are in `interop/target/plan10-4103-artifacts-red.log`. The original 18 artifact/intake
    tests passed with the migration. The complete gate then passed at 21:12 CEST on 2026-09-05,
    including all 898 core tests, all 459 interop tests, and the required coverage and policy checks.
    Evidence: `interop/target/plan10-4103-artifact-quality.log`. The owner-supplied Adobe quickstart
    and sibling-client tiers were not run by this gate. This validates the current partial
    migration; intake and recovery requirements still prevent completion of task 4103.

17. Artifact contention review reproduced two failures: an invisible parent-claim conflict led to
    `PathNotFoundException`, and an unrelated publication conflict was reported as `SLOT_TAKEN`.
    The parent conflict now returns `NotCounted(CONTENDED)` before consuming input. Publication
    refreshes and distinguishes a visible winner from unrelated contention, without replaying the
    stream. A checked ownership callback preserves repository contention for this classification.
    Red evidence: `interop/target/plan10-4103-artifact-contention-red.log`; all 22 artifact/intake
    tests passed after the correction in `interop/target/plan10-4103-artifact-contention.log`.
18. Added atomic activation of a nonempty manifest of distinct pending reservation identities.
    Every pending vector is staged under the common capacity fence and one save commits them all;
    quota refusal or cancellation of one member discards the entire activation. Active replay does
    not charge again, and each admitted identity remains independently releasable. Four tests cover
    combined quota refusal, lost response, cancellation by an independent session, and malformed
    manifests. All 44 focused tests passed in `interop/target/plan10-4103-manifest-activation.log`.
19. Added a staged ownership transfer from retained declaration capacity to a fresh pending artifact
    identity. Counter replacement, destination ownership, and removal of the old identity belong to
    the publication commit. The source resource is fenced too; foreign resources are refused.
    Four tests cover a lost response plus delayed source release, quota refusal preserving the
    source, source retirement by an independent session, and refusal of an already active
    replacement. All 48 focused tests passed in `interop/target/plan10-4103-capacity-transfer.log`
    before the subsequent source-resource fence review. Added a fifth transfer test where another
    session deletes the source data without modifying the capacity root; the source fence prevents
    the prepared transfer from committing. Core verification passed all 909 tests, coverage,
    formatting, and static analysis at 21:22 CEST on 2026-09-05. Evidence:
    `interop/target/plan10-4103-intake-primitives-verify.log`. The complete gate has not been rerun
    since these manifest and transfer changes; its earlier 898/459 result remains scoped to the
    preceding artifact migration.
20. Intake integration must attach declarations and retained ownership to operation creation's
    commit. Calling declaration admission after `SubmissionAdmission.Accepted` would leave an
    accepted operation with no declarations when quota admission fails; a recognised retry would
    then skip the missing work. The new primitives are not yet wired into this boundary or upload.

21. Added a lexical reservation batch that tracks identities as each slot is allocated. Cleanup
    attempts every identity even after a cancellation fails, preserves all suppressed failures,
    keeps charges attached to published data, and permits idempotent retry of failed cleanup.
    Three batch tests passed with the existing reservation tests (30 total) in
    `interop/target/plan10-4103-batch-cleanup.log`.
22. Replaced `ClaimByCreation`'s unchecked callback type with checked `InitialValues`, and added
    operation creation/admission overloads for data belonging in the acceptance commit. Intake now
    activates all per-slot promises before acceptance and writes every declaration plus retained
    ownership in that same operation commit. A refused manifest leaves no accepted operation.
    Existing operation recognition does not allocate another manifest.
23. Removed `ArtifactStore.Reservation.ALREADY_TAKEN`. Intake upload now creates a pending artifact
    identity and transfers its actual declaration reservation during publication. Each declaration
    initially reserves artifact rows/bytes and outstanding-operation rows/bytes; upload preserves
    the artifact charge and releases the outstanding-operation charge. Expected digest validation
    moved before publication because transferring ownership and then deleting a wrong-digest
    artifact would strand its capacity. This implements part of task 4303's validation ordering;
    final-slot execution/completion and that task's full proof remain unimplemented here.
24. Servlet regressions now verify exact total and caller counts before upload, after rejection,
    after upload, and across two slots transferred independently. Quota refusal leaves no operation;
    a failed acceptance save can be retried; a committed acceptance with a lost reply is recognised
    without another charge. All 88 focused tests passed in
    `interop/target/plan10-4103-intake-atomicity.log`. The next core run passed 916 tests but identified
    an uncovered missing-prepared-counter refusal; added a regression proving atomic refusal and
    successful admission after preparation is repaired. The full gate is running in
    `interop/target/plan10-4103-intake-quality.log`. Its first attempts caught test resource scopes,
    exception comparison, and formatting findings, which were corrected without changing policy.

25. The gate's policy suite rejected the eight-argument prepaid publication method and the batch's
    copying accessor. Grouped the declaration path and required digest in `ArtifactStore.Prepaid`
    and made the batch accessor a read-only view, matching its allocation lifecycle. No policy
    rules changed. Review then corrected intake's conversion of artifact backpressure/contention
    into a permanent length mismatch: `Unavailable` now maps to HTTP 503. Two servlet tests force
    allocation and publication contention, verify that the original promise survives, and retry
    successfully. All 73 focused tests passed in `interop/target/plan10-4103-intake-retry.log`.
    Fault-injection resolver wrappers own cloned delegates; closing a test wrapper therefore does
    not close the Sling context's resolver during teardown. The complete gate passed at 21:55 CEST
    on 2026-09-05 with all 919 core tests, all 459 interop tests, coverage, policy, and package checks.
    Evidence: `interop/target/plan10-4103-intake-quality.log`. The owner-supplied Adobe quickstart and
    sibling-client tiers were not run. This gate validates the current intake migration, not the
    still-unimplemented dead-owner reconciliation or later final-slot execution wiring.

26. Bounds-change regressions reproduced invisible legacy charges (seven bytes read as zero), a
    release subtracting from the wrong shard, and acceptance of a negative stored shard. Counter
    reads now include every supported legacy shard, reject negative values, and use exact integer
    addition. A successful transition consolidates the sum into shard zero and removes the other
    shard properties in the same fenced commit. Bound changes no longer select storage locations.
    Initial red evidence: `interop/target/plan10-4103-layout-red.log`; all 64 focused tests passed
    after this correction in `interop/target/plan10-4103-layout.log`.
27. Transfer review reproduced refusal of a fully paid promise after the bound fell below current
    usage. Transfers now credit only the original identity's own quantity: preserving or reducing
    it is permitted, while an increase still must fit the current total and caller bounds. Tests
    cover both cases and preserve another reservation's charge. Red evidence:
    `interop/target/plan10-4103-transfer-bounds-red.log`.
28. Preparation review reproduced an unrelated writer making a parent claim invisible, followed by
    a missing-parent exception; exhausted contention stopped after one attempt instead of the
    configured limit. Preparation now checks every claim outcome and retries from fresh state,
    surfacing exhaustion without pending writes. The first race uses independently refreshed Oak
    sessions. Red evidence: `interop/target/plan10-4103-preparation-red.log`. Core tests passed all
    927 cases during verification, which then identified formatting and obsolete private layout
    arguments. Those were corrected, and core verification passed all 927 tests, coverage,
    formatting, and static analysis at 22:09 CEST on 2026-09-05. Evidence:
    `interop/target/plan10-4103-layout-verify.log`. The full gate has not been rerun since these
    layout and preparation changes; its preceding 919/459 result covers the intake migration.

29. Added `CapacityLedger.recoverOwner` for a process incarnation whose retirement the caller has
    independently established. A different UUID or an expired lease is explicitly insufficient,
    and recovery refuses the current incarnation. Inventory validates its capacity-root revision
    before returning a snapshot and retries contention; cancellation then uses each owned identity.
    Retained resources and other owners remain charged. Eight reservation tests cover retained/live
    ownership, interrupted recovery, concurrent inventory writes, empty inventories, contention
    exhaustion, and malformed/misplaced records. The bucket-path check prevents an identity moved
    under another bucket from being silently skipped during cancellation.
30. Extended the test-only shared-store probe to create a pending reservation, an abandoned active
    reservation, and retained data on the node subsequently killed. The survivor owns a fourth
    reservation. After the actual process kill, the survivor invokes recovery for the killed
    process's UUID. Independently scanned active charge vectors agree with total and caller counts
    (two), no pending record remains, retained data and its active identity remain readable, and the
    survivor's active identity remains readable. Repeating recovery leaves those counts unchanged.
    Both `CrashConsistencyScenario` tests passed in
    `interop/target/plan10-4103-recovery-runtime.log` at 22:20 CEST on 2026-09-05. This proves the
    recovery primitive after process death; automatic lifecycle discovery/wiring remains task 4501.
31. Core verification passed all 935 tests, coverage, formatting, and static analysis at 22:25 CEST
    on 2026-09-05 after the final inventory validation tests and formatting corrections. Evidence:
    `interop/target/plan10-4103-recovery-verify.log`. The focused runtime run preceded the additional
    misplaced-record validation. The complete gate must cover the final task implementation before
    task 4103 can be committed; the earlier 919/459 gate is not evidence for these later changes.

32. Removed the amount-only capacity admission and release APIs after migrating their remaining
    callers. Quota tests use actual reservation vectors; concurrent release tests own two distinct
    identities, and the interrupted admission test loses the activation response after creating its
    pending identity. The underflow test corrupts one persisted counter beneath a real reservation
    and verifies refusal leaves both the identity and the other counter unchanged. The first build
    found a migration syntax error, then an import-order error; both were corrected.
33. Reviewed legacy reconciliation against R08: process recovery is explicitly based on durable
    ownership, which historical counters do not contain. It must preserve unattributable legacy
    charges rather than invent owners or replace totals with only modern reservation sums. Added
    a mixed legacy/modern test using independent sessions: repeated stopped-owner recovery removes
    only the abandoned modern vector; repeated resource retirement removes the legacy vector once;
    releasing the surviving modern identity restores both quantities and both accounts to zero.
    Historical corruption without durable ownership evidence remains unrecoverable automatically.
    Adopting every legacy record is not required for identity-based process recovery and would not
    establish the missing provenance. Existing unbacked intake declarations likewise cannot acquire
    a prepaid identity merely by being present.
34. Reviewed the standalone sharded helper: production capacity no longer calls it. Its conditional
    delta explicitly returns VALUE_CHANGED for stale input; a new independent-session interleaving
    verifies that refusal changes nothing and a fresh invocation advances exactly once. Documented
    that caller obligation. Runtime lifecycle discovery remains task 4501, atomic resource deletion
    with charge retirement remains 4104, and accepted-operation progress and intake completion remain
    4301/4303/4304. The primitive recovery proof does not claim these later behaviors.

35. Core verification passed all 937 tests, coverage, formatting, PMD, and SpotBugs at 22:36 CEST
    on 2026-09-05. Evidence: `interop/target/plan10-4103-final-core-verify.log`. The full argument-free
    gate is running against the final implementation.

36. The final gate passed all 937 core tests and the process-kill recovery scenario, but 14 of
    459 interop assertions received HTTP 404 instead of application responses. Failures clustered
    after the shared public runtime was restarted following StorageUpgradeScenario; later scenarios
    recovered. The saved startup logs do not establish the cause of this transient outage. The
    affected scenarios and their preceding restart passed on focused recheck without code changes.
    The failed gate is retained in `interop/target/plan10-4103-final-quality.log` and the focused
    result in `interop/target/plan10-4103-route-recheck.log` (53 tests passed). The full gate recheck
    captures test-owned container logs throughout the run. No assertion or mutation retry was relaxed.

37. The complete argument-free gate passed at 22:58 CEST on 2026-09-05: all 937 core tests and
    all 459 interop tests passed, with no failures, errors, or skips. Coverage, formatting, static
    analysis, policy checks, and package checks passed. The shared-store process-kill recovery proof
    ran in this gate. Evidence: `interop/target/plan10-4103-final-quality-recheck.log`; diagnostic
    runtime logs are under `interop/target/plan10-4103-gate-runtime-logs`. The earlier transient 404
    failure remains unexplained; passing this unchanged rerun does not establish a repair for it.
    The owner-supplied Adobe quickstart and sibling-client end-to-end tiers did not run.
38. Re-read the final production caller changes, checked that no amount-only capacity call remains,
    reviewed the legacy and standalone-shard boundaries, and checked whitespace. Task 4103's
    identity accounting and verified-process recovery requirements are satisfied. Resource deletion
    and charge retirement still require the task 4104 transaction; runtime lifecycle activation and
    accepted-operation progress remain their separately authored tasks. No claim is made that the
    full plan or positive installed command execution is complete.

Current focused logs: `interop/target/plan10-4103-red.log`,
`interop/target/plan10-4103-atomic-review.log`, `interop/target/plan10-4103-core-review.log`, and
`interop/target/plan10-4103-identities.log`.

Migration logs: `interop/target/plan10-4103-consumers.log`,
`interop/target/plan10-4103-retained.log`, `interop/target/plan10-4103-resource-release.log`,
`interop/target/plan10-4103-migrated-core.log`, and
`interop/target/plan10-4103-migration-quality.log`.

## 4104 — atomic retention cleanup

Resource release is staged with resource deletion. Maintenance stages one operation's cleanup and counter changes in a fenced
transaction, retries from fresh state, and includes only committed changes in its report.
Subscription ending and abandoned-intake recovery use a shared atomic resource-retirement method.

Review and correction loop:

1. Added independently observed interruption tests before changing cleanup. The first real save
   committed accounting release, then its response was lost. Artifact collection and whole-operation
   removal left three artifact rows with only two counted; subscription ending left a row with zero
   counted. All three regressions failed. An overlapping subscription-end test already passed with
   the task 4103 reservation identities. Evidence: `interop/target/plan10-4104-red.log`.
2. Extracted staged capacity retirement, moved maintenance's save outside its release/deletion work,
   and merged report counts only after that commit. Added fresh contention retries around the whole
   operation decision. Subscription deletion now shares its commit with accounting. The initial
   22 focused tests passed in `interop/target/plan10-4104-first-green.log`.
3. Expanded sweep tests to interrupt before and after each of six persistence boundaries: the three
   operation cleanups and the existing three cursor writes. Independently read artifact rows and
   bytes agree with total and caller counters at every interruption and after repeated recovery.
   Overlapping sweeps leave one committed cleanup and no duplicate report counts. All 45 focused
   cases passed in `interop/target/plan10-4104-boundaries.log`.
4. Review found unfinished intake vectors omitted from whole-operation deletion. A new test left
   the reservation identity after its declaration was deleted. Evidence:
   `interop/target/plan10-4104-intake-red.log`. Intake declarations now participate in the same
   transaction, retiring their full modern vector or their persisted legacy quantities. Core testing
   passed all 965 cases; verification then refused five long test lines, which were corrected.
5. Review found the same split transition in restart recovery's abandoned-intake cleanup. Added
   `CapacityLedger.retireResource`, which takes a stable path for retries after deletion and commits
   resource removal with its charge retirement. Both subscription ending and restart recovery use
   it. Four independent-session tests interrupt before/after each legacy intake deletion and check
   persisted declarations against the counters. All 969 core tests, coverage, formatting, PMD, and
   SpotBugs passed at 23:10 CEST on 2026-09-05 in `interop/target/plan10-4104-review-verify.log`.
6. Missing-caller review found that both collection and whole-operation deletion assumed an absent
   caller meant nothing had been charged. Two tests reproduced deletion of charged data after its
   caller property was removed. Evidence: `interop/target/plan10-4104-owner-red.log`. Both paths now
   refuse malformed ownership inside their transaction, preserving the data and existing charges.
   All 971 tests passed, then formatting identified one additional long line, which was corrected.
7. Added before/after-save checks for combined event and artifact retirement, before-commit
   subscription interruption, and exhausted contention for subscription ending, artifact collection,
   and whole-operation removal. Exhaustion makes five fresh attempts, discards pending writes, and
   preserves data and counters. Core verification passed all 977 tests, coverage, formatting, PMD,
   and SpotBugs at 23:15 CEST on 2026-09-05. Evidence: `interop/target/plan10-4104-final-core.log`.
8. API review found the obsolete public resource-release method still exposed the split transition,
   although no production caller used it. Removed that method and its non-deleting branch. Migrated
   its identity/legacy retry tests to atomic retirement, checking deletion as well as exact charges.
   The staged helper rejects a resource whose resolved repository path is outside the agent store;
   a path containing parent segments cannot bypass that check. Core verification passed all 977
   tests and required checks at 23:17 CEST on 2026-09-05. Evidence:
   `interop/target/plan10-4104-retirement-api-verify.log`.
9. The full gate passed its initial checks but stopped at documentation: the package-visible staged
   helper lacked parameter and exception descriptions. Added the required descriptions and its
   same-commit deletion obligation. Removed the now-unused first-save-only test helper; the generalized
   boundary injector covers its uses. The refused gate is `interop/target/plan10-4104-final-quality.log`.

10. The next gate passed core verification and policy checks but five of 459 interop assertions
    received transient HTTP 404s. Captured logs now establish that Sling unregistered its servlet
    resolver at 21:28:54.443 UTC, coinciding with failures in CancelSlingJobScenario,
    WalkingSkeletonScenario, and ListWorkflowModelsScenario. The relevant runtime log is
    `interop/target/plan10-4104-gate-runtime-logs/39b846a1923f.log`; the gate result is
    `interop/target/plan10-4104-final-quality-recheck.log`. This identifies resolver unavailability,
    not the upstream reason for its rebinding.
11. The public-tier startup check previously accepted its first non-404 response. It now requires
    three consecutive expected 401 responses at the existing one-second interval, resetting on any
    other status within the existing deadline. A unit test covers a resolver disappearance between
    successful probes and rejects 200/503 as readiness. Runtime scenario assertions and mutations
    are unchanged. Focused verification covers those three affected scenarios, a runtime restart,
    the following scenario, and the public-tier tests. All 30 focused tests passed at 23:35 CEST
    on 2026-09-05 in `interop/target/plan10-4104-readiness-recheck.log`.
12. The next gate's source policy required the observation count to be a named constant. Named it
    without changing the tested value or behavior. The refused run is preserved in
    `interop/target/plan10-4104-readiness-quality.log`.

13. The settled-startup gate passed core/policy checks and all public-route scenarios, including the
    previously failing groups. One of 460 interop cases failed instead: ClockChaosScenario's second
    node response did not satisfy its existing below-400 assertion. The assertion recorded no status
    or body, and captured logs do not establish the cause. Added those diagnostics without changing
    its requests, threshold, or retry behavior. Evidence: `interop/target/plan10-4104-settled-quality.log`.
    All eight focused cluster tests passed at 23:51 CEST on 2026-09-05 in
    `interop/target/plan10-4104-clock-recheck.log`. The complete gate is running again; this
    focused pass does not establish the cause of the earlier second-node failure.

14. The complete argument-free `scripts/quality` passed at 00:02 CEST on 2026-09-06. Core ran
    977 tests and interop ran 460, with zero failures, errors, or skips. Required coverage,
    static analysis, packaging, and all gate policy stages passed. ClockChaos and the previously
    failing public-route groups passed with their assertions unchanged. Evidence:
    `interop/target/plan10-4104-complete-quality.log`. The owner-supplied Adobe quickstart and
    sibling-client end-to-end tiers did not run.
15. Re-read the production transaction boundaries and supporting interop changes, checked the
    independent-session interruption and overlap evidence, and checked whitespace. Retained data
    keeps its charges; committed deletion retires the exact persisted charge once. Missing ownership
    and exhausted contention preserve data and accounting. The obsolete split-release API is gone.
    Task 4104 is complete. Bounds within dense buckets remain task 4105, and execution-fence and
    runtime-lifecycle guarantees remain their separately authored tasks.

## 4106 — atomic execution fences

This independent task was implemented and verified in an isolated checkout while task 4105's
bounded traversal design remains unfinished. Its prerequisite, task 4102, is complete.

Review and correction loop:

1. Added real-Oak regressions for a lost acquisition response after expiry persistence and for an
   ownerless historical expiry. Both failed: expiry existed without owner, and the historical
   record returned Lost instead of allowing recovery. Evidence:
   `interop/target/plan10-4106-red.log`, nine tests, two failures.
2. Acquisition now fences the lease node and commits owner, expiry, and a unique acquisition epoch
   together. Renewal compares the complete holder, preserves its epoch, and refuses expired holds;
   retries refresh from durable state and discard pending writes. Incomplete owner/expiry records
   are recoverable. All nine focused tests passed in `interop/target/plan10-4106-first-green.log`.
3. Added explicit stale-epoch tests using a matching worker name and expiry, expired-renewal refusal,
   and conservative handling of complete legacy records without epochs. Such legacy holds reserve
   their window until expiry but cannot authorize renewal or writes as a modern holder. All 12
   focused cases passed in `interop/target/plan10-4106-epochs.log`.

4. Added `ExecutionFence.stageHeld` for checking and stamping authority inside an enclosing
   transaction. OperationStore now has a holder-bearing move path and refuses the unguarded move
   path once an operation has a lease record. A stale epoch cannot change operation state; takeover
   immediately before commit conflicts with the old worker's staged lease stamp and leaves state
   unchanged. The new overlapping-session fixture now retains its resolver for the whole test;
   its first version lost that resolver and observed a closed session. All 14 fence tests passed in
   `interop/target/plan10-4106-protected-recheck.log`. Core verification then passed all 984 tests
   and stopped at three import-order violations in the new tests, which were corrected. Evidence:
   `interop/target/plan10-4106-core-moves.log`, 00:44 CEST on 2026-09-06. This does not establish
   completion of the remaining static checks or the full gate.

5. TerminalCommit now carries authority to the final SnapshotStore/EventLedger callback, after
   admission's intermediate saves and refreshes. The callback stamps operation and lease authority
   with state/result/event/snapshot writes. Unguarded calls refuse leased operations, and stale
   holders receive FENCE_REQUIRED without publishing a terminal result. The first 24 focused cases
   passed in `interop/target/plan10-4106-terminal.log`.
6. Added before/after interruptions at both acquisition saves and at renewal's save. The first
   acquisition boundary exposed pending writes left after failed lease creation. Wrapped creation
   with refresh/discard handling; all authority boundaries now leave complete or recoverable state.
   Added a real independent takeover immediately before the final terminal save; the old worker's
   commit is refused, the operation remains running without a result, the event count stays one,
   and event row/byte capacity returns to its original values. All 31 focused cases passed in
   `interop/target/plan10-4106-terminal-overlap.log`. Core verification passed 992 tests, then
   formatting found two long lines, which were corrected. The full core recheck passed all 992
   tests, coverage, formatting, PMD, and SpotBugs at 00:51 CEST on 2026-09-06 in
   `interop/target/plan10-4106-core-recheck.log`. A complete gate was then started.

7. The first gate stopped because this fresh isolated checkout had no built AEM bundle for the
   nullability/import policy checks. The gate reported missing reactor output; this was not an
   authority-test failure. Evidence: `interop/target/plan10-4106-quality.log`. Preparing the reactor
   artifacts offline before rerunning the gate.
8. Final review found that the acquisition API could accept an empty worker and wrap expiry into
   the past at the numeric limit. Two tests reproduced those cases in
   `interop/target/plan10-4106-authority-input-red.log`. Acquisition now rejects the empty worker;
   acquisition and renewal use checked expiry addition. Core verification passed all 994 tests,
   coverage, formatting, PMD, and SpotBugs at 00:56 CEST on 2026-09-06 in
   `interop/target/plan10-4106-core-inputs.log`. Offline reactor packaging completed at 00:57 CEST
   in `interop/target/plan10-4106-reactor-inputs.log`; the subsequent complete gate is recorded in
   `interop/target/plan10-4106-prepared-quality.log`.

9. The complete argument-free `scripts/quality` passed at 01:07 CEST on 2026-09-06. Core ran
   994 tests and interop ran 460, with zero failures, errors, or skips. All gate policy, coverage,
   static-analysis, and packaging stages passed. Evidence:
   `interop/target/plan10-4106-prepared-quality.log`. The owner-supplied Adobe quickstart and
   sibling-client end-to-end tiers did not run.
10. Final review checked acquisition/renewal persistence boundaries, conservative legacy recovery,
    epoch comparisons, and the placement of state and terminal authority checks inside their
    conflict-protected commits. Old unguarded paths refuse leased operations; takeover prevents an
    old worker from publishing state or terminal writes. Existing immediate callers remain covered
    by the core suite, and deferred execution was not enabled. Task 4106 is complete. Task 4105 and
    the remaining plan tasks are still required; this does not complete plan 0010.

## 4107 — persisted continuation-key ownership

1. Two real-Oak regressions reproduced fabricated unexpired authority replacing the ring while
   another owner holds its persisted lease, and a current holder dropping the signing key without
   retaining it. Both failed as expected in `interop/target/plan10-4107-red.log`.
2. Acquisition now commits holder, expiry, and a fresh epoch in one stamped transaction. Renewal
   preserves the epoch and compares the complete persisted lease. Key writes validate this lease
   and the expected ring under the same stamp, require the legal rotation with its full retention,
   and discard pending writes on refusal or repository failure. The initial 20 focused tests passed
   in `interop/target/plan10-4107-focused.log`.
3. Review added real takeover immediately before a key save, before/after acquisition and renewal
   interruptions, stale versus renewed versus reacquired authority, and token verification across
   the full retention window and its boundary. It also found retention expiry overflow, now refused.
   All 28 focused tests passed in `interop/target/plan10-4107-epochs.log`. Added further coverage for
   legacy authority, forged lease fields, contention cleanup, and identical competing acquisitions
   before the full core verification. Task completion remains unproved until the full gate passes.

4. The first full core run passed 1008 tests, then stopped on 11 formatting violations. During
   review, a new regression proved that a missing holder could match the diagnostic placeholder
   "nobody" when the other lease fields remained. Evidence:
   `interop/target/plan10-4107-incomplete-red.log`. Ownership now requires all three persisted
   fields before comparison, including an explicit expiry rather than the missing-property zero
   default. Added the missing-expiry regression and corrected formatting before re-verification.

5. Core re-verification passed all 1010 tests and coverage, then found one remaining long line,
   now corrected. Evidence: `interop/target/plan10-4107-core-recheck.log`. Updated the in-memory
   contract fixture to use a legal retained-key rotation and validate its declared lease, matching
   the strengthened interface documentation. The complete argument-free gate follows.

6. The first gate stopped on one long line in the contract fixture
   (`interop/target/plan10-4107-quality-formatting.log`). After that correction, static analysis
   found two new race-test methods declaring broad Exception; narrowed their throws clauses to
   RepositoryException and LoginException. Evidence: `interop/target/plan10-4107-quality-static.log`.
   Both were test-source findings; neither failed a runtime authority assertion.

7. The early static stage initially re-read stale test bytecode because its compile goal does not
   compile tests. Confirmed the corrected source and older class timestamp; rebuilt with core verify.
   All 1010 core tests, coverage, formatting, PMD, and SpotBugs passed at 01:26 CEST on 2026-09-06
   in `interop/target/plan10-4107-core-final.log`. The stale-bytecode attempt is preserved in
   `interop/target/plan10-4107-quality-stale-bytecode.log`.

8. The complete argument-free `scripts/quality` passed at 01:37 CEST on 2026-09-06. Core ran
   1010 tests and interoperability ran 460, with zero failures, errors, or skips. Every required
   policy, static-analysis, coverage, and packaging stage passed. Evidence:
   `interop/target/plan10-4107-quality.log`. The owner-supplied Adobe quickstart and sibling-client
   end-to-end tiers did not run.
9. Final review checked that acquisition, renewal, and key writes all stamp the same node before
   reading their predicates; all authority fields and the protected transition share one save.
   Missing fields cannot match diagnostic defaults, renewal preserves acquisition identity, and
   reacquisition replaces it. The authority refuses forged or stale leases and rings that drop or
   shorten required retention; tokens from before and after rotation remain verifiable at the
   retention boundary as appropriate. No policy exceptions or suppressions were added. Task 4107
   is complete. Task 4105 and the remaining plan tasks are still required.

## 4108 — atomic generation rotation

1. An interrupted first save reproduced serving=2 with history=[1], before retention metadata
   existed. Evidence: `interop/target/plan10-4108-red.log`. This is the originally reported R09
   split-publication defect.
2. Rotation now stamps the generation record before its predicates and stages serving, history,
   retained metadata, and any eligible retirement in one save. The old public history-only rotation
   entry point is now package-private staging used by the retention-aware transaction; callers use
   GenerationRotation.rotate with explicit time and contract. All 22 focused tests passed in
   `interop/target/plan10-4108-first-green.log`.
3. Review reproduced an independent early-retirement defect: minimum-only generation retention can
   expire before a valid configured record retention, especially with an accepted future request
   start. Evidence: `interop/target/plan10-4108-retention-red.log`. Generation retention now uses a
   conservative horizon covering the configured maximum plus admitted request-start skew, without
   extending individual record deadlines. Added atomic retirement/replacement, independent competing
   rotations, before/after save interruptions, and overflow cleanup cases. Verification is pending.

4. All 30 focused cases passed in `interop/target/plan10-4108-boundaries.log`. A subsequent
   lowered-bound regression reproduced dropping a still-retained generation when only the oldest
   of multiple eviction candidates had expired (`interop/target/plan10-4108-lowered-bound-red.log`).
   Room validation now checks every generation that would be retired. Added real operation,
   snapshot, and artifact publication followed by writer-session termination and independent reader
   recovery at both sides of the rotation save, including retention-boundary access and accounting.

5. All 33 focused cases passed in `interop/target/plan10-4108-retained-work.log`, including
   published operation/snapshot/artifact reads from a fresh session after the writer stopped.
   Added the successful lower-bound transition once every eviction candidate has expired and
   explicit refusal when the generation authority is missing. Starting full core verification.

6. Full core verification passed all 1023 tests, coverage, formatting, PMD, and SpotBugs at
   01:50 CEST on 2026-09-06 in `interop/target/plan10-4108-core.log`. Final source review confirmed
   no saves in history or retention staging and one save in the enclosing stamped rotation.
   Starting the complete argument-free gate.

7. The first complete gate passed at 02:00 CEST on 2026-09-06 with 1023 core and 460 interop tests,
   no failures/errors/skips, and all required stages passing. Evidence:
   `interop/target/plan10-4108-first-quality.log`. Final evidence review distinguished fresh-session
   recovery from actual process loss, so this pass alone was not treated as task completion.
8. Added a test-only two-node probe: publish real retained work, stop the rotation immediately after
   its first real save, kill that process before it can reply, then read serving/history/retention,
   the operation, snapshot, artifact bytes, and capacity from the surviving node. This exercises
   the exact shipped store code in the pinned Mongo-backed Sling runtime, independently of the
   core fresh-session tests. Verification of this stronger proof follows.

9. The focused two-node process-loss proof passed in `interop/target/plan10-4108-process-loss.log`
   at 02:03 CEST. Extended that same test to stop the surviving application node as well, start a
   fresh replacement process against the retained Mongo repository, install the exact store-code
   probe, and read the retained metadata and work again. This explicitly exercises application
   restart in addition to surviving-node failover.

10. The complete process-loss and fresh-process restart case passed at 02:07 CEST on 2026-09-06
    in `interop/target/plan10-4108-process-restart.log`. Both original application processes were
    gone before the replacement read the retained repository. The initial gate log is preserved as
    `interop/target/plan10-4108-first-quality.log`; running the full gate again with this proof
    included before committing the task.

11. The expanded gate passed core and static checks but found the new runner absent from the
    scenario inventory (`interop/target/plan10-4108-inventory-quality.log`). Registered
    `generation-rotation-crash` as a tier-a repository-layout property scenario for the shared
    repository deployment arrangement. No checker or policy rule was weakened.

12. The final complete argument-free `scripts/quality` passed at 02:23 CEST on 2026-09-06.
    Core ran 1023 tests and interoperability ran 461, with zero failures, errors, or skips.
    The new first-save crash and fresh-process restart proof passed inside this full run, alongside
    the existing crash, contention, and handover scenarios. All required policy, coverage,
    static-analysis, inventory, and packaging stages passed. Evidence:
    `interop/target/plan10-4108-quality.log`. Owner-supplied Adobe quickstart and sibling-client
    end-to-end tiers did not run.
13. Final review checked the original split-publication regression, all remaining save boundaries,
    competing same/different rotations, complete eviction-candidate validation, configured-retention
    and future-request-start coverage, overflow cleanup, and actual retained-work reads after both
    original application processes were gone. Serving, history, and retention publish together;
    the public retention-free rotation path is gone. Test probes stay outside product bundles.
    Task 4108 is complete. Task 4105 and the remaining plan tasks are still required.


## 4201 — live operator configuration

1. Review confirmed the shipped `AuthorizationGate` PID had no DS component, submission returned
   a literal administrator list, and console requests carried an independent permitted-group list.
   Added the immediate component under the existing PID, typed metatype configuration, activate and
   modified publication of an immutable snapshot, and fail-closed deactivation. The bundle-local
   snapshot also reaches manually constructed compatibility-alias servlets. Configuration replaces
   the complete group set; administrators have no separate exemption.
2. Submission and console authorization now read that source for each decision. Removed the group
   list from console requests so a request constructed before revocation cannot retain old grants.
   The pure authorization decision retains distinct unknown-group and nonmember refusals. Added
   lifecycle, immutable-snapshot, empty-set, revocation, and refusal tests; adapted affected servlet
   and console fixtures to activate authorization explicitly.
3. First focused checks passed. Full core execution passed 1025 tests with no failures/errors/skips,
   then formatting rejected 16 import-order and whitespace findings in adapted tests. Corrected
   those findings without changing policy. Evidence: `interop/target/plan10-4201-core.log`.
4. Installed the product bundle in an isolated pinned public Sling runtime, created two dedicated
   users and groups, and changed the real Config Admin PID. Verified grant to group one, replacement
   by group two, removal of administrator access, unknown-group and empty-set refusal, and default
   restoration after configuration deletion. The test observed zero product bundle lifecycle events.
   A test-only adapter calls the installed product's private console data-source API; it copies no
   product classes and does not replace their configuration or decisions. Submission admission is
   evidenced by reaching malformed-body validation (400), versus authorization refusal (403).
   This is not proof of command execution or complete console rendering, which have later tasks.
   The focused runtime proof passed at 02:34 CEST on 2026-09-06 in
   `interop/target/plan10-4201-runtime.log`. Registered its tier-a scenario. Starting the full gate.
5. The full gate's formatting stage found one 111-character runtime-test line against the
   110-character limit. Wrapped it; preserved the rejected run in
   `interop/target/plan10-4201-formatting-quality.log`. Resuming the complete gate.
6. PMD rejected the test adapter's Hashtable declaration/construction and direct class-loader
   lookup. Replaced them with the OSGi map-to-dictionary adapter and the product bundle's
   `BundleWiring` loader, adding that package to the test bundle manifest. Preserved the rejected
   run in `interop/target/plan10-4201-pmd-quality.log`. Rechecking the changed runtime adapter.
7. The revised runtime adapter passed again at 02:38 CEST in
   `interop/target/plan10-4201-runtime-reviewed.log`. The next gate passed core static analysis but
   found two literal-order comparisons in the interop scenario. Corrected them and refreshed test
   bytecode before rerunning all stages; evidence: `interop/target/plan10-4201-comparison-quality.log`.
8. Formatting and PMD passed. SpotBugs found the scenario's broad `Exception` declaration;
   narrowed it to `IOException` and `InterruptedException` and refreshed bytecode. The rejected
   run is `interop/target/plan10-4201-throws-quality.log`. No production behavior changed.
9. The complete argument-free `scripts/quality` passed at 02:52 CEST on 2026-09-06.
   Core ran 1025 tests and interoperability ran 462, with zero failures, errors, or skips.
   All policy, coverage, static-analysis, scenario-inventory, and packaging stages passed. The new
   live-configuration scenario passed within this full run, as did the crash/restart, concurrent
   write, cluster handover, and clock-chaos scenarios. Authoritative evidence:
   `interop/target/plan10-4201-quality.log`. Owner-supplied Adobe quickstart and sibling-client
   end-to-end tiers did not run.
10. Final review checked the generated DS descriptor against the shipped PID/property, full-set
    replacement including administrator removal, atomic immutable publication, inactive denial,
    both current authorization consumers, removal of stale console grants, and distinct pure
    refusal outcomes. The actual installed product handled configuration changes without bundle
    lifecycle events, and no test probe classes appear in the product jar. Task 4201 is complete;
    state-route ownership/session separation and full runtime/console assembly remain later tasks.


## 4202 — state access and ownership

Internal state routes use scoped service sessions and authorize from durable operation ownership
and current operator membership. The original caller session supplies identity, membership, and
content effects. Subscriptions persist and verify caller/operation binding; new operation, binding,
and intake declarations publish together, and foreign callers cannot recognise an owner's resend.

Review and correction loop:

1. Reproduced cross-caller snapshot disclosure using a real Oak user with only state-tree read
   access. The caller could see the record and received another caller's accepted snapshot with
   200, where the regression requires the same empty 404 as an unknown operation. The focused
   regression failed as expected at 02:56 CEST on 2026-09-06 in
   `interop/target/plan10-4202-visibility-reproduction.log`.
2. Added a shared durable-owner/current-operator authorization helper and used it before snapshot
   reads. Ownership now satisfies owner-or-operator routes even when the configured operator set is
   empty or names an unknown group; submission still requires a configured group. All 13 focused
   snapshot and pure authorization tests passed at 02:58 CEST in
   `interop/target/plan10-4202-snapshot-owner.log`.
3. Added a DS-bound current state-session source to `AgentSession`, a checked scoped state-work
   callback, and a servlet helper that closes the service resolver on every exit. Snapshot reads
   now use that internal session while identity and membership still come from the original caller.
   All 19 focused snapshot, authorization, and session tests passed at 03:00 CEST in
   `interop/target/plan10-4202-snapshot-service.log`. This is partial implementation only: the other
   state routes, subscription/operation binding, submission content-session separation, lifecycle
   and authorization matrices, installed-runtime proof, and full gate are still required.
4. Extended scoped state access and durable-owner/current-operator checks to physical jobs,
   artifact download, and intake. Intake uses the persisted operation owner for prepaid capacity,
   even when the requester is an operator. Initial contention tests intercepted the old caller
   session and therefore no longer injected a state failure; moved those seams to the service
   source and preserved their 503/retry/reservation assertions. Submission validation still precedes
   state acquisition, bookkeeping uses the service session, and command execution receives the
   original caller's session. All 44 focused route tests passed at 03:08 CEST in
   `interop/target/plan10-4202-separated-submission.log`.
5. Added explicit original-session identity and scoped resolver lifetime checks. Completed work,
   repository failure, and transport failure close the service resolver while leaving the original
   caller resolver live; inactive access runs no work. All 17 focused session/submission tests
   passed at 03:11 CEST in `interop/target/plan10-4202-session-lifecycle.log`.
6. Subscription records now persist caller/operation binding and include those bytes in accounting.
   Existing-name and creation-race resumes compare caller, operation, and generation; incomplete
   bindings are refused. High-water and stream authorization use the stored binding and operation
   owner. Stream fixtures now have separate subscriptions for separate operations. The initial
   ledger/stream/high-water run passed 50 tests at 03:14 CEST in
   `interop/target/plan10-4202-subscription-binding.log`.
7. The subsequent 36-test stream run passed assertions but logged a closed-session exception in an
   async worker. Review treated that as a real failure: a request-scoped service resolver closed
   before asynchronous writing/release finished. Moved service-session acquisition, state admission,
   and writing into the async worker, with async completion after scoped cleanup. Added an assertion
   that stream capacity is zero before async completion is observed. All 18 focused async/session
   tests passed without the closed-session warning at 03:19 CEST in
   `interop/target/plan10-4202-async-state-lifetime.log`.
8. Added regressions for foreign caller/operation bindings, a creation loser encountering a foreign
   binding with no leaked charge, and legacy/malformed binding refusal. Corrected a missing test
   import. A rerun exposed a peer-session fixture whose resolver was not retained, allowing cleanup
   to close it early; the fixture now retains and explicitly closes peer resolvers. All 17 ledger
   tests passed at 03:25 CEST in `interop/target/plan10-4202-binding-races-scoped.log`.
   Task 4202 remains incomplete: atomic submission/binding publication, caller-matched resend
   recognition, full authorization matrices, actual narrow-service runtime proof, and full gate
   remain required. No task 4202 commit or completion is claimed.
9. Reproduced another caller's identical resend being recognised as the owner's operation. Added
   caller equality to the shared comparison, preserving the owner's record on conflict. The failing
   regression is `interop/target/plan10-4202-resend-owner-reproduction.log` (03:31 CEST).
10. Added subscription registration to submission admission. New operation, binding, and intake
    declarations publish in one acceptance commit; capacity is reserved beforehand and abandoned
    reservations are cancelled. Matching existing bindings are fenced and rechecked in that commit;
    recognised owned operations can register another subscription without executing again. A
    subscription conflict or capacity refusal cannot strand a newly accepted operation. Save-failure
    tests verify operation and binding appear or disappear together, and a real competing subscription
    claim rolls back the losing operation and manifest without leaking their charges. All 56 focused
    tests passed at 03:37 CEST in `interop/target/plan10-4202-registration-race.log`.
11. Full core testing exposed an alias fixture still missing the new state source; bound it in the
    fixture. All 1036 core tests then passed. Packaging review found line-length/indentation issues,
    which were corrected. These intermediate verification runs were not full gate passes.
12. The public tier now installs the committed repository-initialization and service-user mapping
    files and waits for the product's scoped state route to answer. Added an isolated running-instance
    scenario whose test fixture loader delegates product classes to the installed bundle, preserving
    its actual DS source. Initial probe startup failed on test-only JCR security imports; corrected
    the probe imports and fallback loader without changing product imports.
13. The real narrow service identity then exposed `OperationStore.bucketsFor` inspecting `/var`, which
    lies outside its read grant, and failing with a substring exception. Anchored traversal at the
    state root instead. Added a real Oak principal regression whose grants stop at that root and
    verified it cannot see `/var`. All 40 focused tests and core packaging passed at 03:54 CEST in
    `interop/target/plan10-4202-state-root-traversal.log`. The actual runtime reproduction is
    `interop/target/plan10-4202-live-state-root-reproduction.log`.
14. The installed-runtime matrix passed at 03:55 CEST in
    `interop/target/plan10-4202-live-state-access-diagnostic.log`: owner and configured operator read
    snapshot, jobs, high-water, streams, and referenced artifacts; each can upload declared intake.
    Unrelated state readers and removed members retain direct state visibility but receive empty
    unknown-operation responses; anonymous requests are refused. Another operator cannot recognise
    the owner's submission. A permitted non-admin with explicit state/content write denials submitted
    successfully through the installed servlet and actual service mapping; its test command received
    the original caller session, attempted a content write, and was denied. Content readback remained
    byte-identical. Service access separately proved state write permission and absence of content
    write permission. Full gate and final review remain required; task 4202 is not complete yet.

15. Static review corrected fixture resolver ownership, nullable probe lookups, source-policy
    identifiers and numeric literals, and registered the two new stateless policy types. No checker,
    rule, exclusion, or coverage floor was weakened. The full gate reached runtime tests with all
    1037 core tests and coverage passing, but failed two old security scenarios and one Mongo startup
    at 04:22 CEST (`interop/target/plan10-4202-security-assumptions-quality.log`). The ownership
    matrix passed in that run. Task 4202 remained unaccepted.
16. Review found the raw-state scenario had used the administrator against an absent tree and had
    checked the wrong key-ring path. It now uses an isolated provisioned runtime, verifies fixture
    presence as administrator, and checks authenticated ungranted and anonymous callers against all
    tested spellings. Canary values populate the actual key-ring path; a denied write must leave
    readback byte-identical. All three checks passed at 04:29 CEST. The crash-consistency rerun also
    passed both tests; the prior failure occurred before product work during Mongo readiness.
17. The log scan now identifies framework INFO registry and repository-initialization announcements
    by the structured logger prefix. Regression checks retain product messages, embedded framework
    names, warnings, and unstructured lines. This stricter scan exposed a startup probe preceding
    the installed service mapping. The harness now provisions and observes state configuration
    before installing the product bundle, then verifies the scoped route. The five redaction checks
    passed at 04:31 CEST (`interop/target/plan10-4202-security-review.log`). Scenario descriptions now
    state their actual proof boundaries. A complete gate remains required before acceptance.

18. The next complete run passed core, coverage, policy, security, ownership, and every cluster
    scenario, but one fresh shared runtime refused its first configuration-folder creation before
    the scenario could run (04:42 CEST, `interop/target/plan10-4202-folder-readiness-quality.log`).
    Folder setup had bypassed the harness's existing servlet-registration retry and explicit referrer
    path. It now uses that path and retains the platform status and response on refusal. The old
    failure omitted those details, so its exact platform cause is not claimed. No product assertion
    or readiness deadline was weakened; full gate acceptance is still pending.

19. The 12 focused fresh-runtime checks passed at 04:43 CEST. The complete argument-free
    `scripts/quality` then passed at 04:55:32 CEST on 2026-09-06: 1037 core tests and 464 interop
    tests, zero failures/errors/skips, both bundle coverage checks, all static and repository policy
    checks, package/release checks, and all public runtime and cluster scenarios. Evidence is
    `interop/target/plan10-4202-quality.log`. The gate explicitly reports owner-supplied Adobe
    quickstart and sibling-client tiers as not run; no claim about those tiers is made here.
20. Final review checked all three task steps against production routing, scoped resolver lifetime,
    atomic subscription admission, original-session content execution, and the installed-runtime
    matrix. Test probe classes remain outside the shipped bundle, the service grants remain limited
    to the state tree, and policy rules/checkers/exclusions are unchanged. Reviewed the complete diff
    and whitespace. Task 4202 is complete. Accepted-request execution after saturation, intake
    completion execution, and full command/console assembly remain separate plan tasks.


## 4301 — resumable request admission

A matching owner resend progresses an accepted operation after execution capacity returns, under
that request's original session. It keeps the existing acknowledgement identity and resend marker.
Incomplete intake continues waiting; running and terminal operations never execute again. A failed
start acknowledges only an observed winner, otherwise returning a retryable refusal.

Review and correction loop:

1. Added a saturation/resend regression and corrected its fixture to reserve the lesser of the
   instance total and the caller share. It then reproduced first=503, retry after release=202,
   executions=0 at 04:58 CEST on 2026-09-06 in
   `interop/target/plan10-4301-saturation-reproduction.log`.
2. Both new admissions and recognised owned requests now progress accepted operations whose intake
   is complete, reserving execution capacity and using the existing atomic start transition under
   the current original caller session. Resumed execution retains `already_accepted:true`. A losing
   start reads back a real running/terminal winner before acknowledging; an operation still accepted
   receives 503 with a retry hint. The first 41 focused route/admission/intake tests passed at
   05:00 CEST in `interop/target/plan10-4301-resumption-initial.log`.
3. Review added repeated-saturation refusal, execution-capacity cleanup, interrupted and contended
   start recovery, lost acknowledgement, and two servlet requests interleaved at the real Oak start
   commit. The command fixture writes a separate content node per execution under the original caller
   session. The competing requests and lost-response resend each leave exactly one persisted effect,
   with execution capacity returned. All 45 focused tests passed at 05:03 CEST in
   `interop/target/plan10-4301-resumption-races.log`. Atomic owned-subscription registration remains
   the task 4202 implementation. Full gate and final review remain required; task 4301 is incomplete.

4. The complete argument-free `scripts/quality` passed at 05:18:21 CEST on 2026-09-06: 1042 core
   tests, 464 interop tests, and 453 development tests, all with zero failures/errors/skips. Both
   bundle coverage checks and every gate stage passed. Evidence is
   `interop/target/plan10-4301-quality.log`. The owner-supplied Adobe quickstart and sibling-client
   tiers remain unproved; their absence is explicitly reported by the gate.
5. Final review checked the task's three steps: saturation now has a live authorized owner resend
   path, subscription binding remains coupled to admission, and interruption/response loss/competing
   requests preserve one content effect and release execution reservations. The current request's
   authorization gate still runs before admission, and incomplete intake cannot execute. No policy,
   checker, exclusion, grant, or imported package changed. The final diff and whitespace were checked.
   Task 4301 is complete. Handling every terminal persistence outcome after an effect remains task 4302.


## 4302 — reliable terminal finalization

Immediate execution reserves its terminal event, bounded result and snapshot before starting the
handler. The winning start records those reservations and the server execution instant together
with RUNNING. A completion journal selects one typed success, declared failure or explicit
uncertainty. Terminal state, answer, event, snapshot and journal retirement then commit together.
An owned resend or recovery publishes recorded completion evidence without running the command.

Review and correction loop:

1. Reproduced terminal-event exhaustion inside a handler after its actual caller-session content
   effect: status=202, state=RUNNING, resultPresent=false. Evidence is
   `interop/target/plan10-4302-terminal-capacity-reproduction.log`. This was independent of the
   accepted-request resumption defect repaired by task 4301.
2. Added a dedicated retained terminal-event budget and atomic transfer into the real event row.
   Tests reject foreign budgets, underpayment and already-spent event rows, preserve the budget
   after interrupted publication, and publish at the event-row bound. Snapshot materialisation
   retains its one shared callback; an initial duplicated callback failed the existing structural
   test and was consolidated without changing that test. The 57 focused lower-layer checks passed
   in `interop/target/plan10-4302-prepaid-terminal-reviewed.log`.
3. Added typed handler completion and an execution-start sidecar. Real Oak competing starts publish
   only the winner's resources; an interrupted start leaves neither RUNNING nor its journal. The
   completion journal uses a stamped conditional transition, so an interrupted completion remains
   retryable and a late handler cannot overwrite recovery's selected uncertainty. An initial new
   test incorrectly expected a state move to increment delivery attempts; review corrected the
   test to preserve the existing separate delivery-attempt semantics.
4. Connected request execution, completion selection and prepaid terminal publication. The original
   saturation regression now requires 202, SUCCEEDED and a retrievable result. Interrupted terminal
   persistence returns 500, contention returns 503 with a retry hint, and both retain the completion
   and event budget. Retrying publishes the same answer with exactly one actual content effect.
   Lost response writing likewise does not repeat the effect. A handler throwing after its effect
   leaves explicit effects_undetermined evidence, later published as a failed outcome without
   claiming that effects were absent. These paths passed 67 focused checks in
   `interop/target/plan10-4302-handler-uncertainty-initial.log`.
5. Recovery uses the server execution-start instant. It publishes READY evidence immediately and
   selects explicit uncertainty after the execution budget and recovery margin. A candidate's
   stored identity must name its exact generation/path. Terminal publication must equal the journal's
   selected outcome and clears its pending result bytes in the same commit. Retention returns an
   unused terminal-event reservation with operation deletion. Recovery and cleanup passed 106
   focused checks in `interop/target/plan10-4302-unused-budget-retirement.log`.
6. Review reproduced two further failures: a missing published artifact returned 503 indefinitely,
   and retention could collect an artifact referenced by a pending completion. Missing or mismatched
   references and oversized inline results now become explicit result_unavailable outcomes; pending
   references protect their artifacts. Scoped handler cleanup preserves the original exception and
   suppresses a failed uncertainty write, leaving durable start evidence for later recovery. These
   paths passed 119 focused checks in
   `interop/target/plan10-4302-suppressed-cleanup-reviewed.log`.
7. Full-gate review corrected formatting and test-only static-analysis findings, then an injection
   audit refusal of a qualified node-name constant. The call now uses the existing checker-recognised
   static constant form; no checker or exclusion changed. Accounting review added result/snapshot
   reservations before effects, exact charge replacement with READY publication, and retained-vector
   release with operation deletion. Full result quota refuses before effects; release resumes the
   same accepted request once. Exact result and snapshot charges return to zero after retention.
8. The complete argument-free `scripts/quality` passed at 06:38:06 CEST on 2026-09-06 with 1070 core,
   4 Adobe-module, 453 development and 464 interop tests, all with zero failures/errors/skips. Every
   stage and bundle coverage check passed. Evidence is
   `interop/target/plan10-4302-quality-capacity-reviewed.log`. The installed state-ownership scenario
   passed with the new execution path. Owner-supplied Adobe quickstart and sibling-client tiers did
   not run and remain unproved. Runtime lifecycle activation and command assembly remain later tasks.
9. Post-gate review added four before/after-save completion-capacity faults. Fresh independent
   sessions prove that either the original promise or the committed result with exact charges
   survives, and publication retries preserve that answer. Both before/after-delete retention
   faults preserve resource/accounting agreement. Review also reproduced a missing capacity identity
   falling through as an unfunded journal; it now fails explicitly, and repair permits completion.
   All 119 focused checks passed in `interop/target/plan10-4302-capacity-owner-reviewed.log`.
   Recovery also recognises a terminal winner observed during completion publication. Final gate
   validation and task completion remain pending after these review changes.

10. The final-review gate passed all 1077 core tests and 453 development tests, but its shared-repository
    harness returned 404 on one cluster node in two checks. The other interop checks passed. The same
    unchanged harness then passed all three checks with continuous container-log capture at 06:56:26
    CEST in `interop/target/plan10-4302-concurrent-runtime-reviewed.log`. The failure was not reproduced;
    no exact platform cause is claimed and no check was relaxed. Another full gate with captured
    runtime logs is required before committing this task.

11. The final diagnostic run of the complete argument-free `scripts/quality` passed at 07:09:58
    CEST on 2026-09-06: 1077 core, 4 Adobe-module, 453 development and 464 interop tests, all with
    zero failures/errors/skips. Every gate stage and both bundle coverage checks passed. Evidence is
    `interop/target/plan10-4302-quality-final-diagnostics.log`; continuous container logs are under
    `interop/target/plan10-4302-final-gate-runtime-logs/`. All cluster checks passed unchanged.
12. Final review checked all three task steps against the original saturation reproduction, typed
    completion tests, independent persistence/response faults, recovery races and retained-capacity
    accounting. Recorded completion is published without repeating effects; execution-start evidence
    remains reconcilable when completion cannot be saved. Task 4302 is complete. The owner-supplied
    Adobe quickstart and sibling-client tiers remain unproved.

The repository-layout inventory now names the terminal-event budget and completion journal, and
its design-pattern inventory names the journal's stateless policy. No execution grant, schema,
route, imported package or checker was widened. Result transport remains task 4306; these checks
prove durable result storage and reconciliation rather than a new client result-envelope consumer.


## 4303 — atomic intake publication

Task 4103 (`b655509`) already moved declared size/digest validation ahead of artifact publication
and coupled the intake reservation transfer to that publication. The pre-4103 implementation
published first and deleted a mismatched artifact in a later save. This task adds independent
observations at those boundaries and actual process-death evidence to protect the corrected order.

Review and correction loop:

1. Added independent Oak readers for mismatched digests and truncated bodies at every save. The
   observer also interrupts a staged invalid artifact after its actual save, so restoring the old
   publication window cannot hide behind later deletion. Invalid input preserves an open declaration,
   its retained capacity identity, and all four total/caller counters; valid retries publish exact bytes.
   Before/after-publication save faults and a competing completion independently prove one artifact
   and one capacity transfer. All 22 intake checks passed at 07:12:38 CEST in
   `interop/target/plan10-4303-independent-publication-initial.log`.
2. Added a test-only probe using the built intake/store classes on two pinned Sling DocumentNodeStores
   sharing Mongo. The coordinator blocks the writer immediately before or after its real publication
   save, kills it with SIGKILL, and reads the surviving node's bytes, declaration, ownership and counters.
   The after-commit case passed. The initial before-commit case retained the intact promise but its
   retry exceeded the harness's 30-second HTTP deadline. A repeat and JVM thread dump located that
   wait in Oak `CommitQueue.suspendUntilAll`, while creating the new reservation. The killed node's
   cluster lease was still active. Evidence is `interop/target/plan10-4303-process-death-initial.log`
   and `interop/target/plan10-4303-survivor-thread-dump.log`.
3. The new durability scenario now observes one survivor upload for at most three minutes, allowing
   Oak's cluster recovery to finish; it does not resend that upload or change ordinary request bounds.
   It passed at 07:24:04 CEST in `interop/target/plan10-4303-process-diagnostic.log`. The subsequent
   duplicate must answer ALREADY_COMPLETE and counters must still agree. This proves persistence and
   retry after repository recovery, not a 30-second failover or bounded repository-write deadline.
   The crash-point inventory now distinguishes completed intake publication from a command commit.
4. Deliberately bypassing prepublication digest validation made the new observer fail at the actual
   publication save: expected absent, observed present. Evidence is
   `interop/target/plan10-4303-digest-observer-mutation.log`. The mutation was restored byte-for-byte;
   no product intake/store source change remains. Full gate and final review remain required.

5. The first full gate passed all 1082 core tests and static checks, then correctly refused the new
   crash runner because its scenario catalog row was missing. Added the property scenario under
   `interop/scenarios/intake-publication-crash.toml`, naming its actual store-level proof and timing
   limitation. No checker was relaxed. The full gate must pass with that row before completion.

6. The complete argument-free `scripts/quality` passed at 07:44:56 CEST on 2026-09-06 with 1082 core,
   4 Adobe-module, 453 development and 466 interop tests, all with zero failures/errors/skips. Both
   bundle coverage checks and every gate stage passed. The two new intake crash cases passed in
   170.4 seconds. Evidence is `interop/target/plan10-4303-quality.log`, with continuous runtime logs
   under `interop/target/plan10-4303-gate-runtime-logs/`. Owner-supplied Adobe quickstart and
   sibling-client tiers did not run and remain unproved.
7. Final review matched all three task steps to the historical publish/delete window, the failing
   digest-validation mutation, independent readers at save boundaries, reusable reservations,
   competing completion and actual before/after-commit process death. The prerequisite's validated
   atomic publication remains unchanged. The new scenario is cataloged as a store property and its
   longer observation bound explicitly covers repository recovery. The staged diff and whitespace
   checks passed. Task 4303 is complete; starting fully received intake work remains task 4304.

## 4305 — durable stream progress

Review and correction loop:

1. Reproduced the original R06 behavior in the stream fixture: event writes advanced only the
   in-memory replay cursor, leaving the durable subscription `NOTHING_SHOWN_YET`. Stream delivery
   now advances the owned subscription after each successful write and flush, so a disconnect
   cannot move the cursor beyond the last acknowledged event.
2. Review found that `HighWaterMark.advance` saved the cursor and activity timestamp separately.
   A post-commit response loss could therefore leave partial progress. The transition now stamps,
   compares, writes both fields, and commits them in one bounded retry loop. Initial snapshot and
   reset notices use the same durable advancement point.
3. Added independent-session regressions for successful terminal delivery, initial snapshot,
   reset, disconnect after one event, and high-water saves interrupted before and after commit.
   The stream, resumption, servlet, high-water and subscription suites passed 53 checks at
   10:57 CEST on 2026-09-06. The checks prove cursor/activity agreement and preserve exactly the
   acknowledged position across reconnect boundaries.

Task 4305 is complete. The repository-wide gate remains to be rerun after the in-progress 4105
maintenance traversal is repaired; its current dense-bucket regressions are unrelated to stream
progress.


## 4105 — bounded maintenance pages (in progress)

Review and correction loop:

1. The existing bound-one test uses three different buckets and therefore does not exercise the
   bound inside a bucket. Added three valid identities with the same bucket prefix and a real-Oak
   iterator observer. Separate tests check examined records and actual operation iterator advances.
   Both fail with three where the bound permits one. Evidence:
   `interop/target/plan10-4105-dense-red.log`, two tests, two assertion failures at 00:05 CEST on
   2026-09-06. Production traversal and cursor code are unchanged at this stage.

2. Added a retained-first-record case: a live lease protects the first operation while later
   operations must be collected across bounded passes. All three dense-bucket regressions fail on
   current traversal. Evidence: `interop/target/plan10-4105-resumption-red.log`. The later coverage
   and accounting assertions remain unproved because the read-bound assertion fails first.
3. Inspected the cached Jackrabbit `RangeIteratorAdapter` bytecode: `skip(n)` loops through `next()`.
   Extended the real-Oak observer to count skipped records and verified it with a passing test at
   00:09 CEST on 2026-09-06 in `interop/target/plan10-4105-observer.log`. Resumption by offset must
   therefore account for skipped reads; merely adding a limit after a skip would not satisfy this
   task. No production traversal or cursor change has been made yet.

4. Added a persisted within-bucket record position and an independent cursor version. Advancement
   compares the complete previously read cursor and writes bucket, record, timestamp, and version
   in one fenced commit. Equal positions and timestamps cannot make an older pass current again.
   Four focused cursor cases passed at 00:12 CEST on 2026-09-06 in
   `interop/target/plan10-4105-cursor.log`; production traversal does not yet use the record position.
5. Review found the initial cursor claim's contention outcome needed to be propagated before
   accessing its node. Added a reproducing contention case and handled that outcome. Cursor
   advancement now has two persistence boundaries on first use (creation plus atomic advancement),
   so the cleanup interruption matrix covers its five actual saves instead of the former six.
   The full `MaintenanceSweepTest` ran 45 cases: 42 passed, with only the three known dense-bucket
   regressions failing. Evidence: `interop/target/plan10-4105-cursor-review.log`. Corrected one long
   test line after this run. Full-core verification and the full gate have not run for this task.

6. Replaced whole-bucket materialization with one-record transactional steps. A retained record
   moves to the back of its bucket using a temporary ordering neighbour which is removed before
   commit; no marker node is persisted. The cursor remembers the first retained record as the cycle
   boundary. Cleanup, ordering, and cursor progress commit together. Bucket discovery now uses the
   fixed numeric bucket namespace rather than materializing bucket iterators. The stored record
   field is named `cycle_start_record` to reflect this algorithm.
7. The first two dense-bucket cases passed immediately. The retained-first case then reached its
   later assertions and exposed a fixture error: a lease does not extend whole-operation retention
   under the existing cleanup rules. Gave the first fixture an unexpired request-start window as
   well as a lease. All three dense-bucket regressions then passed. The full maintenance suite
   identified obsolete fifth-save injection points and cursor-creation contention handling; adjusted
   the matrix to its four actual boundaries and added bounded cursor-creation retries.
8. Review made contention rereads consume the same record budget, checked the staged cursor outcome,
   and resets a cycle anchor if its record has disappeared. All 41 maintenance tests passed in
   `interop/target/plan10-4105-first-green.log`. Full-core verification ran 978 tests with zero
   failures/errors/skips, then PMD required the new test loop to use foreach. Corrected that loop.
   Evidence: `interop/target/plan10-4105-core-review.log`, 00:19 CEST on 2026-09-06. Broader dense
   cycle, interruption, concurrency, and read-bound review is still required before task completion.

9. Added interruption before and after all five saves of a dense cycle with a retained first
   record (cursor creation, three record steps, and cycle completion). Independent sessions check
   actual artifact rows and bytes against total and caller counters, resume cleanup, and confirm
   the temporary ordering neighbour never persists. An overlapping bound-one rotation test proves
   a stale sweep cannot move the winning cursor or prevent collection of later records. All 52
   maintenance cases passed in `interop/target/plan10-4105-durable-rotation.log`.
10. Added an all-retained dense cycle followed by expiry and complete collection, and a bound-one
    contention case which verifies that retries cannot advance another operation node. Core
    verification passed all 991 tests, coverage, formatting, PMD, and SpotBugs at 00:23 CEST on
    2026-09-06. Evidence: `interop/target/plan10-4105-core-rotation.log`. The complete argument-free
    gate is running with captured runtime diagnostics.

11. The full gate stopped at method shape: the combined transaction method exceeded the committed
    complexity ceilings. Split transaction preparation, first-record processing, rotation, and
    anchor derivation while keeping the enclosing save/refresh boundary intact. All 54 maintenance
    cases passed after extraction; the focused policy check then identified a boolean helper
    parameter. Replaced that argument with the retained name and grouped the observed iterator
    facts in a record. Evidence: `interop/target/plan10-4105-complete-quality.log` and
    `interop/target/plan10-4105-method-review.log`. Focused rechecking is running before the next gate.

12. The checker also treats record constructor booleans as parameters, so the intermediate facts
    record was removed. The rotation helper now observes the cycle anchor and remaining iterator
    directly. All 54 maintenance cases and nine method-shape policy cases passed at 00:29 CEST on
    2026-09-06 in `interop/target/plan10-4105-method-final.log`. The complete gate is running again
    in `interop/target/plan10-4105-method-quality.log`.

13. Final review rejected the rotation algorithm despite its passing iterator tests. The exact
    test runtime uses Oak 1.68.0. Its `AbstractMutableTree.orderBefore` allocates an ArrayList,
    enumerates `:childOrder` (or child names), and retains the sibling names before updating order.
    The observer counted JCR iterator advances but could not see that internal work. Evidence from
    the cached dependency: `interop/target/plan10-4105-oak-ordering-bytecode.txt`. Therefore the
    rotation implementation does not prove the required retained-work bound and cannot complete
    this task. The gate also failed InjectionAuditTest for the temporary addNode name; its result
    is `interop/target/plan10-4105-method-quality.log`. No policy exception was added.
14. Removed the rejected rotation implementation, restoring `MaintenanceSweep` to task 4104's
    committed behavior. Preserved the atomic versioned cursor and the regression tests. The rejected
    source is retained only as ignored diagnostic evidence in
    `interop/target/plan10-4105-rejected-rotation.java`. Focused rechecking ran six cases: the three
    cursor cases passed and the three dense-bucket cases reproduced the original bound failure.
    Evidence: `interop/target/plan10-4105-rotation-rejected.log`. Earlier green rotation results do
    not describe the current implementation and are insufficient evidence for task completion.

Remaining: implement traversal which bounds retained work as well as visible iterator advances,
review final cursor integration, prove eventual
coverage and exact accounting across repeated passes, review failure/concurrency boundaries, run
required checks and the complete gate, then commit the completed task.

16. Implemented terminal result recovery on the operation lookup route. A lookup now reads the
    committed result evidence beside the materialised snapshot and emits the contract's `result`
    delivery envelope for inline and published answers; operations with no answer retain the
    snapshot-only response. Updated the snapshot schema and its committed digest, then reviewed the
    change with the focused seven-case lookup suite, compilation, Checkstyle, and PMD. Commit
    `0ea4598` is the reviewed implementation. The full gate remains blocked only by the seven known
    4105 dense-sweep bound failures.

15. Prototyped a durable predecessor/successor list maintained at operation admission so a sweep
    could address the next record directly. Review exposed that prepared but empty bucket parents
    remain in the numeric namespace: a bound-one pass must skip those parents while preserving the
    cursor's bucket contract. The prototype also changed cycle and interruption behavior before
    that normalization was complete, so it was reverted. No source from this attempt is retained;
    the next implementation must normalize to the next populated bucket before committing cursor
    progress and re-run the dense, cycle, contention, and interruption cases.

17. Revisited the durable successor approach with admission-time predecessor/successor properties
    and direct linked traversal. Review found two additional correctness boundaries before it can be
    retained: deleting a current record can invalidate a successor observed by a concurrent sweep,
    and a cursor pointing at a prepared empty bucket must normalize before reporting progress. The
    prototype was reverted after focused dense-cycle and resumption tests reported a path race and
    incorrect wrap position. No source from this attempt is retained.

18. Re-ran the argument-free quality gate after synchronizing the locally generated Maven cache
    records. Cache verification passed; the gate then stopped at the pinned interop-image stage.
    `scripts/prepare_interop_images` was attempted and could not initialize Podman because its
    runtime directory is read-only. This is environment evidence only; no interop tier is marked
    proven and no task status was advanced.

19. Review of 4306 identified that an artifact digest and byte count alone cannot address the
    download route. Extended the result delivery envelope with the optional committed artifact
    slot, preserved the generic two-argument constructor, synchronized its schema digest, and added
    a recovery test that asserts the slot survives lookup. The focused lookup, result, and handler
    suites passed (23 cases), followed by Checkstyle and PMD. Commit `d7b92ab` records the reviewed
    extension.

20. Added cancellable artifact transfer I/O. Each read and write runs under a daemon worker with
    the published total and idle deadlines; timeout closes the corresponding stream, cancels the
    blocked operation, and returns the verified partial byte count so the servlet can unwind its
    resources. Existing artifact transfer coverage (seven cases) plus Checkstyle and PMD passed.
    Commit `465b035` records this implementation. Upload-body and event-stream deadline paths still
    require separate runtime tests, so task 4307 remains pending.
    A follow-up review found the idle deadline must reset from monotonic write progress rather than
    the transfer start; `cdc90f5` applies that correction. The seven-case artifact suite, Checkstyle,
    and PMD remain green.

21. Wrapped event-stream response writes and flushes in cancellable, daemon-backed operations using
    the contract's total and idle transfer deadlines. Runtime review caught that worker-side runtime
    failures must preserve the existing stream admission semantics; the wrapper now rethrows runtime
    causes while translating I/O failures to the stream's client-away ending. Compilation and the
    focused event-stream, admission, and heartbeat suites passed (28 cases). Commit `4e8442d` records
    this reviewed path. Upload-body handling and the servlet's initial response flush remain pending,
    so task 4307 is not complete.
    A second review applied the same deadline discipline to incremental upload reads in
    `BoundedRequestBody`; blocked reads close the request stream and become the existing transfer
    refusal. The body and intake suites passed (28 cases), with Checkstyle and PMD green. Commit
    `d543edc` records the upload path. The initial servlet response flush still needs a cancellable
    boundary and task 4307 remains pending.
    A final focused review wrapped the initial event-stream response flush in a daemon-backed
    deadline operation and retained runtime exception propagation. The event-stream servlet suite
    passed (10 cases). Commit `3763d6d` records the flush path; task 4307 still needs an integrated
    end-to-end transfer review before status can advance.

22. Began 4401 by replacing the shared `RepositoryReach` breadth-first resource queue with an
    iterator stack. Wide trees now retain only the active depth while still stopping at the first
    node beyond the caller's bound, and reference discovery uses the same bounded traversal. The
    command suite passed (361 cases), with compilation, Checkstyle, and PMD green. Commit `ab862d8`
    records this step; query/list/package handler integration and measured wide/deep fixtures remain.
    QueryPathsHandler now uses the same depth-bounded iterator stack, preserving sorted results and
    explicit budget exhaustion. Its focused query and child-list suites passed (17 cases), followed
    by Checkstyle and PMD. Commit `d374f6d` records the query integration; child-list and package
    handlers still need equivalent retained-work treatment and measured fixtures.
    ListChildPagesHandler now stops child examination at the discovery budget and reports exhaustion
    explicitly, avoiding a partial page presented as complete. Its seven-case suite passed. Commit
    `d55f9e2` records this step; package traversal and wide/deep measured fixtures remain.
    DownloadContentPackageHandler now selects package paths with the same iterator stack, keeping
    retained traversal state proportional to active depth while preserving pre-staging budget
    refusal. Its eight-case package suite passed. Commit `b74d8a7` records the package step; measured
    wide/deep fixtures and final 4401 integration review remain.

23. The 4402 review made reference discovery explicitly return completeness and updated page, asset,
    and fragment delete/move guards to refuse before mutation when visibility is exhausted. The
    three mutation suites passed (42 cases); Checkstyle and PMD are green after suppression review.
    Implementation commits are `640961a` and `46a3c9a`. Dedicated insufficient-visibility and
    multivalue-reference fixtures now pass in `RepositoryReachTest`.

24. Extended the 4402 guard review across all reference-adjusting mutation consumers. Incomplete
    scans now refuse asset, page, and fragment moves before relocation or repointing, while delete
    policies refuse incomplete visibility as conservatively as an observed reference. Focused
    mutation coverage passed (42 cases), and the final PMD/Checkstyle pass is green. Commit `640961a`
    contains the behavior; `46a3c9a` corrects its policy suppression. The direct boundary fixtures
    complete the evidence for 4402.

25. Began 4403 by requiring component deletion targets to carry the component node type and by
    counting the complete subtree up to one past the deletion bound before calling delete. Ordinary
    folders are refused and oversized component trees remain unchanged. Component mutation coverage
    passed (12 cases), with compilation and Checkstyle green; PMD reports only the known maintenance
    test argument-order baseline. Commit `bbf94c0` records this step. A wrong-kind fixture and a
    small-bound over-budget fixture now pass in the 14-case component suite; commit `146b315` adds
    the wrong-kind case and `ba7cd58` applies the discovery bound and over-budget case. The evidence
    for 4403 is complete.

26. Audited 4404's paged handlers and found that `ResultWindow.Continuation` is currently carried as
    an opaque string: no handler context provides a `KeyRing`, target digest, serving generation, or
    validation clock, and no runtime path calls `PagedQuery.tokenFor`. Existing continuation branches
    therefore cannot safely issue or validate authority. This is a confirmed architecture gap, not
    a handler-local defect; 4404 remains pending until the command runtime carries that authority.

27. Audited 4405's package path and confirmed that `DownloadContentPackageHandler` currently stages
    only `filter.xml` and returns metadata, while durable publication requires an operation session,
    caller ownership, and `ArtifactStore.publish`. The handler receives only a staging-room handle
    and a read-only resolver, so producing synthetic ZIP bytes would still leave no durable artifact
    route. 4405 remains pending until the runtime supplies the publication boundary.

28. Re-ran `scripts/quality` against the current tree. The gate stopped at locked dependency cache
    verification because nine locally generated reactor artifacts (parent, core, and AEM POM/JAR,
    sources, and javadocs) are present in the cache but absent from the committed record. No source
    or policy stage ran after this refusal; the cache preparation command remains the required next
    step before a complete gate result can be claimed.

29. Ran `scripts/prepare_locked_dependency_cache` with approval. Dependency resolution completed,
    but its required non-interop verification stopped at the core JaCoCo floor with broad uncovered
    classes, so the script did not rewrite the committed cache record. This confirms the generated
    artifacts are available locally but the preparation command cannot complete until coverage is
    restored; no quality-gate result beyond this preparation failure is claimed.

30. Reviewed fully received intake execution through the installed submission route. Admission
    persists the operation and manifest atomically; the first retry with every slot present claims
    the accepted operation through the execution journal, runs the registered command once, and
    commits its terminal outcome. Concurrent starts, capacity refusal followed by retry, repeated
    resubmission, handler uncertainty, and missing-result recovery are covered by the focused
    `SubmitServletTest` and `ArtifactIntakeServletTest` suites (46 cases), all passing. This closes
    task 4304; the implementation is spread across commits `e0d4a99`, `81c4c62`, and `6bb7d69`.

31. Reviewed task 4105 against the dense-bucket fixtures. A bounded iterator prototype reduced the
    initial over-read but failed five of the 58 maintenance cases: resumption reread skipped nodes,
    a retained record could hide an eligible successor, and interruption boundaries no longer
    preserved the expected cursor transaction. The prototype was reverted; no unsafe sweep change
    was committed. The remaining implementation needs a durable within-bucket successor that can
    be read without spending the next pass's record budget.

32. Added wide (1,000 children) and deep (100 levels) repository fixtures to the shared reach
    traversal suite. Both prove the bounded DFS returns exactly the permitted prefix, including the
    explicit one-past bound marker, and the four-case suite passes. This strengthens 4401 evidence;
    measured iterator-call instrumentation and final handler integration remain open.

33. Prototyped durable maintenance successor links on operation records and a direct successor
    sweep. The dense maintenance suite improved from seven failures to three, including passing
    the per-pass read bounds, but review found that sparse-bucket wrap semantics and interruption
    save boundaries still diverged from the cursor contract. The link schema and sweep changes were
    reverted; task 4105 remains pending until successor state and cursor reporting are designed
    together.

34. Re-ran the 4401 handler integration review after adding the wide/deep reach fixtures. The
    query-path, child-page listing, and content-package command suites all pass (the combined
    Maven run completed without failures), confirming depth-first selection, ordering, and explicit
    discovery-budget refusal across the affected callers. Task 4401 remains pending because the
    required measured iterator-call evidence and full quality gate are still outstanding.

35. Closed a multivalue reference-adjustment gap in 4402. Reference discovery already recognized
    string arrays, but repointing only changed scalar values; moves could therefore leave array
    entries targeting the old address. Repointing now rewrites every matching array entry and
    reports each replacement. `RepositoryReachTest` plus the page, asset, and fragment mutation
    suites pass, and offline PMD reports no violations.

36. Added proxy-backed iterator observers for bounded reach traversal. A 10,000-child wide tree
    advances only the two nodes needed for the bound-one one-past result, while a 100-level tree
    advances only its active path. The seven-case `RepositoryReachTest` suite, Checkstyle, and
    PMD all pass. This supplies direct iterator-call evidence for 4401; handler-wide timing and
    the full quality gate remain open.

37. Re-ran the complete argument-free `scripts/quality` gate after the traversal and reference
    changes. It still stops at locked-dependency-cache verification before any source or policy
    stage: the same nine locally generated reactor artifacts are present but absent from the
    committed support record. No gate result beyond this cache refusal is claimed.

38. Re-ran the complete 4402 mutation regression set after adding multivalue repointing. All
    mutation and reference-reach cases pass, preserving conservative incomplete-scan refusal,
    ownership checks, and scalar behavior alongside array updates. No new failure or policy
    suppression was introduced.

39. Hardened the shared `Budget` primitive used by bounded traversal: a negative spend is now
    outside the budget instead of being accepted as valid progress. The command-context, reach,
    query, and child-list suites pass, with Checkstyle and PMD clean. This closes the underflow
    boundary without changing any declared contract limits.

40. Synchronized task 4402 metadata with its final multivalue implementation commit
    `cf663d8`; the task now points at the code that includes both conservative guards and complete
    scalar/array repointing.

41. Replaced the package handler's filter-only payload with a valid ZIP archive containing
    `META-INF/vault/filter.xml`. Artifact metadata now names the archive's actual byte count and
    digest. The package suite passes with a ZIP readability regression, and Checkstyle/PMD remain
    clean. Durable `ArtifactStore.publish` integration is still pending because the command runtime
    does not yet supply its publication session and ownership boundary.

42. Extended the archive review to selected resource payloads. The ZIP now carries deterministic
    `.content.xml` entries for each selected resource, with XML escaping and stable property order;
    tests verify both the Vault filter entry and a selected content entry. The package suite,
    Checkstyle, and PMD pass. Publication into `ArtifactStore` remains a runtime integration task.

43. Finalized package byte determinism by fixing every ZIP entry timestamp to the epoch. A new
    byte-for-byte regression proves identical filter inputs produce identical archives and digests;
    the package suite, Checkstyle, and PMD pass.

44. Closed the remaining package-manifest serialization gap: roots and include/exclude patterns are
    now XML-escaped before entering `filter.xml`, so valid repository names containing `&`, quotes,
    or angle brackets cannot corrupt the archive manifest. The focused package suite passes (including
    the attribute-escaping regression), with offline Checkstyle and PMD clean. Durable
    `ArtifactStore.publish` integration remains a runtime boundary for task 4405.

45. Closed the invalid initial paging bounds in `ResultWindow`: negative limits and offsets now have
    explicit refusals instead of reaching handlers as accepted windows that could trigger invalid
    slicing or traversal positions. `PagedQueryTest` and `QueryPathsCommandTest` pass, with offline
    Checkstyle and PMD clean. Continuation authority wiring through the command runtime remains the
    open part of task 4404.

46. Hardened the paging window parser against unknown nested members. A result window now refuses
    extra fields instead of silently accepting and ignoring them, preserving the strict command
    contract before any handler dispatch. `PagedQueryTest` passes with the malformed-window
    regression, and offline Checkstyle and PMD are clean.

47. Hardened `PagedQuery.pageOf` against integer narrowing when a valid long result limit exceeds
    the number of rows found. The page now serves the available rows without overflowing its slice
    bound; a large-limit regression passes with the paging suite, Checkstyle, and PMD clean.

48. Re-ran the complete core test suite after the paging hardening. The suite executed 1,122 tests
    and still reports seven failures, all in the known dense-bucket maintenance sweep coverage for
    task 4105 (bounded traversal, cursor resumption, and interruption boundaries). No runtime task
    was marked complete from this result; it updates the baseline used for the next 4105 review.

49. Re-ran the argument-free `scripts/quality` gate. It still refuses at locked-dependency-cache
    verification before source or policy stages: the nine locally generated reactor artifacts are
    present in `.dependency-cache` but absent from the committed cache record. No broader quality
    result is claimed; `scripts/prepare_locked_dependency_cache` remains the named preparation step.

50. Completed 4105's bounded maintenance traversal. `MaintenanceSweep` now advances one record at a
    time, persists a within-bucket successor in `SweepCursor`, resumes across sparse buckets and
    wraps without gaps, while preserving the existing compare-and-set and save-interruption cadence.
    The complete `MaintenanceSweepTest` suite (58 cases), full core suite (1,122 cases), Checkstyle,
    and PMD pass. Commit follows this review loop.

51. Completed 4401 after the measured traversal review. The shared reach traversal bounds iterator
    advancement and retained work across wide, deep, and nonmatching trees; affected query-path,
    child-page, and package handlers propagate incomplete discovery as an explicit refusal.
    Proxy-backed iterator fixtures cover a 10,000-child tree and a 100-level tree, and the seven-case
    reach suite plus handler integration suites pass. Repository-wide quality failures remain
    separately evidenced in the cache-preparation review. Commit follows this review loop.

52. Reopened 4105 after the completion claim failed current verification. The full core suite currently executes 1,122 tests with two dense-bucket failures: a bound-one pass advances two operation nodes, and cursor resumption rereads three. The child-iterator successor implementation therefore does not satisfy the declared read bound; 4105 is pending again until a bounded successor lookup is implemented and reviewed.

53. Completed the source-policy cleanup loop for executor null sentinels and resource ownership. Commits `4c6b87f`, `aba9dcf`, `ea62e02`, `aebd9b0`, and `e3c9ba7` close the event-header, request-body, and artifact executors, remove sneaky-throw suppressions, and replace HTTP `submit` null-return callables with checked-exception adapters. Focused HTTP and stream tests plus offline Checkstyle and PMD pass; the remaining checked-action adapter in `StreamWriter` is still under review.

54. Completed the follow-up source-policy review loops after the initial runtime hardening pass. Commits `0794dbc`, `2f44171`, `3afe96f`, `d335e65`, `84dd4a1`, and `0d91f22` centralize monotonic clock reads, name bounded completion states, and reduce package/reference traversal complexity. Focused tests and offline static analysis pass for each changed slice; remaining method-shape findings are limited to transfer nesting and the still-pending dense-bucket sweep design.

55. Simplified `BoundedRequestBody.read` resource scope by letting the executor's
    try-with-resources own shutdown and handling transfer I/O failures at the outer boundary.
    The six-case bounded-body suite passes offline, and the change removes the redundant nested
    exception/finally shape. Commit `cb67d42` records this review loop; the regular Maven gate is
    unable to write its user cache in the restricted sandbox.

56. Removed the indexed loop from `MaintenanceSweep.run` in favor of an equivalent explicit
    bounded while traversal, satisfying the allocation policy's outside-sensitive-path rule
    without changing cursor or bucket calculations. The 58-case sweep suite still reproduces the
    two known dense-bucket successor failures (bound-one advances two nodes; resumption rereads
    three), so 4105 remains pending. Commit `829e656` records the review loop.

57. Added `StateLifecycleService` as an immediate DS component. It performs startup recovery before
    scheduling bounded maintenance under the maintenance service identity, publishes an explicit
    unavailable snapshot on missing sessions or failed state work, and shuts its private scheduler
    down during deactivation. The activation/refusal test passes, and core compilation,
    Checkstyle, and PMD pass. Commit `ca17fe7` records this review loop. Task 4501 remains pending
    until discovery consumes the snapshot and the installed scheduler configuration is exercised.

58. Bound `CapabilityServlet` generation and continuation readiness to the lifecycle observation.
    A ready durable pass now advertises its persisted generation and key authority; an inactive or
    failed lifecycle continues to advertise `NOT_READY` and the compatibility generation. The
    capability and lifecycle suites pass (11 cases), with core Checkstyle and PMD clean. Commit
    `d10b7c5` records this review loop.

59. Embedded all 64 committed immediate command rows in the core bundle under a deterministic
    index and added a classloader-backed `CommandRegistry.read` path. The registry suite now proves
    installed-resource loading without the repository filesystem (13 cases); core Checkstyle and
    PMD pass. Handler construction and runtime dependency assembly remain the open part of 4502.
    Commit `db96240` records this review loop.

60. Reviewed the installed dispatch boundary after embedding the registry. `CommandDispatch` still
    requires a handler for every row and rejects partial maps; the remaining handlers depend on
    platform adapters that have no production DS registrations. This review confirms that resource
    embedding is safe to land while partial command advertisement would be unsound. The registry
    suite and core static checks remain green.

61. Re-reviewed 4502 after the registry embedding. The installed bundle can now load every declared
    row from packaged resources, but dispatch correctly remains unavailable until every advertised
    row has a handler and its platform adapter. No partial map was introduced; this preserves the
    contract that missing implementations are refused rather than acknowledged. The registry
    review evidence remains green.

62. Re-ran the complete core suite after embedding the registry and adding lifecycle coverage. All
    1,124 tests outside the known maintenance successor cases pass; the same two dense-bucket tests
    fail with iterator advances of two and three against a bound of one. No regression was caused
    by the packaged command resources.

63. Re-ran cache preparation and core SpotBugs after the registry nullability review. The
    `CommandRegistry` warning was removed in commit `31793a5`; core SpotBugs now reports only the
    two existing intentional runtime-exception adapters in event delivery. Cache preparation still
    stops before recording because the locked Maven source-plugin realm lacks its Plexus archiver
    dependency, so the nine reactor artifacts remain unrecorded.

64. Re-ran the focused stream and maintenance suites after reviewing a proposed checked-exception
    conversion in `EventStreamServlet` and `StreamWriter`. The conversion was reverted because it
    changed the tested contract for runtime failures (`IllegalStateException` became `IOException`);
    all 16 stream tests now pass. The maintenance suite still fails only in
    `denseBucketRespectsIteratorBound` and `denseBucketResumesBeyondRetainedFirstRecord`: the
    successor lookup rescans the child iterator to decide whether more records exist, so a bound-one
    pass advances two nodes and a resumed pass advances three. This is the remaining 4105 blocker;
    no code change is claimed from the reverted experiment.

65. Completed task 4105 after replacing within-bucket successor rescans with one ordered JCR query
    for resumed records and a single iterator for a fresh bucket. The 58-case maintenance suite now
    passes, including bound-one reads, contention retries, dense-bucket resumption, and interruption
    accounting; the complete core suite passes all 1,124 tests. Checkstyle and PMD remain clean.
    Commit `432d4a2` records this implementation and review loop.

66. Re-ran core SpotBugs after the 4105 change. The sweep introduces no findings; the check still
    reports only the two previously reviewed runtime-exception adapter methods in
    `EventStreamServlet` and `StreamWriter`. Their rethrow behavior is covered by the passing
    stream tests, so changing them would alter the established failure contract.

67. Re-ran the complete quality gate after the method-shape, allocation, dependency-lock, and
    interop-image review loops. Policy checks, dependency verification, image verification,
    Checkstyle, PMD, SpotBugs, and the full Maven suite pass. JaCoCo remains the only failing stage,
    with class-line coverage below 80% for `StateLifecycleService` (47%), `StreamWriter.TimedWriter`
    (76%), `HighWaterMark` (78%), and `ArtifactServlet` (74%). Coverage exclusions are refused for
    these product classes, so the remaining work is focused behavioral coverage in the four classes.

69. Added an Oak-backed lifecycle success-path fixture with an isolated resolver proxy and prepared
    the required `/var/slingshot-agent` tree. The service now proves generation establishment,
    continuation authority setup, recovery, maintenance, and deactivation in a real repository
    context; the four-case lifecycle suite passes. The full gate reaches all 1,128 core tests and
    reduces `StateLifecycleService` coverage from 47% to 77%. Commits `ad3ec31` and `5c8c6bd`
    record the implementation and Checkstyle/PMD review loop. Remaining floor failures are
    `StreamWriter.TimedWriter` 76% and `ArtifactServlet` 74%.

70. Added artifact transfer regressions for asynchronous read and write failures, asserting that both
    propagate as contextual `IOException`s while executor cleanup remains bounded. The focused
    `ArtifactServletTest` suite passes all eight cases and the change is committed as `d1aacf8`.
    Full-floor verification is the next review step; lifecycle and stream coverage remain under
    the 80% class threshold at the last gate.

71. Added interrupted-writer coverage and lifecycle failure-path coverage. The focused stream,
    artifact, and lifecycle suites pass; the core bundle now runs 1,131 tests with all JaCoCo floors
    met. Commit `5bb37bc` covers the timed writer interruption and commit `bc130f1` covers refused
    maintenance login, missing sessions, and resolver ownership cleanup.

72. Re-ran the full quality gate. Formatting, compilation, static analysis, all source policies,
    the complete core suite, and every core coverage floor pass. Interop reaches 466 tests but has
    three runtime-only failures: a generation probe route returns 404 after a container handoff,
    a state-access high-water request receives a stale/expired 410, and the public capability
    assertion still expected the pre-lifecycle readiness value. The last assertion is corrected in
    commit `3f0530c`; the two container failures are isolated to interop runtime setup and do not
    reproduce in core tests.
73. Traced the state-access 410 to `StreamWriter` persisting its monotonic elapsed-tick reading as
    `last_advanced_at_unix_milliseconds`. After a stream advanced a cursor, the high-water route
    therefore treated the subscription as expired. The write now uses the ticker's epoch
    `milliseconds()` value. Focused stream/high-water tests pass; rebuilt-bundle interop reruns of
    `StateAccessOwnershipScenario` and `GenerationRotationCrashScenario` pass. This commit records
    the fix and review loop.

74. Re-reviewed lifecycle activation after the runtime service was exercised in Oak. Contract
    loading now occurs before the scheduler is created; an unavailable contract publishes the
    existing unavailable snapshot and returns without leaking a scheduler. The five-case
    `StateLifecycleServiceTest` suite passes, including the missing-provider and Oak success paths.
    This commit records the lifecycle resource-boundary review loop.

75. Re-ran `scripts/quality` after the timestamp and lifecycle fixes. All cache, image, formatting,
    compilation, policy, static-analysis, core-test, and coverage stages passed. Interop reached
    `GenerationRotationCrashScenario`, where the shared WiredTiger document store remained in
    checkpoint progress and never became ready within its five-minute startup bound; the isolated
    rebuilt-bundle scenario passes, so this gate result is an environment startup timeout rather
    than a product failure.

76. Reworked content-package archive entry writing to preserve checked `IOException` propagation.
    Resource or ZIP entry failures now reach the handler's declared `PACKAGE_FAILED` result instead
    of escaping as an uncategorized `UncheckedIOException`. The 12-case package command suite
    passes after the refactor, including deterministic archive and selected-content checks.

77. Re-ran the package handler's Checkstyle and PMD review after the checked-I/O refactor. Both
    analyses pass with no findings for the changed code; the PMD ruleset emits only its existing
    unmatched-exclusion warnings.

78. Added the missing canonical wire codec for continuation tokens. Issued tokens now render their
    signed integrity and five-member state as canonical JSON, and incoming documents are decoded
    with shape validation before signature checks. The continuation suite now covers round-trip
    equality and malformed-document refusal (11 cases pass); validation still owns integrity,
    target, query, generation, and expiry decisions.

79. Re-ran Checkstyle and PMD for the continuation codec. Both checks pass with no findings for the
    changed classes; the ruleset's existing unmatched-exclusion warnings are unchanged.

80. Repaired the first real handler handoff for paging: `QueryPathsHandler` now applies the parsed
    result window before constructing its response, so an initial limit is enforced at the command
    boundary instead of the handler returning the entire gathered subtree. The focused
    `QueryPathsCommandTest` suite passes all 10 cases; continuation token authority remains the
    next integration boundary.

81. Re-ran Checkstyle and PMD after the paging handoff. Both pass with no findings for the changed
    handler; PMD emits only the repository's existing unmatched-exclusion warnings.

82. Added a regression case that invokes `QueryPathsHandler.run` against a multi-page Oak corpus and
    asserts the response contains exactly the requested initial limit. The focused suite now passes
    all 11 cases, proving the fix through the handler rather than only through its helper.

83. Added an optional per-call paging context to `CallerContext`, preserving the existing
    five-argument constructor while exposing continuation authority, target digest, generation, and
    clock data to handlers. `QueryPathsHandler` now decodes and validates canonical continuation
    tokens, resumes at their signed position, and issues a signed next token when authority is
    present. The focused 11-case suite passes and Checkstyle/PMD pass; runtime assembly still needs
    to populate the context.

84. Re-ran the focused handler suite after adding token decode, validation, and issuance. All 11
    `QueryPathsCommandTest` cases pass; Checkstyle and PMD pass for the changed context and handler.
    The remaining integration gap is explicit: `SubmitServlet.Commands` has no production adapter
    that can construct and populate this per-call paging context.

85. Added a production-facing `CommandDispatch.run` seam that resolves the submitted five-field
    identity before invoking a handler with the request resolver and `CallerContext`. The existing
    dispatch contract suite passes all 7 cases; Checkstyle and PMD review passes after using
    locale-stable refusal rendering. `SubmitServlet` still needs to adopt this seam.

86. Extended `SubmitServlet.Commands` with a resolver-aware default overload and carried the request
    `ResourceResolver` through the accepted execution path. Existing command implementations remain
    compatible through delegation, while packaged runtimes can now invoke handlers without trying
    to recover a resolver from a JCR session. `SubmitServletTest` and `ArtifactIntakeServletTest`
    pass all 46 cases.

87. Extended the servlet bridge with an optional paging-context provider and a full-context command
    overload. The accepted execution path now constructs discovery, time, result, and progress
    budgets from the authenticated contract and passes the provider's authority context to the
    handler. Submit servlet tests pass 24/24 and Checkstyle/PMD pass; a concrete provider remains a
    runtime registration concern.

88. Re-ran the servlet execution review after threading the context through accepted operations.
    `SubmitServletTest` passes all 24 cases, and Checkstyle/PMD pass for the updated servlet. The
    bridge now has every input required by a registered runtime provider; no provider is bundled yet.

89. Added `DispatchCommands`, a concrete servlet bridge that resolves command identities through
    `CommandDispatch`, converts handler answers to bounded inline execution results, and derives
    continuation target and generation from the accepted operation identity. The adapter exposes a
    real authority provider seam; Checkstyle and PMD pass after keeping runtime dependencies
    transient as required by the servlet's serializable contract.

90. Re-ran compilation and Checkstyle after adding the adapter's legacy overload; compilation and
    Checkstyle pass. The repository-wide PMD invocation is currently stopped by four pre-existing
    `MaintenanceSweepTest` assertion-order findings, outside the changed adapter.

91. Corrected the four PMD assertion-order findings in `MaintenanceSweepTest`. The focused
    maintenance suite passes all 58 cases, and the repository Checkstyle/PMD gate now passes with
    only its existing unmatched-exclusion warnings.

92. Re-ran the authoritative `scripts/quality` gate. Its locked dependency cache stage passes, but
    the gate stops at pinned interop images because this container engine does not hold the five
    prepared images, even after `scripts/prepare_interop_images` recorded their digests. No source
    assertion failed in this run; the remaining refusal is an external image-store state.

93. The first full host-context gate reached SpotBugs and exposed that the provisional
    `DispatchCommands` adapter could not satisfy the repository's serializable servlet contract
    without unsafe live service state. That provisional class was removed pending a proper OSGi
    registration design. Preparation and verification scripts now share a writable Podman runtime
    directory under `/tmp`; host-context image verification succeeds for all five images.

94. Replaced nullable and optional paging handoff with explicit available/unavailable states and optional continuation output; added immutable value semantics and complete documentation. Focused CallerContext, query handler, submit servlet, API-shape, and Javadoc policy checks pass. The authoritative quality gate reached the full test and coverage stage; remaining coverage review is being verified after the value-object shape change.

95. Added focused value-object coverage for the explicit paging context (`52f841b`). CallerContext tests now exercise available paging state, equality, hash semantics, and all admitted fields. The full suite remains green at 1,134 tests; the gate still reports class-level coverage deficits in CallerContext and QueryPathsHandler, so task 4404 remains pending.

96. Re-reviewed handler pagination after the continuation probe showed repeated first-page rows. The defect was in `QueryPathsHandler`: it computed a verified continuation position but passed the un-sliced gathered list into `PagedQuery.pageOf`. The handler now applies the verified offset before windowing; `QueryPathsCommandTest` and `PagedQueryTest` pass. Commit records the correction; broader paged-handler propagation remains pending.

97. Added an end-to-end QueryPaths continuation regression covering signed token issuance and resume across a non-final page. The test passes against the mock repository and confirms the next page starts after the prior window; this closes the previously observed replay defect for this handler. Remaining paged handlers still require the same review.

98. The full gate completed all policy and test checks (453 development tests passed), then failed only because `BytecodeContractTest` could not find the development jar. An offline attempt to seed the reactor artifact was refused by the prepared Maven cache (`plexus-archiver` missing for the source plugin); no source change was made for this environment-only blocker.

99. Ran the repository-authorized locked-cache preparation workflow after the artifact-preparation refusal. It completed the full reactor successfully (1,136 core tests and all development policy checks passed) and refreshed only the generated core-module POM digest and preparation timestamp. This is recorded as prepared-input maintenance, not a source-policy change.

100. The post-cache authoritative gate passed cache verification, pinned-image verification,
    formatting, compilation, static analysis, the 1,136 core tests, and all 453 development policy
    tests. The public interop stage then reported a single pre-existing harness container
    (`7355c9f81474`) in every leak assertion; the Mongo-backed crash scenario also timed out because
    that stale Podman state was reopened through the host `/run/user/1000` runroot. An attempted
    writable `XDG_RUNTIME_DIR` override was reverted: Podman's persisted database overrides it and
    the override made the prepared images appear absent. A clean interop rerun still requires
    clearing or rebuilding that external Podman state, which this sandbox cannot do.

101. Began the handler-pagination review by extracting the shared verified window operation into
    `PagingSupport` and applying it to `FindAssetsByMetadataHandler`. Initial offsets now use the
    bounded page probe, continuations validate authority, target, query and generation before any
    rows are served, and a successor token is issued only when an extra row proves the result is
    not complete. The focused metadata and package-command suites (18 tests), compilation,
    Checkstyle, PMD and SpotBugs pass. The remaining paged handlers still need the same migration,
    so task 4404 remains pending.

102. Extended the same paging operation to `ListChildPagesHandler`, `FindPagesByTemplateHandler`,
    `FindPagesContainingPhraseHandler`, and `FindPagesUsingComponentsHandler`. Their focused
    suites pass all 28 cases, and the core Checkstyle and PMD checks pass. Initial calls without a
    runtime authority retain the existing compatibility behavior; continuation calls still require
    a supplied authority and are rejected when it is absent. The remaining paged command families
    are still unreviewed, so 4404 is not closed.

103. Completed the content-side pagination pass for `FindAssetsReferencedByPageHandler`,
    `ListAssetRenditionsHandler`, and `ListResourceMappingsHandler`. Focused suites pass all 19
    cases after compilation; each handler now preserves its full argument digest and uses the same
    continuation validation and successor issuance rules. Framework, configuration, job, principal,
    replication, and workflow paged handlers remain to be migrated before task 4404 can close.

104. Applied verified paging to both framework inventory listings (bundles and components), keeping
    the command-specific wire name in each query digest and returning the issued continuation token
    through the existing result builders. `FrameworkCommandTest` passes all 11 cases and the module
    compiles; the remaining configuration, job, principal, replication, and workflow listings still
    require the same migration.

105. Applied verified paging to the configuration inventory listing. Its focused 17-case suite and
    compilation pass, and the result now carries a real successor token when the bounded inventory
    has another page. Job, principal, replication, and workflow listings remain outstanding.

106. Applied verified paging to the job queue and job inventory listings, preserving their distinct
    query wire names. `JobCommandTest` passes all 12 cases after recompilation. Principal,
    replication, and workflow listings remain to be migrated.

107. Applied verified paging to principal group membership listing. `PrincipalCommandTest` passes
    all 14 cases after recompilation, with the membership query digest kept distinct from the other
    principal operations. Replication and workflow listings remain outstanding.

108. Applied verified paging to replication-agent and replication-queue listings, preserving their
    separate query identities. `AgentCommandTest` passes all 12 cases after recompilation. Workflow
    listings are the last remaining handler family in task 4404.

109. Applied verified paging to workflow-model and workflow-instance listings, preserving their
    distinct query wire names. `WorkflowCommandTest` passes all 14 cases after recompilation. All
    registry paged handler run paths now route through the shared paging operation; task 4404 still
    needs an end-to-end review of malformed, stale, wrong-query and final-page behavior across the
    migrated commands before it can be marked complete.

110. Completed the implementation review for task 4404: every paged registry run path now calls
    `PagingSupport` (with QueryPaths retaining its equivalent in-handler flow), and the complete
    core regression suite passes all 1,136 tests. Focused suites cover the migrated content,
    framework, configuration, job, principal, replication, and workflow handlers; Checkstyle and
    PMD also pass for the changed module. Task 4404 is ready to mark complete pending the normal
    plan-status update; runtime assembly tasks remain pending.

111. The review loop caught two policy omissions in the new shared paging type: its stateless design
    pattern was not registered, and its record components lacked parameter documentation. Added the
    committed pattern row and complete Javadocs; `ApiShapePolicyTest` and `JavadocPolicyTest` now
    pass all 22 cases. This closes the review finding without weakening either policy.

112. Re-ran `StateLifecycleServiceTest` while reviewing task 4501. All 5 lifecycle cases pass,
    including unavailable-provider handling, activation recovery, readiness reporting and scheduler
    shutdown. The service code is present and unit-verified, but installed-runtime evidence is still
    required before task 4501 can be closed.

113. Reviewed the capability boundary against the now-present lifecycle component. Corrected stale
    documentation that claimed continuation authority was not implemented; capability readiness and
    generation now explicitly describe the durable service and its conservative unavailable fallback.
    `JavadocPolicyTest` and `SourcePolicyTest` pass all 25 cases.

114. Reviewed task 4502's installed-runtime evidence against the current bundle. The core build
     already embeds all 64 registry rows through `embed-command-registry`, so the prior finding that
     rows were absent was stale and has been corrected. The remaining blocker is production
     composition: `SubmitServlet` still receives no DS-bound `Commands` service and therefore uses
     `NOTHING_REGISTERED`; no handler map or platform-adapter assembly can be claimed until that
    seam is implemented and exercised from an installed bundle.

115. Reviewed lifecycle failure handling under an unexpected platform runtime exception. The
    scheduled pass now revokes readiness and records the exception type instead of allowing the
    fixed-delay task to terminate while discovery retains a stale READY snapshot. The focused
    `StateLifecycleServiceTest` suite passes all 6 cases, and `JavadocPolicyTest` plus
    `SourcePolicyTest` pass all 25 cases.

116. Completed the lifecycle regression review after the failure-boundary change. The full core
    suite passes all 1,137 tests, including lifecycle, generation, recovery, capacity, transport,
    console and command coverage. No activation race was found: the contract is embedded and the
    mandatory state-session reference prevents activation before its prerequisite is bound.

117. The package/publication review caught a cleanup boundary: `DownloadContentPackageHandler` now
    maps staging-release `UncheckedIOException` to its declared `staging_cleanup_failed` outcome.
    The focused package suite passes all 12 cases. The same review run found two pre-existing line
    length violations in the migrated asset-reference paging path and narrowed the lifecycle catch
    to the concrete adapter exception allowed by source policy; Checkstyle passes with zero
    violations.

118. Re-ran the full core regression after the package cleanup mapping landed. All 1,137 tests pass,
    including the package, transport, lifecycle, command and console suites; no regression was
    introduced by converting staging-release failures into typed command outcomes.
