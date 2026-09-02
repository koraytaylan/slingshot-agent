# Contributing

Every rule here is a committed policy document with a checker and its own fixtures, and every rule
names the stage of `scripts/quality` that enforces it. Nothing in this file is a convention somebody
has to remember: if it is not checked, it is not a rule, and if it is checked, changing it means
changing a policy file rather than arguing about a habit.

## Running the gate

```
scripts/quality
```

It takes no argument and runs every stage every time. It fetches nothing: Maven runs offline against
a prepared dependency cache, and container images are pinned by digest and never pulled. Two
commands reach the network and say so when they run —
`scripts/prepare_locked_dependency_cache` and `scripts/prepare_interop_images` — and they are the
only two.

## The rules a change is held to

- Every dependency comes from the prepared cache, whose contents are verified against
  `support/locked-dependency-cache.toml` before anything is built. (stage: locked-dependency-cache)
- Every container image is present at the exact digest `support/interop-images.toml` pins, and no
  tier pulls one. (stage: pinned-interop-images)
- Every file is formatted the way `policy/analysis/checkstyle.xml` decides, which
  `policy/static-analysis.toml` declares. (stage: formatting)
- Every module compiles on Java 21 with every warning an error. (stage: compilation)
- Every static-analysis finding is a build failure, over the configuration
  `policy/static-analysis.toml` declares, with an exclusion file that stays empty.
  (stage: static-analysis)
- No source obtains an administrative session, acts as somebody else, or uses an identifier
  `policy/abbreviated-identifiers.txt` refuses; the whole rule set is `policy/source-policy.toml`,
  and `policy/licence-headers.toml` decides the header every file carries.
  (stage: source-policy)
- Nothing accepts or returns null, and absence is modelled as a type, as
  `policy/nullability.toml` states. (stage: nullability)
- No type is named for its interface with a suffix, a sole implementation is named `Default`, and
  every declared design pattern matches its own structural signature; the two documents are
  `policy/api-shape.toml` and `policy/design-patterns.toml`. (stage: api-shape)
- The ceiling on a method is its nesting rather than a complexity number, so a refusal is written as
  a guard clause; `policy/method-shape.toml` decides how deep. (stage: method-shape)
- Documentation is complete on every axis a checker can decide, as `policy/javadoc.toml` states.
  (stage: documentation)
- Streams are used everywhere except the paths `policy/allocation.toml` declares
  allocation-sensitive, where they are refused. (stage: allocation)
- The Adobe practices that decide whether this survives an upgrade are checked rather than
  remembered, and `policy/adobe-practice.toml` is the list. (stage: adobe-practice)
- Every test passes and every module and class meets the coverage floor `policy/coverage.toml`
  declares; the routes a change adds are the ones `policy/agent-routes.toml` spells, the grants it
  needs are the ones `policy/repository-access.toml` declares, and the nodes it writes are the ones
  `policy/repository-layout.toml` declares. (stage: tests-and-coverage-floor)
- Every way a command can fail answers the status and the retryability the client already declares,
  one committed row per category with no default branch and no hint on a refusal that trying again
  cannot fix, as `policy/failure-status-mapping.toml` states. (stage: status-mapping-coverage)
- The second paths this side carries for the client that exists today are exactly the ones it asks
  for, compared in both directions against the constants `policy/client-route-constants.toml`
  records out of that repository, each one naming the correction that removes it, and none of them
  served by what a customer receives. (stage: tests-and-coverage-floor)
- No command may traverse: every query is declared as data, checked at build time against the
  indexes `policy/query-index-coverage.toml` says each deployment already provides and at run time
  against the plan the instance really returns, and nothing shipped carries an index definition.
  (stage: tests-and-coverage-floor)
- Nothing that must never leave this agent leaves it: every route is driven on a running instance
  with a planted value of every kind `policy/redaction-corpus.toml` names, and every body, header,
  log line and piece of a stream is scanned for all of them. (stage: public-interop-tier)
- Nothing a caller supplies reaches a grammar: every attack shape `policy/injection-corpus.toml`
  declares is driven through every caller-supplied member, and the build reaches no query engine at
  all, because a product with no statement has none for a value to break out of.
  (stage: injection-audit)
