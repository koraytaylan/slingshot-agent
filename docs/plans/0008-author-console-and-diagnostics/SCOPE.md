# Plan 0008 — Author Console and Diagnostics

> A place in the author interface where somebody can see what this agent has been asked to do, what it did, and whether it is healthy — reached the way Adobe Consulting Services Commons is reached, and built without a front-end toolchain.

## Why this plan

Everything so far is answerable over HTTP by a client that already knows what to ask. That is the wrong shape for the two people who most need answers.

The first is an operator who has just installed this into an author instance and wants to know whether it is working. They are not going to write a request; they are going to look under Tools, the way they look for anything else that was installed into their author, and if there is nothing there they will conclude there is nothing there.

The second is whoever is looking at an operation that went wrong. They have an identifier and a question — what state is it in, which events did it emit, how many physical attempts did it take, what did it produce — and the answer is spread across four stores that only this repository knows how to read. A console that assembles them is the difference between a diagnosable system and one where the answer is "check the logs".

So this plan adds the navigation entry, the consoles behind it, and the health checks that appear in the author's own operations dashboard. It adds no build toolchain: Granite renders server-side, the data comes from Java data sources, and the only client-side code is the small amount the live event tail actually needs, written by hand. A front-end pipeline would be a second dependency graph, a second set of licences, and a second thing to keep current, for a handful of tables and one stream.

The console is also the first place this repository's own security model is tested by something other than a suite. A console is reached by a person's browser session rather than by a technical account, and a data source is a servlet like any other — so the permitted-group requirement and the redaction rules apply exactly as they do on the routes, and the proof at the end of this plan is mostly about establishing that they do.

## In scope

- **0031 — Navigation and Console Shell.** A navigation entry under Tools, registered the way Adobe's own extension point expects and replacing no Adobe resource; a console shell built from Granite's own components with one hand-written client library and no package manager; the permitted-group requirement applied to every console resource and every data source behind it; and the shared data-source foundation that reads the agent's stores under the service user only after the viewer has been authorized.
- **0032 — Operations Console.** A paged list of operations with their state, command, caller, and age; a detail view assembling the snapshot, the event ledger, the physical attempts, and the artifacts for one operation; a live tail that follows a running operation through the same event route the client uses; artifact download from the detail view; and a maintenance view showing the generation, the capacity, and what the last sweep did.
- **0033 — Diagnostics and Health.** Health checks that appear in the author's own operations dashboard and say whether the agent is installed, configured, and able to issue continuation tokens; a view of what this build speaks — the contract digests, the route table with its aliases, and the registered commands; a capacity and retention view; and structured logging correlated by operation identifier so a log line and a console row are the same event.
- **0034 — Accessibility, Language, and the Console Proof.** Every console string externalised for translation and every control reachable and labelled without a pointing device; and one proof that the console discloses nothing the routes would not, escalates nothing, and renders correctly against a running author with no browser automation involved.

## Out of scope

- Submitting a command from the console. This agent's submission path is the authenticated route with its derived idempotency key; a button that submitted would need to invent one, and inventing one is the thing Plan 0003 exists to prevent.
- Any front-end build: no package manager, no bundler, no framework, and no generated client-side asset.
- Browser automation. The console is proved by asserting server-rendered markup and data-source responses over HTTP, because Granite renders server-side and a browser driver is a large dependency for something that can be read directly.
- Editing content. This is a console for the agent, not another authoring surface.

## Plan dependencies

Plan 0001 supplies the immutable package, its declared root, and the tiers. Plan 0003 supplies every store the console reads. Plan 0004 supplies the authorization rule, the redaction corpus, and the event route the live tail uses. Plan 0007 supplies the registry the capability view renders.
