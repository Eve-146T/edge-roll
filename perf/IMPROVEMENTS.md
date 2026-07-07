# Edge Roll — renderer optimization log

Goal: artificially throttle the connected Motorola G7 Power to a weak-device
profile, benchmark the renderer, and optimize until satisfied — then spend the
recovered headroom on a longer render distance and a tile fade-in animation.

See `METHODOLOGY.md` for the throttle + benchmark setup. All figures below are the
settled-plateau aggregate (`RESULT` line) of the in-app benchmark; raw logs are in
`data/raw_*.txt`, the CSV in `data/results.csv`, the animated summary in
`anim/perf_optimization.gif` / `.mp4`.

## Headline

| Scene | Before | After | Result |
|-------|--------|-------|--------|
| **Shipped view** (234 tiles) | 24.9 fps | **60 fps (vsync-locked)** | 2.4× — hits the refresh cap |
| **Stress field** (1020 tiles, render distance ×2.4) | 6.9 fps | **50.3 fps** | **7.3×** |
| Draw calls (either scene) | 1 per tile (≈240 / ≈1020) | **4 total** | ~250× fewer |

Unthrottled, the game is vsync-locked at 60 fps the whole time — the throttle
(GPU 216 MHz, CPU 614 MHz, big cluster offline) is what makes frame time, and thus
these wins, measurable.

## Two findings that shaped everything

1. **The bottleneck was the CPU, not the Adreno.** Raising the GPU to its full
   725 MHz changed nothing (25.2 vs 24.9 fps); raising the *CPU* to 1.8 GHz jumped
   it to the 60 fps cap. Edge Roll was **draw-call-submission-bound** — one
   `ModelBatch.render()` per tile, each with its own colour material, ~240 calls a
   frame. GPU-side tricks (MSAA, fewer lights) would have done nothing; collapsing
   draw calls was everything.
