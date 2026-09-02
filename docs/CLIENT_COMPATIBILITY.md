<!--
SPDX-License-Identifier: MIT OR Apache-2.0
Copyright 2026 Koray Taylan Davgana
-->

# Talking to the client that exists today

The two halves of Slingshot disagree about what the routes are called. This document says which
spelling is canonical, what the other repository has to change, what this one carries in the
meantime, and what it will remove when the change lands.

## The canonical spelling

`/bin/slingshot/agent/…`, exactly as `policy/agent-routes.toml` declares it, and nothing else. Every
servlet in this repository takes its path from that table; a source-level rule refuses the prefix
written as a literal anywhere else, so a second spelling has nowhere to be written.

## Why `/libs` is the wrong destination

Adobe reserves `/libs`. A path-bound servlet registered there creates no node and collides with
nothing today, which is exactly why the collision arrives during somebody else's upgrade rather
than during ours.

The operational half is worse. `/libs` is where client libraries live, so a customer's dispatcher
and their content delivery network are frequently configured to pass it more freely than anything
else — cached, unauthenticated, or simply not filtered. An authenticated, state-changing route
sitting inside that namespace is a wider surface than this agent asked for, and it is wide in a way
the people running the instance did not choose and would not expect.

`/bin` carries none of that. It is where a Sling servlet path belongs, dispatchers deny it by
default, and a deployment that wants the agent reachable says so explicitly.

## What the client repository declares today

Read out of it rather than recalled: `policy/client-route-constants.toml` records every route
constant, with the file and the symbol each came from, at a named client commit. Three spellings
appear across two repositories, and no single one of them is served by everything that expects it.

| The client asks for | Where | This side serves |
|---|---|---|
| `/libs/slingshot/agent/operations` | `crates/slingshot-agent-connection/src/job_snapshot_reconciliation.rs` | `/bin/slingshot/agent/snapshot` |
| `/libs/slingshot/agent/jobs` | the same file | `/bin/slingshot/agent/jobs` |
| `/libs/slingshot/agent/subscriptions/high-water` | the same file | `/bin/slingshot/agent/subscriptions/high-water` |
| `/libs/slingshot/agent/events` | `crates/slingshot-agent-connection/src/event_stream_reconnection.rs` | `/bin/slingshot/agent/events` |
| `/libs/slingshot/agent/artifacts` | `crates/slingshot-agent-connection/src/artifact_download.rs` | `/bin/slingshot/agent/artifact` |

The client's own simulator and its daemon suites already ask under `/bin`, and already spell the
artifact route singular — so the client repository disagrees with itself, and the half that is
wrong is its production constants.

## What the client repository has to change

Each of the five constants above moves to the canonical spelling. Every alias row in
`policy/agent-routes.toml` names its own correction in exactly those terms — the symbol, the file,
and the value it becomes — so the work is a list rather than an investigation. When a constant is
corrected, the alias row that carried it goes, and the check that compares the two documents in both
directions fails until it does.

## What this repository carries in the meantime, and how it is turned on

Each alias is a second path to one servlet and never a second implementation, so an alias answers
byte for byte what its canonical route answers, refusals included.

**They are off in what a customer receives.** `rs.slingshot.agent.http.RouteAliasSwitch` ships with
`served.paths` empty, which means the canonical routes and nothing else. A deployment running a
client that still needs an old spelling names exactly the paths it needs — one at a time, so that as
the client is corrected the deployment drops one row and what is left is what is still needed. A
deployment whose client has caught up never has any of them at all.

## What is removed, and when

Every alias, when the constant that asks for it is corrected. There is no row that outlives its
correction: the alias table states a client version and a pending correction per row, the loader
refuses a row that states neither, and `RouteAliasCoverage` fails on an alias no recorded client
constant asks for. That is what stops "we still serve `/libs`" from becoming a thing nobody
remembers deciding.
