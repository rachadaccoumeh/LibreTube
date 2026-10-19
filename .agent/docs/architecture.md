# Architecture

Read when: orienting on the system — layers, packages, how the parts interact.

Written for agents — dense facts, exact paths, present tense. Keep short (the
code is truth, this is the map).

## Layers

- UI: `app/src/main/java/com/github/libretube/ui/activities/MainActivity.kt` hosts navigation and the player
  container; `app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt` is the video page (MotionLayout
  `app/src/main/res/xml/player_scene.xml`: `start` = expanded, `end` = mini player);
  `app/src/main/java/com/github/libretube/ui/views/CustomExoPlayerView.kt` holds controls and gestures.
- Playback: `app/src/main/java/com/github/libretube/services/OnlinePlayerService.kt` / `app/src/main/java/com/github/libretube/services/OfflinePlayerService.kt`
  extend `app/src/main/java/com/github/libretube/services/AbstractPlayerService.kt` (Media3 session service). The UI talks to
  them through a `MediaController` and custom commands.
- Streams: `app/src/main/java/com/github/libretube/api/NewPipeMediaServiceRepository.kt` (NewPipeExtractor +
  `app/src/main/java/com/github/libretube/api/poToken/` WebView BotGuard tokens) returns `app/src/main/java/com/github/libretube/api/obj/Streams.kt`.
- SABR: `app/src/main/java/com/github/libretube/player/SabrMediaSource.kt` → `app/src/main/java/com/github/libretube/player/SabrMediaPeriod.kt` →
  `app/src/main/java/com/github/libretube/player/DefaultSabrChunkSource.kt` → `app/src/main/java/com/github/libretube/player/SabrDataSource.kt` → `app/src/main/java/com/github/libretube/player/parser/SabrClient.kt`
  (one HTTP POST returns UMP parts with audio and video segments).
- Downloads: `app/src/main/java/com/github/libretube/services/DownloadService.kt` (foreground service, queue), providers
  `app/src/main/java/com/github/libretube/repo/RawByteStreamDownloadProvider.kt` (HTTP range requests) and
  `app/src/main/java/com/github/libretube/repo/SabrDownloadProvider.kt`; files in `app/src/main/java/com/github/libretube/helpers/DownloadHelper.kt` (getDownloadDir).
- Data: Room `app/src/main/java/com/github/libretube/db/AppDatabase.kt` (version 26), DAOs in `app/src/main/java/com/github/libretube/db/dao/`; user data
  (subscriptions, history, playlists) behind `app/src/main/java/com/github/libretube/repo/UserDataRepositoryHelper.kt`
  (local, Piped account, or LibreTube sync server).

## Key decisions

- SABR first, DASH/HLS fallback: YouTube removes classic stream URLs; SABR
  streams are not throttled the same way.
- Fullscreen expands the player in place: moving the `SurfaceView` to another
  window destroys the surface (black flash).
- Download state lives in the file size on disk (`DownloadItem.isFinished`
  compares file size with `downloadSize`), not in a status column.
