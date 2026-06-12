package edge.roll

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import edge.roll.core.GameChromeView
import edge.roll.core.GameHostSession
import edge.roll.core.Scores
import edge.roll.game.EdgeRoll

/** Single-game launcher host: builds the shared HUD over the libGDX surface and runs Edge Roll. */
class GameActivity : AndroidApplication() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val chrome = GameChromeView(this, ACCENT)
        chrome.setBest(Scores.best(SCORE_ID))
        val session = GameHostSession(this, SCORE_ID, chrome)
        val game = EdgeRoll(session)

        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useAccelerometer = false
            useCompass = false
            numSamples = 2
            r = 8; g = 8; b = 8; a = 8
            depth = 16
        }
        val gameView = initializeForView(game, config)

        val root = FrameLayout(this)
        root.addView(gameView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(chrome, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
    }

    private companion object {
        const val SCORE_ID = "edgeroll"
        const val ACCENT = 0xFFA146FA.toInt()
    }
}
