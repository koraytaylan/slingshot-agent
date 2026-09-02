# The author console

Everything this agent does is reachable by a client that already knows what to ask. This console is
for the two people that leaves out: the operator who has just installed it and has no way to tell
whether it is working, and whoever is looking at one operation that went wrong and has an identifier
and a question whose answer is spread across four stores.

It sits under Adobe's own **Tools** navigation, because a thing an operator has to be told where to
find is a thing they find once. Nothing here overlays an Adobe resource: the entry is added beside
the platform's own, and the check beside it refuses the day that stops being true.

## Who may see it

The same permitted groups the routes require, applied to every page and every data source, and
decided separately from the service-user read the data sources perform. A viewer outside those
groups is shown nothing and told so — a refusal rather than an empty page, because "there is
nothing here" and "you may not see what is here" send an operator to two different places.

The navigation entry is hidden from a viewer who may not use it rather than shown and refused.

## What each page shows

### `/apps/slingshot-agent/content/console`

Every operation this agent has run, newest first, with its command, its state, and when it started.
Paged in the store's own order against an unbounded read, and a total is shown only where the store
can produce one cheaply — a page that carried two rows and did not count the rest says so, rather
than showing a total of two.

It deliberately offers no way to submit work, cancel an operation, or change anything. A console
that wrote would be a second way to do what the routes do, with a second authorization story and a
second audit trail, and the first thing that differs between the two is the thing nobody tests.

### `/apps/slingshot-agent/content/console/operation`

One operation, assembled from the four stores that hold pieces of it: its snapshot, its ledger, its
attempts, its lease, and the artifacts it published. This is the page the whole console exists for —
without it the answer to "what happened to this operation" is "check the logs".

A running operation is followed live through the same event route the client library uses, rather
than through a second stream. A console watching its own would be a second implementation of the
hardest thing in this repository, and the day the two disagree the operator and the client are
looking at different accounts of the same operation with no way to tell which is right.

An operation that has ended is offered no stream at all, rather than one that opens and closes
immediately: an author instance has a bounded number of them.

### `/apps/slingshot-agent/content/console/maintenance`

The generation this instance is on, how many earlier ones are still readable, how much of the
allotted capacity is in use, and when the sweep last ran. Every number here is one somebody wants
once and always urgently, and none of it is visible from the content or from a log.

A sweep that has never run says so, rather than showing an instant of nought — which reads as
nineteen-seventy and sends somebody looking for a clock problem.

### `/apps/slingshot-agent/content/console/identity`

Which build this is, which two contract digests it holds, which event-store generation it serves,
whether its continuation authority can issue a token, and whether this build claims the deployment
row it finds itself on. Beside them, every route alias with the client version it exists for and the
correction it is waiting on, and every registered command with the fields that decide how it may be
called.

Every digest here is read from what the discovery route reads, and there is no field a second copy
could be put in. A page with its own copy of a digest is a page that can disagree with the route a
client is comparing against, and the disagreement would surface as the client being wrong.

A row this build does not claim is shown as unclaimed rather than hidden. Running somewhere
unclaimed is not a failure; it is the first thing worth knowing when something does not work.

### `/apps/slingshot-agent/content/console/retention`

Per retained kind: what is held now, the bound the contract states for it, what the next sweep would
release, and when the oldest record stops being retained. Capacity says how full a store is now and
retention says when things leave, and an operator who can see one without the other cannot answer
whether it will still be full tomorrow.

The case patience does not fix is named rather than left as a subtraction: a kind that would still
be over its bound after everything eligible had expired needs a wider bound or less kept, and the
page says so.

## What it deliberately does not have

- **A front-end toolchain.** Granite renders server-side and this ships one hand-written client
  library, which does the one thing a server cannot: follow a running operation. Several hundred
  packages to render five tables would be a second dependency graph with its own licences,
  advisories and upgrade cadence, and it would undo the compatibility argument the rest of this
  repository rests on.
- **An overlay.** Nothing here sits on top of a resource the platform provides. An overlay breaks at
  an upgrade months later and does not point at this product when it does.
- **A literal string.** Every word a person reads comes from the translation dictionary, including
  every column heading, empty state, and refusal. Retrofitting that means finding every literal
  somebody wrote in between, and the ones nobody finds are the ones a reader in another language
  meets.
- **A second source of truth.** Every value shown comes from the same store or the same route the
  machine-readable surface answers from, so the console and a client cannot disagree.
