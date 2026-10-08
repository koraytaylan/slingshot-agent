# Documentation review

The questions in `policy/documentation-rules.toml` that no checker answers, answered against the
tree as it stands. Each heading is the question's own identifier, so the checker can tell that an
answer exists without pretending to judge it.

On 2026-10-07, reviewed expired subscription collection through production maintenance. The
maximum persisted retention boundary remains inclusive. Operation and subscription examinations
share the unchanged maintenance work bound, alternate priority and retain independent progress
in the existing sweep cursor. Subscription retirement rereads the current binding after every
conflict and deletes it with its total and caller capacity charges in one commit. Live and malformed
bindings remain allocated. Focused real Oak cases cover the original leak, a concurrent high-water
advance, interrupted saves, overlapping cleanup and progress with a one-row shared budget. This
review makes no complete release, deployed capacity recovery or author-latency repair claim.

On 2026-10-07, reviewed reference-search deadline enforcement. One inclusive monotonic
timer now covers root resolution, metadata selection, provider/native text reads and lazy
traversal. A callback returning after the deadline leaves discovery incomplete and prevents
further provider calls. The checks cannot interrupt an already blocked repository call.
Deterministic clock cases cover the last-resource counterexample, both sides of each native
and legacy provider boundary, traversal and absent-root resolution. Existing reference
scope, native identity, complete metadata, rollback, admission and node bounds remain in
force. The observed large repository scope still exceeds the complete-walk node budget;
this review makes no successful global adjusted-move or remote-latency repair claim.

On 2026-10-07, reviewed request-local lookup observations after two original policy refusals.
The servlet retains no instance state. Clock and receiver references exist only for one call,
and fixed numeric fields reach the existing writer without naming its event type outside logging.
The focused integration seam begins after the base request-shape check; original routed controls
continue to exercise that check. The unchanged registry still declares a stateless servlet.

On 2026-10-07, reviewed numeric observations for slow authenticated operation-lookup refusals.
One request-local monotonic clock separates servlet setup, internal state dispatch, generation
inspection, ownership, snapshot materialization, the existing response, and state return.
An operator event is eligible only after an opaque 404 has already flushed and elapsed work
has reached one second. Success, anonymous requests and other refusals emit no new event.
Only fixed metric names, nonnegative duration values and numeric work-entry/completion flags
enter the event; request identities, repository values and absolute readings are unavailable
to its representation. State return can include outer refusal handling after exceptional work,
and the flags distinguish that work from a normal callback return. The measurements exclude
platform authentication and the base servlet's request-shape check. They cannot interrupt a
blocked provider call and establish neither a server bottleneck cause nor a latency repair.
Focused clock controls preserve wrap, backwards readings, saturation and submillisecond
remainders. Real service-dispatch controls preserve the original empty response and retry hint,
require event delivery after the existing flush, and verify default-servlet serialization.
The original operation-lookup assertions and transport deadlines remain unchanged.

On 2026-10-07, reviewed reference-property name classification. A character scan
retains the original encoding-marker and translated-name decisions without allocating a stream
for each selected property. Every non-ASCII UTF-16 unit requires native identity preservation,
including surrogate units, so supplementary names retain the original classification. Exhaustive
UTF-16 and encoding-marker controls and all affected reference controls pass. Native and provider
value reads, metadata completeness, visibility, and resource/time bounds are unchanged. The
allocation policy declares only this character scan sensitive; its existing thresholds remain.
Balanced synthetic Oak measurements show lower allocation. They do not prove remote latency
improvement, successful oversized reference-adjusting moves, or original PNG settlement.

On 2026-10-08, reviewed first-reference priority traversal deadline handling.
The shared elapsed clock now gates iterator hasNext, next and skipped priority-path reads.
Six deterministic controls retain the inclusive boundary and prevent further provider reads
following expiry. ALL reference-adjusting move traversal and existing resource/time bounds
remain unchanged. Focused measurements do not establish a remote latency cause or speedup.

