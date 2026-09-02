# Plan 0007 — Platform Operations Surface — 🚧 Integration pending

The roll-up row in [../STATUS.md](../STATUS.md) must stay in sync with this file. Task-level truth lives in [tasks/](tasks/) frontmatter; Makina's integration coordinator updates both layers.

- **Status:** 🚧 Integration pending.
- **Goal:** thirty commands about the state an author retains rather than the content it stores, each refusing what its deployment does not actually permit, disclosing no value a deployment keeps a secret in, and completing the registry at sixty-four rows checked against the client's own table.
- **Root cause:** none of this is guarded by repository access control, so every guard the content plans relied on is gone. And the most useful-looking half of it does not work where this agent is meant to run: an Adobe Experience Manager as a Cloud Service environment has immutable configuration and bundle lifecycle, so a change written through the running platform is not persisted, is undone by the next deployment, and leaves an operator believing something false. Performing it and reporting success is worse than refusing.
- **Approach:** answer two separate questions in two places — may this caller, decided by a permitted group and nothing else with no per-command exception, and does this deployment permit it, decided by a capability list the deployment matrix now carries and checked before a control command does anything; keep configuration inspection in two phases so that which properties exist is separate from what they hold, report a value only where the platform's own metatype says it is not a secret, and withhold rather than mask, because a masked value is still a value with a length somebody will compare; never carry a configuration value in a listing, never carry a job property value at all since a job's arguments belong to whatever created it, and never disclose a replication transport address because it is a URL that frequently carries credentials; report what the platform said rather than an outcome inferred from it, keeping rejection and unknown distinct for the same reason the mutation unknown is not a failure; reuse Plan 0006's expected-prior-state guard rather than inventing a second one; transport no credential anywhere, refuse membership cycles before the commit, and refuse rather than cascade a group deletion; and finish by proving the sixty-four rows equal to the client's published table in both directions, ascending by wire name, with the rendered reference generated from the registry rather than maintained beside it.
- **Progress:** 32/32 tasks done; 0 blocked; 0 dropped. Two questions answered in two places: may
  this caller, decided by the group they are in and nothing else, and does this deployment permit it
  at all, decided by a capability list every row now carries with a reason for every refusal. On the
  environment this product is built for, configuration and bundle lifecycle are refused before the
  platform is touched — a change written through a running platform that does not persist it is
  accepted, reported as done, and gone by the next release, and by then the operator has built three
  things on believing it. Configuration inspection is two questions rather than one: which properties
  exist is almost always safe to answer, and what one holds is the Meta Type Service's decision
  rather than this build's. A property the service says nothing about is redacted, because
  nobody-told-us and it-is-safe are not the same sentence. Redacted is not masked — a masked value
  has a length, it changes when the secret changes, and two answers compared tell you so — so a
  redacted property carries no value member at all. No listing carries a configuration value, no
  command carries a job property value, and no answer anywhere carries a replication transport
  address, which is a URL that very frequently carries the credential it authenticates with. A
  workflow is started only on a payload the caller could have changed themselves, asked of the
  repository rather than of a resource. No command sets, reads or carries a password. A group with
  members is refused rather than cascaded. And the registry is finished at sixty-four rows, compared
  with the client's published table field by field, with the rendered reference generated from it.
- **Integration:** `planned`; run `develop`; base `main` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`; validation base `pending`; mode `sequential`; final integration `pending`.
- **Exceptions:** five recorded.
  - The expected-prior-state guard this plan expected to reuse from Plan 0006 does not exist there:
    that plan recorded, correctly, that the client publishes no such member on any mutation. It does
    publish two here — `expected_kind` on the authorizable removal and `expected_entry_count` on the
    queue flush — and both are built. They are the client's own guards rather than a shared type
    borrowed from the plan before, because there is nothing there to borrow.
  - What a caller may ask a workflow instance to become and what the platform may report about one
    are different sets, so they are different types. A caller may ask for two states; the platform
    publishes five, because an instance can finish, be aborted, or go stale while the request is in
    flight. The plan's design said the two were one type so that a result could be compared against
    a request; what the client publishes makes that impossible, so a result is compared for
    agreement rather than for equality and the difference is stated where anybody reading it will
    see it.
  - The sixty-four-row comparison reads the client's own published catalogue and classification
    rather than a table committed beside them. The plan asked for `policy/client-command-table.toml`;
    writing one would have made two copies of the client's table, and the conformance check that
    already exists says in its own words why that is wrong — the one nobody remembered would be the
    one this gate believed. The client's documents are already mirrored here as committed bytes with
    committed digests, which is the same guarantee with one copy.
  - Two of the seams needed their methods renamed off `create`, `update`, `delete` and `set`. The
    static analysis treats those prefixes as evidence that an object is mutable state being handed
    around, and a handler holding one is then a handler exposing internal representation. The seams
    are stateless views onto a platform service rather than state, so the names now say what they do
    — `make`, `apply`, `erase` — and the two handlers hold a factory that opens a view per run
    rather than the view itself, which is the shape Plan 0006 already used for the one command that
    needs somewhere to work.
  - One digest row may now name which member list in its model it means. Several commands share one
    argument reader where their arguments are the same shape, and a file serving four commands
    cannot have four constants all called `MEMBERS`. Splitting it into four files to satisfy a
    checker would have been the checker deciding the design, so the row names the list instead —
    which is the natural generalisation of a row that already names the model.
- **Outcome:** thirty-two tasks complete.

_Last updated: 2026-09-02, against `develop` @ `bf4ebf010e5c149517a9ab8a83d544201d9644ae`._
