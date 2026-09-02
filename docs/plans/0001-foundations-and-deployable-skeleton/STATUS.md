# Plan 0001 — Foundations and Deployable Skeleton — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending. Every task is complete and verified; the final integration commit is not made.
- **Goal:** produce one reproducible Adobe-archetype project that installs into an author instance, answers the discovery route the sibling's client already knows how to call, and cannot be changed without passing a gate that takes no argument and fetches nothing.
- **Root cause:** the client half of Slingshot already exists and already holds an agent to a versioned transport contract, and every claim this repository will ever make is a claim about software running inside somebody else's Adobe Experience Manager deployment. Nothing about that is provable from a unit test, so the harness that installs a package and asks it questions has to exist before the first feature does — and the dependency footprint that decides where that package can be installed has to be a checked property of the built artifact rather than an intention in a README.
- **Approach:** record the archetype generation and pin the build wrapper; prune to eight modules, separate repository policy from container interop so a policy check never needs a container engine, and split the product into a Sling-only `core` bundle and an Adobe-only `aem` bundle so a public container tier can prove the whole protocol surface without a licensed input; pin Java 21 by reading class files rather than properties; declare supported deployments as rows that constrain the bytecode target and carry no evidence they wrote for themselves; embed the sibling's transport bounds byte-equivalently beside this side's own event-stream, request, and lease bounds in one digest-authenticated contract file that nothing may declare a second time; make the module direction and both bundles' complete imported-package sets executable two-way checks over built manifests; reproduce the sibling's dual licence and its publication boundary; assemble a static-analysis, source-policy, coverage, package-analysis, and dependency gate chosen so a later scan has nothing left to report, and behind it six policies that decide what this repository's Java may look like — no null accepted or returned anywhere and absence modelled as a type, no `Impl` suffix and a sole implementation named `Default` after its interface, nesting rather than a complexity number as the primary method ceiling so refusals are written as guard clauses, documentation complete on every falsifiable axis, streams everywhere except declared hot paths where they are refused, and the Adobe practices that decide whether this survives an upgrade — all behind one argument-free `scripts/quality` that runs offline against a separately prepared and independently verified dependency cache and an equally pinned and verified set of interop images, refusing with the preparation command named rather than fetching either; and land one route, one container package, one service user whose grants are a list somebody can read out loud and include no power over anybody else's identity, a rootless Podman harness this repository owns that starts one instance or two against one shared repository because every property about contention needs the second, three tiers, and a coverage gate that refuses a feature with no scenario while the comparison is still vacuous.
- **Progress:** 29/29 tasks done; 0 blocked; 0 dropped. All four workstreams are complete: the
  scaffold and its contracts, the licence and publication boundary, the twelve gate policies, and
  the route, the container, the service user, the harness, the tiers, the interoperability-coverage
  gate, and the project documents.
- **Integration:** `in progress`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; mode `sequential`; final integration `pending`.
- **Exceptions:** seven, each recorded where it was made.
  - `core` compiles against the open Sling, Oak, JCR, and Open Service Gateway Initiative artifacts
    rather than against `com.adobe.aem:aem-sdk-api`. Task 0106 requires `core`'s resolved compile
    classpath to carry no Adobe-namespaced package, and the Adobe artifact carries the whole
    `com.day.cq` surface, so the two could not both hold. The Adobe artifact remains the single
    provided platform artifact the `aem` bundle compiles against, which is what the architecture
    says it is for.
  - The nullability contract admits a package-level `@NotNullByDefault` as a second permitted form
    beside the per-member annotation, recorded in `policy/nullability.toml` with its reason. The
    contract is unchanged; what changed is where it is stated.
  - Task 0101's structure rules live in `development/src/main/java/.../ProjectScaffold.java` rather
    than inside the test its footprint names, so that every check in this repository is a policy
    class with a thin test rather than one check being shaped differently from the other twelve.
  - Task 0403's declared-against-created comparison lives in
    `development/src/test/java/.../RepositoryAccessTest.java`, and its on-instance half in
    `interop/src/test/java/.../tier/RepositoryAccessScenario.java`, rather than in the single
    `interop/.../RepositoryAccessTest.java` the footprint names. The two halves are answered by two
    different things — a policy document and a running repository — and one file holding both would
    have made a policy check depend on a container engine, which is the separation this plan set
    out to keep.
  - Task 0405's public tier installs the Sling-only bundle and, where a scenario needs it, the
    committed configuration through the platform's own installer, rather than the whole container
    package. The container installs its bundles under the author run mode, which a plain Apache
    Sling runtime does not have, so a tier that handed it the container would prove nothing about
    the bundles. What the container carries, where each part goes, and under which run mode is
    proved from the built artifact by `ContainerPackageTest`, and the licensed tier is where the
    whole package installs.
  - Task 0406's refusals are proved here and its running half is not. An absent jar, a jar whose
    digest is not the recorded one, and a missing acknowledgement refuse distinctly and start
    nothing; installing both bundles on an owner's own quickstart is declared and unproved until an
    owner runs `scripts/interop_quickstart_tier` with a jar licensed to them, which is the one thing
    this repository cannot do on anybody's behalf.
  - The service user's refusal at `/content`, `/apps`, and `/home` is proved as the absence of any
    access-control entry naming its principal, rather than by attempting a write as it. A service
    user has no credential to authenticate with, so a write attempt as that principal is not
    something a scenario can make over the transport; default-deny is what makes the refusal true,
    and an entry granting it is the one thing that would stop it being so.
- **Outcome:** the eight-module reactor builds on Java 21 with the two-bundle split proved by a
  classpath check; the sibling's seventy-one transport bounds are reproduced byte-equivalently
  beside this side's twenty-five, digest-authenticated before a bound is parsed and reached through
  ninety-six named constants; one argument-free `scripts/quality` runs nineteen declared stages
  entirely offline out of a locked dependency cache and a digest-pinned image, closing by naming the
  two tiers it did not run and the command for each. A real Apache Sling runtime, started rootlessly
  from an image pinned by digest, holds the Sling-only bundle active with every import resolved and
  the Adobe bundle absent, answers the capability document the sibling's discovery already expects,
  refuses the same route to a caller nobody authenticated without disclosing a field of it, and
  creates out of the committed configuration exactly the grants the access policy declares — with
  nothing for the agent's own identity at content, applications, or the user tree. Every feature
  that is served brings its own scenario, checked against the registrations in the product's own
  sources, and the five product documents name no route, tier, stage, or policy that does not
  exist.

_Last updated: 2026-09-01, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
