package edge.roll.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally synthesized sound effects, shared by every game.
 *
 * Available sound names (pitch-shift with `rate` 0.5..2.0 for variety):
 *  tap, tick, blip, pop, place, perfect, combo, success, fail,
 *  whoosh, boom, coin, rise, slide
 */
object SoundFx {
    private const val SR = 44100
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        pool = p
        val dir = File(ctx.cacheDir, "sfx").apply { mkdirs() }
        thread(name = "sfx-synth") {
            val sounds = synthAll()
            val expected = sounds.size
            val loaded = java.util.concurrent.atomic.AtomicInteger(0)
            // ready only once SoundPool reports every sample decoded — play() of
            // an undecoded sample is a silent no-op.
            p.setOnLoadCompleteListener { _, _, _ ->
                if (loaded.incrementAndGet() >= expected) ready = true
            }
            for ((name, pcm) in sounds) {
                val f = File(dir, "$name.wav")
                if (!f.exists() || f.length() == 0L) f.writeBytes(wav(pcm))
                ids[name] = p.load(f.path, 1)
            }
        }
    }

    fun play(name: String, rate: Float = 1f, vol: Float = 1f) {
        if (!ready || !Settings.soundEnabled) return
        val id = ids[name] ?: return
        val v = vol.coerceIn(0f, 1f)
        pool?.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    // ------------------------------------------------------------------ synth

    private fun synthAll(): List<Pair<String, ShortArray>> = listOf(
        "tap" to synth(40) { t, p -> sin(t * 1150.0 * TAU) * decay(p, 5.0) },
        "tick" to synth(20) { t, p -> sin(t * 2300.0 * TAU) * decay(p, 6.0) },
        "blip" to synth(70) { t, p -> square(t * (640.0 + 420.0 * p)) * 0.5 * decay(p, 3.0) },
        "pop" to synth(70) { t, p -> sin(t * (380.0 + 1500.0 * p * p) * TAU) * decay(p, 4.0) },
        "place" to synth(90) { t, p ->
            (tri(t * 145.0) * 0.8 + noise() * 0.35) * decay(p, 5.0)
        },
        "perfect" to synth(260) { t, p ->
            val f = 1318.0
            (sin(t * f * TAU) + 0.45 * sin(t * f * 2.0 * TAU) + 0.2 * sin(t * f * 3.0 * TAU)) /
                1.65 * decay(p, 4.5)
        },
        "combo" to synth(140) { t, p ->
            sin(t * 880.0 * TAU + 4.0 * sin(t * 26.0 * TAU)) * decay(p, 3.0)
        },
        "success" to arpeggio(doubleArrayOf(523.25, 659.25, 783.99, 1046.5), 120, 460),
        "fail" to synth(420) { t, p ->
            (saw(t * (520.0 - 360.0 * p)) * 0.7 + noise() * 0.12 * p) * decay(p, 2.2)
        },
        "whoosh" to lowpassed(190, 0.10) { _, p ->
            noise() * sin(p * PI).pow(1.4)
        },
        "boom" to lowpassed(380, 0.035) { t, p ->
            (noise() * 0.9 + sin(t * 64.0 * TAU) * 0.8) * decay(p, 3.2)
        },
        "coin" to synth(190) { t, p ->
            val f = if (t < 0.035) 987.77 else 1318.5
            square(t * f) * 0.45 * decay(p, 2.6)
        },
        "rise" to synth(300) { t, p ->
            sin(t * (280.0 + 1000.0 * p.pow(1.5)) * TAU) * (0.6 + 0.4 * sin(t * 30.0 * TAU)) *
                sin(p * PI).pow(0.5)
        },
        "slide" to lowpassed(130, 0.22) { _, p -> noise() * sin(p * PI).pow(0.8) * 0.8 },
    )

    private const val TAU = 2.0 * PI
    private val rng = Random(42)

    private fun decay(p: Double, k: Double) = exp(-p * k) * min(1.0, p * 60.0)
    private fun square(cycles: Double) = if (cycles - cycles.toLong() < 0.5) 1.0 else -1.0
    private fun saw(cycles: Double) = 2.0 * (cycles - cycles.toLong()) - 1.0
    private fun tri(cycles: Double) = 1.0 - 4.0 * abs((cycles - cycles.toLong()) - 0.5)
    private fun noise() = rng.nextDouble() * 2.0 - 1.0

    /** gen receives (t seconds, progress 0..1) and returns -1..1 */
    private fun synth(ms: Int, vol: Double = 1.0, gen: (Double, Double) -> Double): ShortArray {
        val n = SR * ms / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val p = i.toDouble() / n
            val v = (gen(t, p) * vol).coerceIn(-1.0, 1.0)
            out[i] = (v * 30000).toInt().toShort()
        }
        return out
    }

    /** Like synth but runs the signal through a one-pole lowpass (alpha = cutoff feel). */
    private fun lowpassed(ms: Int, alpha: Double, gen: (Double, Double) -> Double): ShortArray {
        val n = SR * ms / 1000
        val out = ShortArray(n)
        var acc = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val p = i.toDouble() / n
            acc += alpha * (gen(t, p) - acc)
            out[i] = (acc.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
        }
        return out
    }

    /** Overlapping note arpeggio, soft square + sine blend. */
    private fun arpeggio(freqs: DoubleArray, noteMs: Int, totalMs: Int): ShortArray {
        val n = SR * totalMs / 1000
        val out = DoubleArray(n)
        val noteN = SR * noteMs / 1000
        for ((k, f) in freqs.withIndex()) {
            val start = k * (noteN * 3 / 4)
            for (i in 0 until (noteN * 2).coerceAtMost(n - start)) {
                val t = i.toDouble() / SR
                val p = i.toDouble() / (noteN * 2)
                out[start + i] += (sin(t * f * TAU) * 0.7 + square(t * f) * 0.18) * decay(p, 3.5) * 0.8
            }
        }
        return ShortArray(n) { (out[it].coerceIn(-1.0, 1.0) * 30000).toInt().toShort() }
    }

    // ------------------------------------------------------------------- wav

    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val o = ByteArrayOutputStream(44 + dataLen)
        fun s(s: String) = o.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) {
            o.write(v and 0xFF); o.write((v shr 8) and 0xFF)
            o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
        }
        fun i16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        s("RIFF"); i32(36 + dataLen); s("WAVE")
        s("fmt "); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
        s("data"); i32(dataLen)
        for (v in pcm) { o.write(v.toInt() and 0xFF); o.write((v.toInt() shr 8) and 0xFF) }
        return o.toByteArray()
    }
}
