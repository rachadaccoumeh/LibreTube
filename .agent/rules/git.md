# Git

Read when: branching, committing, tagging a build, or adding a file that might
not belong in git.

<!-- check-docs:off -->
Remote: GitHub `rachadaccoumeh/LibreTube` (`origin`, the fork).
`upstream`: GitHub `libre-tube/LibreTube` (read only, merge source).
Long-lived branches: `master` (the working branch, holds all fork work),
`merge/v32-sabr` (older integration branch), `release/v31.4`,
`release/v31.4-clean`, `feature/full-backup-restore`.
<!-- check-docs:on -->

## Commits

Conventional commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`,
`test:`, `merge:`), **one line, lowercase, no period, no trailers** (no
`Co-Authored-By`, no session links).

```
fix(downloads): parallel downloads, safe resume, no cascade delete
```

## Branches

<!-- check-docs:off -->
Work directly on `master` and push it to `origin`; no other work branches
(decided 2026-10-19). Never force-push.
<!-- check-docs:on -->

## Upstream merges

1. `git fetch upstream`, then `git merge --no-commit upstream/master` on `master`.
2. Resolve each conflict by keeping both behaviours (fork feature + upstream
   fix). Upstream API renames win; re-apply the fork logic on top.
<!-- check-docs:off -->
3. Watch for duplicate string names in `app/src/main/res/values/strings.xml`
   (both sides add keys): rename the upstream key in `values/` and in all
   `values-*/` translations.
<!-- check-docs:on -->
4. Gate: `./gradlew :app:compileDebugKotlin` and `:app:testDebugUnitTest`.
5. The database version must stay ≥ the installed one (26). Check
   `app/schemas/com.github.libretube.db.AppDatabase/` is unchanged or extended.

## Tags & releases

No releases from this fork; the app is used as a debug build on one phone.

## Never commit

A secret in git stays in history after deletion: every clone, fork and former
contractor keeps it, and the only fix is rotation. The `.gitignore` files block
most of these; this list is the reason — never bypass it with `git add -f`.

<!-- check-docs:off -->
- Signing material: `*.jks`, `*.keystore`, `*.p12`, `keystore.properties`
- Machine-local: `local.properties`, IDE user data (`.idea/workspace.xml`)
- Generated/build output: `build/`, `.gradle/`, `.kotlin/`
<!-- check-docs:on -->
