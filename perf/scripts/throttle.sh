#!/usr/bin/env bash
# Artificially restrict the connected Motorola G7 Power (msm8953 + Adreno 506)
# to simulate a weak device, so renderer optimizations produce a measurable,
# honest FPS delta instead of being hidden under the 60 Hz vsync cap.
#
# Fully reversible via restore.sh. Requires Magisk root (adb shell su).
#
# Usage: throttle.sh [GPU_PWRLEVEL] [CPU_KHZ]
#   GPU_PWRLEVEL : Adreno power-level index to PIN (0=725MHz fastest .. 7=133MHz slowest). Default 6 (216 MHz).
#   CPU_KHZ      : CPU max/min freq to pin on BOTH clusters, in kHz. Default 614400 (614 MHz).
set -e
GPU_PWR="${1:-6}"
CPU_KHZ="${2:-614400}"
# Wrap so the '>' redirect runs INSIDE the root shell, not the outer adb shell.
SU() { adb shell "su -c '$1'"; }

echo ">> Throttling: GPU pwrlevel=$GPU_PWR, CPU=${CPU_KHZ} kHz (both clusters)"

# --- CPU: pin the little cluster (0-3) low, and OFFLINE the big cluster (4-7) ---
# core_ctl on this SoC ignores a scaling_max_freq cap on the big cluster, so we
# offline it outright for a deterministic weak-device profile (restore.sh re-onlines).
SU "echo $CPU_KHZ > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" || true
SU "echo $CPU_KHZ > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
SU "echo $CPU_KHZ > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
for C in 4 5 6 7; do SU "echo 0 > /sys/devices/system/cpu/cpu$C/online" || true; done

# --- GPU: collapse the allowed power-level window onto one low level ---
# kgsl indices: 0 = fastest. min_pwrlevel = slowest allowed (high idx),
# max_pwrlevel = fastest allowed (low idx). Setting both = L pins at level L.
SU "echo $GPU_PWR > /sys/class/kgsl/kgsl-3d0/min_pwrlevel"
SU "echo $GPU_PWR > /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
# Belt & suspenders: force the devfreq governor to hold that clock too.
FREQ=$(SU "cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies" | tr ' ' '\n' | sort -n | \
       awk -v i=$((7-GPU_PWR)) 'NF{a[NR]=$1} END{print a[i+1]}')
SU "echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor" || true
[ -n "$FREQ" ] && SU "echo $FREQ > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq" || true
[ -n "$FREQ" ] && SU "echo $FREQ > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq" || true
# Keep the clock from napping so the pin is stable during benchmarks.
SU "echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on" || true
SU "echo 1000000 > /sys/class/kgsl/kgsl-3d0/idle_timer" || true

echo ">> Verify:"
echo -n "   CPU0 max: "; adb shell "cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo -n "   online cpus: "; adb shell "cat /sys/devices/system/cpu/online"
echo -n "   GPU max_pwrlevel: "; SU "cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
echo -n "   GPU cur clk (Hz): "; SU "cat /sys/class/kgsl/kgsl-3d0/gpuclk"
