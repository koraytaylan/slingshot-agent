# Plan 0006 — Content Mutation Surface

> Twenty commands that change a repository, each one a single commit that either happened or did not, with every guard an argument rather than a convention.

## Why this plan

Reading is forgiving. A read that gets something wrong tells somebody the wrong thing, and they usually notice. A write that gets something wrong changes a repository somebody else depends on, and the interesting cases are all the ones where it half-worked.

Three of those cases decide the shape of everything here.

**A commit either happened or it did not, and sometimes nobody knows which.** A repository commit that was interrupted after the write and before the acknowledgement leaves a state this side cannot determine. Every mutation therefore declares `mutation_outcome_unknown`, and it is not a decorative category: it is the honest answer, it is distinct from every failure that definitely did nothing, and a caller that receives it looks rather than assumes. Flattening it into a failure would be telling a caller something false about their own repository.

**A guard nobody stated is a guard nobody chose.** Deleting a page that other pages reference is sometimes exactly right and sometimes catastrophic, and the difference is not something this agent can work out. So the reference policy is a required argument on every destructive command — neither refusing nor ignoring an incoming reference is a default somebody inherits. The same rule gives every ordering command its expected sibling, every removal its budget, and every state change its expected prior state.

**Removing a property is not the same as not setting one.** An update that treats an absent member as "leave it alone" and an update that treats it as "remove it" are both defensible, and a caller who guessed wrong finds out later. So removal is its own explicit list, and a property the repository will not let go of is its own category.

The twenty commands then divide by what they touch — pages, the components inside them, assets, content and experience fragments, and the replication that publishes any of it — and each one is a single independently testable target with its own canonical fixtures, its own exact failure set, both sides of every bound, and its own interop scenario on a tier that can actually run it.

## In scope

- **0020 — Page Lifecycle.** The mutation vocabulary every command in this plan shares — reference policy, explicit removal, deletion budgets, the unknown outcome, and single-commit atomicity — then creating, updating, deleting, and moving a page, with a move that adjusts references under a budget rather than silently leaving them broken.
- **0021 — Component Lifecycle.** Adding a component to a page at a stated position, updating and removing one, and reordering against an expected sibling, with an unorderable parent reported as itself rather than as a generic refusal.
- **0022 — Asset Lifecycle.** Creating a folder, creating an asset from a bounded payload with a declared media type, updating metadata with explicit removals, deleting under a reference policy, and moving with the same reference adjustment pages get.
- **0023 — Content and Experience Fragments.** The element vocabulary both fragment kinds share, then creating, updating, and deleting a content fragment against its model, and the same three for an experience fragment against its template and variations.
- **0024 — Replication and the Mutation Proof.** Offering content to replication as an admission rather than a publication, and one proof across every mutation in this plan that a failure left no effect, an interruption left either all of it or none, and a resend under the same identifier changed nothing twice.

## Out of scope

- Everything that inspects or controls the platform rather than the content: configurations, bundles, workflows, Sling jobs, authorizables, and the replication queues themselves. Plan 0007 owns those.
- The registry's completeness and the rendered command reference, which Plan 0007 finishes once all sixty-four rows exist.
- Publishing. This agent offers content to replication and reports what the platform accepted; what a publish instance then does is not something an author can honestly claim.

## Plan dependencies

Plan 0005 supplies the whole framework: the registry, the handler contract, the caller's session, the budgets, the result bounds, and the conformance gate that refuses a command with no scenario. Plan 0003 supplies the one-effect guarantee these commands rely on when a submission is resent. Plan 0004 supplies the route and the status mapping every refusal crosses.
