---
id: owner-supplied-release-metadata
title: "Owner-Supplied Release Metadata"
workstream: "0039"
kind: task
depends_on:
  - release-workflow
gated: false
touches:
  - support/publication-metadata.toml
  - support/github-automation-authority.toml
  - development/src/main/java/rs/slingshot/agent/development/PublicationAuthority.java
  - development/src/test/java/rs/slingshot/agent/development/PublicationAuthorityTest.java
  - docs/RELEASING.md
status: done
merged_as: ""
---
# Owner-Supplied Release Metadata

A namespace is a claim to something somebody has to have verified, and no build should make it on its own. Plan 0001 set the identifier to `rs.slingshot`, reversing a domain the project holds, and then refused to publish under it anyway until an owner records that the registry's verification actually happened. What changes here is that the refusal becomes one an owner can lift deliberately, with every field named.

**Steps:**

1. Author fixtures for absent metadata, partial metadata, an acknowledgement with no metadata, and a complete set with an acknowledgement.
2. Extend `support/publication-metadata.toml` with everything a release needs beyond what Plan 0001 named: each target's own preconditions, the signing identity the Maven repository will check every artifact against, and the acknowledgement that an owner has completed the namespace verification.
3. Write `support/github-automation-authority.toml` naming the repository the automation may act on and the identities it may act as, and refuse a workflow acting outside it.
4. Implement `PublicationAuthority` refusing publication while any field is absent, naming every absent field at once rather than the first.
5. Write `docs/RELEASING.md` describing exactly what an owner supplies, what each field asserts, and what becomes possible once they do — with the fields named from the file rather than listed by hand.

**Tests:**

- Absent, partial, and acknowledgement-only metadata each refuse publication, naming every absent field at once.
- A complete set with an acknowledgement permits publication, and the model is asserted to carry exactly the owner's values with none inferred.
- A workflow acting on a repository or as an identity outside the automation authority is refused naming both.
- Building the container package succeeds in every metadata state, proving the boundary blocks publication and not installation.
- Every field named in `docs/RELEASING.md` exists in the file and every field is documented, in both directions.

- **Done when:** `./mvnw verify -pl development -Dtest=PublicationAuthorityTest` proves publication refused in three incomplete states with every absent field named at once, permitted only under a complete acknowledged set carrying exactly the owner's values, a workflow refused outside the automation authority, installation unaffected in every state, and two-way correspondence between the documented and declared fields.
