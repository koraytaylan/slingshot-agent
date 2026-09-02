# Plan 0003 — Durable Store and Logical Execution

## Two primitives, and nothing else

Oak offers exactly two things this plan can build on, and both of them are properties of a commit rather than of a lock.

**Claim by creation.** Adding a node at a path either succeeds or fails because something is already there. It is atomic, it is decided by the cluster's document store rather than by the instance that asked, and it needs no coordination. Every "exactly one of these may exist" in this plan — a logical operation, a physical attempt, an execution lease, an artifact slot — is a node somebody created or failed to create.

**Compare and set.** A property write is made to fail rather than merge when another session changed the same property since the read, and is retried a bounded number of times before it is reported as contention. Every "advance this only from the value I saw" — a state transition, a high-water mark, a key-ring rotation — is one of those.

There is no third primitive. In particular there is no lock held across a request, because a lock held by a process that stopped is a lock nobody can take, and the failure mode of a stale lock in a cluster is an agent that answers nothing until somebody restarts it.

Counting is where a third primitive looks necessary and is not. Oak's atomic counter mixin increments without a read-modify-write, which is what accounting appears to want, but on a clustered document store its increments are consolidated by a background task and the value a node reads back is eventually consistent. A decision taken on that is right on one instance and quietly wrong on a cluster — correct on the tier, wrong on the customer's author — so the mixin is refused outright and counting is compare-and-set like everything else.

What makes compare-and-set affordable for a hot count is sharding. A count is a declared number of sibling properties, each advanced by compare-and-set, and the total is their sum, so contention is bounded by the shard number rather than by the number of writers. Two nodes advancing different shards from one read can each admit, so a total may be understated by at most one advance per shard while they are in flight — and the bound an admission compares against is therefore the declared bound less that margin. Conservative, and never wrong in the direction that matters.

One accounting authority owns every count and every comparison against a bound; nothing else advances one. That is why it is built with the store primitives rather than beside the ledger and the subscriptions that admit against it — two admission paths over one count is the arrangement in which the total quietly stops meaning anything.

## Paths, not queries

Nothing on the hot path issues a repository query. An operation is found at a path derived from its identifier, an event at a path derived from its sequence, an artifact at a path derived from its slot. A query would need an index, an index has to be current, and "the index had not caught up" is an answer that is wrong rather than slow — which is the one thing an idempotency check may never be.

```
/var/slingshot-agent
├── generation                      the current event-store generation, and the rotation record
├── keys/ring                       the continuation key ring, readable by the service user alone
├── g<generation>
│   ├── operations/<aa>/<bb>/<identifier>
│   │   ├── submission              the derived digest, the contract identity and operation identity,
│   │   │                           the submitting caller, the manifest, the request-start instant
│   │   ├── outbox/<attempt>        one node per physical attempt, claimed by creation
│   │   ├── lease                   the execution fence: holder, expiry, generation
│   │   ├── events/<sequence>       append-only, bounded per operation
│   │   ├── snapshot                the materialised current state
│   │   ├── artifacts/<slot>        one file per slot, with its byte count and digest
│   │   └── intake/<slot>           one file per declared inbound payload slot
│   ├── subscriptions/<aa>/<identifier>
│   ├── capacity                    sharded counts, one per accounted quantity
│   └── capacity/callers/<aa>/<id>  the same counts, per submitting caller
└── g<prior>…                       retained prior generations, bounded in number
```

The two-level bucket is not decoration. Oak degrades when a node grows past roughly ten thousand children, and an operation identifier is sixty-four hexadecimal characters whose first four give sixty-five thousand buckets for free. Deriving the bucket from the identifier rather than from a date means a lookup needs nothing but the identifier, which is all a recovering client has.

## One logical operation, many physical records

A submission arrives with an operation identifier and an idempotency key. This side derives the key itself from the request — Plan 0002's submitted-command digest — and compares. Then exactly one of three things happens.

- No node at the operation's path: create it, with the derived digest recorded. The creation is the acceptance.
- A node exists carrying the same digest: this is the same submission arriving again. Answer from the record. Nothing is enqueued, nothing is executed, and the answer is the one the first submission will produce or already produced.
- A node exists carrying a different digest: two different commands claiming one identifier. That is a conflict and it is reported as one. Overwriting would destroy a record somebody is holding; ignoring would run the wrong command under a name the client will look up later.

The derived digest does not cover the target the command was aimed at or the revision of the environment it named, so the record holds those beside it and admission compares all three. An identifier reused against another target is a different piece of work wearing the same name, and answering it from the first record would be answering confidently about the wrong repository.

A submission whose artifact manifest declares inbound payload slots is accepted and not enqueued. The record is the claim; capacity for every declared byte count is reserved at that moment, so a caller whose payloads will not fit learns it before sending one; the slots are then filled through the intake route; and the last one to complete is what enqueues the job. A command never starts against a payload that is still arriving, and a resend still converges, because the digest covers what the manifest declared rather than what arrived.

Where a command is deferred, physical delivery is at least once and the design says so out loud. Each delivery attempt claims a node under `outbox/`, bounded by the contract's attempt limit; the claim succeeding means this attempt is recorded, not that this attempt may execute. Duplicate physical records are expected, harmless, and visible. Where a command is immediate — which is all of them today — there is no delivery at all: the request that admitted it starts it, and the outbox stays empty.

## The fence

At most one worker may be executing an operation at any instant, and "at most one" has to survive a node disappearing mid-execution.

A worker claims `lease` by creation, writing its holder and an expiry a lease-duration ahead. While it works it renews by compare-and-set against its own holder, at the renewal interval the contract declares. A worker whose renewal fails has lost the fence and stops without writing anything further — it does not finish, because finishing would be a second effect.

