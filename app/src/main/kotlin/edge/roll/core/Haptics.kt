package edge.roll.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback shared by every game.
 * tick   – subtle, for rapid repeated events (movement, counting)
 * click  – standard, for taps/placements
 * heavy  – impactful, for smashes/landings
 * success/fail – game-over flourishes
 * buzz   – custom one-shot
 */
object Haptics {
    private var vib: Vibrator? = null

    fun init(ctx: Context) {
        vib = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // Predefined effects are API 29+. On Android 9 (API 28) fall back to a short one-shot
    // (kind is a local selector so the API-29 constants are only referenced when supported).
    fun tick() = predefined(0, fbMs = 10, fbAmp = 120)
    fun click() = predefined(1, fbMs = 18, fbAmp = 180)
    fun heavy() = predefined(2, fbMs = 34, fbAmp = 255)

    fun success() = waveform(longArrayOf(0, 26, 70, 40), intArrayOf(0, 170, 0, 255))
    fun fail() = waveform(longArrayOf(0, 70, 60, 140), intArrayOf(0, 120, 0, 230))

    fun buzz(ms: Int, amp: Int = 200) {
        if (!Settings.hapticsEnabled) return
        runCatching { vib?.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceAtLeast(1), amp.coerceIn(1, 255))) }
    }

    private fun predefined(kind: Int, fbMs: Int, fbAmp: Int) {
        if (!Settings.hapticsEnabled) return
        if (Build.VERSION.SDK_INT >= 29) {
            val effect = when (kind) {
                0 -> VibrationEffect.EFFECT_TICK
                1 -> VibrationEffect.EFFECT_CLICK
                else -> VibrationEffect.EFFECT_HEAVY_CLICK
            }
            runCatching { vib?.vibrate(VibrationEffect.createPredefined(effect)) }
        } else {
            buzz(fbMs, fbAmp)
        }
    }

    private fun waveform(times: LongArray, amps: IntArray) {
        if (!Settings.hapticsEnabled) return
        runCatching { vib?.vibrate(VibrationEffect.createWaveform(times, amps, -1)) }
    }
}
