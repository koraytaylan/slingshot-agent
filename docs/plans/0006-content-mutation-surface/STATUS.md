# Plan 0006 — Content Mutation Surface — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending.
- **Goal:** twenty commands that change a repository, each one a single commit that either happened or did not, with every guard stated by the caller and every half-way case named rather than flattened.
- **Root cause:** a write that goes wrong changes a repository somebody depends on, and the interesting cases are all the ones where it half-worked — an interrupted commit whose outcome nobody knows, a delete whose references nobody decided about, an update that treated an absent property as a removal, a move that adjusted half the links. Each of those is a place where the comfortable answer is false.
- **Approach:** make every mutation exactly one repository commit, counted by the suite rather than intended, with how many a command owes read from its own registry row so an admission that changes nothing in the repository is not asked for one; give every command three mutually exclusive answers — it happened and here is what changed, it did not and nothing moved, or nobody knows — with the unknown outcome a first-class category rather than an exception, because reporting a failure there tells a caller something false about their own repository; make every guard a required argument with no default, so the reference policy, the removal budget, the expected sibling, and the expected prior state are all choices somebody made; carry setting and removal as two explicit lists so an absent property is neither; adjust references under a budget that refuses before the commit rather than after half of them; check an asset payload's declared media type against a closed set rather than sniffing it and claim nothing about renditions the platform makes later; refuse a fragment element the model does not declare rather than writing a loose property, and refuse an unresolvable model rather than proceeding untyped; report replication as an admission rather than a publication because an author cannot observe a publish instance; and prove them together — byte-identical repositories after every declared failure, the commits each row implies, all-or-nothing under interruption, no second effect on a resend, no disclosure beyond what was asked for, and a registry that partitions into exactly one cross-cutting proof per row so nothing is left unproved because two suites each assumed the other had it.
- **Progress:** 23/23 tasks done; 0 blocked; 0 dropped. The vocabulary twenty mutations share is in
  place before any of them exists. A reference policy with two values and no default, because
  deleting a page other pages link to is sometimes exactly right and sometimes catastrophic and
  nothing here can tell which. A property change as two lists, so a property in neither is left
  exactly as it was — an update that read absence as removal would let a caller who sent a partial
  view destroy the rest of a node, and a name in both lists is refused rather than resolved because
  set-then-remove and remove-then-set disagree. A written value that states whether it holds one
  value or a list rather than having that inferred, since a repository tells those apart even where
  a reader would not. A placement that names the neighbour a component ends up before rather than an
  index, an index being a race with whoever else is editing the page, with the end as a shape of its
  own. Three mutually exclusive answers where the third is that nobody knows — proved over what the
  shapes carry rather than by comparing instances, so the failure it catches is somebody giving the
  unknown answer a claim it must not make. And one-commit atomicity enforced rather than reviewed:
  the caller's session is wrapped, the second commit is refused before it happens and still counted
  so a handler cannot swallow it, and how many commits a command owes is read from its own registry
  row — one for a repository mutation, none for an admission — rather than from its package or its
  name.
