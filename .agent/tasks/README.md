# Tasks — the board

Read when: adding, planning, executing, or closing a task. Markdown task
manager, agents are the runtime. Fixed shapes, no loops.

## Modes

The mode is stated at the top of the root `AGENTS.md` and follows the git
remote: a remote in the Azure DevOps org Rsg-Tech → **ADO mode**; anything else
(personal, GitHub, no remote, library/package) → **Local mode**.

| | Local mode | ADO mode |
|---|---|---|
| Source of truth for open work | `TASKS.md` | the ADO work item |
| Task id | `task-NNN`, minted here | the ADO id, written `AB#<id>` |
| `TASKS.md` | the full board | short index of open `AB#` items that have local notes or a plan, plus local `## Ideas` |
| `history.md` | done log (below) | **absent** — ADO state + finish date and `git log --grep "AB#<id>"` are the record |
| Plan file | `task-<NNN>.md` | `task-<ADO id>.md` |
| Item text | the board line | ADO holds the user-facing text; ADO rules (global agent config) govern it |

ADO items are never written from here without the owner's approval; the
global agent rules for ADO apply. The plan file is where repo-internal detail
lives — file paths, branch names, commit refs, decisions — the things ADO item
text must not carry.

## Files

- `TASKS.md` — **the board.** Every open task, grouped by urgency
  (`## Must` / `## When possible` / `## Ideas`). The only file you scan to
  know what's in flight.
- `history.md` — **Local mode only.** Done log, newest first, **max 40
  entries**. Logs task closures, not commits. A quiet stretch is not a gap.
- `review-queue.md` — shipped surfaces awaiting a human review pass. Nothing
  in it is in flight and nothing is known broken. A row that turns out broken
  becomes a board line (ADO mode: a new bugfix item) and leaves the queue.
- `task-<id>.md` — per-task plan file, only for tasks big enough to plan. Its
  existence is flagged on the board line with `[plan]`.
- `archive/` — retired plan files (+ rotated history in Local mode). Created on
  first use. Write-only; nothing in here is read as current state. Pointers may
  rot — `check-docs.sh` skips it.

## IDs

- Local: `task-NNN`, zero-padded sequential. Next id = highest across board +
  history + archive, plus one. Never reused; reopened work gets a new id
  (`supersedes task-<id>` in the description).
- ADO: the id ADO assigns. Never invent one. Ideas carry no id until they
  become an approved ADO item.

## Board line format

```
- [ ] task-<id> [plan] — short description — owner, YYYY-MM-DD     # Local
- [ ] AB#<id> [plan] — short description — owner, YYYY-MM-DD        # ADO
- idea — short description — owner, YYYY-MM-DD                      # ## Ideas, either mode
```

- **≤200 characters.** Status, findings, blockers' detail and progress go in
  the plan file, never on the line. A line that grows is a missing plan file.
- `[plan]` only when the plan file exists.
- Optional `(due YYYY-MM-DD)` / `(blocked: reason in ≤8 words)` right after the id.
- Intake fixes typos/grammar only — never reword, expand, or reinterpret the
  ask.

**Attribution scope:** the board line carries the owner's name + date —
nothing more. Plan files, rules, docs, and archive entries describe **what**
was decided and **when**, never **who** said it, preferred it, or vetoed it.

## Plan files

One-liner tasks live entirely on the board. A task that needs steps or
decisions gets `task-<id>.md`:

```markdown
# task-<id> — title

## Ask
Verbatim-ish restatement of what was requested.

## Plan
Numbered steps. Files to touch, decisions made, gotchas.

## Status
Where it stands now; blockers; findings. Rewritten, not appended.

## Done when
Concrete acceptance — what must be true to call it done.
```

Add `[plan]` to the board line in the same change that creates the file.

## Workflow

**Intake:** Local — mint the next id, add the line (default `## When
possible`). ADO — propose the item per the global ADO rules; after approval,
add a board line only if the item needs local notes or a plan.

**Planning** ("plan <id>"): write `task-<id>.md`, add `[plan]`.

**Execution** ("do <id>"):
1. Read the board line; if `[plan]`, follow the plan file.
2. Do the work. ADO mode: every commit ends with `AB#<id>`.
3. **QA gate (UI tasks):** before closing, run a read-only verifier pass over
   the diff against the project's QA checklist (the matching rules files +
   `rules/regression.md`). Findings get fixed or explicitly waived in the plan
   file — never silently dropped.
4. **Ship-reconciliation — in the same change that ships the work, never a
   follow-up:**
   - cut the board line;
   - Local: prepend a history entry. ADO: propose the state change per the
     global ADO rules;
   - move `task-<id>.md` to `archive/` if it exists;
   - append a `review-queue.md` row if the work put a surface on screen no
     human has seen;
   - add a `rules/regression.md` entry if the work paid for a trap;
   - run `bash .agent/check-docs.sh` if the change renamed or moved a file.

Urgency changes = cut/paste the line between sections, id stays.

## History entry format (Local mode)

```
- task-<id> — short description — done YYYY-MM-DD: outcome (commits)
```

Newest at the top. **`done`** (shipped as asked), **`closed`** (resolved
without the work — already true, or overtaken), **`dropped`** (deliberately
rejected; say why). A rejected task still gets an entry — a line that vanishes
with no history looks like a silent deletion. Rotation: whoever writes entry 41
moves the oldest beyond 40 to `archive/history.md`, in the same change.

## Across repos

Each repo has its own board. A task that spans repos:
- Local: a line on each board, each naming the other's id. Never mirror a
  single-repo task onto two boards — two lines for one task is drift.
- ADO: one work item; every repo's commits carry the same `AB#<id>`. The plan
  file lives in the repo with most of the work; the others' board lines point
  to it by path.

## Parallel sessions

One task = one branch/worktree. The board line's attribution names who has it
— that line IS the claim. No claim files, no heartbeats.
