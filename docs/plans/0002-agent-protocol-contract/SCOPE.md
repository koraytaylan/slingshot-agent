# Plan 0002 — Agent Protocol Contract

> Every document this agent will ever read or write, its exact bytes, the five fields that say which command a submission means, and the order in which each of them is believed.

## Why this plan

The client half of Slingshot does not identify a command by its name. Two builds can both call something `query_paths` and disagree about what its arguments are, what its result looks like, or how large either may be, so an identity is five fields — the wire name, the semantic version, the digest of the limits, and the digests of both role schemas — and all five have to match or the submission is refused. That rule is worth nothing unless this side computes the same five values from the same bytes, which means the bytes have to be canonical, the canonicalisation has to be the one the client used, and the contract that defines it has to be authenticated before any digest derived under it is believed.

The order matters more than it looks. A role schema carries an annotation naming the contract it was written under. Checking that annotation against the digest of the committed contract bytes *before* the role digest is believed at all is what keeps contract drift and annotation drift as two different failures with two different causes, neither able to hide inside the other. Getting that order wrong produces a system that authenticates something, reports success, and is wrong.

Everything here is bytes and types. No route serves any of it yet, no store persists any of it, and no command exists to be identified. That is deliberate: a document model proved against committed vectors is a thing two independent implementations can agree on, and agreeing before either has a running server is much cheaper than discovering the disagreement over a network.

The bounds come from Plan 0001's contract accessor and are not written down again. A reader that enforces its limit after the document is in hand is a limit on nothing — the memory was already spent — so every bound here is enforced as bytes arrive, which is the same rule the client applies to response heads and for the same reason.

## In scope

- **0005 — Bounded and Canonical Bytes.** One reader that enforces the document bound, the nesting bound, and the member bound incrementally as input arrives, refuses a duplicate member, refuses trailing bytes, and never exposes a partial document; one writer producing exactly the canonical form `slingshot.command-canonical-json/1` defines, proved against the vectors the client is proved against; the digest primitives every authentication uses, with comparison that does not leak by timing; and authentication of the canonical-byte contract itself, before anything derived under it is believed.
- **0006 — Identity and Provenance.** The five-field command-contract identity as one value that is complete or refused, never partially matched; the operation identity that says which operation at which incarnation of which target; the provenance every operation-bearing document carries; the submitted-command digest derived under its own binding version from the transport contract, the five fields, the canonical byte contract, the complete canonical arguments, and the artifact manifest; and the envelope and bounded error document that carry all of it.
- **0007 — Job and Result Documents.** The closed event kinds and the closed snapshot shape, both refusing a kind this build does not know rather than guessing whether it mattered; the capability document that replaces Plan 0001's skeleton with the real advertised set; and the inline result and failure documents with the bound each command's registry row will later declare.
- **0008 — Continuation Tokens and Schema Publication.** The continuation state with the query digest that stops a token being carried from one query to another; the key-ring contract with its rotation, lease, and prior-key retention rules, as an interface every deployment implements identically and none may implement more cheaply; the committed schemas with their digests; and the shared conformance vectors, held to an inventory so a document kind with no vector fails.

## Out of scope

- Any route, servlet, or network behaviour. Plan 0004 owns the transport surface.
- Any persistence: the key ring's durable store, the event store, and the artifact store are Plan 0003's.
- Any command, argument shape, or result shape. Plans 0005 through 0007 own the registry, and the schemas committed here describe the envelope around a command rather than any command's own body.
- Validation against a schema at run time. The typed model is the validator; the committed schemas are what a second implementation reads, and a check proves the two agree.

## Plan dependencies

Plan 0001 supplies the contract accessor every bound is read from, the two-bundle split that keeps all of this in `core`, the source policy that refuses a second declaration of a bound, and the quality gate every task here passes.
