# Architecture

What this repository is today, module by module. Everything it intends to become is in the plan
bundles under `docs/plans`, and stays there.

## The module set

Eight modules, one aggregator, one version property, and no module that declares a version for
anything the aggregator manages.

| Module | Packaging | What it is |
|---|---|---|
| `core` | `jar` (bundle) | The Sling-only bundle: the contract, the route table, the discovery servlet, and the one place a session is obtained. |
| `aem` | `jar` (bundle) | The Adobe-only bundle, and the only module that compiles against `com.adobe.aem:aem-sdk-api`. |
| `ui.apps` | `content-package` | The application tree at `/apps/slingshot-agent`. |
| `ui.config` | `content-package` | The repository initialisation and the service user mapping. |
| `ui.apps.structure` | `content-package` | The immutable roots this product writes, read by every other package and embedded by none. |
| `all` | `content-package` | The one container: two bundles under the author run mode, three content packages. |
| `development` | `jar` | The repository's own policy checkers. It produces no installable artifact. |
| `interop` | `jar` | The container harness and the tiers. It produces no installable artifact. |

## The two-bundle split

`core` compiles against the open Apache Sling, Oak, JCR, and Open Service Gateway Initiative
artifacts, and its resolved compile classpath carries no Adobe-namespaced package at all. That is
checked, in both directions, from the built manifests rather than intended in a comment.

The split exists so that the public tier can prove the whole protocol surface with no licensed
input. A tier that had to install the Adobe bundle would need a quickstart jar licensed to its
holder, and a gate most contributors could not run is a gate that stops being run. The tier
therefore installs `core` alone and asserts that `aem` is *absent* rather than installed and
unresolved, so a failure there is never mistaken for a missing Adobe interface.

The cost of the split is a rule: a package the Adobe platform provides may not be imported by
`core`. `policy/imported-packages.toml` holds each bundle's complete imported-package set, compared
with the built manifest in both directions, and `policy/module-direction.toml` holds the permitted
edges between modules.

## The contract

`support/agent-contract.toml` carries the sibling's seventy-one transport bounds reproduced
byte-equivalently beside this side's own twenty-five event-store, request, and lease bounds, with
its digest in `support/agent-contract.sha256` and the sibling's transport-contract digest in
`support/transport-contract.sha256`. The file is embedded in `core` at build time and authenticated
against its digest before a bound is parsed, and every bound is reached through one of ninety-six
named constants rather than by string key. Nothing may declare a bound a second time.

## The route table

`policy/agent-routes.toml` spells every route this agent will ever serve, each with its method,
media type, whether a body is permitted, and the plan that owns it. A route outside the
`/bin/slingshot/agent` prefix, a duplicate, and a route with no owning plan are three distinct
refusals.

The namespace is a decision rather than an inheritance: Adobe reserves `/libs`, and a third-party
servlet path in that namespace is a collision waiting for an upgrade to happen. One route is served
today, `/bin/slingshot/agent/capabilities`, and the interoperability-coverage gate refuses the day a
second one is served with nothing proving it on a running instance.

## The access model

Two kinds of access, and confusing them is how an agent becomes a way to do things the caller could
not do themselves.

- The agent's own bookkeeping runs as the service user `slingshot-agent-state`, which may read and
  write `/var/slingshot-agent` and nothing else. `policy/repository-access.toml` declares every
  grant with a reason, and a scenario compares the declared grants with the ones a running
  repository created out of the committed configuration, in both directions.
- Everything a caller asked for runs as the caller. A command executes inside the request that
  submitted it, so the caller's session is the request's own and there is nothing to obtain, borrow,
  or grant.

`AgentSession` is the only place a session is obtained, and it offers exactly those two paths. There
is no third: no impersonation, no stored credential, no token. An agent that executed later would
have needed a standing privilege over other people's identities; not needing it is worth more than
any amount of care in bounding it.

## The gate

`scripts/quality` runs nineteen declared stages, in one order, with no argument and no way to run
less of it. `policy/quality-gate.toml` declares the same stages and the same tiers, and the two are
compared in both directions, so a stage that exists in one and not the other fails the build.

Maven runs offline against a dependency cache prepared by `scripts/prepare_locked_dependency_cache`
and verified by `scripts/verify_locked_dependency_cache`; container images are pinned by digest in
`support/interop-images.toml`, prepared by `scripts/prepare_interop_images`, and verified offline by
`scripts/verify_interop_images`. Both preparation commands say outright that they reach the network.
Nothing else does.