- Nothing is published that the registry would reject: every prerequisite
  `policy/central-prerequisites.toml` names is decided here, offline, against the built artifacts
  and the resolved model, with every failure reported at once rather than discovered from a remote
  rejection. (stage: release-contract)
- The container package carries exactly the artifacts, paths, and run modes it declares, and no
  content package writes outside the roots the structure package owns, as
  `policy/package-analysis.toml` decides. (stage: package-analysis)
- Every dependency is declared once, at a version the aggregator manages, in the scope
  `policy/dependencies.toml` permits. (stage: dependency-policy)
- Each bundle's complete imported-package set is the one `policy/imported-packages.toml` declares,
  read from the built manifest. (stage: imported-package-footprint)
- No module reaches a module `policy/module-direction.toml` does not permit it to reach.
  (stage: module-direction)
- Every feature that is served brings its own scenario, and every scenario names a tier
  `policy/quality-gate.toml` declares and a deployment row the matrix holds.
  (stage: interop-coverage)
- Every command that changes something outside itself declares exactly one kind of change, and
  exactly one cross-cutting suite claims that kind — `policy/mutation-safety.toml` declares the
  kinds, and selection is by what a registry row declares rather than by where its handler lives, so
  a row claimed by nobody and a row claimed by two are both refused. (stage: mutation-coverage)
- Every deployment declares which platform controls it provides and why it refuses the ones it
  does not — `support/deployments.toml` — and every control a command needs is one of the closed set
  in `ControlCapability`, mapped in `policy/control-capabilities.toml` and compared in both
  directions. (stage: control-capability)
- This registry and the client's published table are the same sixty-four rows, compared field by
  field because the fixes differ. (stage: registry-completeness)
- The rendered command reference in `docs/COMMANDS.md` is generated from the registry and checked
  against it, so a command that exists appears there or the build does not pass.
  (stage: command-reference)
- Every command that changes the platform is gated by a capability some deployment can refuse and
  claimed by exactly one cross-cutting suite. (stage: platform-coverage)
- Nothing this product writes sits on top of a platform resource, and no package filter reaches
  outside the roots the structure package declares. (stage: overlay-audit)
- The console is one hand-written script, one stylesheet, and no package manager anywhere in the
  repository. (stage: front-end-footprint)
- The public tier starts, installs the Sling-only bundle, and answers from a running instance
  rather than from the build; what the documents may claim about it is decided by
  `policy/documentation-rules.toml`. (stage: public-interop-tier)

## The code doctrine

Six policies decide what this repository's Java may look like. Each is one sentence, and each names
the file that decides it.

- **Nothing is null.** Absence is a type, and an `Optional` is a return value rather than a
  parameter or a field — `policy/nullability.toml`.
- **No type is named `Impl`.** A sole implementation is named `Default` after its interface —
  `policy/api-shape.toml`.
- **The ceiling is on nesting.** A method that would nest deeper is one that should have refused
  earlier — `policy/method-shape.toml`.
- **Documentation covers what a checker can decide.** Every public member, every parameter, every
  return, and every thrown reason — `policy/javadoc.toml`.
- **Streams everywhere but the declared hot paths.** A path that cannot afford an allocation says so
  and is checked for it — `policy/allocation.toml`.
- **Adobe's practices decide durability.** The ones that decide whether this survives an upgrade of
  somebody else's instance — `policy/adobe-practice.toml`.

## Changing a rule

A rule changes in three places at once: the policy document, the checker under
`development/src/main/java/rs/slingshot/agent/development/`, and the fixtures that prove the checker
refuses what it says it refuses. A checker with no fixture is a rule nobody has watched refuse
anything.

Adding a stage means adding it to `scripts/quality` and to `policy/quality-gate.toml`; the two are
compared in both directions, so neither can drift.

## What a checker cannot decide

Whether a sentence is true, whether a document is complete, and whether a failure message tells a
reader what to do are questions no checker answers. They are a closed checklist in
`policy/documentation-rules.toml`, answered in [docs/DOCUMENTATION_REVIEW.md](docs/DOCUMENTATION_REVIEW.md).