On 2026-10-08, reviewed operation bucket preparation. Remaining missing empty
buckets are staged under one fenced ancestor claim before the separate operation publication.
Partial existing chains and interrupted saves retain recoverable structural state. Original
admission, quota, terminal and replay controls pass. Diagnostic first-submission measurements
show two fewer saves; no remote latency improvement is established.

On 2026-10-08, reviewed execution-start capacity preparation. Terminal event and
completion-result identities are allocated together, then activated together using existing batch
transactions before the separate execution-start publication. Interrupted pending, activation
and publication commits preserve cleanup and retained budgets. Original admission and recovery
controls pass. Local first-submission measurements show two fewer saves and six fewer refreshes;
no remote latency improvement is established.

On 2026-10-08, reviewed capacity counter preparation on already prepared layouts.
Preparation refreshes the session before checking the exact total and caller counter paths.
When both exist, it returns without checking and refreshing each ancestor. Missing counters
continue through the existing creation primitive and bounded fresh conflict retries. No counter
values are cached, and reservation admission and publication remain separate transactions.
Deterministic Oak controls cover all accounted quantities, discarded transient changes and
missing-counter contention. The original first submission uses 57 refreshes instead of 120,
with the same 21 saves. These local call counts establish no remote latency improvement.

The historical review below began on 2026-09-01. On 2026-09-29, the changed command
reference, architecture and handler documentation were reviewed against incremental discovery
version 2. This scoped update does not renew every historical claim below.

On 2026-10-05, reviewed the content-fragment predicate's primary-type check before its
content-child lookup. The real-handler regression covers 128 synthetic nonasset resources,
requiring current-authority checks and complete examination while eliminating their child
lookups. Existing fragment flag, title, traversal and continuation assertions remain in the
complete core check. This is a provider-call reduction; source review and focused feedback
establish neither complete release evidence nor a live latency improvement.

On 2026-10-05, reviewed submission admission diagnostics. A capacity refusal names the
accounted quantity, projected count, reached bound and total or caller scope. A failed accounting
transition names its closed write outcome instead. Neither detail accepts caller content or
identifiers. The HTTP status, empty response, retry behavior and capacity bounds remain the
existing contract; these diagnostics do not establish which condition a live instance encountered.

On 2026-10-06, reviewed component witness collection after a page qualifies. The group keeps
the first contributing source for each required type and avoids further component-type reads.
Traversal still checks every descendant's current visibility and primary type, discovers nested
pages independently and validates the original witnesses before emission or replay. The synthetic
regression covers both match modes, sparse late witnesses, continuation across small work budgets
and nested page order. A provider-call reduction does not establish a live latency improvement.

On 2026-10-06, reviewed phrase matching's value-map adaptation. Each call reads one current
content map for the two declared searched properties. The property order, short-circuiting,
fresh title materialization and absence of excerpts are unchanged. The map is a local value,
never retained across resources, visits, pages or replay. Synthetic regressions cover title and
description matches, absent phrases, ignored properties and changed values on subsequent visits.
Reducing redundant adaptations does not establish a live latency improvement.

The command reference now states explicit completion, provider order, fixed cursor lifetime,
the latest-page replay window and permission/content-change behavior. The architecture describes
runtime-owned caller resolver clones and reads bounds and route inventories from their authorities.
The handler contract no longer claims handlers cannot delegate retained state. Focused tests
support these implementation descriptions; current candidate-pair and licensed-environment
evidence remain separate requirements.

On 2026-10-01, the scoped review includes `query_paths` version two, its handler,
result rows and query-index declaration. The root is evaluated as well as descendants;
rows carry addresses only, while the shared page envelope reports completion and examined
nodes. The handler delegates to the existing bounded cursor registry and issues no repository
query. The reference states provider order, unchanged initial limits and offsets, replay and
authority checks, lifetime and cooperative bounds. Full gates and live deployment remain
separate evidence from this source review. Shared metadata changes increment the
other 71 command identities by one patch version; their payload shapes and
numeric bounds are unchanged. The wire records and schema correspondence data
follow those explicit version decisions.

