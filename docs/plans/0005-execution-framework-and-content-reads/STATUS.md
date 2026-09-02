# Plan 0005 — Execution Framework and Content Reads — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending.
- **Goal:** build the machinery every one of sixty-four commands is made from so that writing the fortieth one differently is not possible, and prove it with the fourteen commands that replace nothing.
- **Root cause:** a large command surface does not fail because one command is written badly. It fails because a later one obtains its own session, spends an unbounded traversal, chooses its own result limit, invents a failure category, or arrives without an interop test — each of which looks reasonable alone. And in Adobe Experience Manager one of those is not a style problem: a query no index covers is answered by walking the repository, which is how a single command takes an author instance down.
- **Approach:** give every command its own registry file rather than a shared list, so sixty pieces of work are not one queue; hand a handler a caller context carrying the requesting user's own session and its budgets and make session acquisition unavailable to it, so running as the caller is structural rather than intended — and declare in each row where the command runs, with every shipped command `Immediate` and executing inside its caller's request, so that session is simply the request's and no grant over anybody's identity is needed; refuse a `Deferred` row until somebody answers whose identity it would run under; wrap a read command's resolver so a commit throws and the access class becomes a guarantee about what the caller owns, and give the one read that needs scratch space a framework-owned staging area inside the agent's own tree — a place to write rather than a way to obtain a session; declare every query and check it against the indexes the deployment already provides, failing the build rather than the author when none covers it, and ship no index definitions into somebody else's repository; bind a continuation token to the canonical digest of every argument that changes the rows or their order, with the client's six continuation categories as the six ways that check fails; take result bounds from the registry row and publish overflow as an artifact rather than truncating; and define existence as six checkable facts — a row, matching schemas, a two-way typed model, a two-way failure set, vectors at and past every bound, and an interop scenario — so a command that is missing any of them does not exist.
- **Progress:** 20/20 tasks done; 0 blocked; 0 dropped. The framework's first three pieces are in
  place before any command exists, which is the order that makes the fortieth command hard to write
  differently. Every command is declared in a file of its own — no shared list, so sixty pieces of
  work are not one queue — stating its bound, its failure categories, whether a caller supplies an
  operation key, how much room it may take inside the agent's own tree, and where it runs; rows come
  back in wire order however the files were found, a row that disagrees with itself is refused, and
  a row that would run later, in a job, is refused naming the identity question nobody has answered.
  A handler is one method that receives what it may use and can obtain nothing, resolved only after
  the five-field identity has been verified, with the categories it can produce and the ones its row
  declares compared in both directions. And what it receives is the caller's own resolver, handed in
  for the length of one call rather than held on a value it could keep, beside three budgets it did
  not choose — each reported as itself so a caller is told which thing ran out — a progress sink
  bounded by the ledger, and, only where its row declared room, a place to write that resolves every
  name inside itself and is given back however the command ended. Session acquisition is refused by
  parsing rather than by review: six forms, each proved on a fixture that reaches for it, and a
  fixture that names all six while explaining why they are refused passes. And the two guarantees
  that are only guarantees if a machine checks them: a read command is given a resolver that
  refuses a commit — directly and three frames down through a helper, with the same refusal —
  refuses a delete, a create, a move, a copy, a revert, a discarding refresh and a clone of itself,
  and yields no session, so "this command replaces nothing the caller owns" is a property of the
  machinery; and every query is declared as data, compared at build time against the indexes each
  deployment row already provides and at run time against the plan the instance really returns,
  refused before a node is examined where that plan would walk. This product ships no index
  definition, asserted over the archives a customer installs. Paging now ends definitely: a token
  is issued where rows remain and not otherwise, so an absent one is the end rather than an unknown.
  The token is bound to the
  canonical digest of every argument but the window — the window being how a caller asked for this
  page rather than part of what they asked about, and the one argument whose inclusion would make
  every token wrong for its own successor — so a token carried to another query is refused as
  another query's rather than as damaged. The client's six continuation failures each reach their
  own category through a switch with no default branch. And nothing is ever truncated: a result is
  counted and digested as it is written, so an answer too large to carry is known to be so before it
  has been held — the write that crosses the bound hands what was gathered to the overflow and
  replaces the buffer rather than emptying it, and the assembly reports the most it ever held so
  that "it never builds the whole answer" is checked rather than claimed. What overflows becomes an
  artifact carrying the wire's own count and digest, which a caller fetches and verifies for itself;
  room for it is taken before the command runs, since reserving afterwards spends the whole cost of
  a read to reach a failure that was knowable before it started; and a publication that fails
  answers no result at all, because a reference to an artifact nobody wrote turns one failure into
  two. The fourteen reads are all in, ending with the two that describe the
  deployment rather than its content: both directions of resource translation, sharing one reading
  and not one document because resolving depends on the request it happens under and mapping does
  not; and the mapping table itself, read from where the platform keeps it and answered with the
  credentials stripped out of every address it holds, because a mapping rule is a place an operator
  writes a password and a command that reports storage is not a way to collect them.
