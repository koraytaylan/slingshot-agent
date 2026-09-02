# Security model

This runs inside your author instance, as a service user, in the same process as your content. That
combination is the shape of a privilege escalation, so this states what the agent may and may not
do, in the order somebody evaluating it would ask.

## The trust boundary, in one sentence

**Anyone in a permitted group can do, through this agent, exactly what they could already do by hand
in your author — and nothing else.**

That sentence is the whole model, and it is not softened anywhere below. Widening the permitted
groups widens who can act through the agent. That is the decision you are making when you change
that list, and it is the only decision that changes who can do what.

## Who may call

Two questions, answered in two places, and both have to say yes.

- **Are you authenticated?** Every route refuses a caller who presented no identity, without
  disclosing a single field of what it would have answered.
- **Are you in a permitted group?** Starting work additionally requires membership of a group an
  operator configured. The shipped configuration names `administrators` and nothing else.

The console applies the same requirement to every page and every data source, and it hides the
navigation entry from a viewer who may not use it rather than showing them a refusal.

## What a command runs as

**The person who asked.** A command executes inside its caller's own request, on that request's own
session, so the repository's own access control decides its outcome — not this product's opinion of
it. A path the caller may not read is answered as absent rather than as forbidden, which is what the
repository itself would say.

There is no impersonation call anywhere in either bundle. Not a guard against impersonation: no code
that could impersonate. A guard can be got round; an absence cannot, and the build refuses the day
one appears.

## What the agent itself may do

The service user exists for the agent's own bookkeeping — its operation records, its event ledger,
its capacity counters, its key ring — and for nothing else. Its grants are a readable list in the
repository initialisation this product ships, and they do not include writing content.

So widening the permitted groups changes who may call and never what the agent may do. Those are two
different things and they are kept apart on purpose.

## What it holds about identities

Nothing.

- No impersonation, as above.
- No stored credential. No command sets, reads, or carries a password.
- No token of yours. The only key material this product holds is its own continuation key ring,
  which signs the tokens a paged read hands back, and which never leaves the repository — not in a
  response, a log line, an event, a stored artifact, a health check message, or the console.

## What the platform commands can and cannot reach

Thirty of the commands are about the state an author retains rather than the content it stores, and
none of that is guarded by repository access control — so each of them is decided twice: by the
group the caller is in, and by whether the deployment provides that control at all.

On Adobe Experience Manager as a Cloud Service, configuration and bundle lifecycle are refused
before the platform is touched, because a change written through a running platform that does not
persist it is accepted, reported as done, and gone by the next deployment. Refusing is better than
succeeding falsely.

Beyond that: no configuration value in any listing, no job property value in any command, and no
replication transport address anywhere — that last being a URL which very frequently carries the
credential it authenticates with.

## What this deliberately does not do

- **It does not write anything as itself.** Every content change is the caller's.
- **It does not offer a console that writes.** Five screens, all read-only, because a console that
  wrote would be a second way to do what the routes do with a second authorization story, and the
  first thing that differs between two of those is the thing nobody tests.
- **It does not claim a publication happened.** Offering content to replication is an admission to a
  queue; an author instance cannot observe a publish instance, and a result saying "published" would
  be the most useful-looking and most false thing this surface could say.
- **It does not add an index, a package manager, or a run-time dependency of its own.** What it
  binds to is what your platform already provides, listed in the release's components file.

## Reporting a problem

Open an issue in this repository. If you believe you have found something that would let a caller do
more than they could by hand, say so plainly in the title — that is the one class of defect this
whole design exists to prevent, and it is the one worth interrupting anything else for.
