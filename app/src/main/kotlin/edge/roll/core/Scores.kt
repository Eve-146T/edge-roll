package edge.roll.core

import android.content.Context
import android.content.SharedPreferences

/** Persistent high scores, one entry per game id. */
object Scores {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("scores", Context.MODE_PRIVATE)
    }

    fun best(id: String): Int = prefs.getInt("best_$id", 0)

    /** Records [v] if it beats the stored best. Returns true when it is a new best. */
    fun submit(id: String, v: Int): Boolean {
        if (v <= best(id)) return false
        prefs.edit().putInt("best_$id", v).apply()
        return true
    }
}
