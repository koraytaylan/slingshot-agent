---
id: service-user-and-repository-initialisation
title: "Service User and Repository Initialisation"
workstream: "0004"
kind: task
depends_on:
  - container-package-assembly
gated: false
touches:
  - ui.config/pom.xml
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - ui.config/src/main/content/META-INF/vault/filter.xml
  - policy/repository-access.toml
  - core/src/main/java/rs/slingshot/agent/repository/AgentSession.java
  - core/src/main/java/rs/slingshot/agent/repository/package-info.java
  - core/src/test/java/rs/slingshot/agent/repository/AgentSessionTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/RepositoryAccessTest.java
  - "interop/src/test/resources/fixtures/repository-access/**"
status: done
merged_as: ""
---
# Service User and Repository Initialisation

Two kinds of access, and confusing them is how an agent becomes a way to do things the caller could not do themselves. The agent's own bookkeeping is the agent's, under a service user with a list of permissions somebody can read out loud. Anything the caller asked for runs as the caller, so the repository's own access control is what decides it.

That second sentence is only cheap because of a decision made elsewhere: a command executes inside the request that submitted it, so the caller's session is the request's own and there is nothing to obtain, borrow, or grant. An agent that executed later would have had to impersonate, and impersonation is a standing privilege over other people's identities that somebody would then have to justify. Not needing it is worth more than any amount of care in bounding it.

**Steps:**

1. Author fixtures for the accepted access-control set, for a service user granted write outside its own tree, for a mapping naming a subservice that does not exist, and for code obtaining an administrative session.
2. Write `policy/repository-access.toml` declaring the service user, each subservice name, and the exact permissions each holds on each path, with a reason per grant.
3. Write the repository initialisation script creating the service user and its access-control entries under `/var/slingshot-agent` and nowhere else, and the service user mapping for each subservice, both as configurations in `ui.config`.
4. Implement `AgentSession` as the only place a session is obtained: one method for the agent's own state under its service user, one that adapts the request's own user, and no third. There is deliberately no way to obtain a session for a caller who is not the one making the request — no impersonation, no credential, no stored token — which is what makes the second method's guarantee unconditional rather than bounded.
5. Refuse an administrative or deprecated login anywhere in repository-owned Java, as a source-policy rule rather than as a convention, and refuse impersonation outright — anywhere, by anything, including inside `AgentSession` — because the one design that would have needed it is the one this product does not have.

**Tests:**

- The declared grants are asserted equal to the ones the initialisation script creates, in both directions.
- A grant outside `/var/slingshot-agent`, a grant with no reason, and a mapping naming an unknown subservice are each rejected distinctly.
- `AgentSession` exposes exactly two ways to obtain a session, and a fixture adding a third is rejected.
- The caller's session is proved to carry exactly the requesting user's permissions, compared against a direct login as them.
- No impersonation call exists anywhere in repository-owned Java, asserted over the module and over the built bundles, and a fixture using one is rejected naming it.
- An administrative login anywhere in repository-owned Java is rejected by the source policy, including inside a comment-adjacent fixture that must pass.
- On an installed instance, the service user is asserted to be able to write its own tree and asserted to be refused a write to `/content`, `/apps`, and `/home`, so nothing a caller asks for can be done by the agent's own identity.

- **Done when:** `./mvnw verify -pl core -Dtest=AgentSessionTest && ./mvnw verify -pl interop -Dtest=RepositoryAccessTest` proves two-way correspondence between declared and created grants, three distinct rejections, exactly two session paths whose caller session carries exactly the requesting user's permissions, a source-policy refusal of administrative login and of impersonation anywhere, and an installed service user that can write its own tree and cannot write content, applications, or home.
