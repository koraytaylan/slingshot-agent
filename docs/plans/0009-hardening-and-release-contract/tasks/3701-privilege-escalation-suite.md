---
id: privilege-escalation-suite
title: "Privilege Escalation Suite"
workstream: "0037"
kind: task
depends_on:
  - clock-chaos
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/PrivilegeEscalationScenario.java
  - interop/scenarios/privilege-escalation.toml
  - development/src/main/java/rs/slingshot/agent/development/EscalationSurface.java
  - development/src/test/java/rs/slingshot/agent/development/EscalationSurfaceTest.java
status: done
merged_as: ""
---
# Privilege Escalation Suite

This runs inside somebody else's author, with a service user, in the same process as their content. That combination is the shape of a privilege escalation, and every guard against it was put in earlier. This is the suite that says whether all of them actually hold.

It attacks in every direction the architecture makes possible rather than the ones that seem likely, because the ones that seem likely are the ones that were already thought about.

**Steps:**

1. Enumerate the escalation directions from the architecture rather than from imagination: a caller reaching content through a command that they could not reach directly; a caller reaching the agent's own state tree; a caller reaching the continuation key ring; a handler obtaining a session other than the request's own; a command executing under any identity but the requesting user's; a console data source rendering something its viewer may not see; a command whose declared access class does not match what it does; and a caller reaching a platform control their deployment row does not provide.
2. For each direction, construct the strongest attempt the surface permits — including through every route, every alias, every alternative path spelling, and every console resource — and assert it is refused.
3. Assert positively rather than only negatively: for every read command, a caller who cannot read a tree directly receives exactly the refusal the repository would give, and for every write command, a caller who cannot write receives the same.
4. Implement `EscalationSurface` deriving the attack surface from the built packages and the registry, so a route, alias, console resource, or command added later is attacked without editing this suite.
5. Assert the declared access class matches behaviour for all sixty-four: every `Read` command is proved unable to commit through the caller's resolver and to reach nothing of the caller's beyond it, and every `Write` command is proved to commit only through the caller's session. A read that declares a staging budget is held to the same rule and to one more — every byte it stages is proved to land inside the agent's own tree through the framework's staging area, and it is proved to obtain no session — because a scratch directory is exactly where a read would grow into a write.

**Tests:**

- Every enumerated direction is attacked across the derived surface and refused, with a fixture weakening each guard detected in turn.
- A caller's repository access decides every content command's outcome, proved by comparing against a direct repository attempt as the same caller.
- No caller reaches the agent's state tree or the key ring, through any route, alias, spelling, or console resource.
- No impersonation exists to succeed: the built bundles are asserted to contain no impersonation call at all, so there is no path by which a command runs as anybody but the requesting user.
- Every command is proved to have executed under the request's own session, asserted from the repository's own view of who wrote, across all sixty-four.
- The service user's own identity is proved unable to write content, so widening the permitted groups changes who may call and never what the agent itself may do.
- Every `Read` command is proved unable to commit through the caller's resolver and every `Write` command proved to commit only through the caller's session, across all sixty-four.
- Every row declaring a staging budget is proved to obtain no session, to write only inside the agent's own tree, and to escape neither its root nor its budget through any path shape.
- The surface is derived from the built packages and the registry, proved by adding a fixture route and expecting it to be attacked with no change to the suite.

- **Done when:** `./mvnw verify -pl interop -Dtest=PrivilegeEscalationScenario && ./mvnw verify -pl development -Dtest=EscalationSurfaceTest` proves every enumerated escalation direction refused across a package-and-registry-derived surface with each weakened guard detected, content outcomes equal to a direct repository attempt by the same caller, no reachable state tree or key ring by any path, and access class matching behaviour for all sixty-four commands including every staging-declaring read proved sessionless and confined to the agent's own tree.
