# Evidence-backed runtime findings

Paths below are relative to the repository root. Line numbers refer to the reviewed commit.
Probe sources and observed outputs are preserved in [EVIDENCE.md](EVIDENCE.md).

## R01 — P1: The installed runtime has no command implementation

**Current installed behavior.** SubmitServlet's no-argument constructor selects NOTHING_REGISTERED
(lines 162–163); its serves method returns false (133–134), and admission returns 400 before any
command runs (274–278). A real installed, Active core bundle answered capabilities with an empty
command_contracts array and rejected a locally validated query_paths submission with HTTP 400. That response corroborates the source
finding; the probe did not instrument the exact rejection branch.
The bundle contains eight DS components, all routes/alias, and no command registry TOML resources.

CapabilityServlet (54, 143–144, 162–167, 181–186) also supplies fixed generation 1 and readiness false instead of
reading the durable generation/key authority. CommandRegistry.read (93) requires a filesystem policy
directory. Aem's ReplicatorAdmission has no DS registration; required platform adapters lack production
registrations. Console resources reference slingshot-agent/datasource types with no
registered renderers. Scheduler configuration files do not themselves connect the maintenance or
recovery classes.

**Impact:** the declared command surface cannot be used; configuring or installing the package does
not complete its assembly. Utility classes and command conformance rows are insufficient.

**Repair:** package the registry and compose real immediate dispatch and supported adapters; bind
discovery to actual readiness; activate the state lifecycle and console resources. Require successful
installed-bundle command execution and populated console rendering. Keep deferred execution refused.

**Tasks:** runtime-command-assembly, runtime-lifecycle-services, console-runtime-assembly.

## R02 — P1: Identical concurrent writes defeat the exclusivity primitive

**Reproduced on embedded Oak.** CompareAndSet.java:52–67, ClaimByCreation.java:50–65 and
OperationStore.java:184–198 rely on a failed save to identify a competing writer. Oak can merge
identical concurrent assignments/creations. Deterministically pausing one save and completing the
other produced two WRITTEN results for the same expected/next value, two Accepted admissions for
the identical submission, and two Held transitions from ACCEPTED to RUNNING.

SubmitServlet.java:351–364 runs the command after those successful outcomes and describes the state
write as its complete mutual exclusion. Identical counter increments use the same defective premise.

**Impact:** both contenders can pass the gates intended to prevent duplicate mutation, and quota
increments can be lost. The probes exercise admission/start separately; they do not claim to have
performed two mutations through the currently disconnected installed dispatcher.

**Repair:** make competing ownership writes conflict using a unique persisted acquisition/version
identity, or another conditional-write mechanism proven on the supported Oak stores. The winning
identity must participate in the same transaction as the protected change; checking it earlier is
insufficient. Verify identical requests and identical next values, not only differing writes.

**Task:** exclusive-store-transitions.

## R03 — P1: State authorization is substituted with repository visibility

**Reproduced through the servlet using a real Oak non-owner session.** OperationLookupServlet.java:
195–211 reduces readability to nodeExists. PhysicalJobServlet.java:130–139, ArtifactServlet.java:
187–202, HighWaterServlet.java:146–165, StreamSession.java:146–157 and IntakeSlotWrite.java:145–166
likewise lack the declared owner-or-operator decision. The probe granted a non-owner only read access
to the state subtree, no administrator membership, and obtained another caller's snapshot with 200.

SubmitServlet.permittedGroups (540–541) separately hard-codes administrators: the shipped
AuthorizationGate configuration cannot add a dedicated operator group or remove administrators.
Every state route adapts the request resolver to its JCR session. AgentSession.withAgentState exists
but has no production call site. The shipped ACL grants state writes to the service identity, not
ordinary content authors.

**Impact:** a permitted non-admin operator cannot do internal bookkeeping without extra repository
grants, while broad state read access exposes other callers' records. Reusing the service session
without adding explicit ownership checks would make that exposure worse. The probe's extra ACL is
an explicit condition; it is not a claim that shipped anonymous users can read the state tree.

**Repair:** wire live group configuration and a central ownership decision; use narrow service
sessions for state and the original caller only for requested content actions. Bind subscriptions,
artifacts and operations to persisted owners. Test owner, operator, unrelated reader and removed
operator across every state route.

**Tasks:** live-operator-configuration, state-access-and-ownership.

## R04 — P1: Acceptance can strand work or acknowledge a missing terminal outcome

**Reproduced using SubmitServlet with its existing command injection seam.**

- Admission persists ACCEPTED before execution capacity is checked (SubmitServlet.java:279–324).
  After capacity frees, retry follows Recognised and never starts the work (300–303):
  first=503, retry=202, executions=0, state=ACCEPTED.
