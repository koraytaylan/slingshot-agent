# Plan 0008 — Author Console and Diagnostics

## Where it appears, and what it does not touch

Adobe Experience Manager has one place people look for things that were installed into their author: Tools. Adobe Consulting Services Commons puts itself there, the Groovy Console puts itself there, and an agent that does not is an agent whose operator concludes nothing was installed.

The mechanism is Adobe's own extension point, and using it correctly means adding rather than replacing. A navigation entry is a new resource under this product's own root, plus one entry in the tools navigation that points at it. Nothing under `/libs` is overlaid, no Adobe resource is shadowed, and the package's filter still covers exactly `/apps/slingshot-agent` and the single navigation node — which is checked, because an overlay that quietly shadows an Adobe resource is the defect that appears at the next upgrade rather than at install.

## No front-end build

Granite renders server-side. A console page is a resource whose type is one of Granite's own components, its data comes from a Java data source, and the markup arrives complete.

So there is no package manager, no bundler, no framework, and no generated client-side asset. The only client-side code is what the live event tail genuinely needs — an event-source subscription and a table that appends rows — written by hand into one client library.

That is not minimalism for its own sake. A front-end pipeline is a second dependency graph with its own licences, its own advisories, and its own upgrade cadence, and this whole repository's compatibility argument rests on having exactly one provided dependency and embedding nothing. Adding a few hundred packages to render four tables would undo it.

## Two identities, again

A console is reached by a person's browser session; the routes are reached by a technical account. Underneath, they are the same thing: a servlet, an authenticated Sling user, and a permitted group.

So the console applies exactly the rules Plan 0004 established. Every console resource and every data source behind it requires a permitted group. A viewer outside it sees the navigation entry absent rather than a page that refuses — because a tool nobody may use is better not advertised — and a direct request to a data source is refused the same way the routes refuse.

The data sources read the agent's stores under the service user, because that is where the stores live and no person's session can read them. That inversion is exactly why the authorization check happens first and separately: the service user is doing the reading, so the decision about whether the reading should happen cannot also be the service user's.

Redaction is the routes' corpus, unchanged. A console is a response like any other, and the audit drives it.

## What the console shows

**Operations.** A page of operations with state, command, caller, and age, from the operation store. Paged the way everything else is paged, and filtered by state and command rather than by free text, because a free-text filter over a store with no index is the traversal this repository does not do.

**One operation.** The interesting view, and the reason the console exists: the snapshot, the event ledger in order, the physical attempts with the nodes that recorded them, the lease and its expiry, and the artifacts with their sizes and digests. Four stores assembled into the one answer somebody actually wants.

**A running operation.** The same event route the client uses, subscribed from the browser. Not a second stream implementation and not a polling loop — the route already exists, already resumes from a cursor, and already ends its own sessions, and a console that used something else would be a second thing to keep correct.

**Maintenance.** The current generation, the retained ones, the capacity counters against their bounds, and what the last sweep did. This is where an operator finds out that a store is filling up, which is a question nobody thinks to ask until it matters.

**What this build is.** The contract digests, the route table with every alias and the client version it exists for, and the registered commands with their access classes and bounds. The same values discovery returns, rendered for a person — so an operator diagnosing a version disagreement can read both sides.

## Health, where an operator already looks

Adobe Experience Manager has an operations dashboard with health checks in it, and an agent that publishes its own readiness there is one an operator finds without being told to look.

Six checks, each answering one question with one cause: is the state tree present and are its access-control entries the declared ones; is the continuation-key authority ready to issue tokens that will still validate; is the store inside its capacity bounds; is the deployment row this instance matches one the build claims to support; is every route the table declares actually registered and reachable here; and would every declared query still be answered from an index on this repository. Each is separate because each has a different fix, and a single aggregate check would tell an operator only that something is wrong.

The last two are there because of how this fails on somebody else's instance rather than on ours. A path-bound servlet registers only for prefixes the servlet resolver permits, so a deployment that has narrowed them has an agent that is installed, active, and unreachable — which looks like nothing at all. And a query is cheap only while the index covering it exists, which is a property of the customer's repository rather than of this build. Both are silent, both are theirs to fix, and neither is visible anywhere else.

## Logs and the console are the same events

Every log line this repository writes carries the operation identifier when there is one, so a console row and a log line can be joined without guessing. The lines are structured, the redaction corpus applies to them exactly as it does to responses, and the source policy refuses a log statement that interpolates a value the corpus covers.

That is the last piece of "diagnosable": an operator with a console row can find the logs, and an operator with a log line can find the console row.

## Proving it without a browser

Granite renders server-side, so the console's markup can be fetched and asserted directly. The proof requests each console page as an authorized user, asserts the rendered markup contains the expected rows and values, requests each data source directly, and asserts the same refusals a route would give to an unauthorized caller.

A browser driver would add a large dependency and a class of flakiness, to prove something that is already fully determined by a server response. The one thing genuinely client-side — the live tail — is proved by driving the event route the way the tail does and asserting what it would receive.
