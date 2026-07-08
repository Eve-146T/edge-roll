package edge.roll.core

import android.app.Activity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The GameSession implementation: marshals score/banner/game-over to the HUD on the UI thread. */
class GameHostSession(
    private val activity: Activity,
    private val id: String,
    private val chrome: GameChromeView,
) : GameSession {

    private val scoreV = AtomicInteger(0)
    private val over = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    init {
        // The HUD's pause button / resume countdown flips this; the GL thread reads it.
        chrome.onPauseStateChanged = { p -> paused.set(p) }
    }

    override val score: Int get() = scoreV.get()
    override val isOver: Boolean get() = over.get()
    override val isPaused: Boolean get() = paused.get()

    override fun setScore(v: Int) {
        if (over.get()) return
        scoreV.set(v)
        ui { chrome.setScore(v) }
    }

    override fun addScore(d: Int) {
        if (over.get()) return
        val v = scoreV.addAndGet(d)
        ui { chrome.setScore(v) }
    }

    override fun banner(text: String) {
        if (over.get()) return
        ui { chrome.banner(text) }
    }

    override fun runStarted() {
        ui { chrome.hideOptions() }
    }

    override fun gameOver() {
        if (!over.compareAndSet(false, true)) return
        val finalScore = scoreV.get()
        val isNew = finalScore > 0 && Scores.submit(id, finalScore)
        if (isNew) {
            SoundFx.play("success")
            Haptics.success()
        } else {
            SoundFx.play("fail")
            Haptics.fail()
        }
        ui { chrome.showGameOver(finalScore, Scores.best(id), isNew) }
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)
}
