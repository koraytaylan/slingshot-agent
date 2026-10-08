# Interoperability tiers

Every claim this repository makes is a claim about software running inside somebody else's Adobe
Experience Manager deployment, and none of that is provable from a unit test. Three tiers exist for
it. Each one says what it proves, what it refuses, and the exact command that runs it.

The harness underneath all three is code this repository owns: it drives Podman rootlessly, declares
the ports it needs and no others, captures output to a bounded file rather than to memory, and
cleans up through the handle it started a container with rather than by looking a name up. There is
no container-orchestration test dependency, because the harness is the thing every suite depends on
behaving.

## Tier A — public Apache Sling

**Command:** `scripts/quality`

Runs as part of the gate, on any machine and in continuous integration, and needs nothing licensed.
It starts a pinned image built from `apache/sling` and `eclipse-temurin`, installs the Sling-only
bundle, and asks the running instance questions:

- the `core` bundle reaches the active state, which means every package it imports is provided;
- the `aem` bundle is absent rather than installed and unresolved, so a failure here is never
  mistaken for a missing Adobe interface;
- `/bin/slingshot/agent/capabilities` answers the document the unit suite proved, field by field;
- the same route refuses a request nobody authenticated, and discloses no field of the document in
  the refusal;
- the committed repository initialisation creates exactly the grants `policy/repository-access.toml`
  declares, and the agent's own identity holds nothing at `/content`, `/apps`, or `/home`.

It refuses, rather than pulling, when a pinned image is absent or its digest differs, and the
refusal names `scripts/prepare_interop_images`.

### Runtime and scenario isolation

The public image uses digest-pinned Sling 14 and the declared Java 21 runtime.
The Containerfile records why the older Sling 12 image could not read Java 21
classes. A readiness failure on the current image must be investigated from
that run's retained logs rather than attributed to the old ASM incompatibility.

Ordinary scenarios obtain one shared runtime through `SharedPublicSlingTier`.
Scenarios that stop the platform, replace bundles, or change storage lifecycle
use a dedicated runtime. Leak checks allow only the shared instance while its
test process is alive; shutdown releases it.

## Optional Tier B — owner-supplied Adobe quickstart

**Command:** `scripts/interop_quickstart_tier`

This is optional external validation, outside the Plan 10 and release gates. The Adobe Experience
Manager quickstart jar is licensed to whoever holds it: it is never committed, never cached in this
repository, never published, and never fetched. Its absence refuses this tier explicitly rather than
silently claiming a run.

An owner puts their own jar at the path `support/quickstart-tier.toml` records, states its digest,
and sets the acknowledgement only they can set. Three things refuse distinctly and start nothing: an
absent jar, a jar whose digest is not the recorded one, and a missing acknowledgement. With all
three in place, the tier builds a container image locally from that jar — never as a build artifact
and never pushed — installs both bundles and all three content packages, and runs the same scenarios
Tier A runs.

## Optional Tier C — sibling client end to end

**Command:** `scripts/interop_client_tier`

This is optional external validation, outside the Plan 10 and release gates. It runs the sibling
repository's own client executable against a running agent when that executable is available, so
its failures are cross-repository defects rather than local ones.

The executable is never committed here, never cached, and never fetched: it is built from the
sibling repository at the exact commit `support/client-tier.toml` names, and its holder records the
digest of what they built and acknowledges that this repository will run it. Absent it, the tier
refuses distinctly — no executable, an executable that is not the recorded one, a pinning naming a
version or a range rather than a commit, and an acknowledgement nobody made are four different
answers, each naming what its holder has to do.

A result is about that one commit and says so. The client is configured through its own profile
mechanism and nothing else — a profile document and a selection document under the configuration
root its own contract names, in a scratch home directory the tier owns — so the exchange proved is
the one a user would have rather than one this repository arranged.

## Preparing what the tiers need

Two commands reach the network, and both say so when they run:

```
scripts/prepare_interop_images
scripts/prepare_locked_dependency_cache
```

Two verify offline what they prepared, and are what the gate actually runs:

```
scripts/verify_interop_images
scripts/verify_locked_dependency_cache
```

A tier that pulled an image at gate time would make the gate's claim to fetch nothing false, which
is why the preparation and the verification are two commands rather than one.