- running ignores TerminalCommit.commit's outcome (371–375). Filling terminal event capacity inside
  a successfully invoked handler produced status=202, state=RUNNING, resultPresent=false.
  TerminalCommit can explicitly return AtCapacity or Refused.
- A submission with outstanding intake slots returns early (284–296). ArtifactIntakeServlet.java:
  144–155 acknowledges the completed upload; nothing consumes Written.outstanding==0 to execute the
  command, and resubmission still takes the Recognised branch.

**Impact:** ordinary saturation, failed finalization or payload intake leaves clients waiting on work
that will never finish. A handler may already have changed content before its outcome is lost.
The hardcoded SUCCEEDED outcome and uncaught handler failures also need a real typed completion path
when dispatch is connected.

**Repair:** give durable acceptance a defined progress path under the original caller request,
reserve required execution/finalization resources before an irreversible effect, handle every terminal
commit outcome, and make intake completion explicitly startable and retry-safe. Recovery must
classify uncertain effects without blindly repeating them or acquiring someone else's identity.

**Tasks:** resumable-request-admission, reliable-terminal-finalization, intake-completion-execution.

## R05 — P1: Durable command results have no transport consumer

**Source-proven; enabled-dispatch blocker.** TerminalCommit writes result_document/result_slot and
provides answerIn (TerminalCommit.java:285–303,330–365), but no production caller invokes answerIn.
OperationLookupServlet.rendered (214–230), terminal event construction and JobSnapshot emit only
generation, operation identifier, kind and sequence. SubmissionResponse is an acknowledgement.
ArtifactServlet requires a known slot, whose descriptor is not exposed by any of those responses.

**Impact:** even an otherwise successful command cannot return its inline answer through these routes;
a client cannot discover the actual overflow descriptor from the stored terminal result.

**Repair:** connect the stored result/failure to the sibling's authoritative response envelope and
resynchronisation contract. Do not invent an extra snapshot member without reconciling schemas and
digests. Prove known inline bytes and an overflow artifact can be recovered after response loss.

**Task:** terminal-result-delivery.

## R06 — P1: Event delivery never updates the durable subscription cursor

**Reproduced through the stream servlet.** StreamWriter.java:175–207 writes and flushes events and
updates only a local cursor. There is no production call to HighWaterMark.advance anywhere.
The probe emitted event:succeeded with HTTP 200 and then read NOTHING_SHOWN_YET from the same
subscription. Submission likewise never calls SubscriptionLedger.subscribe, so the probe had to
seed the subscription exactly as the current tests do.

**Impact:** normal submissions cannot establish their follow-up stream, and a manually established
subscription reports no delivery even after sending terminal news. LAST_ADVANCED_AT never renews
through delivery, so active subscribers can expire and reconciliation cannot rely on their cursor.

**Repair:** persist the owned subscription during admission; define and implement the transport's
delivery/acknowledgement point, including flush-versus-commit ambiguity; advance cursor and activity
together. Prove reconnect, terminal completion, reset and disconnect against durable state.

**Task:** durable-stream-progress.

## R07 — P1: Wrong artifact bytes become durable before digest validation

**Source-proven; intake activation blocker.** IntakeSlotWrite.streamed (195–213) calls
ArtifactStore.publish, which saves the binary, before verified checks its declared digest
(IntakeSlotWrite.java:217–231). A mismatch is undone by a later delete/save. outstanding and the
ALREADY_COMPLETE guard treat existence in ArtifactStore as completion.

**Trigger:** a crash or another request between publication and rejection. **Impact:** an invalid
payload survives as a completed slot, or concurrent intake mistakes it for validated input. Retry
can be refused as already complete. A subsequent compensating deletion is not an atomic guarantee.

**Repair:** validate length and expected digest before publishing a completed slot, or stage bytes in
a state that completion/readers cannot observe. Couple publication with the reservation transition.
Inject a failure and a competing reader at each persistence boundary.

**Task:** atomic-intake-publication.

## R08 — P1: Concurrent release loses quota and permanently blocks callers

**Reproduced on two Oak sessions.** CapacityLedger.java:169–171 ignores ShardedCount.advance's
WriteOutcome. ShardedCount.java:52–53 reads the expected count before CompareAndSet refreshes it.
Two racing releases left total=0, caller share=1 after both operations ended.

**Impact:** repeated losses exhaust the caller's eight command slots with no work running.
SubmitServlet's finally block and StreamAdmission.close both call this path. Total and caller
accounting also commit separately, so a release cannot be treated as a single successful event.

**Repair:** track idempotent reservations and release caller/total accounting atomically, retrying
contention from fresh state and surfacing unrecoverable failure. Reconcile process death from durable
reservation identities. Do not merely clamp negative values and hide the discrepancy.

**Task:** consistent-capacity-accounting.

## R09 — P1: Cleanup and generation rotation publish partial durable transitions

**Reproduced with injected Oak save failures; currently dormant lifecycle code.**

