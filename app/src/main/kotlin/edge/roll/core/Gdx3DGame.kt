package edge.roll.core

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Base class for every 3D game (libGDX). Provides:
 *  - PerspectiveCamera + lit Environment + ModelBatch, gradient sky
 *  - tap / drag / swipe input callbacks (GL thread, same as [tick])
 *  - auto-disposed model factories: box / sphere / cylinder / cone
 *  - juice: camera [shake], [flash] overlay, [burst3d] cube-shard explosions
 *
 * After session.gameOver() the loop keeps running for death animation —
 * guard gameplay logic with session.isOver.
 */
abstract class Gdx3DGame(val session: GameSession) : ApplicationAdapter() {

    lateinit var cam: PerspectiveCamera
    lateinit var env: Environment
    private lateinit var batch: ModelBatch
    private lateinit var shapes: ShapeRenderer
    val mb = ModelBuilder()

    var bgTop: Color = Color.valueOf("3A2A7E")
    var bgBottom: Color = Color.valueOf("120E2C")

    /** Total elapsed seconds. */
    var time = 0f
        private set

    /** Whole-[render] CPU time, ms. Note: on Android this absorbs the vsync back-pressure
     *  stall (the driver blocks inside a GL call), so it's ~frame time when vsync-capped. */
    var frameCpuMs = 0f
        private set

    /** The stall-free per-frame CPU **work**: [tick] (game update) + [renderWorld] (batch build),
     *  in ms — no GL flush/clear/swap. This is the honest "CPU cost per frame" that stays
     *  meaningful even when wall-clock is pinned at the 60-fps vsync ceiling. */
    var frameWorkMs = 0f
        private set

    val sw: Int get() = Gdx.graphics.width
    val sh: Int get() = Gdx.graphics.height

    private val owned = ArrayList<Model>()
    private val rnd = Random(System.nanoTime())
    private var shakeMag = 0f
    private var flashColor = Color(1f, 1f, 1f, 0f)
    private val camSave = Vector3()

    abstract fun init()
    abstract fun tick(dt: Float)
    abstract fun renderWorld(batch: ModelBatch, env: Environment)

    open fun onTap(x: Float, y: Float) {}
    open fun onDown(x: Float, y: Float) {}
    open fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {}
    open fun onUp(x: Float, y: Float) {}
    open fun onSwipe(dir: Int) {}

    /** A directional press from a D-pad / TV remote / keyboard ([LEFT]/[RIGHT]/[UP]/[DOWN]).
     *  Separate from [onSwipe] so touch-swipe handling is never double-triggered. */
    open fun onKeyDir(dir: Int) {}

