# Plan 0004 — Transport Surface — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending.
- **Goal:** serve every route the client calls, under a request policy that makes a path-bound servlet safe, with a stream that survives the gateway it runs behind, and prove it by running the client's own executable against a real author.
- **Root cause:** a path-bound Sling servlet is reached before the access control that would otherwise decide the request, so an agent registered this way is a privilege escalation unless authorization is explicit and the work runs as the caller; a long-lived response on a Cloud Service author is ended by a gateway at an interval nobody declared and occupies one of a bounded number of request threads while it waits; and the two repositories do not agree on what the routes are called, with three spellings across them and no single one served by everything that expects it.
- **Approach:** bind every servlet to its exact path and refuse every other spelling — selector, extension, suffix, trailing segment — before a parameter is read; require an authenticated caller everywhere and a member of one of the permitted groups to submit — shipped naming `administrators` alone and widened only by a configuration somebody writes, the way the Groovy Console is gated — keeping the agent's bookkeeping on the service user and executing every command inside its caller's own request on that request's own session, so running as the caller needs no grant and no impersonation, bounded by an execution budget below the smallest request window any supported deployment declares and by a count of how many executions this instance holds at once; bound the request body as it arrives and refuse unrequested codings and ambiguous framing rather than resolving them; map every refusal to a category the client already declares, with a capped retry hint only where retrying can work; encode events under their three bounds with an over-bound event a stream error rather than a truncation; serve the stream asynchronously under a declared concurrency bound and end each session at a declared length well inside the gateway's, letting the client's own resumption carry it across; recognise a cursor as a generation and a sequence so a stale one resets explicitly rather than jumping silently; serve artifacts with an independently verifiable count and digest, take them in against a manifest the client declared before sending a byte so capacity is reserved up front and a command never starts against a payload still arriving, and disclose no repository path; serve the client's current spellings as declared aliases checked against its committed constants while recording the correction the client repository owes; and finish with a redaction suite, a disruption proof at every phase, and a tier that runs the pinned client executable end to end.
- **Progress:** 21/21 tasks done; 0 blocked; 0 dropped. Workstream 0013 has the one servlet base
  every route extends, with the shape of a request settled from what the request is before anything
  reads it; the gate that requires somebody in particular on every route with no argument through
  which one could be exempted; and the operator authorization table with the three ways a
  configuration can mean nobody kept apart. It also has the two platform prerequisites written down
  for an operator by their own configuration identifiers, and request bodies bounded as they arrive
  with two framings refused rather than resolved. Workstream 0014 has the route that starts work:
  it derives the key rather than believing the one it was sent, answers with the record's own
  values in exactly the nine members the client knows, runs an immediate command inside the
  caller's own request on that request's own session, and bounds how many executions this instance
  holds at once. Beside it are the three routes a client reconciles through — what one operation
  became, what its physical attempts were, and how far a subscription has been served — each
  answering from the store alone and each keeping "not there yet" apart from "never there"; and the
  failure mapping, which is one committed row per category with no default branch and no retry hint
  on a refusal that trying again cannot fix. The intake route completes the workstream: a declared
  payload arrives against what its own manifest said before it was sent, addressed by operation and
  slot rather than by a repository path, with nothing partial ever reachable, nothing charged twice
  for a retry, and a command that does not start until the last declared slot completes.
  Workstream 0015 has the one route that stays open. An event is encoded under its three bounds
  with an over-bound event ending the stream rather than being truncated, because a truncated event
  is not a smaller event but an unparseable one. The route refuses before it opens anything — an
  unknown subscription, an incarnation this store does not serve, an operation this caller cannot
  see — as ordinary responses, then releases the request thread and writes from a pool this bundle
  owns, bounded by the same number that bounds admission rather than by however many clients
  connect. A session heartbeats on the contract's interval whether or not there is news, ends itself
  at the bound the contract publishes with a final heartbeat and a clean close rather than waiting
  for a gateway to sever it, and every one of the four endings leaves through the one path that
  gives the room back. A subscriber with no position is told where it is before it is told anything
  else; a cursor is read as an incarnation and a sequence together, so a stale one is a reset
  carrying what to resynchronise from rather than a silent jump to the beginning of a store nobody
  has seen. Workstream 0016 begins with the other half of the artifact surface: a result too large
  to answer inline, addressed by operation and slot rather than by a repository path, with the
  recorded count and digest in the head before the body so a reader verifies rather than believes,
  a stalled transfer ended at the idle bound without ending one that is merely large, and an
  artifact whose stored bytes do not digest to what its record says refused with no byte of it
  sent. Beside it, the reconciliation with the client that exists today: every route constant that
  repository declares is recorded out of it at a named commit, the second paths this side carries
  are compared with those constants in both directions, each alias names the client version that
  asks for it and the correction that removes it, and none of them is served by what a customer
  receives — an alias is a path a deployment turns on one at a time, and `/libs` is a namespace a
  dispatcher passes more freely than anything else. The workstream finishes with three proofs that
  are about what leaves rather than what is served: one corpus of eight kinds of thing that must
  never leave, scanned against every body, every header, every log line and every piece of a stream
  produced by driving every route on a running instance; the tier that runs the sibling's own
  executable, pinned by origin, exact commit and digest, refusing distinctly and naming what its
  holder has to do rather than quietly not running; and a connection severed at each of eight
  enumerated points with a reset rather than a close, after each of which the instance is required
  to survive, answer identically, and leave no room occupied.
