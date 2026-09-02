# Plan 0001 — Foundations and Deployable Skeleton

## What this repository is

The Slingshot agent is the half of Slingshot that runs inside Adobe Experience Manager. The sibling repository holds a command line and a local daemon that submit work over a versioned transport and follow it; this repository holds the thing that receives that work, runs it against the repository the author owns, and reports what happened. The two halves are held to one another by `policy/author-agent-transport-contract-1.json` and by the five-field command-contract identity, both of which are reproduced here as committed bytes with committed digests rather than restated.

Nothing here is a library somebody links against. It is a content package that ends up installed in an author instance, and its whole observable surface is a small set of authenticated HTTP routes plus an author console for looking at what those routes have been asked to do.

How it gets there depends on the row, and the difference matters enough to state before anything else. On Adobe Experience Manager as a Cloud Service — the row this is built for — `/apps` is immutable and a package carrying `/apps` content cannot be installed into a running instance at all; the container package is a coordinate a customer's own project depends on and embeds in their container, and it reaches the author through their deployment pipeline. That is how Adobe Consulting Services Commons reaches a Cloud Service instance too. On a self-managed or managed-services author the same artifact is a file an operator installs by hand. One built artifact, two routes in, which is why the publication boundary declares two distribution targets rather than one — and why a claim about "installing" is always a claim about a particular row.

## The module set

The project is generated from Adobe's own archetype and then pruned. Eight modules remain, and the generated modules that are not in this list — the front-end build, the mutable content package, the dispatcher configuration, the user-interface test module — are removed by the scaffold task rather than left in place unused.

Every artifact is under the group identifier `rs.slingshot`, and every Java package under the root `rs.slingshot.agent`. Both reverse `slingshot.rs`, the domain the project holds, which is what makes the namespace one the central repository can verify rather than one this build asserted about itself.

Owning the domain is not the same as having verified the namespace, and the publication boundary keeps the two apart. A domain somebody holds is a fact about the world; a completed namespace verification is a fact about a registry, recorded by the owner who did it. Until that record exists a publish is refused, for exactly the reason every other declaration here is refused without its evidence.

| Module | Artifact | What it is |
|---|---|---|
| *(root)* | `slingshot-agent-parent` | Aggregator: the dependency and plugin management every module inherits, and the only place a version is written |
| `core` | `slingshot-agent-core` | The bundle that needs Sling, Oak, JCR, and OSGi and nothing else |
| `aem` | `slingshot-agent-aem` | The bundle that needs `com.day.cq` and `com.adobe.granite` |
| `ui.apps` | `slingshot-agent-ui.apps` | The immutable content package: console resources, overlays, client libraries |
| `ui.apps.structure` | `slingshot-agent-ui.apps.structure` | The repository structure package declaring the roots the others may write |
| `ui.config` | `slingshot-agent-ui.config` | Open Service Gateway Initiative configurations and the repository initialisation script |
| `all` | `slingshot-agent-all` | The container package an operator installs, embedding the other five in a proven order |
| `development` | `slingshot-agent-development` | Every repository-policy check, and the toolkit they are all built on |
| `interop` | `slingshot-agent-interop` | The Podman harness, the tier images, and the scenarios that install a package and observe it |

`core` and `aem` are two bundles rather than one because the split is what the interop harness is built on. Everything in `core` resolves against a plain Apache Sling runtime, so the transport, the store, the canonical bytes, and the execution framework are proved on a public image with no licensed input at all. Only `aem` needs Adobe's quickstart, and only the command handlers are in it.

The direction is one-way and executable: `aem` depends on `core`, `core` depends on nothing in this repository, and neither bundle depends on a package module. `development` and `interop` depend on their declared tooling set, the built artifacts they read, and the product modules at test scope — a policy check reads a produced archive and an interop scenario drives real types, so the edge is real and it is one-way. No product module reaches either, and neither reaches the other.

