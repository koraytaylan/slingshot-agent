# Working in this repository

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. It states every rule a change is held to and names
the stage of `scripts/quality` that enforces each one, so nothing here is a convention somebody has
to remember.

Three things are worth knowing before the first command:

- `scripts/quality` is the whole gate. It takes no argument, runs every stage every time, and
  fetches nothing. If it refuses because a prepared input is missing, it names the command that
  prepares it.
- Every rule in this repository is a committed policy document with a checker and its own fixtures.
  Changing what the code may look like means changing a file under `policy/`, not a habit.
- [ARCHITECTURE.md](ARCHITECTURE.md) describes the module set and the two-bundle split;
  [docs/INTEROP.md](docs/INTEROP.md) describes the three tiers and what each one proves.