- **Integration:** `planned`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `pending`; mode `sequential`; final integration `pending`.
- **Exceptions:** each recorded where it was made.
  - Refusals answer with a status and an empty body rather than through the platform's own error
    handler. That was found on a running instance: Sling's error page names the servlet that
    refused, lists the filters the request passed through, and prints a timing trace — to an
    unauthenticated caller — and it differs between two refusals that have to be
    indistinguishable, so a caller could tell an unknown user from a wrong password by the shape of
    the trace. Task 1603's redaction suite inherits the rule rather than discovering it.
  - Task 1304's referrer-filter half is proved as far as a public tier can prove it. The pinned
    image is an Apache Sling starter and does not carry the bundle that filter lives in: a write
    naming a foreign host is served there, which is the finding the scenario records rather than a
    defect it found. What is proved is that nothing else on that tier refuses such a request — which
    is what makes naming the filter in `docs/DEPLOYMENT.md` necessary rather than decorative — and
    that no configuration this product ships excludes a route from either filter. The filter's own
    refusal happens on a customer's author, which is Tier B and needs a licensed input this
    repository deliberately does not have.
  - Task 1303's proof on a running instance is the refusal rather than the admission, and it
    arrived with task 1401's scenario rather than as one of its own: an authenticated caller
    outside every permitted group is refused the submit route on a real instance, and refused
    before its body is looked at. The admitted half cannot be arranged on the public tier at all —
    it has no user-manager surface, so a request to put somebody in a group falls through to the
    platform's own write servlet, which answers that it cannot create `userManager` under
    `/system`. That a member is admitted is proved in the unit suite against a real repository's
    own user manager, where the group is created and the caller put in it.
  - The lookup routes answer the four-member snapshot document the committed schema declares, and
    not the richer shape the client's own connection crate parses. The two repositories disagree
    here: `schemas/agent-protocol/job/snapshot.json` — carried from the client and checked
    two-way in Plan 0002 — declares exactly the incarnation, the operation, the kind, and the
    sequence, with additional members refused, while the client's `JobSnapshot` expects an echo
    block, an attempt count, a retention, and a progress figure beside them. This side answers the
    committed schema, because a document a schema refuses is a document neither side can validate.
    Task 1602 owns the record of what the client repository has to change.
  - Task 1401's "one operation under resend on a running instance" and task 1406's payload arriving
    on one are proved in the unit suite and not yet on a running one, because this build registers
    no command: a submission naming work nothing here runs is refused before a record is written,
    which is the property that matters while the command surface is still being built. Both
    running-instance halves arrive with Plan 0005's first command.
  - The stream route's servlet lives in `rs.slingshot.agent.http` beside every other route
    servlet rather than in `rs.slingshot.agent.stream` where task 1502 placed it. The base every
    route extends is sealed, which is what makes its permitted subclasses a decision the compiler
    checks rather than whatever happens to compile, and a sealed class outside a named module may
    only permit types in its own package. The choice was between that guarantee and a file's
    location; the stream's own policy — the encoder, the session, the admission, the heartbeat, the
    bound, the resumption, the reset, the writer, the pool — is all in `stream`, and the servlet is
    the one thing that is a route.
  - Tasks 1503, 1504 and 1505 have no running-instance scenario of their own, and their unit
    proofs stand alone. A stream that is actually served needs a subscription to follow, and
    nothing this product exposes takes one over the wire: there is no subscribe route in the
    committed table, because a subscription is taken by the client's own daemon through work this
    plan does not own. On a fresh instance every stream is therefore a refusal, which is what
    `EventStreamScenario` proves and proves properly — that each refusal arrives, ends, and never
    opens a stream to say it would not open one. Heartbeats on a real connection, a session ending
    at its bound, a resumption across that ending, and concurrent streams at the saturation
    boundary all need a subscriber on the other end. They arrive with the client-driven tier in
    workstream 0016, alongside the running-instance halves Plan 0003 and task 1401 recorded for the
    same reason.
  - The two rows the committed table gives `/bin/slingshot/agent/artifact` — bytes leaving on a
    read and bytes arriving on a write — are one servlet registration. Sling registers a path-bound
    servlet by its path alone and reads a component's declared methods only for a resource-type
    registration, so two components on one path would mean whichever one the resolver happened to
    pick answered both methods; that is what a running instance answered before this was found, and
    a `GET` was refused as a method the table does not give. What is registered now is the transfer
    servlet, which hands a write to the intake servlet's own row, and the intake row settles the
    request's shape against itself before it reads anything. The unit suite asserts the path is
    registered exactly once.
  - Task 1406's running-instance scenario arrived here rather than with the task, for the same
    reason its own half did: nothing is waiting for a payload on a fresh instance, so what the
    scenario exhibits is that bytes for work nobody declared are refused before any of them is
    stored.
  - What no tier has watched yet is a `text/event-stream` response passing through a real ingress
    without being buffered. A buffered stream is not a slow stream; it is a stream that delivers
    nothing until it ends, and nothing this side does changes that. `docs/DEPLOYMENT.md` states the
    requirement per deployment row and marks both rows' streaming support declared and unproved,
    which is what a row that no machine has run is.
  - Task 1602's aliases answer byte-identically because they are a second path to one servlet, and
    the unit suite proves that by turning one on and comparing an alias answer with its canonical
    route's byte for byte. What the running-instance scenario proves is the other half and the one
    that matters more: on an instance carrying what this build ships, no alias answers at all —
    not even with a refusal, which would mean a servlet of this product was registered there.
  - Task 1605's client-side half is not proved here. What a caller concluded after a severance is
    read from the client's own output, and that needs the conformance tier and an executable this
    repository does not have; what a store holds after one needs a command this build registers and
    does not. What is proved is this side's half at every enumerated point: the connection is
    severed with a reset rather than a close, nothing comes back, and the instance survives,
    answers identically afterwards, and leaves no room occupied. Where a severance lands depends on
    what the route had to say — on an instance where nothing has been submitted, the stream,
    transfer and intake routes answer without a body, so "part way through" lands in the answer's
    head. Both halves arrive with Plan 0005's first command and an owner-supplied client.
- **Outcome:** every route the client calls is served under a request policy that makes a
  path-bound servlet safe, with a stream that ends its own sessions inside the gateway's limit, an
  artifact surface that verifies rather than asserts, the client's old spellings carried
  deliberately and temporarily, and one corpus that says what may never leave.

_Last updated: 2026-09-02, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