On 2026-10-02, the scoped review includes asset discovery version `2.0.0`.
The command reference describes retained provider iterators, explicit bounded
progress, root inclusion and pruning of asset descendants. Compared the original
binary length inspection, usable single-string MIME fallback, unknown-size
filtering, all-tags default and UTF-8 tag ordering with the producer, typed
consumer and independently preserved regressions. No binary is downloaded to
measure its size. The reference preserves current caller authority, latest-page
replay, expiry and cooperative bounds. Numeric limits and the 71 other payload
shapes remain unchanged; shared identities receive their patch increments.
This source review makes no full-gate, candidate-pair or deployment claim.

On 2026-10-05, the scoped review includes submission phase diagnostics in
`docs/CLIENT_COMPATIBILITY.md`. Compared the timing boundaries with the servlet's actual inline
runtime invocation, completion journalling, terminal publication and response-write order. The
document states excluded authentication, body intake, network and cleanup time, per-request resend
semantics, integer rounding and fixed numeric output. It makes no claim about live delay causes or
the result of gates, candidate-pair tests or deployment.

On 2026-10-06, the scoped review includes native reference inspection in
`ReferenceProperties` and its six `ReferenceNativeNodeReadTest` regressions.
Complete metadata selection still precedes stored value reads. Native property
identity, exact scalar and multiple matching, unreadable metadata and value
refusals, ordinary provider values and the legacy adapter fallback remain
unchanged. A native node is held only within one inspection; later visits
adapt again, and no stored values are cached between properties or stages.
The synthetic regression measures 128 adaptations for 128 resources with six
native text properties, compared with 896 before this change, with the same
metadata and value read counts. This review makes no live latency, global
reference scan completion, release gate or deployment claim. Original scan
budgets, deadlines, caller authority and command contracts remain unchanged.

On 2026-10-06, the scoped review also includes the test-only capacity view
in `ExclusiveTransitionProbe` and six `CapacityProbeVisibilityTest` cases.
Separate initialization commits can become visible on another cluster node
at different times. The view retains its existing `unprepared` response
until the deployment counter, caller counter and reservation tree are all
visible. Complete layouts retain the same four counts, and corrupt counters
still fail. The original crash assertions, expected accounting values,
polling and durability deadlines, kill points and product capacity behavior
are unchanged. The probe and these tests are not shipped in product bundles.
Two partial-visibility cases reproduced the old failure; all six cases and
all 2089 core tests now pass. This review makes no complete release-gate,
live latency, deployment or global reference-scan completion claim.

On 2026-10-06, the scoped review includes capability phase diagnostics in
`docs/CLIENT_COMPATIBILITY.md`. Compared the fixed numeric grammar, request-local monotonic
measurement, shape and established-identity refusals, metadata and lifecycle reads, document
rendering and response preparation with the actual answer method. Platform authentication,
base route checks, writer acquisition, response delivery and cleanup remain explicitly outside
the measurements. The body and command identities are unchanged. Deterministic clock tests,
real servlet response cases and the original running public scenario cover the stated behavior;
this source review makes no release-gate, deployment, live repair or causal latency claim.

On 2026-10-06, reviewed deletion reference traversal in `RepositoryReach` and
eleven `ReferencePriorityTraversalTest` regressions. The canonical caller-visible
target parent and the remaining global content traversal alternate resource
examinations. The global frontier excludes the parent subtree, so neither visits
resources already assigned to the other. One original resource and time budget
spans both frontiers. Complete absence still requires all relevant visible
branches; unreadable metadata and exhausted budgets remain conservative refusals.
Unavailable or aliased parent lookups retain the original global traversal.
Move reference discovery retains its original complete traversal and order.
The nearby synthetic reference requires three resource examinations, compared
with 1,003 in the original global traversal. An early global reference requires
at most four examinations, compared with 1,003 in the rejected whole-parent-first
candidate. Alternation prevents either frontier from waiting for the other to
finish. This review makes no live latency or complete reference-adjustment
improvement claim. Caller authority, policies, scan bounds and deadlines remain
unchanged.

