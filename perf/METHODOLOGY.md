# Benchmark methodology

## Device under test
Motorola G7 Power (`moto g(7) power`, codename *ocean*) — Qualcomm msm8953
(Snapdragon 632), **Adreno 506** GPU, Android 15 (LineageOS), Magisk root.

## Artificial throttle (to expose GPU/CPU-bound behaviour)
Unthrottled, the game is **vsync-capped at 60 fps** (16.7 ms) so renderer wins are
invisible. To make frame time the bottleneck we pin the device *down* to a
weak-device profile with root (`perf/scripts/throttle.sh`), fully reversible with
`perf/scripts/restore.sh`:

| Knob | Stock | Throttled |
|------|-------|-----------|
| GPU (Adreno 506) | msm-adreno-tz, up to 725 MHz | **pinned pwrlevel 6 = 216 MHz** |
| CPU little (cores 0-3) | schedutil, up to 1.80 GHz | **pinned 614 MHz** |
| CPU big (cores 4-7) | schedutil, up to 1.80 GHz | **offlined** (weak-device sim) |

Clocks were verified stable *during* a run (sampled every 1 s): GPU 216 MHz /
CPU 614 MHz / cores 0-3, flat the whole run.

## Repeatable in-app benchmark (`bench` intent extra)
`GameActivity` accepts `--ez bench true` plus `benchWidth/benchDepth/benchSecs/benchWarmup`.
In bench mode `EdgeRoll` builds a **deterministic static tile field** (default 9×26 =
234 tiles, all opaque, unique per-tile colours = worst-case for draw-call batching),
freezes gameplay, holds the standard follow-cam pose, and every frame records the
**true frame interval** (`Gdx.graphics.rawDeltaTime`). It logs per-second windows and
a final aggregate `RESULT` line (avg / p50 / p95 / p99 ms, fps, draw count) to logcat
under tag `EdgeBench`, then auto-exits. `perf/scripts/bench.sh` drives one pass and
appends a CSV row to `perf/data/results.csv`.

## The JIT-warmup trap (why warmup = 22 s)
The debug APK is `debuggable`, and **ART will not AOT-compile a debuggable app**
(`dumpsys package dexopt` → `status=verify`). Under the 614 MHz throttle the render
loop therefore starts *interpreted* — the first frames take ~500 ms (2 fps) — and
JIT-compiles the hot path over ~20 s, ramping smoothly to a **rock-solid plateau**
(±0.2 fps for the entire tail). Measuring the ramp would be meaningless, so every
run **skips the first 22 s** and aggregates only the settled plateau. The plateau
tracks steady-state (JIT-warmed ≈ AOT release) performance, and the *relative*
speedups between steps — the whole point — are compilation-independent.

Audio/haptics are also disabled in bench mode (`setprop debug.edgeroll.bench 1`) so
the background DSP-synth thread doesn't contend for the 4 little cores.

## Baseline
`00_baseline` (stock renderer, throttled): **24.85 fps, 40.2 ms avg, p99 43.9 ms,
237 draw calls.** All speedups in `IMPROVEMENTS.md` are measured against this.