Another worker may take the lease only after the recorded expiry has passed, and takes it by compare-and-set against the exact expired record it read. Two workers racing to take an expired lease both compare against the same record, and exactly one write succeeds.

The lease is not the only guard. The terminal transition is itself a compare-and-set from a non-terminal state, so a worker that somehow ran past its expiry still cannot write an outcome over one another worker already wrote. The lease keeps two workers from doing the work; the terminal compare-and-set keeps two workers from reporting it.

The renewal interval is a third of the lease, so a worker has two missed renewals of margin before another may take over — which is what makes an ordinary garbage-collection pause not a handover.

## The terminal commit

An outcome, its result, the event that announces it, and the snapshot that records it land in one commit or none of them do. A state that says `succeeded` beside a result that was not written is a client fetching an answer that does not exist; a result written beside a state that still says `progress` is an answer nobody will ever fetch; and a terminal state with no terminal event in the ledger is a lookup that says finished to a subscriber who is still waiting. The operation record's state and the snapshot's kind are two representations of one fact, so the commit that advances one advances the other — which is why the terminal commit is built after the ledger rather than before it, and after the artifact store, because naming an artifact is not writing one and a terminal commit that wrote artifact bytes itself would be a second artifact writer.

Where the result is inline, the state and the result are properties written together. Where it is an artifact, the bytes, the byte count, and the digest are written and committed by the artifact store first, and only then is the state advanced to name them — and the terminal commit refuses to name an artifact that is not yet committed, which is what makes the ordering a property rather than a convention. An artifact that exists and is not yet referenced is garbage the sweep will collect; a reference to an artifact that does not exist is a broken answer.

## Events and snapshots

The ledger is append-only and the snapshot is materialised beside it in the same commit as the event that changed it. They cannot disagree, because nothing writes one without the other, and there is no job whose lateness could make them differ.

Sequences start at zero and strictly increase within one operation and generation. A reconnecting subscriber resumes from its cursor; a subscriber with no cursor is given the snapshot and the events after it, which exposes nothing that was already exposed and retracts nothing that was.

Capacity is admitted before an event is written, through the sharded counts under `capacity`, against the per-generation row and byte bounds the contract declares and against the submitting caller's own share of each. A bound that is only a total is a bound one caller can spend on everybody else's behalf, and an agent that stopped admitting because one client was busy is indistinguishable, from every other client, from one that is broken. Admission refused is a distinct outcome from a write that failed, because the fixes are different: one is a store that is full and needs a sweep, the other is a store that is broken.

## Generation

The generation says which incarnation of the store an identifier belongs to. It starts at one, never repeats, and never decreases, and it is part of every path, so two generations cannot collide however similar their identifiers.

Rotation is explicit and never implicit. It writes a new generation, retains prior generations up to the contract's bound with their own retention, and refuses to rotate while a prior generation is still inside its retention window. A rotated store is why the client's discovery compares generations before it submits: a client holding rows from a generation this agent no longer serves has to be told, not silently answered from a store that was rebuilt underneath it.

## Restart, and the reconciliation that is not only a restart

In-flight operations are reconciled from the store and from nothing else. An operation whose lease has expired and whose state is non-terminal is eligible to be picked up again. An operation whose outcome is recorded is finished, whatever the job system still believes. An operation whose physical attempts are exhausted without a terminal state is marked as such, explicitly, rather than retried forever.

An operation that was accepted and never started is left exactly as it is, and the client's own resend under the same derived key starts it — on that resend's request, on that caller's session. Recovery cannot, because recovery has no session for anybody, and that limitation is worth having: it is the same fact that makes impersonation unnecessary. A started operation that never finished is undetermined, because whether its single commit landed is genuinely unknown, and whether it is still running is decided by its start instant against the execution budget rather than by a lease.

The reconciliation runs on a declared interval as well as at startup. An Adobe Experience Manager as a Cloud Service author may not restart for weeks, and a recovery that runs only at startup is a recovery that does not run.

Nothing here re-identifies work or resubmits it. The identifiers came from the client and are derived; inventing one would create a durable thing the client will never ask about, which is worse than a gap the client can see. And nothing retries work whose outcome is ambiguous: an exhausted operation is left undetermined, because "we do not know" is an answer a caller can act on and a second effect is not.

## Where a command actually runs

Inside the request that submitted it. The registry row says so — every command this product ships is `Immediate` — and that one decision pays for most of the guarantees on this page. The caller's session is the request's own, so running as the caller costs nothing and needs no power over anybody's identity. Mutual exclusion is the state's own compare-and-set from accepted to started, so two requests racing produce one execution with no lease to take, renew, or lose. And the whole arrangement costs the repository three commits — the admission, the command's own, and the terminal one — rather than the six and the periodic renewals a job would.

What it costs instead is a bound. A command holds a request thread while it runs, so how long it may run is bounded by a budget that is itself bounded below the smallest request window any supported deployment declares, and how many may run at once is an accounted quantity like everything else. An author whose threads this agent has taken is an author that has stopped serving, which is a worse outcome than refusing a caller.

## The Sling job, and what it is for

The job system carries a physical attempt to a node that can execute it, and that is all. It is not the record of the work, it is not the idempotency mechanism, and its retry policy is not the operation's retry policy. Every durable fact is in the repository before a job is enqueued and remains there when the job system forgets everything it knew.

It exists here for the `Deferred` class, and no command this product ships is deferred — the fake command in the interop tier is what proves the path. The class exists because the day somebody wants a command that cannot finish inside a request, they will need an answer to whose identity it runs under, and the conformance gate stops them until they have one. Building the durable half now and declining to use it is cheaper than designing it at the moment somebody is in a hurry.

That separation is what makes the crash proof possible either way: the container is killed between any two steps, restarted, and the store alone has to produce the right answer.
