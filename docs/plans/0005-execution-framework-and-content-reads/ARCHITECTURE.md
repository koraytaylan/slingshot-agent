# Plan 0005 — Execution Framework and Content Reads

## Where a command lives

A command is two things in two modules, and the split is the same one the whole repository is built on.

Its **contract** — the argument type, the result type, the declared failure categories, the registry row, and the committed schemas — is in `slingshot-agent-core`, and needs no Adobe package at all. So the public interop tier proves every command's argument validation, result shape, boundary behaviour, and failure documents against a running instance, on an image anybody can pull.

Its **handler** — the code that actually reaches a page, an asset, a bundle, or a workflow — is in `slingshot-agent-aem`. Only that half needs the licensed tier.

This is why fourteen commands can be fully specified and half-proved before an Adobe quickstart is involved, and it is why a contract defect and a handler defect fail in different tiers.

## One file per command

The registry is a directory: `core/src/main/resources/registry/<wire_name>.toml`, one file per command, holding the wire name, the semantic contract version, the access class, whether an operation key is required, the result bound, the declared failure categories, and the schema digests.

Not one shared list. A shared list is a file every command task has to edit, which turns a footprint rule into a queue and makes sixty independent pieces of work into one sequence. The same reasoning gave the interop scenarios their own directory in Plan 0001.

What a shared list would have given — an ordering, a completeness check, a rendered reference — is a check over the directory instead, and a check is better than a file anyway because it can also say what is missing.

## A handler receives everything and obtains nothing

```
Result handle(Arguments arguments, CallerContext context)
```

`CallerContext` carries the caller's own resource resolver, the budgets, the operation identity, and a progress sink. It carries no factory, no service reference, and no way to reach the repository except through the resolver it holds. A handler that wanted a second session would have to obtain one, and obtaining one is refused by the source policy — the same rule Plan 0001 used to refuse administrative login, extended to refuse any session acquisition outside `AgentSession`.

Where that resolver comes from is the request, and that is a property of when a command runs rather than of how carefully it is passed around. A command executes inside the request that submitted it, so the caller's session is simply there — nothing obtains it, nothing borrows it, and no grant makes it possible. An agent that executed afterwards would have had to impersonate, which is a standing privilege over other people's identities; the registry's execution class exists so that the day somebody wants a command that cannot finish inside a request, they are stopped and made to answer that question rather than inheriting an answer.

That is what makes "the command runs as the caller" true rather than intended. The agent's own bookkeeping is on the service user, in code the handler cannot reach; everything the handler touches is decided by the caller's own repository access.

Progress is a sink rather than a return value, because a command that runs for a minute and reports nothing is a command whose event stream has nothing to carry.

## Budgets, and why a query may not traverse

Adobe Experience Manager answers a query either from an index or by walking the repository. Walking it is how one command takes an author instance down, and the walk is not usually the author's fault — it is a query somebody wrote that no index covers.

So no command in this build traverses. Every query a handler issues is declared, checked at build time against the index definitions the deployment rows provide, and a query with no covering index is a build failure rather than a slow afternoon in production. Where a question genuinely cannot be answered from an existing index, the command says so through `discovery_budget_exceeded` rather than answering it slowly.

This repository ships no index definitions. Custom indexes live outside `/apps`, they change the shape of somebody else's repository, and installing one is a decision an operator makes rather than a side effect of installing an agent. The checked set is the indexes Adobe Experience Manager already provides, and a command that would need another is a command that does not ship.

Three budgets ride in the caller context and all three come from the registry row or the contract: how many nodes a discovery may examine, how long a command may run before its lease is not renewed, and how many bytes its result may occupy. Each has exactly one failure category, so exceeding one is reported as itself rather than as a generic failure.

## A read replaces nothing, structurally

A command whose access class is `Read` receives a resolver that refuses to commit. Not a convention, not a review item: the wrapper throws, and the framework's own suite proves that every read command's handler fails if it attempts a change.

That turns the access class in the registry row from a description into a guarantee, and it is worth having because the interesting failure is not a read command that obviously writes. It is one that calls a helper that adapts, touches, and commits three frames down.

What `Read` claims is precise: the command replaces nothing the caller owns. One read needs scratch space while it works — a content package has to be built somewhere before it can be published as an artifact — and that pressure is exactly what would otherwise reopen session acquisition. So the framework hands it a place rather than the means to find one: a staging area declared by a byte budget in the registry row, opened and released by the framework, writing under the service user inside the agent's own tree, resolving every path inside its own root and exposing no resolver, no session, and no parent. The handler still reaches the caller's repository through the read-only resolver and nothing else, and the escalation suite holds every staging-declaring row to both halves of that.

## Paging

A paged command takes a result window and an optional continuation token, and returns rows plus a token when there are more.

The token is bound to its query. The query digest covers the canonical bytes of every argument that changes which rows are returned or their order, so a token cannot be carried from one query to another — and the six continuation failure categories the client already declares are the six ways that check can fail, reported as themselves.

A window is bounded by the registry row rather than by the caller. A caller asking for more than the row allows is answered with the row's maximum rather than refused, because the row is the contract and the request is a preference; a caller asking for zero is refused, because a page of nothing is a question nobody meant to ask.

## Results and overflow

Every command declares a result bound in its registry row — sixteen kilobytes for a mutation that reports what it changed, a quarter of a megabyte for an inspection, a megabyte for a listing. A result within the bound travels inline. A result past it is published as an artifact and the answer says where to fetch it, using Plan 0003's store and Plan 0004's route.

Nothing is truncated. A truncated answer is not a smaller answer but an unparseable one, and the client is built to fetch rather than to cope.

## What makes a command exist

A command exists when all six of these are true, and the gate checks all six:

1. A registry file declaring it.
2. Committed argument and result schemas whose digests match the registry row.
3. A typed argument and result whose members agree with those schemas in both directions.
4. A declared failure set equal to the categories its handler can produce, in both directions.
5. Conformance vectors including one at and one past every bound it declares.
6. An interop scenario naming it, on a tier that can run it.

Five of those are checkable in `core` alone. The sixth is why Plan 0001 installed the coverage gate while the comparison was still vacuous.
