<!--
SPDX-License-Identifier: MIT OR Apache-2.0
Copyright 2026 Koray Taylan Davgana
-->

# Deploying the agent

What an operator has to know before this product is installed, and what they have to know if
something they already changed makes it behave differently. Everything here names a configuration
by its own identifier, so somebody who has changed one can tell that this is the one they changed.

## The three platform configurations this product depends on

### `org.apache.sling.servlets.resolver.impl.SlingServletResolver`

Its `servletresolver.paths` property is the list of path prefixes a servlet may register itself
under. Every route this agent serves is under `/bin/slingshot/agent`, and if that prefix is not
permitted, **the agent is simply absent**: the servlets register nowhere, answer nothing, and log
nothing an operator would connect to the symptom. A caller sees the instance's own not-found page,
which is indistinguishable from the package never having been installed.

On Adobe Experience Manager as a Cloud Service the shipped list includes `/bin`, so nothing has to
be changed. On a deployment where somebody has narrowed it, this is the first thing to look at.

### `org.apache.sling.security.impl.ReferrerFilter`

This refuses a state-changing request whose `Referer` is absent, or names a host the deployment
does not allow, **before any servlet is reached**. It is the surprise in this list, because it is
exactly the shape of a command-line client: a program that sends no `Referer` at all.

What this means for the client:

- it sends a `Referer` naming the instance it is talking to, or
- an operator adds that client's host to the filter's `allow.hosts`.

What it does not mean: setting `allow.empty` to true. A deployment that accepts an empty referrer
accepts it for every state-changing request on the instance, not only this agent's, and this agent
is not worth that.

### `com.adobe.granite.csrf.impl.CSRFFilter`

Adobe documents that an authenticated author POST carries a short-lived token, fetched immediately
beforehand from `/libs/granite/csrf/token.json` and sent in the `CSRF-Token` header. The client
already does this. What matters on this side is that the agent's own routes are **not** on the
filter's exclusion list: a route excluded from it is a route where Adobe's documentation is true and
the deployment is not.

### What this repository can and cannot prove about the two filters

The public interoperability tier is an Apache Sling starter, and it does not carry the security
bundle the referrer filter lives in — a scenario there asks it for a write naming a foreign host and
is served, which is the finding rather than a defect. So what this repository proves on a running
instance is that nothing else refuses such a request, which is exactly what makes naming the filter
here necessary rather than decorative. On a customer's own author the filter is present and refuses
before any servlet is reached, and a client that sends no `Referer` will meet it.

The forgery token filter is Adobe's own and is likewise absent from a plain Sling starter. What this
side holds to is that the agent's routes are on no exclusion list of either filter, which is checked
against every configuration this product ships.

## What this product requires of a caller

| Route kind | `Referer` | `CSRF-Token` |
|---|---|---|
| Reads (`GET`) | not required by the platform's filter | not required, deliberately |
| State-changing (`POST`) | required, naming an allowed host | required |

A token on a read would be a prerequisite that buys nothing and one more thing for a client to get
wrong, so this product does not ask for one.

## Where the answers come from

Nothing above is validated by this bundle. Whether a token is genuine is decided by the platform's
own filter, before a servlet is reached and with a key this bundle has no business holding. What
this bundle decides is what happens afterwards, and the answer is that every way of not having a
good token is answered identically: a caller who can tell an absent token from an expired one has
been told that tokens expire, and one who can tell a foreign token from an absent one has been told
whose it was.

## The event stream, and what has to be in front of it

The stream ends itself. This side holds one session for at most
`maximum_event_stream_session_milliseconds` and then closes cleanly after a final heartbeat, so the
client's decoder reads an ordinary ending and resumes with `Last-Event-ID` rather than guessing
about a connection that was severed. That bound only helps if it is under whatever the deployment's
own gateway allows, and the bound is one number for every row, so it has to be under the shortest of
them.

| Deployment row | Request window | What its ingress must allow | Streaming |
|---|---|---|---|
| `aem-cloud-service` | 60000 ms | an idle `text/event-stream` response for longer than the session bound, and heartbeats every `heartbeat_interval_milliseconds` | declared and unproved |
| `aem-6-5-lts` | 300000 ms | the same, plus whatever load balancer an operator put in front of it | declared and unproved |

An operator whose gateway ends a request sooner than the session bound has a stream that ends at a
moment nobody chose. There is one honest response to that: say so, and lower
`maximum_event_stream_session_milliseconds` to sit under it — the contract is the one place that
number lives, and the client reads the same one.

The other requirement is the one a row cannot claim until somebody has watched it happen: the
ingress in front of that row has to pass a `text/event-stream` response
through **without buffering** it. A buffered stream is not a slow stream. It is a stream that delivers nothing at all
until it ends, and no amount of correct behaviour on this side changes that — the events are
written and flushed one at a time here, and a proxy that holds them is a proxy that turns a live
view into a delayed transcript. Until an interoperability tier has watched events arrive over a
row's own ingress, that row's streaming support is **declared and unproved**, like everything else
in this table that no machine has run.

## Reaching the agent through a dispatcher

Everything this agent serves is under `/bin/slingshot/agent`, and a dispatcher denies `/bin` by
default. A deployment that has one in front of its author has to allow the prefix explicitly, or
every request this product makes is refused before it reaches Sling at all — and the refusal comes
back as the dispatcher's, which looks nothing like an agent's refusal and is the reason an operator
would otherwise spend an afternoon on it.

What has to be allowed, in the dispatcher's filter section: the exact path prefix
`/bin/slingshot/agent`, on `GET` and `POST`, with the query members the routes take
(`agent_operation_identifier`, `daemon_subscription_identifier`, `agent_event_store_generation`,
`artifact_slot`) passed through rather than stripped. A filter that allows the path and strips the
query is a filter that turns every request into one this agent cannot read.

The client's old spellings under `/libs/slingshot/agent` are a separate decision, and this side
ships them off. If a deployment turns them on through `rs.slingshot.agent.http.RouteAliasSwitch`,
the dispatcher in front of it is very likely already passing `/libs` more freely than `/bin` —
which is exactly why they are off by default. See
[CLIENT_COMPATIBILITY.md](CLIENT_COMPATIBILITY.md) for what is pending and what removes them.
