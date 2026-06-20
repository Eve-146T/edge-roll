# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Edge Roll is a single-screen Android arcade game built with libGDX (3D, OpenGL ES).
You swipe to tumble a cube along a floating bridge while a collapse front chases
you from behind. It is a standalone app under package `edge.roll`, distributed
via F-Droid and GitHub releases. No accounts, ads, tracking, or network access —
the only permission is `VIBRATE`.

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
runs `assembleDebug` on branches/PRs and `assembleRelease` + GitHub Release on `v*` tags.

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
   global singletons. `MenuActivity` is the **launcher** — a plain Activity with
   live difficulty sliders that persists a `BankConfig` to prefs, then starts
   `GameActivity` (an `AndroidApplication`) which builds a `FrameLayout` stacking
   the libGDX `GLSurfaceView` under a `GameChromeView` HUD and runs the game with
   `BankConfig.load(this)`.

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
  `activity.runOnUiThread` — the lone exception is `setBuffer()`, a ~60 Hz call
  that writes a volatile field the meter polls itself. After `gameOver()` the GL
  loop **keeps running** for the death animation — gameplay logic must guard on
  `session.isOver`.

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
- `game/EdgeRoll.kt` — the whole game. The difficulty model is a **banking
  chase**, not a per-tile timer:
  - **The chase lives in `updateBank()`.** It drains the bank each frame, slides
    `frontF` to match (`-cubeGz - fill*TRAIL`), pushes the meter, and dies when
    the bank empties. A tile crumbles when `-gz <= frontF`.
  - **Time bank, not a tile count.** `bank` (seconds) drains continuously at
    `drainRate()` and is topped up — with diminishing returns — on reaching **any
    fresh tile** (`Tile.visited`), so a forced sideways stretch is survivable but
    oscillating on visited tiles isn't. `session.setBuffer(bank/bankMax)` drives
    the HUD meter; `frontF` (the visible collapse line) is derived from the bank.
  - **All banking numbers live in `BankConfig`** (`bankStart/Max`, `bankReward`,
    `drainBase/Gain/Ramp`), tuned from the menu — *not* in `EdgeRoll`'s companion.
  - **Score = forward distance** (`bestF` = furthest `-gz` reached) + gem bonuses;
    sideways/back moves can't pad it (refilling the bank ≠ scoring).
  - Camera (fixed follow `updateCam`), input (absolute 4-direction swipe/tap), and
    the forward random-walk `generate()` are **unchanged from the original**. The
    crumble/fall state machine (`ALIVE`→`CRUMBLE`→`FALLING`) and the quaternion
    `orient` tumble are as before — only the *trigger* moved from per-tile timers
    to the front.
- `MenuActivity.kt` + `core/BankConfig.kt` — the launcher menu and the tunable
  banking params it edits. `BankConfig.PARAMS` is a list of slider descriptors
  (label/range/get/with lenses); the menu builds one `SeekBar` per entry
  generically and `BankConfig.load/save` round-trips them through prefs. Add a
  knob by adding one `Param` — the menu and persistence pick it up automatically.
- `core/GameChromeView.kt` — the HUD `FrameLayout`, built **entirely in code**
  (never XML): score/best, the `BankMeter` (a self-animating banking gauge down
  the left edge), animated center banners, and the game-over card. The meter
  reads a `@Volatile level` so the GL thread can push it lock-free via
  `session.setBuffer()` (the one `GameSession` call that does *not* marshal to the
  UI thread). RESTART **finishes + relaunches** the activity rather than
  `recreate()`, because libGDX only disposes GL/native resources on a true
  finish — `recreate()` would leak native meshes. Keep that pattern.
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
