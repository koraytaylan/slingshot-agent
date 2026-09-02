# Plan 0003 — Durable Store and Logical Execution — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending.
- **Goal:** turn a job system that delivers physically at least once into one logical operation with exactly one effect, whose durable record answers the same way after a restart, a cluster handover, or a process killed between any two steps.
- **Root cause:** the client's entire recovery story assumes the agent treats the derived operation identifier as an idempotency key, looks work up rather than resubmitting it, and reconciles from what the agent says. None of that holds unless what the agent says is durable, monotone, and unchanged by a restart — and Sling delivers a job more than once, a cluster moves work between nodes, and Oak has no lock a stopped process gives back.
- **Approach:** build on the only two primitives Oak actually offers — claim by node creation and a compare-and-set that fails rather than merges — and refuse a third: Oak's atomic counter mixin consolidates asynchronously on a clustered document store, so a count it backs is right on one instance and quietly wrong on a customer's author, and accounting is therefore sharded compare-and-set proved on two nodes sharing one repository; derive every path from an identifier and issue no query on the hot path, because a stale index is an idempotency answer that is wrong rather than slow; decide idempotency on the independently derived submission digest, compared beside the target identity and environment revision the derivation does not cover, so a resend converges and a different command — or the same command aimed somewhere else — under one identifier is a reported conflict rather than a silent overwrite; record physical attempts in a bounded outbox that never multiplies the logical operation; fence execution with a renewed lease whose loss stops a worker without finishing, backed by a terminal compare-and-set so two workers can neither do the work nor report it twice; write the outcome, the result, the terminal event, and the snapshot in one commit — built after the ledger and the artifact store rather than before them, because a terminal state with no terminal event is a lookup that says finished to a subscriber still waiting and a commit that wrote artifact bytes itself would be a second artifact writer — with artifact bytes committed before the reference that names them; admit through one accounting authority built with the store primitives rather than beside its callers, against per-generation counters and against each caller's own share, before writing, so no client can spend the store on everybody's behalf; anchor retention where the client anchors it, refuse a request-start further from this side's clock than the contract allows, and sweep with a bounded, resumable, deterministic pass that never collects bytes a worker is still about to name; reconcile from the store alone at startup and on an interval, redelivering the one case a client has been told yes about and nothing will ever run, and never re-identifying or resubmitting ambiguous work; rotate generations explicitly with bounded prior retention; and prove all of it by killing the container mid-flight in the public tier and reading the store on the other side.
- **Progress:** 21/21 tasks done; 0 blocked; 0 dropped. Workstream 0009 is complete: the state
  layout, the two conditional-write primitives with sharded counting, the event-store generation,
  the durable key ring, and the one capacity authority every later admission goes through.
  Workstream 0010 is complete: the logical operation record with its whole transition matrix,
  submission idempotency decided on the independently derived digest beside the target and the
  environment revision, the outbox that collapses duplicate deliveries, the execution fence, and
  the job consumer that records a delivery, drops the four kinds no retry can fix, and runs nothing
  for work that already finished. Workstream 0011 is complete: the sequenced append-only ledger
  bounded per operation and per generation through the one capacity authority, the snapshot written
  inside the event's own commit with a pass that folds the ledger and compares all three
  representations, the durable subscription cursor that only ever moves forwards, filtered replay
  with a reset that carries what to resynchronise from, the artifact store that reserves before the
  first byte and commits the bytes with their count and digest, and the terminal commit that lands
  the state, the answer, the last event, and the snapshot together or not at all. Workstream 0012 —
  retention, the sweep, restart recovery, and rotation — is next.
