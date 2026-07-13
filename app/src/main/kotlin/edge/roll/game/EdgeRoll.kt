package edge.roll.game

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.Gdx
import edge.roll.core.BoxBatch
import edge.roll.core.Gdx3DGame
import edge.roll.core.GameSession
import edge.roll.core.Haptics
import edge.roll.core.SoundFx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Edge Roll — swipe to tumble a cube 90° over its edge across a floating
 * bridge of tiles. Tiles crumble and drop into the abyss ~1.2s after you
 * leave them (faster as score climbs), and loitering makes the tile under
 * you give way too. The bridge ahead wiggles, branches into gem spurs and
 * has holes. Score = new tiles traversed, gems pay +3.
 */
class EdgeRoll(session: GameSession) : Gdx3DGame(session) {

    private class Tile(val gx: Int, val gz: Int, val inst: ModelInstance,
                       val colA: ColorAttribute, val blend: BlendingAttribute,
                       val baseCol: Color, var gem: Boolean, val gemInst: ModelInstance?,
                       val phase: Float, val fallAxisX: Boolean, val spin: Float) {
        var state = ALIVE
        var timer = 0f; var total = 1f      // crumble countdown after cube leaves
        var visited = false
        var fy = 0f; var fvy = 0f; var rot = 0f; var life = 0.9f
        var vis = true                      // in view frustum this frame (set by updateTiles)
        var spawn = 1f                      // grow-in progress 0->1 (1 = fully materialized)
        var rscale = 1f                     // current render scale (driven by spawn)
        var gemCol: ColorAttribute? = null  // gem's diffuse attr, colour-faded from the sky during spawn
    }

    private lateinit var tileModel: Model
    private lateinit var cubeModel: Model
    private lateinit var studModel: Model
    private lateinit var gemModel: Model
    private lateinit var cubeInst: ModelInstance
    private lateinit var studTop: ModelInstance
    private lateinit var studFront: ModelInstance
    private lateinit var cubeCol: ColorAttribute

    private val tiles = ArrayList<Tile>()
    private val map = HashMap<Long, Tile>()
    private lateinit var tileBatch: BoxBatch   // all opaque tiles → one draw call

    private var cubeGx = 0
    private var cubeGz = 0
    private var rolling = false
    private var rollFatal = false           // this roll goes into the void → tumble straight into the fall
    private var rollT = 0f
    private var rollDur = 0.15f
    private var dirX = 0
    private var dirZ = 0
    private val orient = Quaternion()       // accumulated cube orientation
    private val tmpQ = Quaternion()
    private val axis = Vector3(0f, 0f, -1f) // current tumble axis (= up x dir)
    private var hasQueued = false; private var qx = 0; private var qz = 0
    private var squash = 0f                 // landing squash 1 -> 0
    private var chain = 0                   // fast-roll combo for pitch riser
    private var lastRoll = -9f
    private var started = false
    private var dwell = 0f                  // time idling on current tile
    private var dwellWarned = false
    private var dying = false
    private var deathPending = false        // cube is falling; game-over punctuation still to come
    private var deathT = 0f                 // seconds since the cube fell off (see DEATH_DELAY)
    private val deadPos = Vector3(); private val deadVel = Vector3()
    private val deadAxis = Vector3(1f, 0f, 0f)
    private var deadRot = 0f; private var deadSpin = 0f
    private var spawnAnim = false           // new tiles fade/grow in once the run is live
    private var genX = 0; private var genZ = -1
    private var hue = (System.currentTimeMillis() % 360L).toFloat()
    private var nextMilestone = 20
    private var lastFallFx = -9f
    private val camPos = Vector3(0f, 7.3f, 6.3f)
    private val camLook = Vector3(0f, 0.4f, -2.2f)
    private val tmpV = Vector3(); private val tmpV2 = Vector3()
    private var downX = 0f; private var downY = 0f; private var consumed = true

