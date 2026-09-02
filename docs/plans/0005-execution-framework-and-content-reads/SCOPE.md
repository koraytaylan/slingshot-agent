# Plan 0005 — Execution Framework and Content Reads

> The machinery every command is built from, and the fourteen read commands that exercise all of it without changing anything.

## Why this plan

Sixty-four commands is a lot of surface, and the way it goes wrong is not that one of them is written badly. It is that the fortieth one is written slightly differently from the first — obtains its own session, spends an unbounded traversal, decides its own result limit, invents a failure category, or quietly does not have an interop test — and nobody notices because each of those looks reasonable on its own.

So the framework comes first, and it is built so those things are not available to do. A handler is given a caller context and cannot obtain a session; a query runs under a budget it did not choose; a result bound comes from the registry row rather than from the handler; a failure category that is not in the command's declared set cannot be constructed; and a command with no interop scenario fails the gate Plan 0001 already installed.

Then the reads. Fourteen commands that replace nothing, chosen to go first because every one of the framework's hard parts — paging, continuation tokens, budgets, result overflow into an artifact, redaction — is exercised by a read, and because a read that gets it wrong tells somebody the wrong thing rather than doing the wrong thing.

Two of them deserve saying out loud. Adobe Experience Manager answers a query either from an index or by walking the repository, and walking it is how a single command takes an author instance down. No command in this build may traverse: every query is checked for index coverage at build time, and one that would fall back to a walk is a build failure rather than a slow afternoon. And a read command runs on a session that refuses to commit, so "this command replaces nothing" is a property of the machinery rather than a claim in a table.

## In scope

- **0017 — Execution Framework.** One registry file per command rather than one shared list, each declaring where its command runs — `Immediate` for all sixty-four, meaning inside the request that submitted it, which is what makes the caller's session simply the request's own and needs no grant over anybody's identity; a handler contract that receives everything it may use and can obtain nothing itself; a caller context carrying the requesting user's own session and the traversal, time, and result budgets, the time budget bounded below the smallest request window any supported deployment declares; read-only enforcement and build-time index-coverage checking, so no command can write when it said it would not and none can traverse at all; the paged-query machinery that issues and validates continuation tokens against the query they belong to; result bounds taken from the registry row with overflow published as an artifact rather than truncated; and a conformance gate under which a command with no schema, no vectors, or no interop scenario does not exist.
- **0018 — Content Reads.** Loading one subtree as a document, querying paths, listing child pages, and finding pages by phrase, by template, and by the components they use — six commands that between them cover every paging and budget path the framework has.
- **0019 — Asset, Fragment, Package, and Resolution Reads.** Finding assets by metadata and by the page that references them, listing renditions, reading a content fragment, producing a content package as an artifact, resolving an address in both directions, and listing the resource mappings that decide it.

## Out of scope

- Every command that changes something. Plans 0006 and 0007 own the mutations and the platform surface, and both are built on this framework rather than beside it.
- The registry's completeness. This plan adds fourteen rows and the machinery that would accept sixty-four; the sixty-four-row registry and its rendered reference are Plan 0007's.
- Everything the transport, the store, and the protocol already own, which is called here and never restated.

## Plan dependencies

Plan 0001 supplies the two-bundle split that lets a command contract be proved on the public tier while its handler needs Adobe's, the source policy, and the interop coverage gate. Plan 0002 supplies the argument, result, and failure documents and the continuation token. Plan 0003 supplies execution, the artifact store, and the budgets' durable side. Plan 0004 supplies the route that admits a submission and the mapping every refusal crosses the wire under.
