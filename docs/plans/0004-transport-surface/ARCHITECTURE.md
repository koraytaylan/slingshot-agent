# Plan 0004 — Transport Surface

## Bound by path, and reachable no other way

Every route is a servlet bound to a path from `policy/agent-routes.toml`. Sling will happily reach a path-bound servlet through a selector, an extension, a suffix, or a trailing path segment, and each of those is a second spelling of a route somebody has to have thought about. So each servlet refuses a request whose path is not exactly its own — no selectors, no extension, no suffix, no trailing segment — and the refusal happens before any parameter is read.

Binding by path also means the servlet is reached *before* the resource whose access control would otherwise decide the request. That is the point of binding by path and it is also the hazard: an agent registered this way is a way to do things the caller could not do themselves, unless something else decides. Two things do.

**Authentication** is Sling's, unchanged. The route answers only a request carrying an authenticated user, and anonymous is refused everywhere including on discovery. On Adobe Experience Manager as a Cloud Service the caller arrives as a technical account through the platform's own token handling; on a local instance it arrives as an ordinary user. Neither path is implemented here — this repository consumes the identity the platform established and never establishes one.

**Authorization** is this repository's, and it is explicit. Submitting requires membership of one of the permitted groups; reading an operation requires being the caller that submitted it or a member of one of them. Which groups those are is an Open Service Gateway Initiative configuration shipped with `administrators` as its only value — the arrangement the Groovy Console established and Adobe operators already recognise. A tool available to administrators and nobody else is a tool an operator can verify their install with; a tool available to nobody is one they cannot tell apart from a broken install; and a tool available to everybody is not gated at all. Widening it is a configuration somebody writes, not a default they inherit.

**And the work runs as the caller.** The agent's own bookkeeping — the store, the ledger, the key ring — uses the service user. Everything a command touches uses the caller's own session, so the repository's access control decides what the command can see and change. That is the difference between an agent and a privilege escalation, and Plan 0001 made it structural by giving `AgentSession` exactly two ways to obtain a session and no third.

That sentence is only true for free because a command executes inside the request that submitted it. The session is the request's, so nothing has to obtain one, and the agent holds no privilege over anybody's identity. An agent that executed afterwards would need to impersonate its callers — a standing power over other people's accounts that somebody would have to justify to the operator granting it — and that is the trade this design declines. What it costs instead is a bound: a command has to finish inside the request window, which the execution budget enforces and the deployment matrix sizes.

## The request is bounded as it arrives

The same rule the client applies to a response head applies here to a request body: a limit checked after the body is collected is a limit on nothing. The body is read incrementally against the contract's submission bound and refused the moment the next byte would cross it.

A body arrives as `identity` or not at all. A content coding the server did not ask for is a body whose decoded length nobody knows, and a bound on an unknown length is not a bound. Ambiguous framing — a length and a chunked encoding together — is refused rather than resolved, because resolving it is choosing which of two senders to believe.

Routes that take no body refuse one rather than ignoring it, because an ignored body is a caller believing something was read.

## Status is a mapping, not a judgement

Every refusal crosses the wire as one of the categories the client's registry already declares, carried in the error document Plan 0002 defined, under a status the mapping assigns. The mapping is data: one row per category naming the status, whether a retry hint accompanies it, and whether the refusal is safe to retry at all.

A retry hint appears only where retrying is the right thing. A conflicting submission, an unauthorized caller, and a refused continuation token are not going to succeed on a second attempt, and telling a client to retry them is telling it to waste an author's request budget. Where a hint does appear it is capped at the contract's own cap, so a server cannot ask a client to wait longer than the client's policy allows.

Nothing about a status decides anything on the client side beyond what the category already said. That is deliberate: the category travels with the answer and the status is transport.

## The event stream

Server-sent events, bounded three ways as they are produced — the line, the event, and the buffer — all from the contract. An event that would exceed a bound is not truncated; it is a stream error, because a truncated event is not a smaller event but an unparseable one.

The route is asynchronous. A synchronous long-lived response holds a request thread, and an Adobe Experience Manager as a Cloud Service author serves from a bounded pool of them; a handful of subscribers would be an author that has stopped serving anything at all. So the servlet starts an asynchronous context, releases the thread, and writes from the bundle's own bounded executor when there is something to write. Its own, rather than the platform's shared scheduler: that pool exists for periodic work and the rest of the instance is already using it, and one entry per open stream on it is this agent spending somebody else's capacity.

