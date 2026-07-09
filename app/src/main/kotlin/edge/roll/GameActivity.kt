package edge.roll

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
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

    private lateinit var chrome: GameChromeView
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        chrome = GameChromeView(this, ACCENT)
        chrome.setBest(Scores.best(SCORE_ID))
        val session = GameHostSession(this, SCORE_ID, chrome)
        val game = EdgeRoll(session)

        // Repeatable renderer benchmark, driven from adb, e.g.:
        //   adb shell am start -n edge.roll/.GameActivity \
        //       --ez bench true --ei benchWidth 9 --ei benchDepth 26 --ei benchSecs 14
        if (intent?.getBooleanExtra("bench", false) == true) {
            game.benchmark = true
            game.benchWidth = intent.getIntExtra("benchWidth", game.benchWidth)
            game.benchDepth = intent.getIntExtra("benchDepth", game.benchDepth)
            game.benchSecs = intent.getIntExtra("benchSecs", game.benchSecs.toInt()).toFloat()
            game.benchWarmup = intent.getIntExtra("benchWarmup", game.benchWarmup.toInt()).toFloat()
        }

        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useAccelerometer = false
            useCompass = false
            numSamples = 2
            r = 8; g = 8; b = 8; a = 8
            // 24-bit depth: 16-bit can't separate the die-pips from the cube face once the cube
            // is far down the abyss (they z-fight/flicker); 24-bit resolves them cleanly.
            depth = 24
        }
        val gameView = initializeForView(game, config)
        // The GL surface holds D-pad/remote focus during play so key events reach libGDX;
        // the game-over card takes focus when shown (see GameChromeView).
        gameView.isFocusableInTouchMode = true

        val root = FrameLayout(this)
        root.addView(gameView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(chrome, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
        gameView.requestFocus()
    }

    /**
     * TV remote / gamepad system keys. Directional + select keys fall through to libGDX
     * (gameplay) or, on game-over / while paused, to the focused HUD buttons.
     *
     * - Dedicated play/pause-style keys always toggle pause.
     * - On TV, OK / select (center) also pauses *during an active run* — many remotes (e.g. TCL)
     *   have no play/pause key, and the center button is redundant for movement (the D-pad's four
     *   arrows cover it). `pauseFromSelect` only consumes the press mid-run; pre-run it falls
     *   through so OK starts the run, and while paused / on game-over it falls through so OK
     *   activates the focused HUD control (RESUME / a toggle / RESTART / EXIT).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    chrome.togglePause(); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    if (isTv && chrome.pauseFromSelect()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val SCORE_ID = "edgeroll"
        const val ACCENT = 0xFFA146FA.toInt()
    }
}
