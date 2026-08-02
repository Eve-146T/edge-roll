# CLAUDE.md

## What this is

Edge Roll is a single-screen Android arcade game built with libGDX (3D, OpenGL ES):
swipe to tumble a cube along a floating bridge while the tiles behind you disappear.
Standalone app, package `edge.roll`, shipped via F-Droid and GitHub releases.

## Build & run

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0). Set
`ANDROID_HOME` or put `sdk.dir=` in `local.properties`.

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed if keystore present, else unsigned
./gradlew lint                   # Android lint (CI keeps this clean)
./gradlew installDebug           # build + install on a connected device
```

Cutting a release: bump `versionCode` + `versionName` in `defaultConfig` and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. Signing comes from `keystore.properties` (local, gitignored) or `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` env vars (CI); with neither, the release build is unsigned rather than failing.

libGDX natives are not checked in. The `.so` files are extracted from `gdx-platform` at build time by the custom `copyAndroidNatives` task into `app/libs/` (gitignored), before the JNI-libs merge. If natives go missing at runtime, look there — do not commit them.

## Architecture

- `edge.roll` — Android host. `App` initializes the singletons (`Scores`,
  `Haptics`, `SoundFx`); `GameActivity` stacks the libGDX `GLSurfaceView` under a
  `GameChromeView` HUD and runs `EdgeRoll`.
- `edge.roll.core` — game-agnostic scaffolding, written as a mini framework in
  case more games get dropped in.
- `edge.roll.game.EdgeRoll` — the entire game.

### The two-thread rule

libGDX runs the game loop on the GL thread; the HUD may only be touched on the UI thread. `GameSession` is the bridge, and it's the only legal crossing: `EdgeRoll` calls `session.addScore()`/`banner()`/`gameOver()` from the GL thread; `GameHostSession` holds that state in `Atomic*` and marshals HUD updates via `runOnUiThread`. After `gameOver()` the GL loop keeps running for the death animation, so gameplay logic must guard on `session.isOver`.

### Gotchas worth knowing before you edit

- `core/Gdx3DGame.kt` — base class for any 3D game: camera, lighting, sky, the
  touch→`onTap`/`onSwipe`/`onDrag` recognizer, juice (`shake`/`flash`/`burst3d`),
  and model factories (`box`/`sphere`/…). Factory-made models are tracked in
  `owned` and disposed for you; anything you create yourself is your problem.
- `core/GameChromeView.kt` — HUD built entirely in code, never XML. RESTART
  finishes and relaunches the activity instead of `recreate()`, because libGDX
  only disposes native resources on a true finish. Keep that pattern.
- `core/SoundFx.kt` — SFX are synthesized at startup on a background thread (no
  audio assets shipped) and loaded into a `SoundPool`. `play()` is a silent no-op
  until every sample decodes. Add a sound via `synthAll()`.
- `game/EdgeRoll.kt` — difficulty is a per-tile crumble timer: leaving a tile
  arms its countdown (`crumbleTime()`, shrinking with score), and loitering past
  `dwellLimit()` drops the tile under you. Read the file for the actual numbers.