- **Integration:** `planned`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `pending`; mode `sequential`; final integration `pending`.
- **Exceptions:** ten recorded.
  - Two of the four guards this plan's design named are not members of any command the client
    publishes, and were not built. There is no deletion budget: the client's four deletes take a
    path and a reference policy, and nothing else. There is no expected prior state: the nearest
    things in its whole surface are an `expected_kind` and an `expected_entry_count` on one command
    each, which belong to the plan after this one. Building either would have been inventing a guard
    the other half never sends — a guard nobody chose, which is the thing this vocabulary exists to
    prevent. The two that are real are built and shared: the reference policy on four commands, and
    the placement the client spells as a component's `placement`. The reference adjustment a move
    performs is likewise not a budget but a flag the caller sets, with the count reported back.
  - Pages are made through the caller's own session rather than through the platform's page manager.
    The manager copies a template's initial content and announces the page to whatever is listening,
    and neither happens here — so a page made by this command carries its type, its template and its
    title, and not whatever the template would have seeded into it. It is written this way because
    the manager cannot be proved either way in this build: there is no `aem-mock` in the offline
    dependency cache, so no unit test can reach it, and the interop tier is a plain Sling runtime,
    so no scenario can either. An untested call into the platform is a claim nobody checks, and this
    plan is about the cases where a write half worked.
  - The client publishes a per-command catalogue — the ways each command may fail and the most a
    result may carry — and nothing on this side had been comparing against it either. Comparing all
    fourteen read rows found one disagreement: `download_content_package` had lost
    `filevault_profile_unsupported` when its profile argument went away with the schema mirror. The
    category is restored so the two halves agree about what a caller may be told, and the row says
    in its own words that this build cannot reach it — it exists for an agent that writes more than
    one package profile. A ninth conformance fact now compares every row's failure set and result
    bound against the client's catalogue.
  - A move that renames the page as well as moving it is refused with nothing changed. The
    platform's resolver moves a node under a new parent and keeps its own name; renaming is a second
    operation this build does not make, and it is checked before anything is staged so that a page
    cannot land one address away from the one the caller asked for while the answer says it went
    where they asked. The client's own schema allows it, so this is a gap rather than a
    disagreement.
  - The plan's reading of the two budgets was corrected against the client's catalogue rather than
    dropped. `deletion_budget_exceeded` and `reference_adjustment_budget_exceeded` are both real
    failure categories the client publishes — they are simply not caller-supplied arguments. The
    contract states the numbers, this side enforces them, and a request past either is refused
    before anything is removed or moved. The earlier exception recorded them as absent; what was
    absent was the argument, not the guard.
  - Ordering is proved on a repository that orders. The resource-resolver mock refuses to order at
    all, and the Sling implementation in this build's offline cache predates the resolver's own
    ordering method — so the four component commands are proved on the mock except for the two that
    need a real order, which are proved on an Oak repository. Reordering needs no page type, which
    is what makes that split possible: it is a question about a node and its siblings.
  - The fragment workstream is six commands and eleven types rather than the eighteen the plan's
    task files name, and the difference is the client's schemas rather than a shortcut. There is no
    element type and no variation-name type: the client carries an element as text or a list of
    text keyed by the element's own name, and what type that element is belongs to the model that
    declares it — a copy of it here would be a second answer to a question the model already
    answers, and the day the two disagree is the day a fragment written through this agent opens
    with a field the editor has never heard of. There is no separate result type per command
    either: four of the six answer one member, `repository_path`, so they share one; the two
    deletes answer the shape every other delete answers and share that; only the experience
    creation has a result of its own, because it alone answers two addresses. And the two deletes
    share one argument reader, because the argument is the same argument — they keep separate rows,
    separate handlers' kinds and separate answers, and share the one place where writing it twice
    would only give two chances to spell it differently.

  - The replication command answers one number rather than the three counts this plan's design
    named, and its two budgets are the contract's rather than the caller's. The client publishes an
    argument of a path and a flag and a result of `accepted_item_count`, and nothing else. So there
    is no candidate limit and no traversal budget in the argument — both are real and both refuse,
    but they are bounds this side enforces rather than numbers a caller sends — and the result says
    how many items were admitted rather than how many were considered, offered and answered
    separately. The flag is still read into a named two-valued type on this side, because
    `recursive` at a call site says nothing and offering one page and offering ten thousand are not
    a true and a false apart.
  - Handing content to the replication service is the one thing in this whole surface that needs an
    Adobe runtime, so it is the first thing to cross the two-bundle line — and crossing it required
    the Sling-only bundle to export a package, which until now exported none. What is exported is
    one package holding one interface: what a platform service does, stated with none of the
    deciding that surrounds it. Every decision — which items are offered, who may read them, how far
    the walk goes, which bounds refuse — stays where a public container tier can prove it, and the
    Adobe bundle's whole footprint is four packages.
  - Making the cross-cutting proof honest turned up a defect in the shared answer mapper: it
    hard-coded the repository mutation's unknown category for every command, so the one command that
    offers rather than writes would have answered `mutation_outcome_unknown` — a category its own
    registry row does not declare, and one the client would refuse. Which unknown a command owes is
    now named at every call site rather than defaulted, because a default is exactly how the wrong
    one gets used again. The three categories themselves moved out of Java and into a committed
    policy, so the plan that adds a fourth kind of change adds two rows and no code.

- **Outcome:** twenty-three tasks complete.

_Last updated: 2026-09-02, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
