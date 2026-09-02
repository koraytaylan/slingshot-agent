# Plan 0002 — Agent Protocol Contract

## Where this lives

Everything in this plan is in `slingshot-agent-core`, in five packages that depend on each other in one direction:

| Package | Owns | Depends on |
|---|---|---|
| `…agent.json` | The bounded reader and the canonical writer | `…agent.contract` |
| `…agent.digest` | Digest derivation, hexadecimal rendering, comparison | nothing |
| `…agent.identity` | The five-field command-contract identity, the operation identity, provenance, and the submitted-command digest | `…agent.json`, `…agent.digest`, `…agent.contract` |
| `…agent.wire` | Envelope, error, job event, job snapshot, capability, result, and failure documents | `…agent.identity` |
| `…agent.continuation` | Continuation state, token, and the key-ring contract | `…agent.identity` |

None of them imports a Sling, Oak, or Adobe package. That is not incidental: it means the whole protocol model is testable with nothing running, and that the public interop tier is proving a servlet's plumbing rather than re-proving the model underneath it.

No package outside `…agent.json` parses or serialises. A type that knows how to read itself from a stream is a type with a second parser in it, and two parsers with different bounds is precisely the failure this whole plan exists to prevent.

## Reading is a bound, not a check afterwards

A limit applied to a document that has already been collected is a limit on nothing: the memory was spent before the check ran. So `BoundedDocumentReader` refuses the moment the next byte would cross a bound rather than once the document is in hand — the same rule the client applies to a response head, arrived at for the same reason.

Four bounds apply, all from `AgentContract`: the total document bytes, the nesting depth, the member count of any one object, and the length of any one member name or string value. A duplicate member is a refusal rather than a last-writer-wins, because two implementations that disagree about which value won will disagree about the digest, and a digest is the whole point. Trailing bytes after a complete document are a refusal for the same reason.

A refused document leaves nothing partially built. There is no half-read envelope to inspect and no field extracted before the refusal, because a caller that could see one would use it.

## Canonical bytes, and the order they are believed in

`slingshot.command-canonical-json/1` is the client's contract and is reproduced here as committed bytes with a committed digest, not as an implementation somebody wrote from the description. Its rules — member ordering, number form, string escaping, and the absence of insignificant whitespace — are proved against the same vector file the client is proved against, so a disagreement is a failing vector rather than a mysterious digest mismatch two systems away.

Authentication happens in one fixed order, and the order is the design:

1. The committed canonical-byte contract's own bytes are authenticated against their committed digest.
2. A role schema's annotation naming that contract is checked against that digest.
3. Only then is the role schema's own digest believed.
4. Only then is a five-field identity assembled from it.

Reversing any two of those produces something that authenticates, reports success, and is wrong. Each step is a distinct failure that names its own cause, because "the contract changed" and "a schema says it was written under a contract it was not" have different fixes and neither may present as the other.

## Identity is five fields or nothing

`CommandContractIdentity` is complete or refused. There is no partial match, no fallback to the wire name, no comparison that ignores one member, and no sixth member — provenance is carried beside the identity and never folded into it. A command that inspects a bundle is authenticated exactly the way one that loads a page is, and an agent that implements half a surface fails the other half's identity check rather than answering it approximately.

`SubmittedCommandDigest` is derived rather than allocated. It comes from the transport contract digest, the five identity fields, the canonical-byte contract digest, the complete canonical argument bytes, and the artifact manifest, joined under a binding version with a field separator that cannot appear inside any field. That is what makes it the idempotency key: a client that crashed between writing a request and recording its outcome arrives at the same key when it restarts, and this side recognises the resend as the same submission rather than as a second piece of work.

This side derives it independently and compares. It does not read the key the client sent and trust it, because a key nobody recomputed is a client asserting what its own request means.

The derivation is the client's, exactly, so it covers what the client's covers and nothing more — which leaves out the target the command was aimed at and the revision of the environment it named. Those are not therefore unchecked. They travel in the operation identity, the durable record holds them beside the digest, and admission compares all three, because an identifier reused against another target is a different piece of work wearing the same name and answering it from the first record would be answering confidently about the wrong repository.

## Closed kinds

Event kinds and snapshot kinds are closed sets. Meeting one this build does not know would mean guessing whether it mattered, and both answers are wrong: treating an unknown terminal kind as progress waits forever, and treating an unknown progress kind as terminal reports an outcome that has not happened. So an unknown kind is a refusal, and the refusal names the kind.

The same closure applies to failure categories. The registry that says which categories a command may report is Plan 0005's, but the document that carries one is here, and it carries exactly a category and the fields that category's shape declares — never a free-form message standing in for a category nobody defined.

## Continuation tokens

A token says where to resume and carries an integrity value binding that to a key this agent holds. The query it belongs to is part of what is signed, so a token cannot be carried from one query to another — which is a real attack and not a hypothetical one, because a position in one result set is a perfectly plausible position in another.

The key-ring contract is a small linearizable store: read a key, write it only if it still holds what you expected, and only while holding the lease. Every deployment implements all of it. A single instance is not permitted a cheaper version, because the guarantees would then change the day somebody added a node and the code depending on them would not know. Nothing here observes node count and nothing branches on which deployment it is. What backs it is Plan 0003's problem; that it is the same contract everywhere is this plan's.

Rotation retains the previous key. A token issued a moment before a rotation is a token somebody is holding, so the prior key outlives the longest token issued under it plus the skew two clocks may differ by, and validation tries the current key and then the prior one — reporting which one succeeded, because "valid under the prior key" is the signal that a rotation is in progress and not merely an internal detail.

## Schemas are published, not consulted

The committed schemas under `schemas/agent-protocol/` exist so that a second implementation has something to read. They are not loaded at run time and nothing validates against them, because the typed model is the validator and a second validator with different bounds is the failure this plan is built to avoid.

What keeps them honest is a check rather than a habit: every document kind's typed model and its committed schema are compared field by field, in both directions, so a field added to one and not the other fails. Their digests are committed beside them and are the values a five-field identity's role-schema members are derived from.

The vectors are shared. Where the sibling commits a vector for a document this agent also produces, the same file is carried here and both sides are proved against it, so a disagreement surfaces as a failing vector in whichever repository changed rather than as a refused submission in production.
