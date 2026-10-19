# .agent index

The dictionary of everything in `.agent/`. One row per active knowledge file:
path | read when. A knowledge file without a row here doesn't exist — updating
this index is part of the same change that adds, renames, or deletes one.
Immutable and generated collections (e.g. `tasks/archive/`, design export
dirs) are covered by their directory rows, never per-file.

**Placement rule**: a fact lives in the root `AGENTS.md` only if violating it
is expensive AND it applies to most sessions; everything else lives here, read
on demand. Nothing under `.agent/` is auto-loaded. The root file stays ≤150
lines.

**Audience rule**: every file here is written for agents — dense facts, exact
paths, present tense, no narration. History belongs in `git log` and the
board's records, not in prose.

## rules/ — behavioural, read before touching the area

| File | Read when |
|---|---|
| `rules/regression.md` | before work in any area listed in it — the traps already paid for, and the guard that stops each coming back |
| `rules/conventions.md` | naming a file/class, placing new code, wiring imports |
| `rules/git.md` | branching, committing, tagging, or adding a file that might not belong in git |

## docs/ — reference, longer-form

| File | Read when |
|---|---|
| `docs/architecture.md` | orienting on the system — layers, packages, how parts interact |

## tasks/ — the board

| File | Read when |
|---|---|
| `tasks/README.md` | adding, planning, executing, or closing a task; modes (ADO / Local) |
| `tasks/TASKS.md` | checking what's in flight |
| `tasks/history.md` | checking what shipped recently — **Local mode only; delete this row in ADO mode** |
| `tasks/review-queue.md` | shipped surfaces awaiting a human review pass |
| `tasks/archive/` | never as current state; retired plan files (and rotated history in Local mode) |

## Tooling

| Path | Read when |
|---|---|
| `check-docs.sh` | after a rename, a file move, or any doc edit — asserts every backticked path in the root file + `.agent/**` still resolves |
