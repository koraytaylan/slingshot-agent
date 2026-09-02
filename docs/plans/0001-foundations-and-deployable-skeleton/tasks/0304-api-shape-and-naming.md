---
id: api-shape-and-naming
title: "API Shape and Naming"
workstream: "0003"
kind: task
depends_on:
  - nullability-contract
gated: false
touches:
  - policy/api-shape.toml
  - policy/design-patterns.toml
  - development/src/main/java/rs/slingshot/agent/development/ApiShapePolicy.java
  - development/src/main/java/rs/slingshot/agent/development/DesignPatternRegister.java
  - development/src/test/java/rs/slingshot/agent/development/ApiShapePolicyTest.java
  - "development/src/test/resources/fixtures/api-shape/**"
status: done
merged_as: ""
---
# API Shape and Naming

A type called `SomethingImpl` tells a reader that somebody needed a second name and reached for the first suffix available. It says nothing about what the type is, it pairs one-to-one with an interface that then had no reason to exist, and it has survived in Java codebases for two decades purely by inertia.

So: no type name in this repository ends in `Impl`. An interface `Foo` whose single default implementation this repository provides names that implementation `DefaultFoo`. Where several implementations exist they are named for what distinguishes them — `RepositoryKeyAuthority` beside `ContinuationKeyAuthority`, `PublicSlingTier` and `QuickstartTier` beside `InteropTier` — because a name that describes the variant is the only reason to have more than one.

**Steps:**

1. Author fixtures for a type suffixed `Impl`, a single-implementation interface whose implementation is not `Default`-prefixed, a public mutable field, a non-final field with no documented reason, an extensible public class with no documented extension point, and a declared design pattern whose structure does not match.
2. Write `policy/api-shape.toml` holding the naming rule, the visibility rule, and the immutability rule, each with its exemptions carrying a reason.
3. Implement the naming rule: no `Impl` suffix anywhere; an interface with exactly one implementation in this repository requires that implementation to be `Default` followed by the interface name; an interface with several requires each to be named distinctly and forbids any of them being `Default`-prefixed, because a default among equals is a decision nobody made.
4. Implement the shape rules: every public type is final or documents its extension points; every field is final unless a reason is recorded; no field is public; visibility is the narrowest that compiles.
5. Write `policy/design-patterns.toml` as a register in which each significant type names the pattern it implements and why that pattern, and implement `DesignPatternRegister` verifying the structural signature of each declared pattern — a declared builder has a build method returning the built type and the built type has no setter; a declared strategy is an interface whose implementations are all registered; a declared value object is final, immutable, and has value equality.

**Tests:**

- An `Impl` suffix is rejected wherever it appears, including on a nested and on a package-private type.
- A single-implementation interface whose implementation is not `Default`-prefixed is rejected; a multi-implementation interface with a `Default`-prefixed member is rejected too, and the two findings are distinct.
- A public field, a non-final field with no reason, and an extensible public type with no documented extension point are three distinct rejections.
- Every declared pattern's structural signature is verified, and a fixture declaring a builder whose built type has a setter is rejected naming both.
- Every significant type has a register entry and every entry names an existing type, in both directions, so the register cannot rot.

- **Done when:** `./mvnw verify -pl development -Dtest=ApiShapePolicyTest` proves no `Impl` suffix anywhere including nested and package-private types, distinct findings for both implementation-naming rules, three distinct shape rejections, structural verification of every declared pattern with a mismatched builder caught, and two-way correspondence between the pattern register and the types it names.
