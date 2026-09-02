<!--
SPDX-License-Identifier: MIT OR Apache-2.0
Copyright 2026 Koray Taylan Davgana
-->

# One file per command

Every command this build serves is declared in a file of its own, named for its wire name. There is
no shared list: a shared list is a file every command task has to edit, which turns a footprint rule
into a queue, makes sixty independent pieces of work into one sequence, and produces a merge
conflict per command on top of that.

A row states its wire name, the semantic contract version it is for, whether it changes anything,
whether a caller supplies an operation key, the result bound it answers under, every way it may
fail, the digests of the schemas it is held to, how much room it may take inside the agent's own
tree while it works, and where it runs.

The directory is empty until the first command lands. An empty directory is an empty registry
rather than a failure, which is what this product was for its first four plans.
