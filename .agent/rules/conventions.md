# Conventions

Read when: naming a file/class, placing new code, wiring imports.

- Package root: `app/src/main/java/com/github/libretube/`. Upper folders by
  kind: `app/src/main/java/com/github/libretube/ui/` (activities, fragments, adapters, sheets, dialogs, views),
  `app/src/main/java/com/github/libretube/services/` (player and download services), `app/src/main/java/com/github/libretube/repo/` (data repositories and
  download providers), `app/src/main/java/com/github/libretube/player/` (SABR media source and parser), `app/src/main/java/com/github/libretube/db/` (Room),
  `app/src/main/java/com/github/libretube/api/` (extractor and HTTP), `app/src/main/java/com/github/libretube/helpers/`, `app/src/main/java/com/github/libretube/extensions/`.
- Naming: `XxxFragment`, `XxxAdapter`, `XxxBottomSheet`, `XxxDialog`,
  `XxxHelper` (stateless `object`), `XxxRepository`.
- UI: XML layouts + view binding (`FragmentXxxBinding`); no Compose.
- Settings: `PreferenceHelper` with keys in `app/src/main/java/com/github/libretube/constants/PreferenceKeys.kt`.
- Strings: new user text goes into `app/src/main/res/values/strings.xml` only;
  translations come from upstream (Weblate).
- Player commands from UI to service: `PlayerCommand` + `sendCustomCommand`,
  handled in `app/src/main/java/com/github/libretube/services/AbstractPlayerService.kt`.
- Generated code: Room (KSP) and protobuf (`app/src/main/proto`) are
  generated at build time; never hand-edit generated sources. Room schema JSON
  in `app/schemas/` is written by the build and is committed.
- Logs: `Log.w`/`Log.i` with `TAG()` (extension) for device diagnostics.
