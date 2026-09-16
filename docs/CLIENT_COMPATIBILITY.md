<!--
SPDX-License-Identifier: MIT OR Apache-2.0
Copyright 2026 Koray Taylan Davgana
-->

# Canonical routes and historical client compatibility

This document distinguishes the canonical routes from opt-in compatibility paths for the client
snapshot at `ee6013218f6213e3c0bce66b1b917522db1552d1`. The five production constants below
already use the canonical paths in client commit `79397e8aa8e28bdeb65603ca7b68ae92d36c3584`,
as verified from that commit's three source files. That source comparison is not a live
interoperability test and does not establish support for every client version.

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

## What the historical client snapshot records

`policy/client-route-constants.toml` records historical route
constant, with the file and the symbol each came from, at a named client commit. Three spellings
appear across two repositories, and no single one of them is served by everything that expects it.

| The client asks for | Where | This side serves |
|---|---|---|
| `/libs/slingshot/agent/operations` | `crates/slingshot-agent-connection/src/job_snapshot_reconciliation.rs` | `/bin/slingshot/agent/snapshot` |
| `/libs/slingshot/agent/jobs` | the same file | `/bin/slingshot/agent/jobs` |
| `/libs/slingshot/agent/subscriptions/high-water` | the same file | `/bin/slingshot/agent/subscriptions/high-water` |
| `/libs/slingshot/agent/events` | `crates/slingshot-agent-connection/src/event_stream_reconnection.rs` | `/bin/slingshot/agent/events` |
| `/libs/slingshot/agent/artifacts` | `crates/slingshot-agent-connection/src/artifact_download.rs` | `/bin/slingshot/agent/artifact` |

In that snapshot, the client's simulator and daemon suites ask under `/bin`, and spell the
artifact route singular — so the client repository disagrees with itself, and the half that is
wrong is its production constants.

## Corrections recorded for that snapshot

Each of the five constants above moves to the canonical spelling. Every alias row in
`policy/agent-routes.toml` names its own correction in exactly those terms — the symbol, the file,
and the value it becomes — so the work is a list rather than an investigation. When a constant is
corrected in the policy snapshot, the alias table must be updated with it for the local comparison
to pass. Changing client source alone does not make this checker fail. The newer client commit
named above has all five corrections; it does not need these aliases for those constants.

## What this repository carries in the meantime, and how it is turned on

Each alias is a second path to one servlet and never a second implementation, so an alias answers
byte for byte what its canonical route answers, refusals included.

**They are off in what a customer receives.** `rs.slingshot.agent.http.RouteAliasSwitch` ships with
`served.paths` empty, which means the canonical routes and nothing else. A deployment running a
client that still needs an old spelling names exactly the paths it needs — one at a time, so that as
the client is corrected the deployment drops one row and what is left is what is still needed. A
deployment whose client has caught up never has any of them at all.

## What is removed, and when

Removing historical compatibility requires an explicit supported-client decision and coordinated
updates to the snapshot and alias table. `RouteAliasCoverage` rejects an alias that no recorded
constant asks for, a missing version or correction, and aliases enabled in shipped configuration.
It reads neither the sibling source nor its Git history. It therefore cannot establish that a
recorded constant really exists, discover a newly added constant, or automatically retire an alias
when a newer client corrects it. These aliases remain opt-in historical compatibility, not a
requirement for the newer commit named above.
