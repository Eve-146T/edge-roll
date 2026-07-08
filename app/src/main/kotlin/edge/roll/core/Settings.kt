package edge.roll.core

import android.content.Context
import android.content.SharedPreferences

/** Persistent player-facing options. Safe to read from the GL thread (values are @Volatile). */
object Settings {
    private lateinit var prefs: SharedPreferences

    /** Master mute for all procedural sound effects. Read from the GL thread. */
    @Volatile var soundEnabled: Boolean = true
        private set

    /** Master toggle for haptic feedback / vibration. Read from the GL thread. */
    @Volatile var hapticsEnabled: Boolean = true
        private set

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        soundEnabled = prefs.getBoolean("sound_enabled", true)
        hapticsEnabled = prefs.getBoolean("haptics_enabled", true)
    }

    fun setSoundEnabled(v: Boolean) {
        soundEnabled = v
        prefs.edit().putBoolean("sound_enabled", v).apply()
    }

    fun setHapticsEnabled(v: Boolean) {
        hapticsEnabled = v
        prefs.edit().putBoolean("haptics_enabled", v).apply()
    }
}