Concurrency is bounded anyway, at a number this repository declares rather than inherits. Admission past the bound is a refusal with a retry hint, which is the honest answer: come back, there is room for a bounded number of subscribers and you are past it.

Sessions end themselves. The contract gives the client a heartbeat timeout and a bounded reconnection policy; it gives no maximum session length, because that is a property of the environment rather than of the protocol. A gateway that ends a request at some interval it does not tell anybody would otherwise sever the stream at a moment nobody chose. So the agent closes at its own declared bound, well inside the gateway's, and the client's existing resumption path carries the subscription across — which is exactly what `Last-Event-ID` and the retry policy were built for.

Heartbeats go out at the contract's interval, from that same executor, whether or not there is anything to say. A stream that has stopped sending them is a stream that has stopped, which is different from a stream that has nothing to say, and that distinction is the entire reason the heartbeat exists.

## Resumption and reset

A reconnection carries `Last-Event-ID`, which is a generation and a sequence together, so a cursor from an earlier incarnation of the store is recognised rather than misread as an early position. Resumption serves strictly after the cursor.

A cursor the store can no longer honour produces an explicit reset carrying the snapshot to resynchronise from — never a silent jump and never an empty result. A subscriber that jumped silently would believe it had seen everything.

A reconnection with no cursor exposes nothing that was already exposed and retracts nothing that was: it is given the snapshot and the events after it, which is the same guarantee Plan 0003 makes in the store and this route does not weaken.

## Artifacts

Artifacts move in both directions, and the inbound one is what makes the artifact manifest mean anything. The manifest a client declares is part of the derived submission digest, so it names each slot's byte count and digest before a byte is sent; the submission is admitted against it and capacity is reserved from it, the caller then fills each slot through the intake route, and the operation becomes startable only when the last one completes. A command never starts against a payload that is still arriving, and a resend converges because the digest covers what was declared rather than what was sent.

An artifact is served with its byte count and its digest, both recorded when it was written, so a reader verifies rather than trusting what the store says about itself. The transfer has its own idle and total deadlines, separate from a finite response's, because a large download that is still moving is not a stalled one — the same distinction the client makes on its side.

Nothing about an artifact route is a repository path. The slot and the operation identify it; a caller cannot name a location, and no response discloses one.

## The routes, and the disagreement about them

This repository serves the table it pinned in Plan 0001, under `/bin/slingshot/agent`:

| Route | Method | What it is |
|---|---|---|
| `capabilities` | GET | What this agent is, compared before anything is submitted |
| `submit` | POST | One command, admitted under its derived digest |
| `operations` | GET | What the store holds about one operation |
| `jobs` | GET | Which physical Sling records a logical operation has |
| `subscriptions/high-water` | GET | How far a subscription has been served |
| `events` | GET | The filtered stream |
| `artifacts` | GET | One artifact slot's bytes |
| `intake` | PUT | One declared payload slot's bytes, on the way in |

The shipped client does not ask for all of those spellings. Its production constants name `/libs/slingshot/agent/artifacts`, `…/events`, `…/operations`, `…/jobs`, and `…/subscriptions/high-water`, while its simulator and its own daemon suites name `/bin/slingshot/agent/capabilities`, `…/submit`, `…/events`, `…/snapshot`, and `…/artifact`. Three spellings across two repositories, and no single one of them is served by everything that expects it.

So this agent serves the pinned table and, beside it, every alias the shipped client actually uses, each declared as an alias in the route table with the client version it exists for and the correction it is waiting on. An alias answers identically to its canonical route — it is a second path to one servlet, never a second implementation — and the alias set is a closed list that a check compares against the client's own committed constants, so an alias nothing needs fails and a client constant nothing serves fails.

`/libs` remains wrong as a destination. Adobe reserves that namespace and a third-party servlet path in it is a collision waiting for an upgrade, even though the registration creates no node. The correction belongs in the client repository; what belongs here is serving the alias so the two halves can be proved against one another today, and a written record of what has to change so that "we still serve `/libs`" is a decision with an owner rather than a thing nobody remembers.

Reaching any of it through somebody else's deployment needs their dispatcher to pass the prefix. That is documented as a requirement with the exact rules, and shipped by nobody: a dispatcher configuration belongs to the deployment it protects.

## Conformance

The last tier runs the client's own `slingshot` executable, pinned by origin and commit, against this agent on a real author. It is the only thing in either repository that proves the two halves speak to one another, and its failures are cross-repository defects rather than local ones — which is why it names the exact client commit it ran against and claims nothing about any other.