    // ---- benchmark mode: set via intent extras before create() (see GameActivity) ----
    var benchmark = false
    var benchWidth = 9
    var benchDepth = 26
    var benchSecs = 38f
    var benchWarmup = 22f     // seconds skipped before collecting (debug-build JIT warms ~20s under throttle)
    private var tileCap = 340   // headroom for the deeper render distance (RENDER_AHEAD)
    // Ablation toggles (adb `setprop debug.edgeroll.nobatch 1` / `.nocull 1`) — for
    // benchmarking the contribution of each optimization; both off in normal play.
    private var noBatch = false
    private var noCull = false
    private var lastDrawn = 0
    private var benchElapsed = 0f
    private var benchWinT = 0f
    private val benchAll = ArrayList<Float>()   // all post-warmup wall-clock frame times (ms)
    private val benchWin = ArrayList<Float>()   // current 1s window
    private val benchCpuAll = ArrayList<Float>() // per-frame CPU time (excludes vsync wait)
    private var benchDone = false

    private fun crumbleTime() = max(0.42f, 1.2f - session.score * 0.01f)
    private fun dwellLimit() = crumbleTime() + 0.8f

    override fun init() {
        tileModel = box(0.96f, 0.26f, 0.96f, Color.WHITE)
        cubeModel = box(0.92f, 0.92f, 0.92f, Color.WHITE)
        studModel = box(0.34f, 0.1f, 0.34f, Color(0.09f, 0.09f, 0.14f, 1f))
        gemModel = box(0.3f, 0.42f, 0.3f, Color.WHITE)
        cubeInst = ModelInstance(cubeModel)
        cubeCol = ColorAttribute.createDiffuse(gdxHsv(hue + 180f, 0.5f, 1f))
        cubeInst.materials.first().set(cubeCol)
        studTop = ModelInstance(studModel)   // die-pip studs make the tumble readable
        studFront = ModelInstance(studModel)
        bgTop = gdxHsv(hue + 30f, 0.5f, 0.4f)
        bgBottom = gdxHsv(hue + 75f, 0.75f, 0.05f)  // near-black abyss
        if (benchmark) {
            buildBenchScene()
        } else {
            for (x in -1..1) for (z in -1..1) addTile(x, z, false)
            generate()
        }
        noBatch = boolProp("debug.edgeroll.nobatch")
        noCull = boolProp("debug.edgeroll.nocull")
        tileBatch = BoxBatch(0.96f, 0.26f, 0.96f, tileCap + 8)
        cam.position.set(camPos)
        cam.lookAt(camLook)
        cam.update()
        spawnAnim = !benchmark   // from here on, freshly generated tiles animate in
        if (!benchmark) session.banner("SWIPE TO ROLL")
    }

    /** Deterministic static stress field for repeatable renderer benchmarks. */
    private fun buildBenchScene() {
        tileCap = benchWidth * benchDepth + 16
        val halfW = benchWidth / 2
        for (gz in 0 downTo -(benchDepth - 1))
            for (gx in -halfW..halfW)
                addTile(gx, gz, false)
        Gdx.app.log(BENCH, "SCENE tiles=${tiles.size} width=$benchWidth depth=$benchDepth secs=$benchSecs")
    }

