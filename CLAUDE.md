# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Edge Roll is a single-screen Android arcade game built with libGDX (3D, OpenGL ES).
You swipe to tumble a cube along a floating bridge while the tiles behind you
crumble and drop into the abyss. It is a standalone app under package `edge.roll`,
distributed via F-Droid and GitHub releases. No accounts, ads, tracking, or network
access — the only permission is `VIBRATE`.

## Build & run

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0). Set
`ANDROID_HOME` (or put `sdk.dir=` in `local.properties`).

```bash
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed if keystore present, else unsigned
./gradlew lint                   # Android lint (CI keeps this clean)
./gradlew installDebug           # build + install on a connected device/emulator
```

There are **no unit/instrumentation tests** and no `test`/`androidTest` source
sets — the build check is "does it compile + lint clean". CI (`.github/workflows/build.yml`)
builds a debug APK on pushes to `main`, on pull requests, and on manual dispatch;
it builds a signed `assembleRelease` and publishes a GitHub Release on `v*` tags.

### libGDX natives are not checked in

The `.so` native libraries are extracted from the `gdx-platform` artifacts at
build time by the custom `copyAndroidNatives` Gradle task into `app/libs/`
(gitignored), wired to run before the JNI-libs merge. If native libs ever go
missing at runtime, that task is the place to look — do not commit them.

### Release signing

`app/build.gradle.kts` reads signing config from `keystore.properties` (local,
gitignored) or, failing that, `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`
env vars (CI). With neither present the release build is produced **unsigned** so
the project still builds for anyone. Bump `versionCode` + `versionName` in
`defaultConfig` and add a `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
for each release.

## Architecture

Three layers, smallest to largest:

1. **Android host** (`edge.roll`): `App` (Application) initializes the three
   global singletons (`Scores`, `Haptics`, `SoundFx`). `GameActivity` is the
   **launcher** (an `AndroidApplication`) — it builds a `FrameLayout` stacking the
   libGDX `GLSurfaceView` (`initializeForView`) under a `GameChromeView` HUD and
   runs `EdgeRoll`. `SCORE_ID`/`ACCENT` live in its companion.

2. **Engine / shared core** (`edge.roll.core`): reusable, game-agnostic
   scaffolding. This is deliberately structured as a *mini game framework* even
   though Edge Roll is currently the only game — `SCORE_ID`/`ACCENT` in
   `GameActivity`, the `GameSession` interface, and "shared by every game"
   comments all anticipate more games being dropped in.

3. **The game** (`edge.roll.game.EdgeRoll`): all Edge Roll-specific gameplay.

### The two-thread split (important)

libGDX runs the game loop on the **GL thread**; the Android HUD must be touched
only on the **UI thread**. `GameSession` is the bridge:

- `EdgeRoll` extends `Gdx3DGame` and calls `session.addScore()`, `session.banner()`,
  `session.gameOver()` from the GL thread.
- `GameHostSession` (the `GameSession` impl) holds score/over state in
  `Atomic*` and marshals every HUD update to the UI thread via
  `activity.runOnUiThread`. After `gameOver()` the GL loop **keeps running** for
  the death animation — gameplay logic must guard on `session.isOver`.

When changing anything that crosses this boundary, keep the rule: game logic on
GL thread reads/writes through `GameSession`; only `GameHostSession` and
`GameChromeView` touch Views.

### Key files

- `core/Gdx3DGame.kt` — abstract base for any 3D game. Owns camera, lighting,
  `ModelBatch`, gradient sky, the touch→`onTap`/`onSwipe`/`onDrag` input
  recognizer, auto-disposed model factories (`box`/`sphere`/`cylinder`/`cone`),
  and "juice" (`shake`, `flash`, `burst3d` cube-shard explosions). Subclasses
  implement `init`/`tick`/`renderWorld`. Models created via the factories are
  tracked in `owned` and disposed for you.
- `game/EdgeRoll.kt` — the whole game. The difficulty model is a **per-tile
  crumble timer**:
  - **Leaving a tile arms its crumble countdown.** `crumbleTime()` =
    `max(0.42, 1.2 - score*0.01)` seconds — a tile drops ~1.2s after you roll off
    it, faster as the score climbs. The per-tile state machine runs
    `ALIVE`→`CRUMBLE`→`FALLING` (gravity + spin + fade), and rolling onto a missing
    or already-falling tile topples the cube into the abyss.
  - **Loitering kills you too.** Idling on the current tile past `dwellLimit()`
    (= `crumbleTime() + 0.8`) makes the ground give way underfoot (`dwell`, with a
    `tick` warning and a red pulse ~0.55s before it goes).
  - **Score = new tiles traversed** (`Tile.visited`, +1 each) + gems (+3);
    re-rolling onto an already-visited tile doesn't score. Reaching `nextMilestone`
    fires a banner.
  - Camera (fixed follow `updateCam`), input (absolute 4-direction swipe/tap with
    its own buffered swipe detection for queued rolls), and the forward
    random-walk `generate()` (lateral wiggle, holey side pads, gem spurs) drive
    the run. The quaternion `orient` accumulates the 90° tumble.
- `core/GameChromeView.kt` — the HUD `FrameLayout`, built **entirely in code**
  (never XML): score/best at the top, animated center banners, and the game-over
  card. RESTART **finishes + relaunches** the activity rather than `recreate()`,
  because libGDX only disposes GL/native resources on a true finish —
  `recreate()` would leak native meshes. Keep that pattern.
- `core/SoundFx.kt` — **all SFX are procedurally synthesized** at startup on a
  background thread (no audio assets shipped), written to WAV in cacheDir and
  loaded into a `SoundPool`. `play()` is a silent no-op until every sample
  reports decoded (`ready`). Add a sound by adding an entry to `synthAll()`.
- `core/Haptics.kt`, `core/Scores.kt`, `core/Palette.kt` — vibration effects,
  `SharedPreferences`-backed per-id high scores, and the color palette/helpers.

### No assets pipeline

Everything is generated at runtime: 3D models from libGDX primitives, sounds via
DSP synthesis, colors via HSV helpers. There are no textures, meshes, or audio
files in the repo (only the launcher icon and store screenshots under
`fastlane/`). Prefer extending the procedural generators over adding binary assets.
