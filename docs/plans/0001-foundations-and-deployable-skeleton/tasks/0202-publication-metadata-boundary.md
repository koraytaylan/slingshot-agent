---
id: publication-metadata-boundary
title: "Publication Metadata Boundary"
workstream: "0002"
kind: task
depends_on:
  - dual-licence-and-source-headers
gated: false
touches:
  - support/publication-metadata.toml
  - pom.xml
  - development/src/main/java/rs/slingshot/agent/development/PublicationBoundary.java
  - development/src/test/java/rs/slingshot/agent/development/PublicationBoundaryTest.java
  - "development/src/test/resources/fixtures/publication-boundary/**"
status: done
merged_as: ""
---
# Publication Metadata Boundary

A publishable group identifier is a claim to a namespace somebody has to have verified, and a repository field is a claim about where the source is. Neither is inferred from a directory name, and until an owner supplies them a publish is refused rather than guessed at.

The identifier is `rs.slingshot`, reversing a domain the project holds, so the namespace is verifiable. Verifiable is not verified: holding a domain is a fact about the world, and a completed namespace verification is a fact about a registry that only the owner who did it can report. The boundary gates on the second, not the first.

**Steps:**

1. Author fixtures for absent metadata, partially supplied metadata, a complete set with no verification record, and a complete verified set.
2. Write `support/publication-metadata.toml` with every field a publish needs — the repository, the developer, the two distribution targets, and a namespace-verified record only an owner sets after actually completing the registry's verification.
3. Name both targets rather than one, because the artifact has two audiences: the central Maven repository, for a project that embeds this package in its own container, and a repository release asset, for an operator who installs the package by hand. Each target declares its own preconditions, and the Maven one is the only target the namespace record gates.
4. Set every module to be unpublished while the record is absent, in the way this build system expresses it, and refuse a publish under an unverified namespace even though the identifier is well formed — because a well-formed identifier is a claim and the record is the evidence.
5. Implement the boundary check so that packaging an installable container is always allowed and publishing is refused with a message naming each absent field and the target it belongs to.
6. Make partial supply a refusal rather than a partial publish: a target is only publishable when every field that target requires is present, and a target with satisfied preconditions is never blocked by another target's absent ones.

**Tests:**

- With the flag unset, every module is asserted unpublished and a publish attempt is refused naming every absent field.
- With the flag set and one field absent, the publish is refused naming that field, and the flag alone does not authorise it.
- With the record present and every field supplied, the model is asserted to carry exactly those values and no inferred one.
- A publish under a well-formed but unverified namespace is refused, proving the boundary gates on the recorded verification rather than on the identifier's shape.
- An identifier that does not reverse the declared domain is refused, so the coordinate and the namespace it claims cannot disagree.
- Each target's preconditions are checked independently: a release asset stays publishable while the Maven namespace record is absent, and the Maven target does not.
- Building the container package succeeds in all three states, proving the boundary blocks publication and not installation.

- **Done when:** `./mvnw verify -pl development -Dtest=PublicationBoundaryTest` proves publication is refused while metadata is absent or partial, that a set flag alone does not authorise it, that a complete set produces exactly the owner's values, and that the installable container builds in every state.
