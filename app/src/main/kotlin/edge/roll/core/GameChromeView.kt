package edge.roll.core

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import edge.roll.R

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
        // On a phone it's a touch-only affordance that never steals D-pad focus. On TV it's a
        // real focusable control: navigate to it with the remote and press OK to pause. Either
        // way the dedicated remote pause/menu key (see GameActivity) also pauses.
        isFocusable = isTv
        isFocusableInTouchMode = false
        // Same as the toggles: no default square focus highlight — the chip draws its own ring.
        defaultFocusHighlightEnabled = false
        contentDescription = "Pause"
        setOnClickListener { pauseGame() }
        // Focus cue is the ring only, no scale: the chip hugs the top-left corner, so growing it
        // would push the ring past the screen edge (and get cropped harder on TVs with overscan).
        setOnFocusChangeListener { v, has -> (v as PauseButton).focused = has }
    }

    private val countdownText = TextView(activity).apply {
        textSize = 96f
        setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        gravity = Gravity.CENTER
        setShadowLayer(dp(10f).toFloat(), 0f, dp(3f).toFloat(), 0xC0000000.toInt())
        // The drop shadow (blur 10dp, offset 3dp down) spills below the glyph; without padding the
        // WRAP_CONTENT bounds clip it and the shadow looks cut off under the digit. Give it room.
        setPadding(dp(12f), dp(14f), dp(12f), dp(20f))
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

    private var runBegun = false   // set once the first move hides the pre-run options

    /**
     * A round, icon-only toggle (translucent black fill, accent ring). Reflects [isOn];
     * tapping flips it, confirms with sound + haptic honouring the *new* state (muting is
     * silent, un-muting isn't), then repaints. [focusable] makes it a D-pad control (used
     * for the TV pause menu): it grows and gains a bright white ring when focused, so the
     * remote user can see and toggle it. Phone chips pass focusable = false and stay touch-only.
     */
    private fun makeToggle(
        iconOn: Int,
        iconOff: Int,
        label: String,
        isOn: () -> Boolean,
        set: (Boolean) -> Unit,
        focusable: Boolean = false,
    ): ImageView = ImageView(activity).apply {
        val pad = dp(11f)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = focusable
        isFocusableInTouchMode = false
        // Suppress Android's default (rectangular) focus highlight: the round background has no
        // focus state, so the system otherwise draws a square behind the focused toggle on top of
        // our own white ring. Our ring is the only focus cue we want.
        defaultFocusHighlightEnabled = false
        fun paint() {
            val on = isOn()
            val hasFocus = isFocused
            setImageResource(if (on) iconOn else iconOff)
            imageTintList = ColorStateList.valueOf(if (on) accent else Palette.withAlpha(Color.WHITE, 110))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.withAlpha(Color.BLACK, 115))
                when {
                    hasFocus -> setStroke(dp(2f), Color.WHITE)
                    on -> setStroke(dp(1f), Palette.withAlpha(accent, 150))
                    else -> setStroke(dp(1f), Palette.withAlpha(Color.WHITE, 60))
                }
            }
            contentDescription = "$label ${if (on) "on" else "off"}"
        }
        paint()
        setOnClickListener {
            set(!isOn())
            SoundFx.play("tap"); Haptics.tick()
            paint()
        }
        if (focusable) setOnFocusChangeListener { v, has ->
            paint()
            v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(120).start()
        }
    }

    private val soundBtn = makeToggle(
        R.drawable.ic_sound_on, R.drawable.ic_sound_off, activity.getString(R.string.cd_sound),
        { Settings.soundEnabled }, { Settings.setSoundEnabled(it) },
    )
    private val hapticBtn = makeToggle(
        R.drawable.ic_haptic_on, R.drawable.ic_haptic_off, activity.getString(R.string.cd_haptics),
        { Settings.hapticsEnabled }, { Settings.setHapticsEnabled(it) },
    )
    private val toggleBox = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        alpha = 0f   // fades in at launch (see init) and when paused
        val size = dp(46f)
        addView(soundBtn, LinearLayout.LayoutParams(size, size))
        addView(hapticBtn, LinearLayout.LayoutParams(size, size).apply { leftMargin = dp(12f) })
    }

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
        addView(pauseBtn, LayoutParams(dp(41.4f), dp(41.4f)).apply {   // 10% smaller than 46dp
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(16f); leftMargin = dp(16f)                  // equidistant from top & left edges
        })
        addView(countdownText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        // Sound + vibration toggles, bottom-left. Shown only pre-run and while paused
        // (see hideOptions / pauseGame / resumeGame); hidden during an active run.
        // These are the touch-only phone chips; on TV they're never shown here (a D-pad
        // can't reach them during play) — the pause menu carries focusable copies instead.
        addView(toggleBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(20f); bottomMargin = dp(28f)
        })
        if (isTv) toggleBox.visibility = GONE else post { showToggles() }   // fade the chips in at launch (phone)

        // Hidden until the run actually begins (shown by hideOptions).
        pauseBtn.visibility = GONE

        // Keep the HUD clear of the status bar / cutout (top) and nav bar / cutout (bottom-left).
        setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val left: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
                val nav = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                left = nav.left; bottom = nav.bottom
            } else {
                @Suppress("DEPRECATION") top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION") bottom = insets.systemWindowInsetBottom
            }
            (topBox.layoutParams as LayoutParams).topMargin = maxOf(dp(48f), top + dp(10f))
            (pauseBtn.layoutParams as LayoutParams).apply {
                // Equidistant from the top and left edges (a square corner inset). The top uses
                // the same value as the left rather than the status-bar/cutout inset, so the chip
                // hugs the corner instead of dropping below a (centred) notch.
                val gap = maxOf(dp(16f), left + dp(12f))
                topMargin = gap
                leftMargin = gap
            }
            (toggleBox.layoutParams as LayoutParams).apply {
                leftMargin = dp(20f) + left
                bottomMargin = dp(28f) + bottom
            }
            topBox.requestLayout()
            pauseBtn.requestLayout()
            toggleBox.requestLayout()
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

    /** Fade the pause chip in (used when a run begins and after a resume). */
    private fun revealPause() {
        pauseBtn.animate().cancel()
        pauseBtn.alpha = 0f
        pauseBtn.visibility = VISIBLE
        pauseBtn.animate().alpha(1f).setDuration(220).start()
    }

    /** Fade the bottom-left sound / vibration chips in (phone launch and while paused). No-op on TV. */
    private fun showToggles() {
        if (isTv) return
        toggleBox.animate().cancel()
        toggleBox.alpha = 0f
        toggleBox.visibility = VISIBLE
        toggleBox.bringToFront()
        toggleBox.animate().alpha(1f).setDuration(220).start()
    }

    /** Fade the sound / vibration / slow-mo chips out (run start and on resume). */
    private fun hideToggles() {
        toggleBox.animate().cancel()
        toggleBox.animate().alpha(0f).setDuration(220)
            .withEndAction { toggleBox.visibility = GONE }.start()
    }

    /** Fade out the pre-run option chips once a run has actually begun; reveal the pause chip. */
    fun hideOptions() {
        runBegun = true
        revealPause()   // pause is only offered during an active run — fade it in
        if (toggleBox.visibility == VISIBLE) hideToggles()
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

    /** Rounded-pill background shared by the game-over and pause-menu buttons. */
    private fun roundedBg(fill: Int, stroke: Int, strokeW: Int) = GradientDrawable().apply {
        cornerRadius = dp(30f).toFloat(); setColor(fill)
        if (strokeW > 0) setStroke(dp(strokeW.toFloat()), stroke)
    }

    /**
     * A rounded pill button. D-pad-navigable on TV — a white ring + slight scale mark the
     * focused button; in touch mode (phone) requestFocus is a no-op, so there's no focus state.
     * (Parents that show it must not clip to padding, or the focus scale clips at the edges.)
     */
    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(36f), dp(13f), dp(36f), dp(13f))
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
            // A focused button scales up 1.06x; without this the extra width spills into the
            // card's padding and gets clipped, so the focus ring looks cut off at the sides.
            clipToPadding = false
            clipChildren = false
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

        // finish + relaunch (NOT recreate): libGDX only disposes GL resources when
        // the activity is truly finishing, so recreate() would leak native meshes.
        // The relaunch must stay INSIDE the current task: a fresh intent (never a
        // copy of activity.intent, whose launcher FLAG_ACTIVITY_NEW_TASK would spawn
        // a new task) started BEFORE finish() (so the task never empties). A
        // task-to-task swap always plays the OEM's default slide on Android 12+ —
        // apps cannot suppress task transitions — whereas an in-task activity open
        // honours the theme's animations: RunSwapAnim (themes.xml) crossfades the
        // fresh run over the game-over screen instead of sliding it in.
        val restartBtn = pillButton("RESTART", true) {
            activity.startActivity(Intent(activity, activity.javaClass))
            activity.finish()
        }
        card.addView(restartBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(pillButton("EXIT", false) { activity.finish() },
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
            clipChildren = false   // focused controls scale up; don't clip their rings
            clipToPadding = false
        }
        box.addView(TextView(activity).apply {
            text = "PAUSED"
            textSize = 32f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(activity).apply {
            text = if (isTv) "press OK to resume" else "tap anywhere to resume"
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

        if (isTv) {
            // TV has no touch: build a D-pad menu right in the pause box — RESUME plus focusable
            // sound/vibration toggles. keyDown stops consuming the D-pad while paused (see
            // Gdx3DGame), so the remote navigates these; OK activates whichever is focused.
            val resumeBtn = pillButton("RESUME", true) { resumeGame() }
            box.addView(resumeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22f)
            })
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                // A focused toggle grows to 1.12x; its white ring spills ~3dp past the toggle on
                // every side. This padding gives that ring room inside the row so it isn't clipped
                // flat where the row (and the pause box) end — the bug a TV tester caught.
                val ring = dp(8f)
                setPadding(ring, ring, ring, ring)
            }
            val size = dp(52f)
            row.addView(makeToggle(
                R.drawable.ic_sound_on, R.drawable.ic_sound_off, activity.getString(R.string.cd_sound),
                { Settings.soundEnabled }, { Settings.setSoundEnabled(it) }, focusable = true,
            ), LinearLayout.LayoutParams(size, size))
            row.addView(makeToggle(
                R.drawable.ic_haptic_on, R.drawable.ic_haptic_off, activity.getString(R.string.cd_haptics),
                { Settings.hapticsEnabled }, { Settings.setHapticsEnabled(it) }, focusable = true,
            ), LinearLayout.LayoutParams(size, size).apply { leftMargin = dp(20f) })
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12f)
            })
            // Land D-pad focus on RESUME (no-op on a phone in touch mode).
            resumeBtn.post { resumeBtn.requestFocus() }
        } else {
            // Phone: tap the scrim to resume; the bottom-left touch chips fade in above it.
            showToggles()
        }
    }

    /** Begin resuming: dismiss the scrim, run the "2 1" countdown, then unfreeze. */
    fun resumeGame() {
        if (!paused || resuming) return
        resuming = true
        if (runBegun) hideToggles()   // a mid-run pause fades them out again; pre-run keeps them

        // Fade the pause scrim (its "PAUSED" label included) fully out *before* the countdown
        // starts, so the label can never overlap the big "2 / 1" digits (which sit at the same
        // centre). Starting the countdown in the fade's end-action keeps the two strictly ordered.
        val startCountdown = {
            countdownText.bringToFront()
            countdownFrom(2)
        }
        val scrim = pauseScrim
        pauseScrim = null
        if (scrim != null) {
            scrim.isClickable = false
            scrim.animate().alpha(0f).setDuration(140)
                .withEndAction { removeView(scrim); startCountdown() }.start()
        } else {
            startCountdown()
        }
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
        revealPause()
        onPauseStateChanged?.invoke(false)
    }

    /** True while the run is paused (read by GameActivity for remote select-to-resume). */
    fun isPausedNow() = paused

    /**
     * OK / select on a TV remote pauses — but only during an active run. Returns true when it
     * consumed the press. Pre-run it returns false so OK still starts the run (rolls forward);
     * while paused / resuming / on game-over it returns false so OK reaches the focused HUD
     * control (RESUME, a toggle, RESTART/EXIT). Lets any remote pause even without a play/pause key.
     */
    fun pauseFromSelect(): Boolean {
        if (!runBegun || paused || resuming || overCard != null) return false
        pauseGame()
        return true
    }

    /** Toggle pause from a TV remote / gamepad system key. No-op after game over. */
    fun togglePause() {
        when {
            overCard != null || resuming -> {}
            paused -> resumeGame()
            else -> pauseGame()
        }
    }

    /** Top-left pause glyph (two bars) drawn in code on a translucent rounded chip. */
    @SuppressLint("ViewConstructor")
    private inner class PauseButton(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f).toFloat(); color = Color.WHITE
        }
        private val r = RectF()

        /** True while D-pad-focused on TV; draws a white ring so the remote user sees it. */
        var focused = false
            set(value) { if (field != value) { field = value; invalidate() } }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = dp(13f).toFloat()
            if (focused) {
                val inset = ringPaint.strokeWidth / 2f
                r.set(inset, inset, w - inset, h - inset)
                canvas.drawRoundRect(r, rad, rad, bgPaint)
                canvas.drawRoundRect(r, rad, rad, ringPaint)
            } else {
                r.set(0f, 0f, w, h)
                canvas.drawRoundRect(r, rad, rad, bgPaint)
            }

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