- **Integration:** `in progress`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `pending`; mode `sequential`; final integration `pending`.
- **Exceptions:** eight, each recorded where it was made.
  - A claim on a document store does not fail for the writer that loses. Two nodes can each find a
    path free, each create it, and each be told they claimed it; the store resolves the collision on
    its own background read and both nodes then agree. That was found on two nodes against one
    shared repository rather than assumed, and it is recorded on `ClaimByCreation` itself: a claim
    means "this node created this record" rather than "no other node did", which is safe because
    what a claim writes is derived from the identifier it claims. Which worker may execute is
    decided by the fenced lease of task 1004, precisely because a claim cannot decide it.
  - Task 0904's authority is named `DefaultContinuationKeyAuthority` rather than the
    `RepositoryKeyAuthority` its footprint names. It is the only implementation of the authority
    contract in this repository, and `policy/api-shape.toml` says a sole implementation is named
    `Default` after its interface. The plan's own name said where it keeps the ring; the class's
    first sentence says that instead.
  - A count is spread only as far as it can afford to be understated. Sharding costs a margin of
    one advance per other shard, and a bound of eight sharded sixteen ways would refuse at eight
    less sixteen — a bound that can never be met. So the shard count is derived from the bound: at
    most one shard per sixteen of what a count may hold, never more than the ceiling, and a count
    too small to shard is exact instead. That was found by writing the admission suite rather than
    by reasoning about it, and it is why `ShardedCount` takes the shard count rather than assuming
    it.
  - Task 1004's two-node fence-handover scenario is declared and unproved, for the same reason as
    the other two: taking a fence is this repository's own Java, and driving two workers at it from
    outside needs a route Plan 0004 owns. What is proved here is the fence against a real Oak
    repository, including a refusal while a hold is live, a takeover after it runs out, a renewal
    that only its holder can make, a busy store told apart from a handover, and a worker that lost
    the fence writing nothing further.
  - Task 0905's two-node capacity-admission scenario is declared and unproved, for the same reason
    as task 0904's rotation scenario: admission is this repository's own Java, and driving it from
    outside an instance needs a route that Plan 0004 owns. What is proved here is the ledger against
    a real Oak repository, including both sides of a total and a per-caller bound, a refusal that
    leaves the counts where it found them, and a second caller admitted while the first is at its
    share.
  - Task 1205 proves what it can prove without a route and no more. `CrashInjector` and
    `CrashConsistencyScenario` are real and run: a node is ended with the signal nothing can handle
    and what it committed is read afterwards from the node that is still up, across the document
    store the two share — which is the ground every crash point stands on, and the seven points
    themselves are enumerated as a closed type rather than as prose. That it is proved across two
    nodes rather than by restarting the one that died is a finding: the pinned public image does not
    come back to a serving state after an ungraceful kill, because the runtime's own model layer
    fails to weave classes out of a bundle cache that was mid-write when the process ended. A
    customer's author is a cluster, where the durable state is in the store and a node is a thing
    that can be replaced, so this is also the arrangement the proof belongs on. What is declared and unproved
    is the half that needs a client: crashing between a submission and its start, or between a
    command's own commit and the terminal one, needs a route that submits work and a client
    recovery path that looks it up again, and Plan 0004 owns both. `DuplicateDeliveryScenario` is
    deferred with them, for the same reason as task 1005's redelivery scenario: a job cannot be
    delivered from outside an instance that has no route to submit one. Neither scenario file is
    written down, because `ScenarioInventory` matches declared scenarios against classes in both
    directions and a file with no class would fail the gate rather than record an intention.
  - Task 1005's `JobRedeliveryScenario` is declared and unproved, and its scenario file is not
    written down at all: `ScenarioInventory` matches declared scenarios against classes in both
    directions, so a file with no class would fail the gate rather than record an intention.
    Redelivery on a running instance needs a route that submits a deferred command, and both the
    route and the interop tier's own fake deferred command belong to Plan 0004. What is proved here
    against a real Oak repository is every verdict the consumer reaches: a delivery executed under
    a fence this node took, one left to the node that holds it, an unknown operation, a foreign
    generation, malformed properties and a nameless delivery, a hand-crafted job naming an
    immediate row, a redelivery of finished work that is recorded and runs nothing, and a delivery
    past the attempt bound.
  - Task 0904's two-node key-rotation scenario is declared and unproved. Rotation is this
    repository's own Java, and driving it from outside an instance needs a route; Plan 0004 owns the
    transport surface, and the scenario arrives with it. What is proved here is the authority
    against a real Oak repository: a ring established once, a rotation lease held by one node at a
    time, every write a compare-and-set against what was read, no branch on node count or
    deployment anywhere in the type, and a refusal to start at all without the platform's strong
    secure source.
- **Outcome:** tasks complete: one logical operation with exactly one effect, built on the two
  primitives Oak actually offers and refusing the third; a submission idempotency decided on the
  independently derived digest beside the target and the environment revision; an outbox that
  collapses duplicate deliveries; a fenced lease no stopped process keeps; an append-only ledger
  bounded per operation and per generation through one capacity authority; a snapshot written in
  the event's own commit with a pass that folds the ledger and compares all three representations;
  a durable subscription cursor that only moves forwards; filtered replay with an explicit reset;
  an artifact store that reserves before the first byte and commits bytes with their count and
  digest; a terminal commit that lands the state, the answer, the last event and the snapshot
  together or not at all; retention anchored where the client anchors it; a bounded resumable sweep
  that collects only what nothing can still be holding; reconciliation that classifies unfinished
  work and starts none of it; explicit rotation with prior incarnations kept and readable; and a
  crash proof on two nodes sharing one document store. What is not proved here is every scenario
  that needs a client to drive it, which Plan 0004 owns.

_Last updated: 2026-09-02, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
