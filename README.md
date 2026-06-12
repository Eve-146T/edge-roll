# Edge Roll

Swipe to tumble a cube 90° over its edge across a floating bridge of tiles.
Tiles crumble and drop into the abyss shortly after you leave them — and faster
the higher you score — so keep moving. The bridge wiggles, branches into gem
spurs, and has holes. Score one per new tile; gems pay +3.

A tiny, loud, satisfying one-thumb arcade game built with libGDX.

| | |
|---|---|
| Package | `edge.roll` |
| Min SDK | 29 (Android 10) |
| Target SDK | 35 (Android 15) |
| Engine | [libGDX](https://libgdx.com/) 1.13.1 |
| Language | Kotlin |

## Build

The project ships a Gradle wrapper, so all you need is a JDK (17+) and the
Android SDK (platform 35, build-tools 35.0.0).

```bash
# point Gradle at your SDK (or create local.properties with sdk.dir=...)
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

The libGDX native `.so` libraries are not committed; the `copyAndroidNatives`
Gradle task extracts them from the `gdx-platform` artifacts on every build.

## Continuous integration

`.github/workflows/build.yml` builds the debug APK on every push to `main`,
on pull requests, and on manual dispatch, then uploads the APK as a build
artifact.

## Project layout

```
app/src/main/kotlin/edge/roll/
├── App.kt              Application: initializes audio, haptics, scores
├── GameActivity.kt     launcher host (libGDX surface + HUD overlay)
├── core/               reusable engine (3D base, HUD, sound, haptics, scoring)
└── game/EdgeRoll.kt    the game itself
```

## License

[GNU General Public License v3.0](LICENSE).