- MaintenanceSweep.java:169–178,219–220,270–277 releases counters before deleting records. Crash
  before deletion left an artifact present with rows=0, bytes=0; retry deleted it and left
  rows=-1, bytes=-4. SubscriptionLedger.end (235–243) releases again even for an absent subscription;
  two calls produced rows=-1.
- GenerationStore.java:160–170 saves the serving generation before history;
  GenerationRotation.java:144–148,168,193–198 saves retention afterward. Interruption left
  serving=2, history=[1], and generation 1 immediately Retired despite promised retention.

**Impact:** negative counters allow over-admission; partially rotated state makes retained work
unreadable and breaks replay/reconciliation.

**Repair:** atomic or durably recoverable, idempotent transitions for deletion/accounting and for
serving/history/retention. Exercise every actual save boundary and duplicate/concurrent requests.

**Tasks:** atomic-retention-cleanup, atomic-generation-rotation.

## R10 — P1: Lease metadata and key writes do not establish durable authority

**Reproduced; currently dormant foundation code.** ExecutionFence.written saves expiry through CAS
before saving owner (148,156–157). Crashing between saves leaves nonzero expiry without an owner.
holderIn returns absent (168–169), so take expects zero (66) and continues losing even after expiry.

DefaultContinuationKeyAuthority.compareAndSet (169–194) checks only the supplied Lease's expiry,
not the persisted RotationLease holder. With a real lease owned by real-holder, a fabricated
unexpired lease owned by never-took-the-lease was accepted as Written.

**Impact:** a partially acquired fence can never be recovered; key writers can violate single-writer
rotation and invalidate continuation/retention promises. This is not evidence of an exposed remote
key-rotation exploit.

**Repair:** persist owner, expiry and acquisition epoch together; require the current persisted
epoch in protected writes and renewals. Recover incomplete historical records and validate legal
key-retention transitions.

**Tasks:** atomic-execution-fences, fenced-continuation-authority.

## R11 — P1: Incomplete reference discovery permits destructive mutation

**Reproduced through handlers; currently behind disconnected dispatch.**
RepositoryReach.pointingAt (71–91) returns partial matches without an exhausted outcome.
DeletePageHandler.java:149–164 treats an empty partial scan as permission to delete;
MovePageHandler.java:155–176 adjusts only discovered references.

With a reference after the first examined node, budget 1 found none and budget 100 found one.
delete_page with refuse_when_referenced then returned Produced, deleted the target and left its
incoming link unchanged. The ordinary discovery budget is 1,024 nodes, so this is a realistic
content-size boundary, not only an artificial limit of one.

**Repair:** distinguish complete from exhausted discovery and refuse destructive actions on an
incomplete scan. Use approved indexed discovery where needed; define what caller-invisible
references can and cannot be promised. Verify single/multiple-value links beyond the budget.

**Task:** complete-reference-guards.

## R12 — P1: Component deletion accepts an arbitrary writable subtree

**Reproduced through ComponentPathHandler; currently behind disconnected dispatch.**
ComponentPathHandler.java:131–137 validates only existence. removed (171–181) recursively deletes;
under (323–334) is unbounded and run ignores CallerContext. The component_invalid category is
declared but not returned by this guard.

An ordinary sling:Folder with five children, discovery budget 1, was deleted successfully as a
component, reporting removed_node_count=6. A mistaken writable site-root path is not rejected.

**Repair:** establish component identity and valid parent/context before any writes, and bound
subtree enumeration/deletion. Refuse folder/page/root paths and oversized component trees with the
repository unchanged. This concerns deletion scope, not escalation beyond the caller's ACLs.

**Task:** bounded-component-deletion.

## R13 — P1: Real handler paging drops rows and bypasses token validation

**Reproduced through handlers; currently behind disconnected dispatch.**
ListChildPagesHandler.java:93–99 truncates rows but always passes an empty continuation;
175–182 treats a ResultWindow.Continuation as the default first page. QueryPathsHandler.java:85 returns all gathered
paths without applying its paging helper. Other listing handlers repeat the empty-token pattern.

Three children queried at offset 1, limit 1 yielded only the middle child and no continuation;
a nonsense token returned a successful first page. query_paths with offset 1, limit 1 returned all
four paths. ResultWindow.initial(-1,-1) also accepts values later rejected by skip/limit.

**Impact:** clients silently miss or repeat content and cannot trust requested windows or token
authentication. Standalone PagedQuery tests do not prove handler integration.

**Repair:** connect verified query-bound continuation issuance/resolution and enforce windows on the
actual run path across the registry. Reject invalid initial bounds. Test concatenation, ordering,
end-of-results and foreign/expired/modified tokens through dispatch.

**Task:** correct-handler-pagination.

## R14 — P2: Traversal budgets do not bound reads or retained work

