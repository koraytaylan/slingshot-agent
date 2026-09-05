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
