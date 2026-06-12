package edge.roll.core

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * Shared HUD overlay used by every game (2D and 3D):
 * score + best at the top, animated center banners, and the game-over card.
 * Must only be touched from the UI thread (GameHostSession marshals for you).
 */
class GameChromeView(private val activity: Activity, private val accent: Int) : FrameLayout(activity) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

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

    private var overCard: View? = null
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

        // Keep the HUD clear of the display cutout.
        setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetTop
            }
            (topBox.layoutParams as LayoutParams).topMargin = maxOf(dp(48f), top + dp(10f))
            topBox.requestLayout()
            insets
        }
    }

    override fun onDetachedFromWindow() {
        bestPulse?.cancel()
        bestPulse = null
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

        fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            text = label
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(36f), dp(13f), dp(36f), dp(13f))
            if (filled) {
                setTextColor(0xFF14102E.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(30f).toFloat(); setColor(accent)
                }
            } else {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(30f).toFloat(); setColor(Color.TRANSPARENT)
                    setStroke(dp(2f), Palette.withAlpha(Color.WHITE, 140))
                }
            }
            setOnClickListener {
                SoundFx.play("tap"); Haptics.click()
                onClick()
            }
        }

        // finish + relaunch (NOT recreate): libGDX only disposes GL resources when
        // the activity is truly finishing, so recreate() would leak native meshes.
        card.addView(button("RESTART", true) {
            val relaunch = activity.intent
            activity.finish()
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
            activity.startActivity(relaunch)
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
    }
}
