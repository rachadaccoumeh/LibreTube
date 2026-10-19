# Review queue — shipped, uninspected

Read when: about to do a human review pass, or a change ships a surface no
human has seen.

Surfaces that are built, believed working, and never looked at by a human.
Not the board: nothing here is in flight, nothing here is known broken. A row
that turns out broken becomes a board line (ADO mode: a new bugfix item) and
leaves this file.

**Fixed shape.** Newest batch first. A row is **deleted** on sign-off — never
annotated, never struck through. Appending a row belongs to the change that
ships the surface; deleting it belongs to the pass that inspects it.

| Batch | Date | What to look at |
|-------|------|-----------------|
| player-ui | 2026-10-19 | related videos under the player as a vertical full-width list |
| upstream-merge | 2026-10-19 | new Welcome screen (sync mode choice) shown once after the update |
