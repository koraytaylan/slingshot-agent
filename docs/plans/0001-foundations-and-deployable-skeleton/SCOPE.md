# Plan 0001 — Foundations and Deployable Skeleton

> One reproducible Adobe-archetype project that installs into an author, proves it is there, and cannot be changed without passing a gate that takes no argument.

## Why this plan

Slingshot's client half already exists in the sibling repository. It speaks a versioned transport contract to an agent it does not contain, and it holds that agent to bounds it reads from `policy/author-agent-transport-contract-1.json`. This repository is that agent. Everything it will eventually do — sixty-four commands, a durable job store, an event stream, an author console — is work that has to be installed into an Adobe Experience Manager author instance and observed running there, which is a different kind of proof from anything a unit test can offer.

So the first plan builds the thing that can be installed and the machine that observes it. Not a command, not a route, not a protocol document: one bundle, one content package, one servlet that answers, and a rootless container harness that installs the package into a real Sling runtime and asks. Every later feature inherits that harness, and the plan that adds it is refused if it does not use it.

The dependency footprint is decided here too, and it is decided by construction rather than by intention. Adobe publishes the whole author API surface as one `provided` artifact, `com.adobe.aem:aem-sdk-api`, and a bundle that embeds nothing has exactly the compatibility of the packages it imports. This plan makes "embeds nothing" a checked property of the built artifact rather than a claim in a README, and it makes every imported package a row somebody chose.

Two bundles rather than one, because the split is what makes the harness affordable. The transport, the store, the canonical bytes, and the execution framework need Sling, Oak, and OSGi and nothing else; only the command handlers need `com.day.cq` and `com.adobe.granite`. Splitting them lets the whole protocol surface be proved against a public Apache Sling image that anyone can pull, and confines the tier that needs Adobe's licensed quickstart to the handlers that genuinely cannot run without it.

## In scope

- **0001 — Reproducible Scaffold and Compatibility Declaration.** Record the exact Adobe archetype coordinates and generation properties that produced the skeleton, prune the generated module set to the eight this product has, give the two tooling modules one shared policy toolkit and one declared dependency set, pin Java 21 as one release-level bytecode contract, declare the supported deployment rows and the Java runtime each provides as data rather than prose, embed the transport contract's bounds once and read them through one typed interface, and make both the module dependency direction and the built bundles' complete imported-package footprint executable checks.
- **0002 — Legal and Publication Boundary.** Reproduce the sibling's `MIT OR Apache-2.0` dual licence and its copyright line, carry an SPDX identifier on every repository-owned source file, state the licence once in the aggregator and inherit it, and refuse packaging or publication while the owner-supplied namespace, repository, and developer metadata are absent — the same boundary the sibling holds, for the same reason.
- **0003 — The Quality Gate and the Code Doctrine.** One `scripts/quality` that takes no argument and fetches nothing, and behind it the rules that decide what this repository's Java is allowed to look like: the static-analysis set chosen to leave a Sonar scan with nothing to report; a source policy under which every declared name is spelled in full, no numeric value carries meaning without a name, and no file passes one thousand lines; a nullability contract in which no method accepts or returns a null and absence is a type rather than an absence; an API-shape policy that refuses the `Impl` suffix outright, names a sole implementation `Default` after its interface, and holds every declared design pattern to its own structural signature; a method-shape policy whose primary ceiling is nesting rather than a complexity number, because nesting is the cause and the number is the symptom; documentation completeness that decides the falsifiable half and reviews the rest; an allocation policy under which streams express transformations everywhere except the declared hot paths, where they are refused; a policy for the Adobe practices that decide whether this survives the next upgrade; a coverage floor that fails a build rather than producing a report; the Adobe content-package analyser Cloud Manager itself runs; and a dependency policy under which no artifact reaches either bundle at compile scope.
- **0004 — Walking Skeleton and Interop Harness.** One bundle that exports nothing and answers one route, one `all` package that installs it in a proven order, one service user with a repository initialisation script and an access-control list narrow enough to name, a rootless Podman harness with no test-container dependency, a public Apache Sling tier that runs on any machine, an owner-supplied Adobe quickstart tier that is refused rather than skipped when the jar is absent, an interop-coverage gate that refuses a feature with no scenario, and the root documents describing what this commit actually contains.

## Out of scope

- The agent protocol's documents, canonical bytes, identity, or continuation keys. Plan 0002 owns them.
- Any durable operation, Sling Job, event, or artifact. Plans 0003 and 0004 own them.
- Any command, any Adobe Experience Manager API call, and the `aem` bundle's handler surface. Plans 0005 through 0007 own them.
- Any author user-interface resource beyond what the walking skeleton needs to be installed. Plan 0008 owns the console.
- Release artifacts, provenance attestation, and the published pipeline. Plan 0009 owns them.
- The Adobe quickstart jar itself, which is licensed to its holder and is never committed, cached, or published from here.

## Plan dependencies

None. This is the first bundle in the repository and every later plan validates against it.
