# LibreTube (personal fork)

Android YouTube client (Kotlin, Views/XML, Media3). Personal fork of
libre-tube/LibreTube with custom features: downloads management (verify,
queue, pause all, full backup with media), NEW badges and manual "caught up" in
the feed, AI assistant, icon-only player actions, SABR playback and downloads.
Single user, installed as a debug build on one phone.

This file is the orchestrator for any agent working in this repo: hard rules,
structural orientation, and a pointer to the index. Detailed behaviour lives in
`.agent/` — nothing there is auto-loaded. `.agent/INDEX.md` is the dictionary:
one row per knowledge file, what it is, when to read it. Read the matching rule
before working in its area.

**Board mode: Local.** Mode rules: `.agent/tasks/README.md`.

---

## Hard rules — read every session

### Never lose the user's downloads
The phone holds ~29 GB of downloads in the app-private folder
`/sdcard/Android/data/com.github.libretube.debug/files/` (video, audio,
thumbnail, subtitle). Uninstall or "clear data" deletes all of it. Update only
with `adb install -r` of an APK signed with the same debug keystore; never
uninstall, never `pm clear`, never install with `-d` downgrades without a check.
Before an install, copy the database (`run-as … tar c databases`) and compare
the `download`/`downloadItem` row counts after. Detail: `.agent/rules/regression.md`.

### Database migrations are additive and tested
`AppDatabase` is at version 26 (upstream is 25; 25→26 is fork-only). A new
schema needs a new version + migration + the exported schema JSON in
`app/schemas/`. Never use `@Insert(REPLACE)` on a parent row with CASCADE
children (`Download` → `DownloadItem`): REPLACE deletes the row first and
cascades. Use `@Upsert`. Detail: `.agent/rules/regression.md`.

### Keep upstream mergeable
Upstream is merged regularly. Keep fork changes small and local to the code they
touch; prefer adding over rewriting upstream files. Resolve merge conflicts by
keeping both behaviours, then build. Detail: `.agent/rules/git.md`.

### Docs record decisions, not people
Never attribute an opinion, preference, veto or discovery to a person by name
in any md file or commit message. State the fact and the reason; say *what*
established it ("measured 2026-10-19", "seen on the phone"), never *who*.

### Git
Conventional commits, one line, lowercase, no trailers. Work directly on
`master`, no other work branches. Never force-push.
Detail and the never-commit list: `.agent/rules/git.md`.

---

## Going outside this repo

- `upstream` remote: `https://github.com/libre-tube/LibreTube` — source of
  merges. Read only.
- NewPipeExtractor fork `com.github.libre-tube:NewPipeExtractor` (JitPack,
  commit pin in `gradle/libs.versions.toml`) — stream extraction.

---

## What's shipped

**Playback.** SABR (YouTube UMP protocol) is the first choice, DASH then HLS
are fallbacks (`app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt`). Auto quality uses Media3
adaptive selection; the SABR client reports real HTTP transfers to the
bandwidth meter (`app/src/main/java/com/github/libretube/player/parser/SabrClient.kt`). Fullscreen expands the player
in place (no dialog window).

**Downloads.** `app/src/main/java/com/github/libretube/services/DownloadService.kt` with raw HTTP and SABR providers
(`app/src/main/java/com/github/libretube/repo/`), queue of max 6 parallel downloads, pause/resume/stop, verify, full
backup/restore including media (`app/src/main/java/com/github/libretube/helpers/FullBackupHelper.kt`).

**Feed.** Local subscriptions feed with NEW badges and a manual "mark as
caught up" divider.

**Ledgers.** Open work: `.agent/tasks/TASKS.md`. Shipped-but-unreviewed
surfaces: `.agent/tasks/review-queue.md`. Traps already paid for:
`.agent/rules/regression.md` — read it before touching the areas it covers.

---

## Stack

Kotlin 2.3, Gradle 9.3, AGP via `gradle/libs.versions.toml`, JDK 17,
compileSdk/targetSdk 36, minSdk 26. Media3 1.9.2, Room 2.8.4 (KSP),
Navigation 2.9, NewPipeExtractor (pinned commit). Views + XML layouts with
view binding, MotionLayout for the player minimize transition.

---

## Build & run

```
./gradlew :app:compileDebugKotlin        # fast gate
./gradlew :app:testDebugUnitTest         # unit tests
./gradlew :app:assembleDebug             # APK: app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with `~/.android/debug.keystore` on the dev Mac; the
installed app has package `com.github.libretube.debug`. `local.properties`
(SDK path) is machine-local. The test phone is an Honor device that hides
`Log.d`; use `Log.w`/`Log.i` for logs that must show in `adb logcat`.
