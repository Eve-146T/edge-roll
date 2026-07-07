# perf/ — throttled-device renderer optimization

Everything from the exercise of throttling the connected Motorola G7 Power to a
weak-device profile, benchmarking Edge Roll's renderer, and optimizing it — plus
the render-distance bump and tile fade-in that followed.

## Read these
- **`IMPROVEMENTS.md`** — what was changed and why, with before/after numbers.
- **`METHODOLOGY.md`** — throttle profile, the in-app benchmark, and the JIT/CPU-bound findings.

## Look at these
- **`anim/perf_optimization.gif`** / `.mp4` — animated speedup graph (6.9 → 50 fps, 7.3×).
- `anim/graph_final.png` — the final frame as a still.
- `anim/fadein_demo.gif` — the new tile fade-in, in-game.
- `data/shot_batched.png`, `shot_rdist.png`, `shot_faces.png` — gameplay screenshots at key steps.

## Scripts (`scripts/`)
| Script | Purpose |
|--------|---------|
| `throttle.sh [gpu_pwrlvl] [cpu_khz]` | Pin GPU/CPU low + offline the big cluster (root). |
| `restore.sh` | Return to stock clocks (re-online cluster, stock governors). |
| `bench.sh <label> [w] [d] [secs] [warmup]` | One plateau-measured benchmark pass → `data/results.csv`. |

## Data (`data/`)
- `results.csv` — every benchmark run (label, fps, frame-time percentiles, draw calls).
- `raw_*.txt` — per-run logcat (per-second windows + aggregate `RESULT`).
- `original_state.txt` — the device's stock clock config captured before throttling.

## Anim (`anim/`)
- `make_graph.py` — regenerates the animated graph (PIL frames → ffmpeg mp4+gif). All values are read from `../data`.

The device was returned to stock (CPU 1.8 GHz, all 8 cores, GPU 725 MHz) after the run.
