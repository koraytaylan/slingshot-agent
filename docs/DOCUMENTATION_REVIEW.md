# Documentation review

The questions in `policy/documentation-rules.toml` that no checker answers, answered against the
tree as it stands. Each heading is the question's own identifier, so the checker can tell that an
answer exists without pretending to judge it.

Reviewed on 2026-09-01, against the commit that completes Plan 0001.

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