- **Integration:** `planned`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `pending`; mode `sequential`; final integration `pending`.
- **Exceptions:** thirteen recorded.
  - Task 1704's run-time half is proved on constructed plans rather than on an instance whose
    index was removed, and its traversal statistics are not read, because this build registers no
    command that issues a query. What the scenario proves on a real build is the half that does not
    need one: that no package a customer installs carries an index definition. The other half
    arrives with the first read command, which is workstream 0018's.
  - Task 1705 needed the client's command contract, which no plan task had mirrored: its hundred
    and forty-four bounds — the result limit and offset among them — lived only in the sibling's
    `schemas/command-contract-limits-1.json`, while this side mirrored the transport contract alone.
    They are now a third digest-authenticated `[command]` table beside `[transport]` and `[agent]`,
    compared name by name against the client's own document in both directions. Two bounds that had
    been carried as this side's own — the command wire name and the semantic contract version — were
    the client's all along and moved; one key, `maximum_sling_job_identifier_bytes`, is declared by
    both client contracts with two values, because the transport bounds it in an envelope and the
    command bounds it as an argument, so it is carried twice under section-qualified paths.
  - Task 1705 refined the source policy's second-declaration rule rather than working around it.
    With a hundred and forty-four more bounds in the contract, every ordinary power of two from four
    thousand to a hundred and thirty thousand became a contract value, and so did a thousand — so
    the rule began reporting a milliseconds-in-a-second conversion and a read buffer as restatements
    of bounds they merely equal. It now examines a number where it could be stating a bound at all:
    bare in an expression, or held in a constant whose own name says it is one. What that gives up
    is a bound behind a deliberately misleading name; what it keeps is every finding worth having.
  - The client's own command schemas had never been mirrored, and all twenty-eight of them
    disagreed. The sibling publishes both role schemas for every one of its sixty-four commands,
    generated from its own types, beside a manifest of their digests — and its schema module states
    the rule this side had not been holding to: "compatibility is a comparison of digests". Nothing
    compared against them, so the fourteen read commands were written against member names the
    other half does not send: `path` against `repository_path`, `root_path` against `root`,
    `page_path` against `page`, and every paged result's `matches` and `next_continuation_token`
    against a `count` and a `continuation_token` this side invented. Not one of the fourteen could
    have answered a real request. Every schema is now the client's own bytes, each row carries the
    client's own digest, and an eighth conformance fact compares the two — the gap being the same
    species as the command contract's, and settled the same way.
  - The check that should have caught it was skipping in silence. The correspondence check compares
    a committed schema with the client's copy where one is carried, and simply passed over any
    schema where none was — which was every command schema. Skipping is now a finding of its own: a
    document nothing on the other side is compared with is a document this side is free to invent,
    which is the one thing a protocol document must not be.
  - Six design decisions this plan recorded were overturned by the client's own schemas, and the
    client won each. The kinds of asset reference a caller may ask about, the last-modified instant
    beside each template match, the profile and per-root dispositions a package was described by,
    and the requirement that a fragment name its variation and a load name its depth were all this
    side's inventions or this side's judgement; the client declares no reference kinds, a match of
    an address and a title, a package of roots with pattern filters and a name, and an optional
    variation and depth. The one that changed a stated safety claim is the rendition listing, which
    this plan had argued should carry no repository path because a path is a way to fetch: the
    client's schema requires one, and it discloses nothing, because a rendition lives underneath
    the asset the caller already named.
  - A resumed page is served at the size the resuming request resolves to rather than at the size
    its own first page used, so a caller who began with pages of twenty-five and then resumed is
    served the default. The fix belongs in the shared document and not here: the continuation state
    is declared by a schema both halves hold, closed at five members with `additionalProperties`
    false, and this side does not get to add a sixth. The client's own result-window module already
    names an `initial_result_limit` among the payload members it specifies, and its schema does not
    carry one — so the two client documents disagree with each other, and until that is settled in
    the sibling repository this side cannot carry the size. Adding it here was tried and reverted:
    the two-way schema check caught it, which is the check working.
  - The gate's interop stage is not reliably green on a loaded machine, and the cause is now named
    rather than called a flake. The pinned public image ships ASM 9.2, which refuses a Java 21 class
    file — `Unsupported class file major version 65` — so Sling Models' weaving hook throws on every
    class it is asked to weave and the platform logs the whole stack, seven hundred and seventy-nine
    times in one container's first two minutes. Requests still complete, so a tier that came up
    answers correctly; what it costs is start-up, and when several scenarios start containers at
    once one occasionally spends longer than the readiness deadline writing stack traces than
    starting. `HighWaterScenario` failed that way in this plan's gate run and passed alone in 8.7
    seconds. The fix is an image whose ASM can read Java 21, which belongs to whoever owns the
    pinned images rather than to this plan; it is written down in `docs/INTEROP.md`.
  - Task 1706's end-to-end half is not proved, because producing an overflowing result needs a
    registered read command and this build registers none. `ResultOverflowScenario` proves what a
    running instance can answer today — that a fetch which finds nothing carries no body a reader
    could mistake for a shortened result, and that the route never states a length it does not
    serve — and the fetch of an answer that actually overflowed, followed from the reference in the
    answer that carried it, arrives with the first read command, which is workstream 0018's.
  - Task 1801 put its handler in the Sling-only bundle rather than the Adobe one its task text
    names. Loading a repository subtree needs the repository and nothing Adobe adds to it, and a
    handler in the Adobe bundle could not be proved by the public tier — the tier that runs on any
    machine with nothing licensed — which is the whole reason the two-bundle split exists. Its
    registry row and schemas likewise follow the paths task 1701 actually established rather than
    the ones 1801's own text guessed at before 1701 was written.
  - Task 1801 found a rule task 1701 had invented and removed it. The registry refused a read that
    required an operation key, on the reasoning that a read is intrinsically idempotent. That is not
    true and is not what the client says: it publishes twenty-six reads that refuse a key beside two
    that require one, because reading a repository twice is not one operation when the repository
    can change in between. A row carries its key requirement and not its idempotency, so there was
    no second fact in the row to check the first against — the check was inventing an answer to a
    question the client had already answered. The comparison now happens in the conformance gate,
    against all sixty-four of the client's own classification rows, which is the one authority for
    it.
  - Task 1802's handler holds the authenticated contract, which is one immutable value and no
    per-run state, so it is registered as an accessor rather than a stateless policy. Its
    continuation categories are the client's five rather than this side's six: a stale generation is
    told apart internally and is not published as a category of its own, because what a caller acts
    on is that the enumeration is gone rather than which of this agent's reasons produced that.
  - Task 1804's search is narrower than a full-text search sounds. It matches a page's title and
    description and nothing else, because those are the properties Adobe's page index covers on both
    supported deployments. A wider search would be a repository walk wearing the name of an indexed
    query, and the index-coverage check refused the wider one when it was first declared. The
    handler holds the searched properties as a list the coverage policy is held against, and the
    suite proves from both sides that a phrase in an uncovered property matches nothing.
  - The index-coverage check refused task 1806's first declaration too, and the correction is the
    same shape: a query is answered by one index rather than by the union of several, so naming
    `jcr:primaryType` beside `sling:resourceType` described a query no index could answer. The
    handler filters on the resource type alone and folds each match up to its containing page
    afterwards, which is what it always did — the declaration had described a query the code does
    not issue. Adobe's own `slingResourceType` index was also unrecorded until then, which had made
    a real platform capability invisible to the policy.
- **Outcome:** pending.

_Last updated: 2026-09-02, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
