# Plan 0007 — Platform Operations Surface

> Thirty commands about the state an author retains rather than the content it stores, a boundary that says which of them a deployment actually permits, and the sixty-four-row registry finished.

## Why this plan

Everything before this touched content, where the repository's own access control decides what a caller may do. Nothing here does. A configuration, a bundle, a workflow instance, a Sling job, a user account, a replication queue — none of them is protected by a repository permission, and a command that reaches one is reaching past every guard the previous two plans relied on.

So this plan starts with the boundary rather than with a command, and the boundary has two halves.

**What a caller may do** is decided by a permitted group and nothing else, because there is no repository access control to fall back on. That makes the group's membership the whole of the authorization story for thirty commands, and it makes the redaction rules stricter: a configuration property is where a deployment keeps its credentials, so a listing may say a property exists and may never say what it holds unless the platform's own metatype says the value is not a secret.

**What a deployment permits** is a property of the deployment rather than of the caller, and it is the part most easily got wrong. An Adobe Experience Manager as a Cloud Service environment has immutable configuration: a change made through the running platform is not persisted, is undone by the next deployment, and would leave an operator believing they changed something they did not. Bundle lifecycle is the same. Refusing those on that row — before anything is attempted, with a category that says the deployment does not permit it — is more useful than performing a change that silently evaporates.

That is why `support/deployments.toml` grows a capability list here, and why a platform-control command checks it first.

The rest is the surface: finding, inspecting, and where permitted changing configurations; listing bundles and components and setting a bundle's state; the workflow models, instances, and controls; the Sling job queues, jobs, and cancellation; users, groups, profiles, disablement, and membership; and the replication agents, queues, and their flush and retry. Then the registry is complete at sixty-four rows, the rendered reference is generated from it rather than written beside it, and one proof covers what all thirty have in common.

## In scope

- **0025 — Platform State and Configuration.** The platform-control capability boundary and the redaction rule that governs every command here; finding and inspecting configurations with a two-phase acquisition that separates what a property is from what it holds; updating and deleting one where the deployment permits it; listing bundles and components; and setting a bundle's state against an expected prior state.
- **0026 — Workflow.** Listing models, starting a workflow against a payload the caller can read, finding and inspecting instances, terminating one, and suspending or resuming one — each with the platform's answer reported rather than an outcome inferred from it.
- **0027 — Sling Jobs.** Listing queues, finding jobs, inspecting one, and cancelling one, with no job property value disclosed by any of them, because a job's properties are where another feature put its own arguments.
- **0028 — Authorizables.** Creating a user or a group, updating a profile, disabling and enabling a user, deleting an authorizable, adding and removing group members, and listing them — with no command transporting a credential and membership cycles refused rather than created.
- **0029 — Replication Management.** Listing agents, inspecting one, inspecting a queue, flushing a queue against an expected length, and retrying one entry — with an agent's transport address never disclosed, because it is a URL that frequently carries credentials.
- **0030 — Registry Completion.** The sixty-four-row registry proved complete and in ascending wire order against the client's own published table; the rendered command reference generated from the registry rather than maintained beside it; and one proof over every platform command that a refusal changed nothing and an unknown outcome is reachable and distinct.

## Out of scope

- Content. Plans 0005 and 0006 own it, and the vocabulary they established is used here rather than restated.
- Any change to what a deployment permits. This plan declares the capability rows and refuses against them; enabling a control on a deployment is an operator's decision made outside this repository.
- The author console that displays any of this. Plan 0008 owns it.

## Plan dependencies

Plan 0001 supplies the deployment matrix this plan extends with capabilities. Plan 0005 supplies the framework, and Plan 0006 the mutation vocabulary — the expected-prior-state guard, the explicit removal list, and the unknown outcome — which the platform-control commands reuse rather than redefine.
