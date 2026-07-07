package edge.roll.core

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.utils.Disposable

/**
 * Batches many equal-sized, axis-aligned, **translation-only** boxes (the bridge
 * tiles) into a SINGLE dynamic mesh and ONE [ModelBatch] draw call, with each
 * box's colour baked into a packed vertex-colour attribute.
 *
 * Why: Edge Roll is *draw-call CPU-bound* under load — issuing ~230 individual
 * `ModelBatch.render()` calls per frame (one per tile, each with its own colour
 * material) dominates the frame on a weak CPU. Collapsing them to one renderable
 * removes almost all of that per-object CPU overhead while keeping the exact same
 * lit look (white material diffuse × per-vertex colour × the scene lights).
 *
 * Rotating boxes or boxes needing per-object alpha (falling/fading tiles) can't
 * share one opaque mesh — render those individually.
 */
class BoxBatch(w: Float, h: Float, d: Float, private val maxBoxes: Int) : Disposable {

    private val mesh = Mesh(
        false, maxBoxes * VPB, maxBoxes * IPB,
        VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked()
    )
    private val basePos = FloatArray(VPB * 3)
    private val baseNorm = FloatArray(VPB * 3)
    private val verts = FloatArray(maxBoxes * VPB * FLOATS)
    private var n = 0

    private val material = Material(
        ColorAttribute.createDiffuse(Color.WHITE),
        IntAttribute.createCullFace(GL20.GL_NONE)  // winding-agnostic; tiles are cheap to double-draw
    )
    private val renderable = Renderable()

    init {
        val hx = w / 2f; val hy = h / 2f; val hz = d / 2f
        // Only the faces ever visible from Edge Roll's fixed high-front camera: top (+Y),
        // front (+Z, toward cam) and the two sides (±X). The bottom (-Y) and back (-Z)
        // faces are never seen, so we don't build them — 33% less geometry per tile, with
        // zero visible difference. 4 faces × 4 cyclic corners, outward normal per face.
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f,  hx, -hy, -hz,  hx, -hy, hz,  hx, hy, hz,  hx, hy, -hz),
            floatArrayOf(-1f, 0f, 0f, -hx, -hy, hz, -hx, -hy, -hz, -hx, hy, -hz, -hx, hy, hz),
            floatArrayOf(0f, 1f, 0f,  -hx, hy, -hz,  hx, hy, -hz,  hx, hy, hz,  -hx, hy, hz),
            floatArrayOf(0f, 0f, 1f,  -hx, -hy, hz,  hx, -hy, hz,  hx, hy, hz,  -hx, hy, hz),
        )
        var v = 0
        for (f in faces) {
            for (c in 0 until 4) {
                basePos[v * 3] = f[3 + c * 3]; basePos[v * 3 + 1] = f[4 + c * 3]; basePos[v * 3 + 2] = f[5 + c * 3]
                baseNorm[v * 3] = f[0]; baseNorm[v * 3 + 1] = f[1]; baseNorm[v * 3 + 2] = f[2]
                v++
            }
        }
        // Static index buffer: two triangles per face, per box, offset by box index.
        val idx = ShortArray(maxBoxes * IPB)
        for (b in 0 until maxBoxes) {
            val vo = b * VPB; val io = b * IPB
            for (fc in 0 until FACES) {
                val q = (vo + fc * 4).toShort(); val o = io + fc * 6
                idx[o] = q; idx[o + 1] = (q + 1).toShort(); idx[o + 2] = (q + 2).toShort()
                idx[o + 3] = (q + 2).toShort(); idx[o + 4] = (q + 3).toShort(); idx[o + 5] = q
            }
        }
        mesh.setIndices(idx)
        renderable.meshPart.mesh = mesh
        renderable.meshPart.primitiveType = GL20.GL_TRIANGLES
        renderable.meshPart.offset = 0
        renderable.material = material
        renderable.worldTransform.idt()
    }

    fun begin() { n = 0 }

    /**
     * Add one box translated to (tx,ty,tz), coloured [packed] (from Color.toFloatBits()),
     * optionally uniformly [scale]d about its centre (for spawn grow-in animations).
     */
    fun add(tx: Float, ty: Float, tz: Float, packed: Float, scale: Float = 1f) {
        if (n >= maxBoxes) return
        var p = n * VPB * FLOATS
        var bi = 0
        if (scale == 1f) {                       // fast path: settled tiles (the vast majority)
            for (i in 0 until VPB) {
                verts[p] = basePos[bi] + tx
                verts[p + 1] = basePos[bi + 1] + ty
                verts[p + 2] = basePos[bi + 2] + tz
                verts[p + 3] = baseNorm[bi]; verts[p + 4] = baseNorm[bi + 1]; verts[p + 5] = baseNorm[bi + 2]
                verts[p + 6] = packed
                p += FLOATS; bi += 3
            }
        } else {                                 // only tiles mid grow-in
            for (i in 0 until VPB) {
                verts[p] = basePos[bi] * scale + tx
                verts[p + 1] = basePos[bi + 1] * scale + ty
                verts[p + 2] = basePos[bi + 2] * scale + tz
                verts[p + 3] = baseNorm[bi]; verts[p + 4] = baseNorm[bi + 1]; verts[p + 5] = baseNorm[bi + 2]
                verts[p + 6] = packed
                p += FLOATS; bi += 3
            }
        }
        n++
    }

    /** Upload the accumulated boxes and enqueue them as one renderable. */
    fun flush(batch: ModelBatch, env: Environment) {
        if (n == 0) return
        mesh.setVertices(verts, 0, n * VPB * FLOATS)
        renderable.meshPart.offset = 0
        renderable.meshPart.size = n * IPB
        renderable.environment = env
        batch.render(renderable)
    }

    fun count() = n

    override fun dispose() = mesh.dispose()

    private companion object {
        const val FLOATS = 7   // x y z  nx ny nz  colorPacked
        const val FACES = 4    // visible faces only: +Y top, +Z front, ±X sides
        const val VPB = FACES * 4    // vertices per box (4 per face)
        const val IPB = FACES * 6    // indices per box (2 triangles per face)
    }
}
