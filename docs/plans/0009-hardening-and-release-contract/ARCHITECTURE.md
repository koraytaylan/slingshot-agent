# Plan 0009 — Hardening and Release Contract

## What makes this repository's threat model unusual

Most software that gets hardened is software somebody runs. This runs inside somebody else's Adobe Experience Manager author, in the same process as their content and their configuration, holding a service user with write access to its own tree and the ability to obtain a session for whoever is calling.

That combination is exactly the shape of a privilege escalation, and every guard against it was put in earlier: path-bound servlets that decide authorization explicitly, commands that run as the caller, a service user whose grants are a list somebody can read out loud, handlers that cannot obtain a session, and a console that decides authorization before the service user reads anything.

The privilege-escalation suite is written first here because it is the suite that says whether all of that actually holds. It attacks in every direction the architecture makes possible: a caller reaching content through a command that they could not reach directly, a caller reaching the agent's own state, a caller reaching the continuation key ring, a handler obtaining a session, a console data source rendering something its viewer may not see, and a command whose declared access class does not match what it does.

## Fuzzing is about bytes somebody else produced

Four places accept bytes this repository did not write: the request body, the continuation token, the resumption identifier, and every command's arguments. Each is fuzzed, coverage-guided, with a committed corpus and deterministic seeds, and each has one property that must hold for every input: it either produces a valid typed value or a refusal naming a bound, and it never produces a partial value, an unbounded allocation, or an exception the caller sees as a server fault.

The canonical writer is fuzzed from the other direction: for every value the reader accepts, writing it must produce bytes the reader accepts back, and writing it twice must produce identical bytes. That round trip is what the five-field identity depends on, so a fuzzer finding a value where it does not hold is finding a submission that would be refused in production for no visible reason.

The fuzzing tool is pinned by exact version and digest, its corpus is committed, and it runs offline. A fuzzer that fetches is a gate whose result depends on a remote server, and a corpus that regenerates is a gate that finds different things on different days.

## Properties, not just examples

Three state machines decide whether one submission produces one effect: the operation's state transitions, the lease's take-renew-lose-expire cycle, and the ledger's append-and-materialise pair. Each is proved by generated sequences rather than by chosen ones, against invariants that must hold after every step:

- No sequence of transitions reaches a terminal state twice with different outcomes.
- No interleaving of two workers' lease operations leaves both holding it.
- No sequence of appends leaves the snapshot disagreeing with the fold of the ledger.
- No sequence of admissions leaves a counter disagreeing with the store's contents.

Those are the four sentences the whole one-effect argument rests on, and a generated counterexample is worth far more than another example that passes.

## Chaos is about what a cluster actually does

An Adobe Experience Manager as a Cloud Service author is not one machine. Work moves, instances stop, and the repository underneath is shared. So the chaos suites run two nodes rather than one wherever the property is about contention, and they inject at the boundaries a cluster actually has: a commit that fails, a commit that conflicts, a repository that is full, a job that is redelivered to another node, and a clock that disagrees with another node's.

Clocks get their own suite because every lease and every retention decision is a comparison of two instants, and the failure mode of a skewed clock is a lease two nodes both think they hold. The suite skews, pauses, and jumps the clock against every such comparison, and the property is always the same: a decision may be conservative and may never be wrong.

## Compatibility is a snapshot, not an intention

Three things must not change silently.

**The wire.** Every document's canonical bytes are snapshotted, and a change to any of them fails the build until somebody records it as a deliberate change with a version. A protocol change that nobody noticed is a client that stops working.

**The store.** Storage compatibility is proved by upgrading rather than by installing: an instance running the previous release, with a populated store, has the new release installed over it, and every operation, event, artifact, and subscription that existed must still be readable and every invariant must still hold. A fresh install proves nothing about an upgrade, and an upgrade is what every real deployment does.

**The platform floor.** The minimum platform version is bound to the deployment rows rather than written separately, so raising it is a change to the matrix and the matrix is what the bytecode contract and the imported-package footprint already check against.

## The advisory input

Dependencies here are almost all `provided`, which means the agent embeds nothing and its advisory surface is the platform's rather than its own. That is a real reduction and it is not the whole story: the build-time and test-scope artifacts are still code that runs, and the platform's own components are still what the agent's imports bind to.

So the advisory input is one exact snapshot, pinned by origin, full commit, and content digest, authenticated offline before anything is checked against it, with no timestamp and no freshness claim — because a snapshot's author chooses those values and neither authenticates anything. What the check establishes is narrow and stated as narrowly as it is true: these declared artifacts, against this exact reviewed snapshot, at the time an owner reviewed it.

## A release is a claim, checked part by part

**These bytes, from this source.** The archives are deterministic: fixed entry order, fixed timestamps, and no environment-dependent content. Building twice from one source produces byte-identical archives, and the build proves it rather than asserting it.

**Embedding nothing.** A components list is published beside the archives. For a package whose product dependencies are all provided, that list is nearly empty, which is a claim worth being able to verify rather than merely stating.

**On these rows.** The acceptance matrix binds every deployment row to the evidence that actually ran against it — which tier, which scenarios, on which instance — and a row with no evidence appears as declared and unproved. A row does not become supported because the code compiled.

**Built here.** The release workflow produces build provenance, one job holds the attestation permission, every action is pinned to a full commit, credentials are never persisted, and no value a caller controls reaches a shell.

**To two audiences, from one build.** The artifact has two consumers who want it in different shapes. An operator installing into their author wants the container package as a file, from a repository release, with no build tool involved. An Adobe Experience Manager project that embeds this package in its own container wants a coordinate its build already resolves, which means the central Maven repository. Both targets are declared, both are published from a single built set, and every artifact that goes to both is asserted byte-identical between them by digest — because the same deterministic archive goes to both places and a divergence between them would be invisible to everyone.

**With every remote refusal decided locally.** The central repository rejects a publication missing a signature, a sources archive, documentation, or any of six pieces of project metadata, and it rejects it at the end — after a release run has built everything and reached the network. Every one of those is decidable here, offline, in a second, which is the same argument the content-package analyser made about deployment pipelines: the most expensive place to find a packaging defect is the last one.

**And not published anyway.** The metadata boundary from Plan 0001 stays closed until an owner opens it. A namespace is a claim to something somebody has to have verified, and holding the domain is not the same as having verified it.

## Two things a person decides

Verification against a real author runs only when explicitly asked for, and it runs only the read commands — the ones the registry itself classifies as replacing nothing, determined from the registry rather than from a list. Its report is about the instance it ran against and about nothing else, labelled so it cannot be mistaken for evidence about a deployment row.

Publication is refused until an owner supplies the namespace, the repository, the developer metadata, and an acknowledgement. That has been true since Plan 0001 and this plan does not relax it; it only makes the refusal one an owner can lift deliberately.
