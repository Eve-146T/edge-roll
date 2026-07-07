#!/usr/bin/env bash
# Run ONE repeatable renderer benchmark pass on the connected device and record
# the aggregate RESULT line (fps + frame-time percentiles over a fixed static scene).
#
# Usage: bench.sh <label> [width] [depth] [secs]
# Appends a CSV row to perf/data/results.csv and prints the RESULT line.
set -e
LABEL="${1:-run}"; W="${2:-9}"; D="${3:-26}"; S="${4:-38}"; WARM="${5:-22}"
PKG=edge.roll
HERE="$(cd "$(dirname "$0")/.." && pwd)"        # perf/
CSV="$HERE/data/results.csv"
RAW="$HERE/data/raw_${LABEL}.txt"
[ -f "$CSV" ] || echo "label,width,depth,fps,avgMs,p50,p95,p99,minFps,frames,tiles,drawn" > "$CSV"

adb shell setprop debug.edgeroll.bench 1 >/dev/null 2>&1 || true   # skip audio/haptics contention
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard          >/dev/null 2>&1 || true
adb shell am force-stop $PKG           >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true
adb shell am start -n $PKG/.GameActivity \
  --ez bench true --ei benchWidth "$W" --ei benchDepth "$D" --ei benchSecs "$S" --ei benchWarmup "$WARM" >/dev/null

# Block (up to secs+12) until the aggregate RESULT line is logged, saving all EdgeBench lines.
timeout $((S+12)) adb logcat EdgeBench:V '*:S' -v brief 2>/dev/null | awk '{print; fflush()} /RESULT/{exit}' | tee "$RAW"

LINE="$(grep -m1 'RESULT' "$RAW" || true)"
if [ -z "$LINE" ]; then echo "!! no RESULT captured for $LABEL"; exit 1; fi
val() { echo "$LINE" | sed -n "s/.* $1=\([0-9.]*\).*/\1/p"; }
echo "$LABEL,$W,$D,$(val fps),$(val avgMs),$(val p50),$(val p95),$(val p99),$(val minFps),$(val frames),$(val tiles),$(val drawn)" >> "$CSV"
echo "== recorded: $LABEL -> fps=$(val fps) avgMs=$(val avgMs) p99=$(val p99) drawn=$(val drawn)"
