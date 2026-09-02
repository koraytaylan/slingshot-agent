# Plan 0004 — Transport Surface

> The routes the client already knows how to call, the request policy they are reached under, a stream that survives the gateway it runs behind, and the first proof that the two halves of Slingshot speak to one another.

## Why this plan

Plans 0002 and 0003 built a protocol model and a durable store that nothing outside this process can reach. This plan attaches them to HTTP, and HTTP is where the assumptions get tested — because the client's contract is not merely about document shapes. It is about which protocol versions may be used, how large each part of an exchange may be, when a body may be believed, what a missing answer means, and what a stream that has stopped sending heartbeats is different from.

The client's side of all that is already built and already proved against a simulator. What is not proved is that a real Adobe Experience Manager author can hold up the other end, and there are three specific places where it is not obvious that it can.

A path-bound Sling servlet is reached before the resource whose access control would otherwise decide the request. That is the whole point of binding by path, and it is also the reason an agent registered this way is a way to do things the caller could not do themselves, unless authorization is decided explicitly and the work runs as the caller rather than as the agent.

A long-lived response on an Adobe Experience Manager as a Cloud Service author sits behind a gateway that ends a request whether or not it is still moving, and occupies one of a bounded number of request threads while it does. The transport contract gives the client a heartbeat timeout and a bounded reconnection policy but no maximum session length, because that is a property of the server's environment rather than of the protocol. So the agent ends its own streams before the gateway does, at a bound it declares, and the client's existing resumption path carries the subscription across.

And the routes themselves are not agreed. The client repository disagrees with itself: its production constants say `/libs/slingshot/agent/…` while its own simulator and daemon suites say `/bin/slingshot/agent/…`, and the two spell the lookup route as `operations` and `snapshot` and the artifact route as plural and singular. One of those has to become the answer. This plan serves the table this repository pinned, serves the client's current spellings as compatibility aliases so the shipped client can actually reach it, and records exactly what has to change on the other side and why.

## In scope

- **0013 — Request Policy and Authorization.** Servlets bound to the pinned table and reachable no other way — not through a selector, an extension, a suffix, or a trailing path; a closed method and media-type policy; an authenticated caller required on every route and a member of one of the permitted groups required to submit, shipped naming `administrators` alone and widened only by a configuration somebody writes; the two-session rule enforced at the boundary, so the agent's own bookkeeping runs as the service user and the caller's work runs inside their own request on their own session — which is what makes it free rather than granted, and why nothing here holds power over anybody's identity; the forgery and referrer prerequisites a platform puts in front of every authenticated author POST, established rather than assumed away; and a request body bounded as it arrives, with ambiguous framing and unrequested content codings refused rather than decoded.
- **0014 — Submission, Lookup, and Intake Routes.** The submission route carrying the whole admission decision of Plan 0003 and nothing more; the operation lookup that lets a client reconcile rather than resubmit; the physical job lookup that says which Sling records a logical operation has; the subscription high-water route; one status mapping under which every refusal is the category the client's registry already declares, with a retry hint where retrying is the right thing and none where it is not; and the intake route those payloads arrive on, bounded and verified against the manifest the submission already declared, without which the artifact manifest inside the idempotency key can only ever say none.
- **0015 — Event Stream.** A bounded encoder that enforces the line, event, and buffer limits as bytes are produced; an asynchronous route that holds no request thread while it waits; heartbeats at the declared interval and a session that ends itself before the gateway does; resumption from a cursor and an explicit reset when the cursor can no longer be honoured; and a hard bound on concurrent streams, because a thread pool exhausted by subscribers is an author that has stopped serving anything at all.
- **0016 — Artifacts, Aliases, and Conformance.** Artifact transfer with the byte count and digest a reader can verify independently; the compatibility aliases — off until a deployment enables them, because `/libs` is a namespace customers pass more freely than any other — and the written record of the cross-repository route correction; a redaction suite proving no response carries a credential, a repository address, a configuration value, or an internal path; the tier that runs the client's own executable against this agent; and a disruption proof that severs the connection at each phase and asserts what the client is left knowing.

## Out of scope

- Any command. The submission route admits and then runs whatever the framework dispatches; what a command does is Plans 0005 through 0007.
- The durable behaviour underneath every route, which is Plan 0003's and is called rather than restated.
- The author console. Plan 0008 owns it, and it reads these stores rather than these routes.
- The dispatcher and content delivery configuration of somebody else's deployment, which is documented here as a requirement and shipped by nobody.

## Plan dependencies

Plan 0001 supplies the route table, the contract accessor, the service user, and the tiers. Plan 0002 supplies every document these routes carry and the identity comparisons they make. Plan 0003 supplies admission, the fence, the ledger, the subscription store, the artifact store, and every durable answer a lookup returns.
