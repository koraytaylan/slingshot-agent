# Slingshot Agent

The Adobe Experience Manager half of Slingshot. The sibling repository holds the command line and
the local daemon that submit work over a versioned transport and follow it; this one holds the agent
that receives it, runs it against the author's own repository, and reports what happened.

It answers eight routes and sixty-four commands, renders five console screens an operator finds
under Adobe's own Tools navigation, and publishes six health checks into the author's own
dashboard. Everything described here is in this commit; nothing here describes what the bundles
under `docs/plans` intend, and the deployment rows carry the evidence that actually ran against them
rather than the evidence somebody hoped for.

## What installs

One container package, `all`, which a customer's own build embeds or an operator installs by hand.
It carries two bundles and three content packages:

| Part | What it is |
|---|---|
| `core` | The Sling-only bundle. Everything in it resolves against a plain Apache Sling runtime. |
| `aem` | The Adobe-only bundle. It is the only thing that compiles against `com.adobe.aem:aem-sdk-api`. |
| `ui.apps` | The application tree, at `/apps/slingshot-agent`. |
| `ui.config` | The service user, its access-control entries, and the subservice mapping. |
| `ui.apps.structure` | The immutable roots this product writes, which every other package depends on. |

The split between the two bundles is what makes the public interoperability tier possible: the whole
protocol surface can be proved on an image anybody can pull, with no licensed input at all.

## What the routes answer

Every route is bound to its exact path and answers one method. A selector, an extension, a suffix,
or a trailing segment is a second spelling of a route, and a route with spellings nobody enumerated
is a route whose policy applies to some of the ways it can be reached — so each of those is refused
before a parameter is read.

| Route | What it is for |
|---|---|
| `GET /bin/slingshot/agent/capabilities` | The discovery document the sibling's client already knows how to read: the event-store generation, the command list, whether a continuation authority is ready, and the digest of the transport contract this side speaks. |
| `POST /bin/slingshot/agent/submit` | Starting work. The submission's key is derived here rather than believed, and an immediate command runs inside this request, on this request's own session, before the acknowledgement is written. |
| `POST /bin/slingshot/agent/snapshot` | What one operation became, answered from the store alone, keeping "not there yet" apart from "never there". |
| `POST /bin/slingshot/agent/jobs` | Which physical attempts one logical operation had, for reconciling after a disruption. |
| `POST /bin/slingshot/agent/subscriptions/high-water` | How far a subscription has actually been served, which is not the number its subscriber last saw. |
| `GET /bin/slingshot/agent/events` | One subscriber's live view of one operation. The only route that stays open, and the only one that ends its own session before somebody else's gateway severs it. |
| `POST /bin/slingshot/agent/artifact` | Bytes arriving for a command that has not started, taken in against a manifest declared before the first byte was sent. |
| `GET /bin/slingshot/agent/artifact` | A result too large to answer inline, served with the byte count and digest a reader verifies for itself rather than trusting what the store says about itself. |

A request nobody authenticated is refused without disclosing a single field of any of them, and
starting work additionally requires membership of a group an operator permitted.

## What an operator gets

Sixty-four commands, one row each in `policy/commands`, split into what they may do: reads that
cannot commit through the caller's own resolver, and writes that commit only through the caller's
own session. Every one of them runs as the person who asked, inside their own request, so it does
exactly what that person could have done by hand and nothing more. There is no impersonation call
anywhere in either bundle.

Five console screens, described in [docs/CONSOLE.md](docs/CONSOLE.md), rendered on the server from
Granite's own components with one hand-written client library and no package manager anywhere. They
are read-only, deliberately: a console that wrote would be a second way to do what the routes do,
with a second authorization story.

Six health checks, each with its own cause, in the operations dashboard — including the two that
catch what only somebody else's instance can break: routes the servlet resolver's configuration
never registered, and a declared query whose covering index that repository no longer has.

Installing it is [docs/INSTALLING.md](docs/INSTALLING.md); what it may and may not do is
[docs/SECURITY.md](docs/SECURITY.md).

## What proves it

Three tiers, described in [docs/INTEROP.md](docs/INTEROP.md). One of them needs nothing licensed and
runs on any machine; it starts a real Apache Sling runtime, installs the bundle, and reads the
capability document back out of it.

One command runs the gate:

```
scripts/quality
```

It takes no argument, runs every stage every time, and fetches nothing: the Maven dependencies come
from a cache prepared separately and verified offline, and the container images are pinned by digest
and never pulled at gate time. It closes by naming the tiers it did not run and the command for
each.

## Licence

Dual licensed under [MIT](LICENSE-MIT) or [Apache 2.0](LICENSE-APACHE), at your option — the same
terms the sibling repository offers, reproduced exactly.