**Reproduced.** RepositoryReach.under (42–53) and QueryPathsHandler.gather (155–175) consume every
child into a queue before checking their node bounds. ListChildPagesHandler (93–96,130–141) counts
matches only after enumeration. A bound-one probe consumed a 10,000-child iterator before stopping.
MaintenanceSweep.java:68–79 checks its bound between buckets; 129–149 materializes and processes an
entire bucket. Two same-bucket records with a row bound of one yielded examined=2.

**Impact:** wide author content or a dense operation bucket still causes unbounded repository reads,
queue allocation and time on shared server resources.

**Repair:** bound iterator advancement and retained work, not just output rows. Preserve ordering
and resumable cursors inside buckets. Account nonmatching children and enforce time budgets during
enumeration. Prove wide/deep and dense-bucket cases with instrumented iterators.

**Tasks:** bounded-repository-traversal, bounded-maintenance-pages.

## R15 — P1: Package download reports an artifact that was never built

**Source-proven; enabled-dispatch blocker.** DownloadContentPackageHandler.java:234–273 stages only
filter.xml, hashes that manifest, fabricates an OverflowPublication.Published descriptor, and
closes its staging area. It creates neither a FileVault archive containing selected content nor an
ArtifactStore publication.

**Impact:** success promises a package whose byte count/digest describe the filter and whose
downloadable bytes do not exist.

**Repair:** build a valid bounded package using the supported platform adapter and caller-visible
content, publish the complete archive durably, and derive the result from the committed artifact.
Verify archive entries, package validation and HTTP download bytes/digest after staging closes.

**Task:** real-package-publication.

## R16 — P1/P2: Current proof paths cannot establish successful runtime behavior

**Observed gate and source evidence.**

- The full scripts/quality reached core tests and stopped: 857 tests, two HighWaterServletTest
  failures (expected 200, actual 410; expected three response members, actual zero).
  HighWaterServletTest.java:55 uses fixed NOW=1788000000000 while HighWaterServlet.java:177 reads
  today's wall clock. The supposedly live subscription expired as the calendar advanced.
- CreatePageScenario.java:93–108 sends anonymous or malformed submissions; ConsoleRenderScenario.
  java:107–115 inspects an anonymous response and local XML; RedactionScenario.java:138–163 scans
  refusals without requiring a successful stream. EventStreamScenario.java:24–28 acknowledges that
  no successful stream is exercised.
- CrashConsistencyScenario.java:109–138 writes a generic node through Sling POST, kills a node,
  and reads that node. It never interrupts the agent's actual multi-save transitions.
- scripts/interop_quickstart_tier runs QuickstartTierTest, which expects the owner's actual jar to
  be absent (40–43). Valid inputs still reach QuickstartTier's unconditional not-built refusal
  (134–135). scripts/interop_client_tier runs ClientConformanceScenario, which expects the executable
  absent (43–49) and does not run the client.

**Impact:** green local class/registration checks cannot establish the advertised command effects,
redaction of successful results, exactly-once behavior, populated console or AEM/client compatibility.
A supplied licensed jar/client executable currently cannot close that evidence gap.

**Repair:** inject the runtime clock for repeatable expiry tests; require positive installed-runtime
paths and fault actual agent save boundaries; separate prerequisite-refusal unit tests from real
owner-tier entrypoints. Record exact deployment/artifact/client identities and leave unavailable
tiers explicitly unproved. The P1 part is false confidence in mutation/exclusivity; the P2 part is
test repeatability and owner-tier execution infrastructure.

**Tasks:** deterministic-subscription-time, positive-runtime-acceptance, executable-owner-tiers.

## R17 — P2: Transfer deadlines cannot interrupt blocked I/O

**Source-proven; artifact-serving path.** ArtifactServlet.transfer (283–313) calls blocking read,
write and flush before checking TransferDeadlines, then resets lastMovedAt immediately after a
write. The initial read has no check at all. It measures using ticker.milliseconds (wall time);
TransferDeadlines subtracts wall timestamps. BoundedRequestBody.read likewise has a byte limit but
no elapsed/idle bound. StreamWriter checks its session limit only between blocking Writer calls.

**Trigger and impact:** a storage read or peer write that does not return prevents the code from
checking its promised bound, holding a request/stream worker indefinitely unless the container
independently enforces a stricter timeout. A wall-clock correction also changes artifact duration.
No live socket-timeout failure was reproduced in this review; the source proves these local checks
alone do not enforce the promise, not that every deployment lacks an effective container timeout.

**Repair:** establish and verify a transport-supported cancellation/timeout mechanism for blocked
I/O and monotonic duration measurement, with cleanup after failure before writer handoff as well
as during writing. Exercise a reader that sends no further bytes and a peer that never drains the
response, and verify threads/sessions/reservations are released within the published bound.

**Task:** enforceable-transfer-deadlines.