`development` and `interop` are separate because they answer different questions. A repository-policy check reads committed files, a resolved build model, and a produced archive, and it needs no running anything; an interop scenario needs a container, an installed package, and an instance that answers. Holding both in one module would mean every policy check carried a container engine as an ambient requirement, which is exactly the property that makes a gate unrunnable on somebody's laptop.

## The dependency footprint

Every module's product dependencies are `provided`. Nothing is embedded, nothing is shaded, and no `Private-Package` or `Embed-Dependency` instruction appears in a bundle. That is not a convention here: the built jar is opened and asserted against, so a dependency that arrives at compile scope fails the gate rather than shipping.

`com.adobe.aem:aem-sdk-api` is the single provided artifact the `aem` bundle compiles against. `core` compiles against the Sling, JCR, and Open Service Gateway Initiative packages that artifact also carries, and its manifest is asserted to import no `com.day.cq` or `com.adobe.granite` package, which is what makes the public tier possible.

Exported packages are declared by annotation on a package rather than by an instruction in a manifest. That is a deliberate choice about footprints as much as about style: a plan that adds an exported package would otherwise have to edit the module's build manifest, which is a file every other task in that module also wants, and sixty tasks queueing behind one manifest is the bottleneck this repository keeps designing away from. An annotation on the package's own declaration lives in the footprint of whoever created the package.

Imported packages are data. `policy/imported-packages.toml` holds one row per package either bundle imports, with the version range it accepts and the deployment rows that provide it. A manifest importing a package with no row fails; a row nothing imports fails. Version ranges are chosen once, in that file, and the bundle manifest is generated to match it rather than the other way round.

Test-scope and build-time artifacts are not product dependencies and are held to a separate rule: they are pinned to exact versions, they never appear in a bundle manifest, and the analysis plugins among them are configured rather than merely present.

## Java and the deployment rows

`maven.compiler.release` is 21 everywhere, in the aggregator, once. A module that sets its own is refused.

`support/deployments.toml` is the only place a supported deployment is declared. A row names the product, the Java runtime it provides, the Sling and Oak versions it carries, whether it is clustered, which interop tier can observe it, and the context path a route is reached under. The Adobe Experience Manager as a Cloud Service row is the one this product is built for. Any other row is a declaration and not a claim: it appears in the table, it constrains the bytecode target and the imported-package ranges, and it carries no evidence until a tier actually runs against it. That distinction is the sibling's, and it is kept for the same reason — a row somebody wrote down is not a machine somebody ran.

The bytecode contract is checked against the rows rather than assumed: a release-level of 21 is refused if any row in the table declares a Java runtime below 21, so adding a row is what forces the conversation rather than a support request.

## Where the numbers live

`support/agent-contract.toml` embeds the bounds this repository shares with the sibling — the transport contract's limits and formulas, byte for byte — together with the bounds only this side has: how long one event-stream session may be held open, how many may be held at once, how large a request body may be before it is refused, and how long a command may run before its lease is not renewed. The file is embedded into `slingshot-agent-core` as a resource and read through one typed accessor.

A value that file declares is written down nowhere else. A constant named after one of its limits, anywhere in the repository, is a second declaration that can disagree with the first quietly, and the source policy refuses it — which is the rule the sibling arrived at after that happened to it once.

The event-stream bounds are this side's own for a reason worth stating. An Adobe Experience Manager as a Cloud Service author sits behind a gateway that ends a long request whether or not it is still moving, and it serves from a bounded request-thread pool that a held stream occupies. The transport contract already gives the client a heartbeat timeout and a reconnection policy with a bounded attempt count; what it does not give is a maximum session length, because that is a property of the server's environment rather than of the protocol. So the agent ends its own streams before the gateway does, at a bound it declares, and the client's existing resumption path carries the subscription across.

## What the walking skeleton is