On 2026-10-06, reviewed complete native reference metadata selection and scoped
property-handle reuse in `ReferenceProperties` and `ReferenceValues`. The complete
metadata pass precedes every stored-value read. Native property handles returned
by that pass are used only within the current inspection, avoiding a second
parent-node lookup for names the provider may translate. Current native types
and values are still read at that point. Ordinary provider reads, legacy adapter
rechecks, unreadable metadata and values, later visits, exact matching and the
original global traversal order and budgets remain unchanged. Eight synthetic
regressions measure the redundant lookups and verify those boundaries alongside
every original core case. This review claims neither complete release evidence
nor a live latency improvement or complete reference-adjustment budget repair.

## accuracy

Held. Every claim in the product documents was read against its own committed source: the module
table against the aggregator's module list, the route against `policy/agent-routes.toml` and the
servlet that registers it, the grants against `policy/repository-access.toml` and against what the
running instance created, the stage count against `policy/quality-gate.toml`, and the tier commands
against the tier rows. Two sentences were narrowed in the writing: the readme says the agent answers
one route rather than that it serves the protocol, and `docs/INTEROP.md` says Tier C is declared and
not yet built rather than describing it in the present tense.

## completeness

Held, for the reader this repository has today. `README.md` answers what installs, what the one
route answers, what proves it, and what runs the gate, in that order, and each answer links to the
document that expands it. What a reader does not get here is a guide to writing a command, because
no command exists to write yet; the plan bundles under `docs/plans` carry that, and the readme says
so rather than leaving a gap where it would be.

## failure-messages

Held for the three refusals a reader is most likely to meet. An absent or altered dependency cache
refuses naming `scripts/prepare_locked_dependency_cache`; an absent or differing container image
refuses naming `scripts/prepare_interop_images`; an absent quickstart jar, a jar whose digest is not
the recorded one, and a missing acknowledgement refuse distinctly and name what the owner has to do.
Each was read as a person meeting it for the first time would read it, and each names a command
rather than a condition.

## licensed-input

Held. `docs/INTEROP.md` states outright that the quickstart jar is licensed to whoever holds it and
is never committed, never cached in this repository, never published, and never fetched, and that
its absence refuses the tier explicitly rather than skipping it. `support/quickstart-tier.toml`
carries the same statement beside the acknowledgement field only an owner can set, and the image
built from it is built at run time and never pushed.

## present-state

Held, re-read against this commit. The readme opened by saying one route was answered and no command
existed; that stopped being true and now reads eight routes, sixty-four commands, five console
screens, and six health checks, with the deployment rows carrying the evidence that actually ran
rather than the evidence somebody hoped for. `docs/INSTALLING.md` says how the artifact arrives on
each row and why the two differ, what the instance is asked for, what it costs in the terms a
platform team asks in, and how to tell it is working - through the checks rather than through prose.
`docs/SECURITY.md` states the boundary in one sentence somebody can quote and does not soften it
anywhere below: widening the permitted groups widens who can act through the agent, and that is the
decision an operator is making. Each was read as somebody meeting the product for the first time
would read it, and no sentence in any of them describes something that is not in this commit.

## reader-path

Held. `AGENTS.md` sends a reader to `CONTRIBUTING.md` first and names the two documents that expand
it. `README.md` stands alone and links onward to `docs/INTEROP.md` and the licence files.
`ARCHITECTURE.md` assumes only the readme. `CONTRIBUTING.md` assumes nothing and closes by pointing
at this review. `docs/INSTALLING.md` and `docs/SECURITY.md` are reachable from the readme and assume
only it; `docs/CONSOLE.md` and `docs/RELEASING.md` are each reachable from the document a reader
would be holding when they wanted it. No document assumes another was read first without saying so.
