# Repair architecture and sequencing

## Make store exclusivity real before enabling mutations

Ownership must be a persisted identity that genuinely conflicts when two sessions compete. A
successful JCR save of identical bytes does not establish a unique winner. Admission, operation
start, counters, fences and generation rotation must use the same proven conflict mechanism.
An in-process lock cannot prove a multi-node invariant.

Persist related facts in one commit where they belong to the same state transaction. Where the
content effect and bookkeeping use different sessions or a platform admission cannot be committed
atomically with state, explicitly represent the unknown outcome and reconciliation path. Do not
promise a single transaction across those boundaries and do not re-execute an uncertain mutation.

Capacity reservations need identities and terminal dispositions so retries, cleanup and recovery
can decide whether a release has already happened. Counter clamping cannot repair lost ownership.

## Separate caller access from state access

Use the narrow service identity only for the agent's state tree. Carry the authenticated caller and
persisted ownership separately from that session, and check owner/operator authority before every
state read, stream or write. Continue to execute caller-requested content through the original
request resolver. Never expand author grants to internal state to compensate for missing wiring.

Operator configuration is an active runtime input. Removing a group must affect the next admission
and diagnostic access decision. Subscriptions and intake slots must belong to the same persisted
caller/operation authority as their parent operation.

## Connect complete request lifecycles

Admission must have a defined path from waiting for capacity or payload to execution under a live
caller request. Reserve finalization capacity before effects where possible; always inspect the
terminal commit result. Persist typed failures and explicit unknown outcomes as well as successes.
An acknowledgement does not substitute for a retrievable terminal result.

The last intake completion must be both validated and startable exactly once. A staged binary is
not a completed slot until expected size and digest have been validated. Connect submission,
subscription registration, stream delivery, durable cursor progress, and terminal result retrieval
as one transport contract, including disconnect ambiguity.

## Bound the work that is actually performed

Check budgets before advancing iterators and retaining children, with explicit exhausted outcomes.
A partial reference search can never authorize deletion or a complete reference rewrite.
Continuation tokens belong on the actual command path, with authenticated query identity and
unambiguous end-of-results. Maintenance cursors must resume inside a bucket.

Durations use a monotonic clock. A bound checked between blocking operations is not sufficient;
transport deadlines must be enforceable while I/O is stalled and cleanup must survive handoff
failure, worker rejection, disconnect and shutdown.

## Activate only after safety dependencies pass

The task DAG puts exclusivity/accounting/access repairs and command guard repairs before runtime
command assembly. Lifecycle services and console assembly are separate targets, so completion of
one cannot imply that the others are running. Package the registry and compose real supported
platform adapters; do not load development policy paths on an author host. Only advertise active,
usable capabilities and read the serving generation/key readiness from durable authorities.

Deferred commands remain refused. ExecutionFence's dormant recovery defect should be repaired as
a foundation guarantee without adding deferred caller execution to the shipping surface.

## Verification order and release decision

1. Run deterministic regression cases against current source and embedded Oak, including actual
   save interleavings. For mutations, observe repository state and count effects independently.
2. Run contention/crash cases against a shared DocumentNodeStore deployment. Embedded Oak proves
   the counterexamples, not the complete supported-cluster fix.
3. Install the product bundle/package and run positive command, result, paging, artifact, stream,
   authorization and console scenarios. Refusal tests remain necessary but cannot replace success.
4. Run the whole scripts/quality gate with existing prepared inputs. Do not substitute targeted
   test commands for that acceptance claim.
5. Treat licensed AEM and sibling-client tiers as optional external validation. They may be run
   when an owner supplies the licensed inputs, but their absence does not block implementation,
   the public acceptance tier, or the plan's completion.

Every P1 finding must be closed before enabling its affected capability. P2 boundedness and
deployment findings must close before the associated reliability/support claim is made.