One route, `GET /bin/slingshot/agent/capabilities`, answering a bounded document that names the transport contract digest this build speaks, the canonical-byte contract digest its schemas will be written under, its event-store generation, whether its continuation-key authority is ready, and the command contracts it holds — which in this commit is none. It is deliberately the discovery route and not a health check, because discovery is the one route whose answer is already fully specified by the sibling's `AdvertisedCapabilities`, and answering it honestly with an empty command list is a more useful skeleton than answering a route nobody will ever call.

The route lives under `/bin`. Adobe reserves `/libs`, and a third-party servlet path in that namespace is a collision waiting for an upgrade to happen, even though a `sling.servlet.paths` registration creates no node. The sibling repository disagrees with itself about this: its production route constants say `/libs/slingshot/agent/…` while its own simulator and its daemon suites say `/bin/slingshot/agent/…`, and the two also disagree about whether the lookup route is `operations` or `snapshot` and whether the artifact route is singular or plural. This repository pins one route table, in `policy/agent-routes.toml`, and Plan 0004 owns both the compatibility aliases that let the shipped client reach it and the record of what has to change on the other side.

Authentication is not this plan's subject, but the skeleton is not exempt from it: the route is registered so that Sling's authentication handler has already run, and it answers only a request carrying an authenticated user. What that user must additionally be permitted is Plan 0004's.

## The interop harness

Three tiers, and the difference between them is what they prove rather than how thoroughly they run.

**Tier A** runs `core` and the packages on a public Apache Sling image. It needs nothing licensed, runs on any machine and in continuous integration, and proves everything that does not touch an Adobe API: the routes, the store, the idempotency key, the continuation-key authority, the event stream, artifact transfer, retention, and the whole execution framework against a fake command whose handler lives in the interop module.

**Tier B** runs `core` and `aem` together on an image built locally from an Adobe Experience Manager quickstart jar the owner supplies. The jar is licensed to its holder: it is never committed, never cached in the repository, never published, and never fetched. Its absence refuses the tier explicitly rather than skipping it, because a suite that quietly does not run is a suite that reports success it did not earn.

**Tier C** is Tier B with the sibling's own `slingshot` executable as the client, pinned by origin and commit. It is the only tier that proves the two halves of the protocol actually speak to one another, and it is the one whose failures are cross-repository defects rather than local ones.

The harness drives Podman rootlessly through a process wrapper this repository owns, and takes no container-orchestration test dependency. That is the same decision the sibling made about its process harness, for the same two reasons: the harness is a thing whose behaviour the suites depend on, so it should be code somebody here can read; and a test dependency that reaches a daemon socket is a dependency with an ambient requirement, which is the opposite of what a hermetic tier is for.

A scenario is its own file under `interop/scenarios/`, naming the tier it needs, the deployment rows it applies to, and the feature it covers. One file per scenario rather than one shared list: a plan that adds sixty scenarios would otherwise serialise sixty tasks behind one file, and a shared inventory is exactly the kind of bottleneck that turns a footprint rule into a queue. `scripts/quality` compares that inventory against the command registry and refuses a registered command with no scenario. In this commit the registry is empty and the comparison is vacuous; from Plan 0005 onward it is the rule that makes "every feature brings its own interop test" enforceable rather than aspirational.

`scripts/quality` takes no argument, fetches nothing, and writes nothing into the repository. It runs formatting, a compile with warnings as errors, the static-analysis set, the source policy, the six code-doctrine policies, the coverage floor, the content-package analyser, the FileVault validators, the dependency policy, the imported-package footprint, the module direction, and Tier A. Tiers B and C are separate commands, because a gate that needs a licensed input is a gate most contributors cannot run.

Fetching nothing is a property something has to arrange rather than a property a script can assert about itself, because Tier A starts a container image and the build resolves artifacts. Both are prepared once by commands that say they reach the network and verified offline afterwards — the locked dependency cache and the pinned interop images — and the gate refuses with the preparation command named when either is absent rather than quietly acquiring it. A gate that fetched a missing input is a gate whose result depends on a remote server after all, which is the thing this arrangement exists to prevent.

