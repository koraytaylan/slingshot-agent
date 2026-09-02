# Plan 0007 — Platform Operations Surface

## Two boundaries, not one

Content commands had a safety net: the caller's own session, and a repository that refuses what the caller may not do. Nothing in this plan has that. A configuration, a bundle, a workflow instance, a job, an account, a queue — none of them is guarded by a repository permission, and reaching one reaches past every guard the previous two plans depended on.

So there are two separate questions here and they are answered in different places.

### May this caller?

Membership of one of the permitted groups, and nothing else. There is no second mechanism and no per-command exception, because a per-command exception is a place where somebody would eventually put a weaker rule. That makes the configured membership the entire authorization story for thirty commands, which is worth stating plainly in the documentation an operator reads before they widen it — and it is why the shipped configuration names `administrators` alone. These thirty reach past every repository guard the content plans relied on, so the group that may run them is the group that already has the standing to do the same things by hand.

### Does this deployment permit it?

A property of the deployment, and the part most easily got wrong.

An Adobe Experience Manager as a Cloud Service environment has immutable configuration. A change written through the running platform is not persisted, is undone by the next deployment, and leaves an operator believing they changed something they did not. Bundle lifecycle is the same: a bundle stopped through a console comes back on the next deployment, and the interval in between is an environment nobody can reason about.

Performing those and reporting success would be worse than refusing them, because the operator's next action depends on believing the answer. So `support/deployments.toml` grows a capability list — which platform controls a row actually provides — and every platform-control command checks it before it does anything at all. The refusal is `platform_control_rejected` with the deployment row named, which tells an operator that the command is fine and their environment does not do that.

The reads are unaffected. Finding a configuration, inspecting one, listing bundles, listing components: those are true on every row, and they are most of what an operator actually wants.

## Values, and the fact that a value exists

A configuration property is where a deployment keeps its credentials. That single sentence decides the shape of every configuration command here.

Inspection is two phases and they are kept apart deliberately. The first acquires the configuration's properties and reports which properties exist, their names, and their declared types from the platform's own metatype description. The second converts and reports values, and it reports a value only where the metatype says the property is not a secret — everything else is reported as present and withheld, never as absent and never as a masked string that somebody will treat as a value.

Withheld rather than masked matters. A masked value is a value: it has a length, it appears in a listing, and it invites a caller to compare two of them. Withheld is a property with no value member at all.

A configuration with no metatype description cannot be classified, and an unclassifiable property is withheld. That is the conservative direction and it is the only safe one: the alternative is publishing an unknown property on the guess that it is harmless.

`configuration_value_unsupported` and `configuration_value_malformed` exist because a property whose type this build cannot represent and one whose stored value does not match its declared type are different problems, and neither may be reported as the other or flattened into a string.

## What a listing never says

Three rules, each learned from a specific place a secret leaves:

- **A configuration listing never carries a value.** Finding configurations reports identifiers, factory identifiers, and the bundle that declared them. Values come only from inspection, under the two-phase rule.
- **A job listing never carries a job property value.** A Sling job's properties are where whatever feature created it put its own arguments, and those arguments belong to that feature rather than to this agent. A job listing says a property exists; inspecting a job says what its declared type is; neither says what it holds.
- **A replication agent's transport address is never disclosed.** It is a URL, and a replication transport URL very frequently carries credentials in its own userinfo. Agents are reported by name, kind, and enablement, and the address is not a field.

The redaction suite Plan 0004 built drives every one of these routes, so the rules are checked rather than remembered.

## The platform answers, and the unknown

Every control command in this plan reports what the platform said rather than an outcome inferred from it. Starting a workflow reports the instance the platform created; terminating one reports what the platform did; setting a bundle state reports the state the platform reports afterwards.

That is the difference between `platform_control_rejected` — the platform said no — and `platform_control_outcome_unknown` — the call left this process and no answer came back. The second is reachable, it is distinct, and it is not flattened into the first, for the same reason `mutation_outcome_unknown` is not flattened into a failure: a caller who receives it looks rather than assumes.

Every control command also takes an expected prior state where the platform exposes one, using Plan 0006's guard vocabulary rather than a second version of it. A bundle transition applied to a state the caller was not looking at is refused; a queue flushed with a different length from the one the caller saw is refused. Both are the same idea as the reorder command's expected sibling, and sharing the vocabulary is what stops the three drifting apart.

## Accounts

Nothing here transports a credential. Creating a user creates an account without a password; setting one is a separate concern this agent does not have and deliberately will not, because a command that carried a password would put one in a submission body, a durable operation record, and an event ledger.

Membership refuses a cycle rather than creating one, and refuses it before the commit, because a group that contains itself transitively is a repository that some tools will walk forever.

Deleting a group that still has members is refused rather than cascaded, because cascading a membership deletion is a change whose blast radius the caller cannot see from the request they wrote.

## Finishing the registry

At the end of this plan the registry has sixty-four rows. Three things then become checkable that were not before:

- The row set is asserted equal to the client's own published command table, in both directions, from the client's committed bytes carried in as a fixture. A command one side holds and the other does not is a refused submission in production, found here instead.
- The rows are in ascending wire-name order, and the ordering is a check over the directory rather than a property of any file.
- The rendered command reference is generated from the registry, so a command that exists appears in it or the build does not pass — which is the same rule the client applies to its own reference, and the reason both documents can be trusted.
