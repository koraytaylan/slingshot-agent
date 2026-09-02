# Plan 0006 — Content Mutation Surface

## One commit, or none

Every mutation in this plan makes exactly one repository commit. Not one per node, not one per property, and never a commit followed by a second commit that fixes it up — because a process that stops between two commits leaves a repository in a state no argument described.

That is affordable because the repository's own session already batches: a handler builds the whole change in its session and commits once. What it costs is discipline, and the framework's suite enforces it by counting commits during every mutation fixture and failing on more than one.

How many commits a command owes is read from its own registry row rather than assumed, because not everything in this plan and the next is a mutation. A row declaring `mutation_outcome_unknown` changes the caller's repository and owes exactly one; a row declaring `admission_outcome_unknown` or `platform_control_outcome_unknown` changes something that is not the caller's repository and owes none. Offering content to replication is not a mutation that happens to commit nothing, and a wrapper that demanded a commit from it would be demanding a write nobody asked for. That same distinction is what the cross-cutting proofs select on, so the suite that proves twenty mutations does not one day find itself demanding a repository commit from a bundle being stopped.

## The three answers a mutation can give

**It happened.** The commit succeeded and the result says what changed — the address, the count, the version. Reporting what changed rather than reporting success is deliberate: a caller comparing the reported address against the requested one catches a whole class of defect that a boolean cannot.

**It did not happen, and nothing changed.** A declared failure category, with a document that carries exactly that category and the arguments that identify what was refused. Every one of these is proved to have left the repository byte-identical.

**Nobody knows.** `mutation_outcome_unknown`. The write left this process and the acknowledgement did not come back, so the commit may have landed. This is the honest answer and it is a category rather than an exception, because a caller who receives it looks rather than assumes — and because the alternative, reporting a failure, is telling somebody something false about their own repository.

The three are mutually exclusive and the framework proves it: no result carries a failure, no failure carries a result, and the unknown outcome carries neither a claim of change nor a claim of no change.

## Guards are arguments

A guard the agent chose is a guard the caller did not.

- Every destructive command takes a **reference policy**: refuse if anything references the target, or remove it anyway. There is no default, because both are right sometimes and this side cannot tell which.
- Every removal takes a **budget**: the maximum number of nodes it may remove. Exceeding it is a refusal that names both numbers, not a partial deletion.
- Every reorder takes an **expected sibling**: the node this one should end up before. A position by index is a race with whoever else is editing the page.
- Every state change takes an **expected prior state** where the platform exposes one, so a change that would apply to a state the caller was not looking at is refused.

None of these is optional in the argument type. An absent guard is a refused submission rather than an inherited default, which is the difference between a caller who chose and a caller who did not know there was a choice.

## Setting, and removing

An update carries two lists: the properties to set with their values, and the properties to remove by name. An absent property is neither — it is left exactly as it was.

That is the only unambiguous arrangement. An update that treated absence as removal would make a caller who sent a partial view destroy the rest; one that treated an empty value as removal would make an intentionally empty string impossible. Two lists cost one extra member and remove the entire class of question.

A property the repository will not let go of — a protected one, an automatically maintained one — is `property_not_removable` rather than a silent no-op, because a caller told the removal succeeded will build on it.

## References, on the way out and on the way in

A move adjusts the references that point at what moved, under a budget, and reports how many it adjusted. Leaving them broken is not an option a caller would knowingly take, and adjusting an unbounded number is how one move rewrites a repository.

`reference_adjustment_budget_exceeded` refuses before the commit rather than after some adjustments, so the answer is never "half your links are updated". That is what one-commit atomicity buys.

A delete under the refusing policy reports which references stopped it, bounded, so the caller can decide rather than guess.

## Assets carry bytes

Creating an asset is the only mutation with a payload, and it is the only one where the request body is the thing rather than a description of it. The payload is bounded by the contract, its declared media type is checked against a closed supported set rather than sniffed, and a payload whose declared and actual size differ is refused before anything is written.

Nothing here generates renditions. The platform's own workflow does that, asynchronously, and claiming it happened because an asset was created would be claiming something this command cannot observe. The result says the asset exists; whether its renditions do is a separate question with its own command.

## Fragments have models

A content fragment's elements are declared by its model, and an element the model does not declare is refused by name rather than written as a loose property — because a fragment carrying properties its model has never heard of is a fragment that reads back differently through every tool.

An experience fragment's variations are the same idea one level up: a variation the template does not permit is refused, and updating a variation that does not exist is distinct from updating a fragment that does not exist.

Both refuse a model or template that cannot be resolved rather than proceeding untyped. An untyped write is the one that looks like it worked.

## Replication is an admission

`replicate_content` offers content to the replication machinery and reports what the platform accepted. It does not report that anything was published, because an author cannot observe a publish instance and a command that claimed to would be claiming something it cannot know.

So the result is an admission: how many candidates were considered, how many were offered, and what the platform said about the offer. `admission_outcome_unknown` exists for the same reason `mutation_outcome_unknown` does, and the queue commands that let somebody find out what happened next are Plan 0007's.

## What the proof at the end covers

One scenario across every mutation in this plan, on the tier that can run them:

- Every declared failure leaves the repository byte-identical, compared before and after.
- Every mutation makes exactly one commit, counted.
- An interruption during any mutation leaves either all of the change or none of it.
- A resend under the same operation identifier changes nothing a second time, which is Plan 0003's guarantee observed from the other end.
- No mutation result or failure discloses a repository path the caller did not supply, a credential, or a property value from outside what was asked for.