    private fun addTile(gx: Int, gz: Int, gem: Boolean) {
        if (map.containsKey(key(gx, gz)) || tiles.size > tileCap) return
        val base = if (gem) Color(1f, 0.82f, 0.25f, 1f) else gdxHsv(hue - gz * 5f, 0.62f, 0.95f)
        val inst = ModelInstance(tileModel)
        val ca = ColorAttribute.createDiffuse(base)
        inst.materials.first().set(ca)
        val bl = BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f)
        val gemCa: ColorAttribute?
        val gi: ModelInstance?
        if (gem) {
            gemCa = ColorAttribute.createDiffuse(GEM_BASE)
            gi = ModelInstance(gemModel).apply { materials.first().set(gemCa) }
        } else {
            gemCa = null
            gi = null
        }
        val t = Tile(gx, gz, inst, ca, bl, base, gem, gi, MathUtils.random(6.28f),
            MathUtils.randomBoolean(),
            MathUtils.random(120f, 260f) * (if (MathUtils.randomBoolean()) 1f else -1f))
        t.gemCol = gemCa
        t.spawn = if (spawnAnim) 0f else 1f   // tiles laid down mid-run grow in; the start field is instant
        tiles.add(t)
        map[key(gx, gz)] = t
    }

    /** Random-walk main path (always connected) + holey side pads + gem spurs. */
    private fun generate() {
        var guard = 0
        while (genZ > cubeGz - RENDER_AHEAD && guard++ < 600 && tiles.size < tileCap) {
            val dist = -genZ
            if (MathUtils.randomBoolean(min(0.42f, 0.2f + dist * 0.002f))) {
                // lateral wiggle (more frequent the further you get)
                genX += if (genX <= -3) 1 else if (genX >= 3) -1
                        else if (MathUtils.randomBoolean()) 1 else -1
                addTile(genX, genZ, MathUtils.randomBoolean(0.03f))
            } else {
                genZ--
                addTile(genX, genZ, MathUtils.randomBoolean(0.04f))
                // widening pad; the absences read as gaps in the bridge
                if (MathUtils.randomBoolean(max(0.16f, 0.5f - dist * 0.003f)))
                    addTile(genX + (if (MathUtils.randomBoolean()) 1 else -1), genZ,
                        MathUtils.randomBoolean(0.05f))
                // gem spur: short risky dead-end branch with a gem at the tip
                if (MathUtils.randomBoolean(0.1f)) {
                    val side = if (MathUtils.randomBoolean()) 1 else -1
                    val len = MathUtils.random(1, 2)
                    for (i in 1..len) addTile(genX + side * i, genZ, i == len)
                }
            }
        }
    }

    // ---- input: own swipe detection for snappy buffered rolls ----

    override fun onDown(x: Float, y: Float) { downX = x; downY = y; consumed = false }

    override fun onDrag(x: Float, y: Float, dx: Float, dy: Float) {
        if (consumed) return
        val mx = x - downX; val my = y - downY
        if (max(abs(mx), abs(my)) < sw * 0.055f) return
        consumed = true
        swipeTo(mx, my)
    }

    override fun onUp(x: Float, y: Float) {
        if (consumed) return
        consumed = true
        val mx = x - downX; val my = y - downY
        if (max(abs(mx), abs(my)) >= sw * 0.045f) swipeTo(mx, my)
    }

    override fun onTap(x: Float, y: Float) = tryRoll(0, -1) // tap = roll forward

    // TV remote / D-pad / keyboard (absolute directions, same as a swipe).
    override fun onKeyDir(dir: Int) = when (dir) {
        Gdx3DGame.LEFT -> tryRoll(-1, 0)
        Gdx3DGame.RIGHT -> tryRoll(1, 0)
        Gdx3DGame.UP -> tryRoll(0, -1)   // up = forward, into the screen
        else -> tryRoll(0, 1)            // DOWN = backward
    }

    override fun onSelect() = tryRoll(0, -1) // center/enter = roll forward

    private fun swipeTo(mx: Float, my: Float) {
        if (abs(mx) > abs(my)) tryRoll(if (mx > 0) 1 else -1, 0)
        else tryRoll(0, if (my < 0) -1 else 1)
    }

    private fun tryRoll(dx: Int, dz: Int) {
        if (session.isOver || dying || session.isPaused) return
        if (rolling) { qx = dx; qz = dz; hasQueued = true; return }
        startRoll(dx, dz)
    }

    private fun startRoll(dx: Int, dz: Int) {
        if (!started) { started = true; session.runStarted() }   // first move: hide pre-run options
        rolling = true
        rollT = 0f
        dirX = dx; dirZ = dz
        axis.set(dz.toFloat(), 0f, -dx.toFloat())
        // A roll toward a missing / already-falling tile is fatal: roll it LINEARLY (no easeOut
        // deceleration) so its angular speed at 90° carries straight into the fall — the tumble
        // over the edge and the drop read as one continuous motion (see finishRoll / updateCube).
        val dest = map[key(cubeGx + dx, cubeGz + dz)]
        rollFatal = dest == null || dest.state == FALLING
        // leaving the tile arms its crumble timer
        map[key(cubeGx, cubeGz)]?.let {
            if (it.state == ALIVE) { it.state = CRUMBLE; it.total = crumbleTime(); it.timer = it.total }
        }
        rollDur = max(0.115f, 0.155f - session.score * 0.0004f)
        Haptics.tick()
        SoundFx.play("whoosh", rate = 1.6f, vol = 0.16f)
    }

    private fun finishRoll() {
        rolling = false
        cubeGx += dirX
        cubeGz += dirZ
        tmpQ.setFromAxis(axis, 90f)
        orient.mulLeft(tmpQ).nor()
        val t = map[key(cubeGx, cubeGz)]
        if (t == null || t.state == FALLING) {
            // Rolled over the edge. Hand the roll's angular + linear velocity straight to the fall
            // so the two are one continuous motion — no decelerate-then-relaunch hitch at the seam.
            val wDeg = 90f / rollDur                                  // roll's angular speed at the seam
            val wRad = wDeg * MathUtils.degreesToRadians
            tmpV.set(dirX * 0.5f, CUBE_Y - TILE_TOP, dirZ * 0.5f)     // cube-center offset from pivot edge
            tmpV2.set(axis).crs(tmpV).scl(wRad)                       // tangential center velocity = ω × r
            die(tmpV2.x, tmpV2.y, tmpV2.z, axis, wDeg)
            return
        }
        squash = 1f
        chain = if (time - lastRoll < 0.5f) min(chain + 1, 16) else 1
        lastRoll = time
        dwell = 0f
        dwellWarned = false
        SoundFx.play("place", rate = min(1.8f, 0.95f + chain * 0.04f))
        Haptics.click()
        burst3d(Vector3(cubeGx.toFloat(), TILE_TOP + 0.08f, cubeGz.toFloat()), t.baseCol,
            n = 4, speed = 2.2f, size = 0.07f, life = 0.4f)
        if (!t.visited) { t.visited = true; session.addScore(1) }
        if (t.gem) {
            t.gem = false
            session.addScore(3)
            SoundFx.play("coin", rate = min(1.6f, 1f + chain * 0.03f))
            Haptics.heavy()
            burst3d(Vector3(cubeGx.toFloat(), 0.9f, cubeGz.toFloat()), Color(1f, 0.9f, 0.35f, 1f),
                n = 16, speed = 5f, size = 0.1f, life = 0.9f)
            flash(Color(1f, 0.85f, 0.3f, 1f), 0.1f)
        }
        if (session.score >= nextMilestone) {
            session.banner("$nextMilestone!")
            SoundFx.play("rise")
            nextMilestone = if (nextMilestone < 50) 50 else nextMilestone * 2
        }
        // hue drift with progress (cube, sky, new tiles)
        cubeCol.color.set(gdxHsv(hue + 180f - cubeGz * 1.5f, 0.5f, 1f))
        bgTop = gdxHsv(hue + 30f - cubeGz * 1.2f, 0.5f, 0.4f)
        bgBottom = gdxHsv(hue + 75f - cubeGz * 1.2f, 0.75f, 0.05f)
        generate()
        if (hasQueued) { hasQueued = false; startRoll(qx, qz) }
    }

    private fun die(vx: Float, vy: Float, vz: Float, ax: Vector3, spin: Float) {
        if (dying) return
        dying = true
        deathPending = true
        deathT = 0f
        hasQueued = false
        cubeInst.transform.getTranslation(deadPos)   // start the fall from the exact current center
        deadVel.set(vx, vy, vz)
        deadAxis.set(ax)
        if (deadAxis.len2() < 0.001f) deadAxis.set(1f, 0f, 0f)
        deadSpin = spin
        deadRot = 0f
        // The cube tips over and keeps falling from here, kicking up debris. The heavy death
        // punctuation (boom + shake + red flash + game-over card) is held back DEATH_DELAY
        // seconds by tick()/triggerDeath so you get to watch the cube drop into the abyss.
        SoundFx.play("whoosh", rate = 0.55f, vol = 0.4f)
        Haptics.tick()
        burst3d(Vector3(deadPos), Color(1f, 0.45f, 0.3f, 1f), n = 20, speed = 6f, size = 0.13f, life = 1f)
    }

    /** After [DEATH_DELAY] of watching the cube fall, deliver the impact + game-over card. */
    private fun triggerDeath() {
        deathPending = false
        SoundFx.play("boom")
        Haptics.heavy()
        shake(0.7f)
        flash(Color.RED, 0.28f)
        session.gameOver()
    }

    private fun startFall(t: Tile) {
        if (t.state == FALLING) return
        t.state = FALLING
        t.fvy = -0.6f
        t.life = 0.9f
        t.inst.materials.first().set(t.blend)  // becomes fade-able now
        if (abs(t.gx - cubeGx) + abs(t.gz - cubeGz) <= 4 && time - lastFallFx > 0.12f) {
            lastFallFx = time
            SoundFx.play("pop", rate = 0.65f, vol = 0.45f)
            burst3d(Vector3(t.gx.toFloat(), 0.05f, t.gz.toFloat()), t.baseCol,
                n = 3, speed = 1.8f, size = 0.06f, life = 0.4f)
        }
        // the ground gave way underfoot
        if (!dying && !session.isOver && !rolling && t.gx == cubeGx && t.gz == cubeGz)
            die(0f, 0.4f, 0f, tmpV.set(0.8f, 0f, 0.6f), 170f)
    }

    override fun tick(dt: Float) {
        if (benchmark) { benchTick(dt); return }
        if (dying && deathPending && !session.isPaused) {   // watch the cube fall, then punctuate
            deathT += dt
            if (deathT >= DEATH_DELAY) triggerDeath()
        }
        if (!session.isOver && !session.isPaused) {
            if (rolling) {
                rollT += dt / rollDur
                if (rollT >= 1f) finishRoll()
            } else if (started && !dying) {
                dwell += dt
                val rem = dwellLimit() - dwell
                if (rem < 0.55f && !dwellWarned) {
                    dwellWarned = true
                    SoundFx.play("tick", rate = 1.5f, vol = 0.6f)
                    Haptics.tick()
                }
                if (rem <= 0f)
                    map[key(cubeGx, cubeGz)]?.let { startFall(it) }
                        ?: die(0f, 0.4f, 0f, deadAxis, 150f)
            }
        }
        squash = max(0f, squash - dt * 6f)
        updateCube(dt)
        updateTiles(dt)
        updateCam(dt)
    }

    private fun updateCube(dt: Float) {
        if (dying) {
            deadVel.y -= 26f * dt
            if (deadPos.y > -70f) deadPos.mulAdd(deadVel, dt)
            deadRot += deadSpin * dt
            cubeInst.transform.setToTranslation(deadPos).rotate(deadAxis, deadRot).rotate(orient)
        } else if (rolling) {
            val t = min(1f, rollT)
            // Normal roll eases out for a firm landing; a fatal roll stays linear so its angular
            // speed at the edge matches the fall it hands off to (no hitch — see finishRoll).
            val ang = if (rollFatal) 90f * t else 90f * (1f - (1f - t) * (1f - t))
            cubeInst.transform
                .setToTranslation(cubeGx + dirX * 0.5f, TILE_TOP, cubeGz + dirZ * 0.5f) // pivot edge
                .rotate(axis, ang)
                .translate(-dirX * 0.5f, CUBE_Y - TILE_TOP, -dirZ * 0.5f)
                .rotate(orient)
        } else {
            val tile = map[key(cubeGx, cubeGz)]
            val bob = if (tile != null && tile.state != FALLING)
                sin(time * 1.7f + tile.phase) * 0.045f else 0f
            val s = squash
            cubeInst.transform
                .setToTranslation(cubeGx.toFloat(), CUBE_Y + bob - 0.09f * s, cubeGz.toFloat())
                .scale(1f + 0.13f * s, 1f - 0.2f * s, 1f + 0.13f * s)
                .rotate(orient)
        }
        studTop.transform.set(cubeInst.transform).translate(0f, 0.48f, 0f)
        studFront.transform.set(cubeInst.transform).translate(0f, 0f, -0.48f).rotate(Vector3.X, 90f)
    }

    private fun updateTiles(dt: Float) {
        val dl = dwellLimit()
        val fr = cam.frustum
        var i = tiles.size - 1
        while (i >= 0) {
            val t = tiles[i]
            if (t.gz > cubeGz + 5 || (t.state == FALLING && t.life <= 0f)) {
                map.remove(key(t.gx, t.gz))
                tiles.removeAt(i); i--; continue
            }
            // Frustum visibility (grid-based, stable for non-falling tiles). An off-screen
            // ALIVE tile animates to nothing visible, so skip all its per-frame work — this
            // is what makes a large render distance cheap. Reused by renderWorld.
            t.vis = noCull || t.state == FALLING || fr.sphereInFrustum(t.gx.toFloat(), 0f, t.gz.toFloat(), TILE_CULL_R)
            if (!t.vis && t.state == ALIVE) { i--; continue }
            val bob = sin(time * 1.7f + t.phase) * 0.045f
            val c = t.colA.color
            when (t.state) {
                ALIVE -> if (t.spawn >= 1f) {                        // settled: cheap path (no scale/lerp)
                    t.inst.transform.setToTranslation(t.gx.toFloat(), bob, t.gz.toFloat())
                    c.set(t.baseCol)
                } else {                                             // materializing: grow + rise + colour-fade
                    t.spawn = min(1f, t.spawn + dt / SPAWN_DUR)
                    val e = 1f - (1f - t.spawn) * (1f - t.spawn)     // easeOutQuad
                    t.rscale = 0.12f + 0.88f * e
                    t.inst.transform
                        .setToTranslation(t.gx.toFloat(), bob - (1f - e) * 0.45f, t.gz.toFloat())
                        .scale(t.rscale, t.rscale, t.rscale)
                    c.set(t.baseCol).lerp(bgTop, 1f - e)             // out of the sky colour
                }
                CRUMBLE -> {
                    t.timer -= dt
                    val p = 1f - max(0f, t.timer) / t.total
                    val j = sin(time * 41f + t.phase) * 0.05f * p  // crumble jitter
                    t.inst.transform.setToTranslation(t.gx + j, bob - 0.14f * p, t.gz + j * 0.6f)
                    c.set(t.baseCol).mul(1f - 0.45f * p, 1f - 0.45f * p, 1f - 0.45f * p, 1f)
                    if (t.timer < 0.3f && sin(time * 50f) > 0f) c.lerp(1f, 0.25f, 0.2f, 1f, 0.55f)
                    if (t.timer <= 0f) startFall(t)
                }
                else -> { // FALLING: gravity + spin + fade
                    t.fvy -= 22f * dt
                    t.fy += t.fvy * dt
                    t.rot += t.spin * dt
                    t.life -= dt
                    t.blend.opacity = max(0f, t.life / 0.9f)
                    t.inst.transform.setToTranslation(t.gx.toFloat(), t.fy, t.gz.toFloat())
                        .rotate(if (t.fallAxisX) Vector3.X else Vector3.Z, t.rot)
                }
            }
            // dwell pressure: tile underfoot pulses red as time runs out
            if (started && !dying && !rolling && t.gx == cubeGx && t.gz == cubeGz &&
                t.state != FALLING && dl - dwell < 0.6f)
                c.lerp(1f, 0.2f, 0.18f, 1f, 0.3f + 0.3f * sin(time * 26f))
            if (t.gem && t.gemInst != null) {
                // Materialize the gem in lockstep with its tile's spawn (grow + rise + colour-fade
                // out of the sky) instead of popping it in at full size at the render edge. e == 1
                // once settled, so a fully-grown gem's transform/colour are exactly as before.
                val e = 1f - (1f - t.spawn) * (1f - t.spawn)
                t.gemInst.transform
                    .setToTranslation(t.gx.toFloat(), 0.62f * e + bob * 1.6f - (1f - e) * 0.45f, t.gz.toFloat())
                    .rotate(Vector3.Y, time * 150f + t.phase * 50f)
                    .rotate(Vector3.X, 35f)
                    .scale(t.rscale, t.rscale, t.rscale)
                t.gemCol?.color?.set(GEM_BASE)?.lerp(bgTop, 1f - e)
            }
            i--
        }
    }

    private fun updateCam(dt: Float) {
        val fx: Float; val fy: Float; val fz: Float
        if (dying) { fx = deadPos.x; fy = deadPos.y; fz = deadPos.z }
        else { fx = cubeGx.toFloat(); fy = 0.4f; fz = cubeGz.toFloat() }
        val k = min(1f, dt * 5f)
        camPos.lerp(tmpV.set(fx * 0.8f, 7.3f, fz + 6.3f), k)
        camLook.lerp(tmpV2.set(fx * 0.9f, fy, fz - 2.2f), if (dying) min(1f, dt * 3f) else k)
        cam.position.set(camPos)
        cam.up.set(0f, 1f, 0f)
        cam.lookAt(camLook.x, camLook.y, camLook.z)
    }

    override fun renderWorld(batch: ModelBatch, env: Environment) {
        var drawn = 0
        tileBatch.begin()
        for (i in tiles.indices) {
            val t = tiles[i]
            if (!t.vis) continue                            // frustum-culled in updateTiles
            if (noBatch || t.state == FALLING) {
                batch.render(t.inst, env); drawn++          // per-tile (ablation) / fading blended draw
            } else {
                // opaque ALIVE/CRUMBLE tile → batched into one mesh (colour incl. tint/pulse)
                t.inst.transform.getTranslation(tmpV)
                tileBatch.add(tmpV.x, tmpV.y, tmpV.z, t.colA.color.toFloatBits(), t.rscale)
            }
            if (t.gem && t.gemInst != null) { batch.render(t.gemInst, env); drawn++ }
        }
        if (!noBatch && tileBatch.count() > 0) { tileBatch.flush(batch, env); drawn++ }  // every opaque tile: 1 call
        // Keep drawing the falling cube until it reaches its rest depth (-70, where updateCube
        // stops it) so it stays on-screen right up to the game-over card instead of blinking out
        // early into empty abyss.
        if (!dying || deadPos.y > -72f) {
            batch.render(cubeInst, env)
            batch.render(studTop, env)
            batch.render(studFront, env)
            drawn += 3
        }
        lastDrawn = drawn
    }

    override fun dispose() {
        if (::tileBatch.isInitialized) tileBatch.dispose()
        super.dispose()
    }

    // ------------------------------------------------------- benchmark harness

    private fun benchTick(dt: Float) {
        val raw = Gdx.graphics.rawDeltaTime
        benchElapsed += raw
        if (benchElapsed > benchWarmup && !benchDone) {   // skip warmup / shader-compile spikes
            val ms = raw * 1000f
            benchAll.add(ms); benchWin.add(ms); benchCpuAll.add(frameWorkMs)
            benchWinT += raw
            if (benchWinT >= 1f) { logStats("win", benchWin); benchWin.clear(); benchWinT = 0f }
        }
        if (benchElapsed >= benchSecs && !benchDone) {
            benchDone = true
            logStats("WORK", benchCpuAll)     // stall-free CPU work/frame (tick+build); before RESULT sentinel
            logStats("RESULT", benchAll)
            Gdx.app.exit()
        }
        // exercise the same per-frame update + render path as gameplay (minus input/death)
        updateCube(dt)
        updateTiles(dt)
        updateCam(dt)
    }

    private fun logStats(kind: String, ms: ArrayList<Float>) {
        if (ms.isEmpty()) return
        val sorted = ms.toFloatArray(); sorted.sort()
        val n = sorted.size
        var sum = 0f; for (v in sorted) sum += v
        val avg = sum / n
        fun pct(p: Float) = sorted[min(n - 1, ceil(p * (n - 1)).toInt())]
        val p50 = pct(0.5f); val p95 = pct(0.95f); val p99 = pct(0.99f)
        Gdx.app.log(BENCH, "$kind frames=$n avgMs=${f2(avg)} fps=${f2(1000f / avg)} " +
            "p50=${f2(p50)} p95=${f2(p95)} p99=${f2(p99)} minFps=${f2(1000f / p99)} " +
            "tiles=${tiles.size} drawn=$lastDrawn")
    }

    private fun f2(v: Float) = (kotlin.math.round(v * 100f) / 100f).toString()

    private fun boolProp(k: String): Boolean = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, k) as String) == "1"
    } catch (e: Throwable) { false }

    private companion object {
        const val BENCH = "EdgeBench"
        const val TILE_CULL_R = 0.9f   // bounding-sphere radius for frustum culling a tile
        const val RENDER_AHEAD = 28    // tiles generated/visible ahead of the cube (was 16)
        const val SPAWN_DUR = 0.34f    // seconds for a freshly generated tile to grow in
        val GEM_BASE = Color(1f, 0.92f, 0.35f, 1f)   // gem's settled amber (read-only; faded from the sky on spawn)
        const val DEATH_DELAY = 2f     // seconds to watch the cube fall before the game-over card
        const val ALIVE = 0; const val CRUMBLE = 1; const val FALLING = 2
        const val CUBE_Y = 0.6f    // resting cube center height
        const val TILE_TOP = 0.13f // tile top surface / tumble pivot height
        fun key(gx: Int, gz: Int) = (gx.toLong() shl 32) xor (gz.toLong() and 0xffffffffL)
    }
}