2. **The debug APK is `verify`-only** (ART won't AOT-compile a debuggable app), so
   the render loop starts interpreted (~500 ms/frame!) and JIT-warms over ~20 s to
   a rock-solid plateau. Every benchmark skips a 22 s warmup and measures only the
   plateau; relative speedups are compilation-independent.

## The optimizations (in order)

Measured on the 1020-tile stress field (the shipped 234-tile view is vsync-capped
after step 3, so it can't show the later gains — the stress field can).

| # | Optimization | fps | vs baseline | draw calls |
|---|--------------|-----|-------------|-----------|
| 0 | Baseline (per-tile draws) | 6.91 | 1.0× | 1023 |
| 1 | Frustum culling | 10.19 | 1.5× | 663 |
| 2 | **Batched tile mesh** | 46.72 | **6.8×** | 4 |
| 3 | Update-loop culling | 48.38 | 7.0× | 4 |
| 4 | Hidden-face LOD | 49.81 | 7.2× | 4 |
| 5 | Rest-free tile fade-in | 50.26 | 7.3× | 4 |

### 1 · Frustum culling  (`EdgeRoll.updateTiles` / `renderWorld`)
Tiles outside the camera frustum were still issuing a full draw call. A per-tile
bounding-sphere test against `cam.frustum` skips them. Small on the shipped view
(almost everything is on-screen) but worth **1.5×** once the render distance grows
and a third of the field is off-screen (1023 → 663 tiles drawn).

### 2 · Batched tile mesh  (`core/BoxBatch.kt`) — the big one
Every visible opaque tile is written into **one dynamic mesh** with its colour
baked into a packed vertex-colour attribute, and drawn in a **single**
`ModelBatch.render()` call — using the stock lit shader, so the look is identical.
~1020 draw calls → 1. This is a **6.8×** jump and takes the shipped view straight
to the 60 fps vsync cap. Falling/fading tiles (which need per-object alpha) still
draw individually; there are only ever a handful.

### 3 · Update-loop culling  (`EdgeRoll.updateTiles`)
Off-screen ALIVE tiles were still running their per-frame bob/transform/colour
update. Now the frustum test computed for rendering is reused to **skip the
animation work** for anything off-screen. Cheap, and it makes a large render
distance affordable.

### 4 · Hidden-face LOD  (`core/BoxBatch.kt`)
From Edge Roll's fixed high-front camera the bottom (`-Y`) and back (`-Z`) faces of
a tile are **never visible**, so the batch mesh doesn't build them — 24 → 16
vertices per tile (33% less geometry to transform, write and upload), with zero
visible difference.

### 5 · Rest-free tile fade-in  (see feature below)
The new spawn animation (task requirement) adds **no steady-state cost**: settled
tiles take a branch that skips the scale/colour-lerp math entirely, so only the
handful of tiles currently materializing pay for it.

## Unthrottled reality check (ms per frame on the full-power device)

The phone's panel is 60 Hz, so **unthrottled the game is vsync-locked at 16.7 ms/frame**
— you can't *see* the optimization as a higher number until the workload is heavy
enough to break that ceiling. Measured with an added per-frame timer
(`Gdx3DGame.frameWorkMs` = stall-free `tick` + `renderWorld`; wall-clock is
`rawDeltaTime`):

| Scene (unthrottled) | Baseline (per-tile) | Optimized (batched) |
|---|---|---|
| Shipped 234 tiles — wall | 16.7 ms · 60 fps *(vsync)* | 16.7 ms · 60 fps *(vsync)* |
| **1020-tile field — wall** | **24.4 ms · 41 fps** *(drops frames)* | **16.7 ms · 60 fps** *(vsync-locked)* |
| Optimized CPU work / frame | — | 3.4 ms (234) · 6.0 ms (1020) of the 16.7 ms budget |

So on the real device the win is: **the heavy field that the old renderer can't hold
60 fps on (41 fps) stays locked at 60 fps after batching**, and the optimized renderer
uses only ~⅓ of the frame budget — headroom for thermals, battery and more content.
The throttle (→ `40.2 ms` baseline) is simply how we made the *shipped* scene's win
visible as a number, since the real device is vsync-bound there.

(`frameWorkMs` isn't a fair *cross-renderer* number — the batched renderer front-loads
its cost into `renderWorld` while the per-tile renderer's cost lands later in
`ModelBatch.end`/draw submission, which shows up in the wall-clock above. Raw logs:
`data/raw_un_*.txt`.)

## Increased render distance
With the shipped view now pinned at 60 fps with headroom to spare, the forward
render distance went from **16 → 28 tiles ahead** (`RENDER_AHEAD` in `EdgeRoll`) —
a visibly longer bridge (see `data/shot_rdist.png`) that still holds 60 fps on the
throttled device, because the batched renderer + culling absorb the extra tiles.

## New feature: tiles fade in instead of popping in
Freshly generated tiles now **materialize**: they grow from 12% → full size,
rise ~0.45 units into place, and colour-fade out of the sky over 0.34 s
(`SPAWN_DUR`), instead of appearing instantly. Implemented within the opaque batch
(scale + colour only, no blending needed), so it's essentially free. Demo:
`anim/fadein_demo.gif`. Code: the `spawn`/`rscale` fields + the `ALIVE` branch in
`EdgeRoll.updateTiles`, plus the optional `scale` arg in `BoxBatch.add`.

## Files changed
- `core/BoxBatch.kt` — **new** batched box renderer (packed vertex colour, hidden-face LOD, spawn scale).
- `game/EdgeRoll.kt` — batched `renderWorld`, frustum + update culling, render distance, fade-in, benchmark harness + ablation toggles.
- `GameActivity.kt` — `bench` intent extras.
- `App.kt` — skip audio/haptics synth under `debug.edgeroll.bench` (benchmark isolation only).

## Reproduce
```bash
perf/scripts/throttle.sh          # pin GPU 216 MHz / CPU 614 MHz / big cluster offline
perf/scripts/bench.sh 00_baseline # one plateau-measured run -> data/results.csv
perf/scripts/restore.sh           # back to stock clocks
python3 perf/anim/make_graph.py   # regenerate the animated graph frames
```
Ablation: `adb shell setprop debug.edgeroll.nobatch 1` (per-tile draws) /
`debug.edgeroll.nocull 1` (no frustum cull) before a bench run.