The source policy is a module of `development` rather than a shell script, and it parses Java rather than matching text: a file that names a forbidden construct in a comment or a string literal passes. It reports a file, a line, a rule, and a symbol, ordered deterministically. What it decides is closed — naming, file length, cyclomatic and cognitive complexity, suppression annotations, second declarations of a contract value, and the licence header — and what it cannot decide is a review checklist rather than a check that pretends to.

## What the Java is allowed to look like

Six policies, each its own file and its own checker, because they fail for different reasons and a single "style" check would report them all as one.

**Nothing is null.** No method accepts a null argument or returns a null value, and the checker decides it rather than a reviewer noticing. Absence is modelled by a type — a closed outcome, an empty collection, or a return-only `Optional` — which this repository was already doing everywhere that mattered before the rule existed: an admission is an outcome, a fence attempt is an outcome, a write is an outcome. `Optional` never appears in a parameter position, because it converts a caller's simple decision into a wrapper they have to build. The JetBrains annotations carry the contract, at provided scope, and a check proves neither bundle imports their package at runtime.

**No type is named `Impl`.** That suffix says a second name was needed and the first available one was taken. An interface `Foo` whose sole implementation this repository provides names it `DefaultFoo`; where several exist they are named for what distinguishes them, and none is `Default`, because a default among equals is a decision nobody made. Every public type is final or documents its extension points, every field is final without a recorded reason otherwise, and no field is public.

**Design patterns are declared and verified.** `policy/design-patterns.toml` has each significant type name the pattern it implements and why, and the checker verifies the declared pattern's structural signature — a declared builder has a build method returning the built type and the built type has no setter. A pattern that has to be declared with a reason is a pattern somebody chose; one that is merely present is a pattern somebody copied.

**The ceiling is on nesting, not on a number.** Complexity in a method is almost always nesting, and nesting is almost always a refusal written as an `else` instead of as a return. So the method-shape policy bounds nesting depth first, refuses an `else` attached to a block whose every path returns, and reports the guard clause that would remove the finding rather than only the finding. Cyclomatic and cognitive ceilings sit beside it as the symptom they are. Boolean parameters are refused in favour of a named two-valued type, which is the decision this repository already made for the reference policy and now makes everywhere.

**Documentation covers what a checker can decide.** Every type and every non-private member documented, every parameter and declared failure described, no summary that is the member's name with the spaces put back. Whether the prose is accurate or worth reading is a reader's judgement and stays a review checklist, which is the same split the rest of this repository uses.

**Allocation is bounded and streams are conditional.** A stream is the right way to express a transformation over a collection and a hand-rolled indexed loop doing the same thing is worse in every respect. But a stream in a path that runs per byte or per event allocates a pipeline and a capture each time round, so `policy/allocation.toml` declares which paths are allocation-sensitive and the rule inverts inside them. This runs inside somebody else's author sharing a heap with their content; waste here is not a benchmark number, it is a customer's instance pausing.

**And the Adobe practices that decide durability.** A resolver closed on every path including the early return, the resource abstraction preferred over direct repository access unless a row records why it cannot serve, no mutable state on a declarative-services component, no manual service lookup where a declared reference would do, and a deprecation list that names the replacement for every interface it refuses. Each is a real documented practice, each has a version of this code that passes every other check here and is still wrong at the next upgrade, and each is structurally decidable.

Two things follow from all of it. Every rule is decided by parsing rather than by matching text, so a file naming a forbidden construct in a comment or a string literal passes. And nothing is switched off where it is inconvenient: `@SuppressWarnings` and every analyser-specific suppression are refused outright, because Java has no equivalent of an expectation that stops applying when the situation it was written for ends.

## How the rules are enforced

The static-analysis set is chosen so that a Sonar scan finds nothing left to say: the rules that overlap Sonar's Java profile are enabled at build time and fail the build, and a suppression is refused outright rather than configured away. `@SuppressWarnings` is refused for the same reason the sibling refuses `#[allow(...)]`; the annotation that states a reason and stops applying when the situation ends does not exist in Java, so nothing takes its place.
