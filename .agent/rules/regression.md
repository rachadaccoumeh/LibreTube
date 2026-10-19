# Regression — traps already paid for

Read when: before touching any area named below — and before "simplifying"
anything that looks defensive without a reason attached.

Bugs already paid for. Each was subtle, shipped or nearly shipped, and cost
real debugging. The **guard** is what keeps it dead — don't simplify it back
out.

When a trap is paid for anywhere in the repo, its entry lands here in the same
change — that is what makes the payment permanent.

## Device data

**An app update could have wiped ~29 GB of downloads.**
Downloads live in the app-private external folder
(`DownloadHelper.getDownloadDir`), which Android deletes on uninstall. An
install signed with another key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
and the usual "fix" is an uninstall.
**Guard:** compare the APK signer (`apksigner verify --print-certs`) with the
installed APK before install; only `adb install -r`. Copy the app databases folder with
`run-as` first and compare row counts after.

## Downloads

**Downloading a second quality of a video made the app forget the first one.**
`DownloadDao.insertDownload` used `@Insert(REPLACE)` (an upstream change). SQLite
REPLACE deletes the old `download` row, and `DownloadItem` has
`onDelete = CASCADE`, so every item of that video vanished from the database
while its file stayed on disk.
**Guard:** `insertDownload` is `@Upsert`. Never REPLACE a parent row with
CASCADE children.

**Resumed SABR downloads were corrupt.**
`SabrDownloadProvider` is recreated on every resume and wrote the init segment
again into the middle of the file; it also saved the new position to the
database before the segment was flushed.
**Guard:** the init segment is written only when the file is empty;
`sink.flush()` runs before `updateDownloadItem`.

**"Parallel" downloads ran one at a time and the downloads list stuttered.**
`DownloadService` ran blocking network reads on a single-thread dispatcher; the
fragment ran `toggleVisibilities()` (a file-size read for every download) on
every progress event.
**Guard:** provider calls run in `withContext(Dispatchers.IO)`; progress is
batched every 500 ms; `toggleVisibilities()` is skipped for progress events.

## Player

**Auto quality always played the highest resolution and stalled.**
"Auto" is `Int.MAX_VALUE`; `SET_RESOLUTION` set a *minimum* video height of
`Int.MAX_VALUE`, which no track meets, so the selector locked one fixed track.
**Guard:** `AbstractPlayerService` clears the size constraints for
`Int.MAX_VALUE`. Check `trackSelection.length() > 1` when touching quality code.

**The bandwidth estimate read ~1 Gbit/s on a 5 Mbit/s connection.**
One SABR response holds audio and video segments; the second data source read
its segment from memory, and `SabrDataSource` reported that as a network
transfer.
**Guard:** `SabrDataSource` is `BaseDataSource(false)`; only
`SabrClient.readAndMeasure` reports real HTTP transfers to the bandwidth meter.

**The video drew small in the top-left corner after the player expanded.**
MotionLayout lays out only its direct children; the nested video surface kept
the mini-player size.
**Guard:** `binding.player.requestLayout()` at the end of every player
transition in `PlayerFragment`.

**Fullscreen flashed black and stuttered.**
Fullscreen moved the `SurfaceView` into a separate `Dialog` window, which
destroys the video surface.
**Guard:** fullscreen changes the `start` ConstraintSet in place
(`openOrCloseFullscreenDialog`); the minimize listeners ignore it via
`isFullscreenAnimating`. Do not reintroduce a fullscreen dialog.

## Logging

**`Log.d` lines never appear in `adb logcat` on the test phone.**
The Honor ROM drops debug-level app logs.
**Guard:** use `Log.w`/`Log.i` for diagnostics that must show on the device.
