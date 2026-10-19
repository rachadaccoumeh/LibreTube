# Rules — behavioural

Read when: creating a new rule file.

Read the matching rule BEFORE touching its area. Rules are written for agents:
dense facts, exact paths, present tense, no narration. A rule states the
invariant + the checkable reason; never a person's taste. Every rule file
opens with a one-line `Read when:` so an agent can decide without reading on.

Starter set — keep what fits the project, delete the rest, and register every
survivor in `.agent/INDEX.md`:

- `conventions.md` — naming, file placement, imports
- `regression.md` — traps already paid for + the guard that stops each coming back
- `git.md` — commits, branches, tags, never-commit list
- add per layer only when the project has it: backend, state, routing, auth, pages (UI QA checklist)
