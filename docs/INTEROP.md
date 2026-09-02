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

### A known slowness in the pinned image

The image ships `org.objectweb.asm` 9.2, and that version refuses a Java 21 class file:
`Unsupported class file major version 65`. Apache Sling Models registers a weaving hook, so every
class it is asked to weave produces an `IllegalArgumentException` wrapped in a
`ClassFormatError: Weaving hook failed`, and the platform logs the whole stack. The requests still
complete — the failure is logged rather than fatal — so a tier that came up answers every question
here correctly.

What it costs is start-up time. A single container writes megabytes of stack traces in its first
two minutes, and when several scenarios start containers at once on a loaded machine one of them
occasionally spends longer than the readiness deadline producing them rather than starting. A tier
that fails this way reports `NEVER_BECAME_READY` and names the log it kept; the same scenario run
on its own comes up in about eight seconds. The fix is a pinned image whose ASM can read Java 21,
which is an image to be prepared rather than a value to be changed here, and until then a
`NEVER_BECAME_READY` on a loaded machine is to be reproduced alone before it is believed.

## Tier B — owner-supplied Adobe quickstart

**Command:** `scripts/interop_quickstart_tier`

Not part of the gate. The Adobe Experience Manager quickstart jar is licensed to whoever holds it:
it is never committed, never cached in this repository, never published, and never fetched. Its
absence refuses this tier explicitly rather than skipping it, because a suite that quietly does not
run is a suite reporting success it did not earn.

An owner puts their own jar at the path `support/quickstart-tier.toml` records, states its digest,
and sets the acknowledgement only they can set. Three things refuse distinctly and start nothing: an
absent jar, a jar whose digest is not the recorded one, and a missing acknowledgement. With all
three in place, the tier builds a container image locally from that jar — never as a build artifact
and never pushed — installs both bundles and all three content packages, and runs the same scenarios
Tier A runs.

## Tier C — sibling client end to end

**Command:** `scripts/interop_client_tier`

Not part of the gate. It is the only tier that proves the two halves of the protocol speak to one
another, by running the sibling repository's own client executable against a running agent, so its
failures are cross-repository defects rather than local ones.

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