    /** The select / center / enter key (D-pad center, ENTER, gamepad A). */
    open fun onSelect() {}

    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val UP = 2
        const val DOWN = 3
    }

    // ----------------------------------------------------------------- setup

    override fun create() {
        cam = PerspectiveCamera(60f, sw.toFloat(), sh.toFloat()).apply {
            position.set(7f, 7f, 7f)
            lookAt(0f, 0f, 0f)
            near = 0.1f
            far = 400f
            update()
        }
        env = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.55f, 0.6f, 1f))
            add(DirectionalLight().set(0.85f, 0.85f, 0.8f, -0.45f, -0.85f, -0.35f))
            add(DirectionalLight().set(0.25f, 0.22f, 0.3f, 0.6f, -0.2f, 0.5f))
        }
        batch = ModelBatch()
        shapes = ShapeRenderer()

        Gdx.input.inputProcessor = object : InputAdapter() {
            private var downX = 0f
            private var downY = 0f
            private var lastX = 0f
            private var lastY = 0f
            private var downAt = 0L
            private var swiped = false
            private val swipeDist = sw * 0.085f
            private val tapSlop = sw * 0.03f

            override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                if (pointer != 0 || session.isOver) return false
                downX = x.toFloat(); downY = y.toFloat()
                lastX = downX; lastY = downY
                downAt = System.currentTimeMillis()
                swiped = false
                onDown(downX, downY)
                return true
            }

            override fun touchDragged(x: Int, y: Int, pointer: Int): Boolean {
                if (pointer != 0 || session.isOver) return false
                val fx = x.toFloat(); val fy = y.toFloat()
                onDrag(fx, fy, fx - lastX, fy - lastY)
                lastX = fx; lastY = fy
                if (!swiped) {
                    val dx = fx - downX
                    val dy = fy - downY
                    if (abs(dx) > swipeDist || abs(dy) > swipeDist) {
                        swiped = true
                        onSwipe(
                            if (abs(dx) > abs(dy)) { if (dx > 0) RIGHT else LEFT }
                            else { if (dy > 0) DOWN else UP }
                        )
                    }
                }
                return true
            }

            override fun touchUp(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                if (pointer != 0 || session.isOver) return false
                val fx = x.toFloat(); val fy = y.toFloat()
                onUp(fx, fy)
                if (!swiped && abs(fx - downX) < tapSlop && abs(fy - downY) < tapSlop &&
                    System.currentTimeMillis() - downAt < 350
                ) {
                    onTap(fx, fy)
                }
                return true
            }

            // D-pad / TV remote / keyboard. On game-over *and while paused* we don't
            // consume, so the HUD's focusable buttons (RESTART/EXIT, or the pause
            // menu's RESUME + sound/vibration toggles) get D-pad focus navigation
            // instead of the keys being eaten by the frozen game.
            override fun keyDown(keycode: Int): Boolean {
                if (session.isOver || session.isPaused) return false
                when (keycode) {
                    Keys.DPAD_LEFT -> onKeyDir(LEFT)
                    Keys.DPAD_RIGHT -> onKeyDir(RIGHT)
                    Keys.DPAD_UP -> onKeyDir(UP)
                    Keys.DPAD_DOWN -> onKeyDir(DOWN)
                    Keys.CENTER, Keys.ENTER, Keys.BUTTON_A, Keys.SPACE -> onSelect()
                    else -> return false
                }
                return true
            }
        }
        init()
    }

    // ----------------------------------------------------------------- frame

    override fun render() {
        val cpuT0 = System.nanoTime()
        // Paused (incl. resume countdown): keep drawing the frozen frame but advance nothing.
        val dt = if (session.isPaused) 0f else min(Gdx.graphics.deltaTime, 0.035f)
        time += dt
        val tickT0 = System.nanoTime()
        tick(dt)
        val tickNs = System.nanoTime() - tickT0
        updateShards(dt)

        Gdx.gl.glViewport(0, 0, sw, sh)
        Gdx.gl.glClearColor(bgBottom.r, bgBottom.g, bgBottom.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        // Gradient sky.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        shapes.projectionMatrix = uiMatrix.setToOrtho2D(0f, 0f, sw.toFloat(), sh.toFloat())
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.rect(0f, 0f, sw.toFloat(), sh.toFloat(), bgBottom, bgBottom, bgTop, bgTop)
        shapes.end()
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)

        val shaken = shakeMag > 0.005f
        if (shaken) {
            camSave.set(cam.position)
            cam.position.add(
                (rnd.nextFloat() * 2f - 1f) * shakeMag,
                (rnd.nextFloat() * 2f - 1f) * shakeMag,
                (rnd.nextFloat() * 2f - 1f) * shakeMag,
            )
            shakeMag *= (1f - 6.5f * dt).coerceAtLeast(0f)
        }
        cam.viewportWidth = sw.toFloat()
        cam.viewportHeight = sh.toFloat()
        cam.update()

        batch.begin(cam)
        val rwT0 = System.nanoTime()
        renderWorld(batch, env)
        val rwNs = System.nanoTime() - rwT0
        frameWorkMs = (tickNs + rwNs) / 1_000_000f
        renderShards(batch)
        batch.end()

        if (shaken) cam.position.set(camSave)

        if (flashColor.a > 0.004f) {
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
            shapes.projectionMatrix = uiMatrix.setToOrtho2D(0f, 0f, sw.toFloat(), sh.toFloat())
            shapes.begin(ShapeRenderer.ShapeType.Filled)
            shapes.setColor(flashColor)
            shapes.rect(0f, 0f, sw.toFloat(), sh.toFloat())
            shapes.end()
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
            flashColor.a = (flashColor.a - 2.6f * dt).coerceAtLeast(0f)
        }
        frameCpuMs = (System.nanoTime() - cpuT0) / 1_000_000f
    }

    private val uiMatrix = Matrix4()

    // ----------------------------------------------------------------- juice

    fun shake(mag: Float = 0.4f) {
        shakeMag = max(shakeMag, mag)
    }

    fun flash(color: Color = Color.WHITE, alpha: Float = 0.3f) {
        flashColor.set(color.r, color.g, color.b, max(flashColor.a, alpha))
    }

    private class Shard(
        val inst: ModelInstance,
        val vel: Vector3,
        val rotAxis: Vector3,
        val rotSpeed: Float,
        var life: Float,
        val maxLife: Float,
        val size: Float,
        val pos: Vector3,
        val blend: BlendingAttribute,
    )

    private val shards = ArrayList<Shard>()
    private var shardModel: Model? = null

    /** Cube-shard explosion at a world position. */
    fun burst3d(at: Vector3, color: Color, n: Int = 14, speed: Float = 6f, size: Float = 0.16f, life: Float = 0.8f) {
        val model = shardModel ?: box(1f, 1f, 1f, Color.WHITE).also { shardModel = it }
        repeat(n) {
            val inst = ModelInstance(model)
            val blend = BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f)
            inst.materials.first().set(ColorAttribute.createDiffuse(color), blend)
            val vel = Vector3(
                rnd.nextFloat() * 2f - 1f,
                rnd.nextFloat() * 1.6f - 0.3f,
                rnd.nextFloat() * 2f - 1f,
            ).nor().scl(speed * (0.4f + rnd.nextFloat() * 0.9f))
            val l = life * (0.5f + rnd.nextFloat() * 0.7f)
            shards.add(
                Shard(
                    inst, vel,
                    Vector3(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()).nor(),
                    (rnd.nextFloat() - 0.5f) * 720f,
                    l, l, size * (0.6f + rnd.nextFloat() * 0.9f),
                    Vector3(at), blend,
                )
            )
        }
        if (shards.size > 240) shards.subList(0, shards.size - 240).clear()
    }

    private fun updateShards(dt: Float) {
        var i = shards.size - 1
        while (i >= 0) {
            val s = shards[i]
            s.life -= dt
            if (s.life <= 0f) {
                shards.removeAt(i)
            } else {
                s.vel.y -= 14f * dt
                s.pos.mulAdd(s.vel, dt)
                val k = (s.life / s.maxLife).coerceIn(0f, 1f)
                s.blend.opacity = k
                s.inst.transform.idt()
                    .translate(s.pos)
                    .rotate(s.rotAxis, s.rotSpeed * (s.maxLife - s.life))
                    .scale(s.size * (0.4f + 0.6f * k), s.size * (0.4f + 0.6f * k), s.size * (0.4f + 0.6f * k))
            }
            i--
        }
    }

    private fun renderShards(batch: ModelBatch) {
        for (s in shards) batch.render(s.inst, env)
    }

    // ----------------------------------------------------------- model utils

    private val attrs = (Usage.Position or Usage.Normal).toLong()

    fun mat(color: Color): Material = Material(ColorAttribute.createDiffuse(color))

    fun box(w: Float, h: Float, d: Float, color: Color): Model =
        mb.createBox(w, h, d, mat(color), attrs).also { owned.add(it) }

    fun sphere(diameter: Float, color: Color, div: Int = 20): Model =
        mb.createSphere(diameter, diameter, diameter, div, div, mat(color), attrs).also { owned.add(it) }

    fun cylinder(diameter: Float, height: Float, color: Color, div: Int = 24): Model =
        mb.createCylinder(diameter, height, diameter, div, mat(color), attrs).also { owned.add(it) }

    fun cone(diameter: Float, height: Float, color: Color, div: Int = 16): Model =
        mb.createCone(diameter, height, diameter, div, mat(color), attrs).also { owned.add(it) }

    /** Bright HSV color helper; hue in degrees. */
    fun gdxHsv(h: Float, s: Float = 0.75f, v: Float = 1f): Color {
        val hh = (((h % 360f) + 360f) % 360f) / 60f
        val i = hh.toInt() % 6
        val f = hh - hh.toInt()
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        return when (i) {
            0 -> Color(v, t, p, 1f)
            1 -> Color(q, v, p, 1f)
            2 -> Color(p, v, t, 1f)
            3 -> Color(p, q, v, 1f)
            4 -> Color(t, p, v, 1f)
            else -> Color(v, p, q, 1f)
        }
    }

    override fun dispose() {
        batch.dispose()
        shapes.dispose()
        owned.forEach { it.dispose() }
        owned.clear()
    }
}
