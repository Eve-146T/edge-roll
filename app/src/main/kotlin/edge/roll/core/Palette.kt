package edge.roll.core

import android.graphics.Color

/** Bright, candy-colored shared palette. */
object Palette {
    /** Deep indigo game background — makes the neon accents pop. */
    const val BG = 0xFF14102E.toInt()
    const val BG_LIGHT = 0xFF241B4A.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    val colors = intArrayOf(
        0xFFFF3B6B.toInt(), // raspberry
        0xFFFF7A1A.toInt(), // orange
        0xFFFFC400.toInt(), // amber
        0xFFB6F32A.toInt(), // lime
        0xFF2AE881.toInt(), // mint
        0xFF1FD9D2.toInt(), // teal
        0xFF35AAFF.toInt(), // sky
        0xFF7A6BFF.toInt(), // violet
        0xFFC44BFF.toInt(), // purple
        0xFFFF4BD8.toInt(), // magenta
    )

    fun nice(i: Int): Int = colors[((i % colors.size) + colors.size) % colors.size]

    fun hsv(h: Float, s: Float = 0.78f, v: Float = 1f): Int =
        Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Linear blend between two ARGB colors. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(sa: Int, sb: Int) = (sa + ((sb - sa) * k)).toInt()
        return Color.argb(
            ch(Color.alpha(a), Color.alpha(b)),
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)),
        )
    }
}
