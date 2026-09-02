# Installing

This runs inside your author instance, as a service user, in the same process as your content. This
says how it arrives, what it needs, what it costs, and how to tell it is working — in the terms a
platform team asks in rather than in the terms a feature list is written in.

## How it arrives

It depends on the row, because the rows differ in one way that decides it: whether `/apps` can be
written at run time.

- **Adobe Experience Manager as a Cloud Service.** `/apps` is immutable. The container package is
  embedded in your own project's build and deployed through your pipeline like the rest of your
  code. Nothing is installed by hand, and nothing this product ships expects to be.
- **Adobe Experience Manager 6.5 LTS.** `/apps` is writable. The container package is installed as a
  content package, by hand or through the package manager.

Either way it is one artifact — `slingshot-agent-all` — carrying two bundles and three content
packages. What each part is, and why the bundles are two rather than one, is in the
[README](../README.md).

## What it needs from your instance

- **A service user and its grants**, created by the repository initialisation this product ships.
  The grants are a readable list and they do not include writing content. That is not an oversight:
  the agent itself may not write your content, and widening who may call it does not change that.
- **A permitted group.** The shipped configuration names `administrators` and nothing else. Nobody
  outside it may start work. Changing that list is the decision described in
  [SECURITY.md](SECURITY.md).
- **Nothing else.** No index is shipped and none is required: every query this product issues is
  declared as data and checked against the indexes each row already provides. A command that would
  have needed a new index was refused at build time and rewritten to walk the resources it was given
  instead.

## What it costs

The numbers a platform team actually asks about, all of them bounded by the contract this build
authenticates rather than by whatever the code happens to do:

- **Repository writes per operation.** Exactly one commit for a command that changes your
  repository, and none for one that changes something the platform owns — enforced by a wrapper that
  refuses the second commit and reads how many are owed from the command's own registry row.
- **Concurrency.** A total bound on commands running at once and a per-caller bound beneath it, so
  one client cannot spend the instance's capacity on everybody else's behalf. Both are in
  `support/agent-contract.toml`.
- **Request threads.** An event stream does not hold one. The stream is written from a pool this
  bundle owns after the request thread is released, which is the whole reason the streaming route is
  separate from the rest.
- **Storage.** Everything the agent writes lives under one root the initialisation creates, counted
  against per-kind bounds, and collected by a sweep whose work is bounded per pass so it never runs
  long enough to matter.

## How to tell it is working

Through the health checks rather than through prose. Six of them appear in your own operations
dashboard, each with its own name and its own cause:

| Check | What it says |
|---|---|
| `state-tree` | The agent's own tree exists and carries exactly the declared access-control entries, naming the first difference rather than counting them. |
| `continuation-authority` | The key ring can issue a token and accept it back — performed rather than inspected, at most once per declared interval, saying when it last ran. |
| `capacity` | Every counted thing against its bound, named, so somebody can watch one approaching rather than meet it. |
| `deployment-row` | Which row this instance matches and whether this build claims it. Unclaimed is not a failure and says so. |
| `route-registration` | Every declared route is registered here — and if one is not, the servlet resolver's own permitted prefixes, because that is the configuration to open. |
| `query-coverage` | Every declared query is answered by an index rather than by a walk, naming the query and the index it wants. |

A check that could not run reports that it could not, which is a different verdict from finding
something wrong. A dashboard that conflated the two would have you chasing a store that is fine
because the check beside it timed out.
