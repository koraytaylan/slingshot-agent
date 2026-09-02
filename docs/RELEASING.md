# Releasing

A release is a claim: these bytes, built from this source, doing what this documentation says, on
these deployment rows. Every part of that claim is either checked or explicitly not claimed, and
this describes which is which — and what an owner has to supply before any of it may be published.

Nothing here blocks a build, an install, or an operator taking the container package and putting it
on an author. What it blocks is publication, which is a claim to somebody else's namespace.

## What an owner supplies

Four things, none of which a build can decide on its own, all recorded in
`support/publication-metadata.toml`:

- **The repository address and its source-control connection.** A claim about where this source
  lives. A build that inferred it from a remote would be inferring it from wherever it was cloned.
- **The developer name and identifier.** Who is publishing. The registry requires it, and so does
  anybody trying to work out who to ask about an artifact they found in a dependency tree.
- **The signing identity and its public key fingerprint.** A signature is a claim that these bytes
  came from whoever holds that key. The key itself is never in this repository, and never will be.
- **The namespace verification acknowledgement.** The group identifier `rs.slingshot` reverses
  `slingshot.rs`, a domain the owner holds, so the namespace is *verifiable*. Verifiable is not
  verified: holding a domain is a fact about the world, and a completed registry verification is a
  fact about that registry which only the owner who did it can report. The boundary gates on the
  second, and `namespace.verified` is set by an owner and by nobody else.

Beside them, `support/github-automation-authority.toml` names the one repository the automation may
act on and the identity it may act as. A workflow runs with credentials scoped to wherever it
happens to be running, which is a property of the runner rather than a decision anybody made — so
the decision is written down, and a workflow acting outside it is refused.

## What each target needs

There are two targets because the artifact has two audiences, and a target whose preconditions are
met is never blocked by another target's absent ones.

- **The Maven repository** serves a customer project that depends on this container package and
  embeds it in its own. It requires everything above, including the verified namespace.
- **The release asset** serves an operator installing by hand. It requires the repository address
  and nothing else, because nobody is claiming a namespace by attaching a file to a release.

`PublicationAuthority` reports every absent field at once rather than the first, because somebody
about to release wants the list rather than one line of it per attempt.

## What the release does

`scripts/build_release_artifacts` builds the container package, both bundles, their sources and
documentation archives, and the components list, and records what each one hashes to in
`support/release-artifacts.toml`.

`scripts/verify_release_artifacts` builds a second time from the same source and compares entry by
entry, reporting the first entry that differs. Every archive is deterministic by construction —
fixed entry order, every entry carrying the instant the source declares, nothing
environment-dependent inside — so a difference is a real difference rather than an argument about
build environments.

The release workflow builds once and publishes the same bytes to both targets, because two builds
are two sets of bytes and two sets of bytes under one version is the disagreement nobody notices
until somebody compares them. Exactly one job may produce build provenance, and that job holds no
write access to anything it could be describing: a statement made by the thing it is about is not a
statement.

## What a release does not claim

- **Freshness of the advisory review.** The advisory snapshot is pinned by commit and content
  digest and reviewed by an owner as those exact bytes. There is no timestamp anywhere and no
  freshness claim, because a snapshot's author chooses both and neither authenticates anything.
- **A deployment row no evidence ran against.** A row in `support/deployments.toml` is a
  declaration. It becomes supported when a tier actually runs against it, and stays declared and
  unproved otherwise — the code compiling is not evidence about somebody else's platform.
- **Anything about a licensed tier that did not run.** The tiers that need a licensed input are
  named, with the exact command for each, rather than skipped: a suite that quietly does not run is
  a suite reporting success it did not earn.
