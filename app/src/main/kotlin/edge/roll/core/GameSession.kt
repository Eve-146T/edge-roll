package edge.roll.core

/**
 * Bridge between a game and its host (HUD, scoring, lifecycle).
 * Safe to call from any thread (3D games call from the GL thread).
 */
interface GameSession {
    val score: Int
    val isOver: Boolean

    /** Sets the HUD score. Ignored after gameOver(). */
    fun setScore(v: Int)

    fun addScore(d: Int = 1)

    /** Flashes a big animated message in the center of the screen ("PERFECT!", "COMBO x5"). */
    fun banner(text: String)

    /**
     * Ends the run: saves the high score, plays success/fail sound + haptic,
     * and shows the shared game-over card with RESTART / EXIT.
     * The game's update loop keeps running afterwards (for death animation) —
     * guard gameplay logic with [isOver].
     */
    fun gameOver()
}
