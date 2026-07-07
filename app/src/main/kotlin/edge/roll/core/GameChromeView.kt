package edge.roll.core

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The game's HUD overlay: score + best at the top, animated center banners,
 * and the game-over card. Must only be touched from the UI thread
 * (GameHostSession marshals for you).
 *
 * Created programmatically (never inflated from XML) and shows dynamic,
 * single-locale game text (scores, banners), so ViewConstructor / SetTextI18n
 * are intentionally suppressed.
 */
@SuppressLint("SetTextI18n", "ViewConstructor")
class GameChromeView(private val activity: Activity, private val accent: Int) : FrameLayout(activity) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    /** Running on Android TV / Google TV — pause is via the remote, not a touch chip. */
    private val isTv = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private val scoreText = TextView(activity).apply {
        textSize = 52f
        setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        gravity = Gravity.CENTER_HORIZONTAL
        setShadowLayer(dp(6f).toFloat(), 0f, dp(2f).toFloat(), 0x80000000.toInt())
        text = "0"
    }

    private val bestText = TextView(activity).apply {
        textSize = 15f
        setTextColor(Palette.withAlpha(Color.WHITE, 170))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val bannerText = TextView(activity).apply {
        textSize = 40f
        setTextColor(accent)
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        gravity = Gravity.CENTER
        setShadowLayer(dp(8f).toFloat(), 0f, dp(2f).toFloat(), 0xA0000000.toInt())
        alpha = 0f
    }

    private val pauseBtn = PauseButton(activity).apply {
        isClickable = true
        isFocusable = false          // touch-only affordance: never steals D-pad focus from the game
        contentDescription = "Pause"
        setOnClickListener { pauseGame() }
    }

    private val versionText = TextView(activity).apply {
        textSize = 11f
        setTextColor(Palette.withAlpha(Color.WHITE, 90))   // deliberately dim
        typeface = Typeface.DEFAULT_BOLD
        text = appVersionLabel()
    }

    private val countdownText = TextView(activity).apply {
        textSize = 96f
        setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        gravity = Gravity.CENTER
        setShadowLayer(dp(10f).toFloat(), 0f, dp(3f).toFloat(), 0xC0000000.toInt())
        alpha = 0f
    }

    private var overCard: View? = null
    private var pauseScrim: View? = null
    private var paused = false
    private var resuming = false

    /** Fired (UI thread) when the effective pause state flips; wired by [GameHostSession]. */
    var onPauseStateChanged: ((Boolean) -> Unit)? = null

    private var bestPulse: ValueAnimator? = null
    private val topBox = LinearLayout(activity)

    init {
        isClickable = false
        isFocusable = false

        topBox.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(scoreText)
            addView(bestText)
        }
        addView(topBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(48f)
        })
        addView(bannerText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addView(pauseBtn, LayoutParams(dp(46f), dp(46f)).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(16f); leftMargin = dp(16f)
        })
        addView(versionText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(12f); leftMargin = dp(16f)
        })
        addView(countdownText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        if (isTv) pauseBtn.visibility = GONE   // TV pauses via the remote (play/pause / menu)

        // Keep the HUD clear of the status bar / navigation bar / display cutout.
        setOnApplyWindowInsetsListener { _, insets ->
            val (top, left, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.displayCutout(),
                )
                Triple(i.top, i.left, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                Triple(insets.systemWindowInsetTop, insets.systemWindowInsetLeft, insets.systemWindowInsetBottom)
            }
            (topBox.layoutParams as LayoutParams).topMargin = maxOf(dp(48f), top + dp(10f))
            (pauseBtn.layoutParams as LayoutParams).apply {
                topMargin = maxOf(dp(16f), top + dp(10f))
                leftMargin = maxOf(dp(16f), left + dp(12f))
            }
            (versionText.layoutParams as LayoutParams).apply {
                bottomMargin = maxOf(dp(12f), bottom + dp(8f))
                leftMargin = maxOf(dp(16f), left + dp(12f))
            }
            topBox.requestLayout()
            pauseBtn.requestLayout()
            versionText.requestLayout()
            insets
        }
    }

    override fun onDetachedFromWindow() {
        bestPulse?.cancel()
        bestPulse = null
        countdownText.animate().cancel()
        super.onDetachedFromWindow()
    }

    fun setBest(best: Int) {
        bestText.text = if (best > 0) "BEST $best" else ""
    }

    fun setScore(v: Int) {
        scoreText.text = v.toString()
        scoreText.animate().cancel()
        scoreText.scaleX = 1.18f
        scoreText.scaleY = 1.18f
        scoreText.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    fun banner(text: String) {
        bannerText.animate().cancel()
        bannerText.text = text
        bannerText.alpha = 1f
        bannerText.scaleX = 0.5f
        bannerText.scaleY = 0.5f
        bannerText.animate()
            .scaleX(1.1f).scaleY(1.1f)
            .setStartDelay(0)
            .setInterpolator(OvershootInterpolator(2.4f))
            .setDuration(220)
            .withEndAction {
                bannerText.animate().alpha(0f)
                    .setStartDelay(380)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(300)
                    .withEndAction { bannerText.animate().setStartDelay(0) }
                    .start()
            }
            .start()
    }

    fun showGameOver(score: Int, best: Int, isNewBest: Boolean) {
        if (overCard != null) return
        scoreText.visibility = GONE
        bestText.visibility = GONE
        pauseBtn.visibility = GONE   // a finished run can't be paused

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(0xB8000000.toInt())
            isClickable = true
            alpha = 0f
            animate().alpha(1f).setDuration(260).start()
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28f), dp(26f), dp(28f), dp(24f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(0xFF1E1840.toInt())
                setStroke(dp(2f), accent)
            }
        }

        card.addView(TextView(activity).apply {
            text = "GAME OVER"
            textSize = 26f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        card.addView(TextView(activity).apply {
            text = score.toString()
            textSize = 58f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })

        val bestLabel = TextView(activity).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            if (isNewBest) {
                text = "★ NEW BEST! ★"
                setTextColor(accent)
            } else {
                text = "BEST $best"
                setTextColor(Palette.withAlpha(Color.WHITE, 180))
            }
        }
        card.addView(bestLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f); bottomMargin = dp(20f)
        })
        if (isNewBest) {
            bestPulse = ValueAnimator.ofFloat(1f, 1.12f, 1f).apply {
                duration = 700; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a ->
                    val s = a.animatedValue as Float
                    bestLabel.scaleX = s; bestLabel.scaleY = s
                }
                start()
            }
        }

        fun roundedBg(fill: Int, stroke: Int, strokeW: Int) = GradientDrawable().apply {
            cornerRadius = dp(30f).toFloat(); setColor(fill)
            if (strokeW > 0) setStroke(dp(strokeW.toFloat()), stroke)
        }
        fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            text = label
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(36f), dp(13f), dp(36f), dp(13f))
            // D-pad-navigable on TV; a white ring + slight scale mark the focused button.
            // In touch mode (phone) requestFocus is a no-op, so there's no visual change.
            isFocusable = true
            isFocusableInTouchMode = false
            val normal: GradientDrawable
            val focused: GradientDrawable
            if (filled) {
                setTextColor(0xFF14102E.toInt())
                normal = roundedBg(accent, 0, 0)
                focused = roundedBg(accent, Color.WHITE, 3)
            } else {
                setTextColor(Color.WHITE)
                normal = roundedBg(Color.TRANSPARENT, Palette.withAlpha(Color.WHITE, 140), 2)
                focused = roundedBg(Palette.withAlpha(Color.WHITE, 40), Color.WHITE, 3)
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
            }
            setOnClickListener {
                SoundFx.play("tap"); Haptics.click()
                onClick()
            }
        }

        // finish + relaunch (NOT recreate): libGDX only disposes GL resources when
        // the activity is truly finishing, so recreate() would leak native meshes.
        val restartBtn = button("RESTART", true) {
            val relaunch = activity.intent
            activity.finish()
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
            activity.startActivity(relaunch)
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        }
        card.addView(restartBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(button("EXIT", false) { activity.finish() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            })

        scrim.addView(card, LayoutParams(dp(290f), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        card.translationY = dp(60f).toFloat()
        card.scaleX = 0.85f; card.scaleY = 0.85f
        card.animate().translationY(0f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.4f)).setDuration(320).start()

        overCard = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Put D-pad focus on RESTART for TV remotes (no-op on a phone in touch mode).
        restartBtn.post { restartBtn.requestFocus() }
    }

    /** Pause the run: freeze the game and show the tap-anywhere-to-resume scrim. */
    fun pauseGame() {
        if (paused || resuming || overCard != null) return
        paused = true
        pauseBtn.visibility = GONE
        SoundFx.play("tap"); Haptics.click()
        onPauseStateChanged?.invoke(true)

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
            setOnClickListener { resumeGame() }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(activity).apply {
            text = "PAUSED"
            textSize = 32f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(activity).apply {
            text = "tap anywhere to resume"
            textSize = 15f
            setTextColor(Palette.withAlpha(Color.WHITE, 170))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })
        scrim.addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        pauseScrim = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Begin resuming: dismiss the scrim, run the "2 1" countdown, then unfreeze. */
    fun resumeGame() {
        if (!paused || resuming) return
        resuming = true
        pauseScrim?.let { s ->
            s.isClickable = false
            s.animate().alpha(0f).setDuration(160).withEndAction { removeView(s) }.start()
        }
        pauseScrim = null
        countdownText.bringToFront()
        countdownFrom(2)
    }

    private fun countdownFrom(n: Int) {
        countdownText.text = n.toString()
        countdownText.alpha = 1f
        countdownText.scaleX = 1.7f
        countdownText.scaleY = 1.7f
        SoundFx.play("tick", rate = 1.1f, vol = 0.7f)
        Haptics.tick()
        countdownText.animate()
            .scaleX(1f).scaleY(1f).alpha(0f)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(650)
            .withEndAction { if (n > 1) countdownFrom(n - 1) else finishResume() }
            .start()
    }

    private fun finishResume() {
        countdownText.alpha = 0f
        paused = false
        resuming = false
        if (!isTv) pauseBtn.visibility = VISIBLE
        onPauseStateChanged?.invoke(false)
    }

    /** True while the run is paused (read by GameActivity for remote select-to-resume). */
    fun isPausedNow() = paused

    /** Toggle pause from a TV remote / gamepad system key. No-op after game over. */
    fun togglePause() {
        when {
            overCard != null || resuming -> {}
            paused -> resumeGame()
            else -> pauseGame()
        }
    }

    private fun appVersionLabel(): String = try {
        @Suppress("DEPRECATION")
        val name = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        if (name.isNullOrBlank()) "" else "v$name"
    } catch (e: Exception) {
        ""
    }

    /** Top-left pause glyph (two bars) drawn in code on a translucent rounded chip. */
    @SuppressLint("ViewConstructor")
    private inner class PauseButton(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            r.set(0f, 0f, w, h)
            val rad = dp(13f).toFloat()
            canvas.drawRoundRect(r, rad, rad, bgPaint)

            val barW = dp(5f).toFloat()
            val barH = h * 0.40f
            val gap = dp(6f).toFloat()
            val cx = w / 2f
            val barTop = (h - barH) / 2f
            val br = dp(2f).toFloat()
            r.set(cx - gap / 2f - barW, barTop, cx - gap / 2f, barTop + barH)
            canvas.drawRoundRect(r, br, br, barPaint)
            r.set(cx + gap / 2f, barTop, cx + gap / 2f + barW, barTop + barH)
            canvas.drawRoundRect(r, br, br, barPaint)
        }
    }
}
