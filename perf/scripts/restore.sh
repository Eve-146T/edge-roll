#!/usr/bin/env bash
# Restore the Motorola G7 Power to its stock, unthrottled clock configuration.
# Values captured from the device before throttling (see perf/data/original_state.txt).
set -e
# Wrap so the '>' redirect runs INSIDE the root shell, not the outer adb shell.
SU() { adb shell "su -c '$1'"; }

echo ">> Restoring stock clocks"

# --- GPU: reopen the full power-level window and hand back to the stock governor ---
SU "echo 7 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel"
SU "echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
SU "echo 133330000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
SU "echo 725000000 > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
SU "echo msm-adreno-tz > /sys/class/kgsl/kgsl-3d0/devfreq/governor"
SU "echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on" || true
SU "echo 80 > /sys/class/kgsl/kgsl-3d0/idle_timer" || true

# --- CPU: re-online the big cluster, then restore stock schedutil + full range ---
for C in 4 5 6 7; do SU "echo 1 > /sys/devices/system/cpu/cpu$C/online" || true; done
SU "echo 1804800 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
SU "echo 614400 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
SU "echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor"
SU "echo 1804800 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
SU "echo 633600 > /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"
SU "echo schedutil > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor"

echo ">> Verify (should read stock):"
echo -n "   CPU0 max: "; adb shell "cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
echo -n "   CPU4 max: "; adb shell "cat /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
echo -n "   GPU max_pwrlevel: "; SU "cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
echo -n "   GPU governor: "; SU "cat /sys/class/kgsl/kgsl-3d0/devfreq/governor"
