package hivens.ui.skin3d

import hivens.ui.render3d.Texture
import hivens.ui.render3d.Tri
import hivens.ui.render3d.Vtx
import hivens.ui.render3d.rasterize
import hivens.ui.scene3d.Face
import hivens.ui.scene3d.Vec3
import hivens.ui.scene3d.collectTriBatches
import hivens.ui.scene3d.project
import hivens.ui.scene3d.rotate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// The gold pin for the rig migration: an identity-pose rig fed through the
// scene traversal must rasterize BIT-IDENTICAL to the flat pre-rig renderer.
// This is what licenses the per-part node structure (and its different face
// emission order) -- any depth-tie winner flipped by the reordering, any
// vertex drifting a ulp through the transform path, shows up here as a pixel
// diff. The reference below is a frozen copy of the old SkinView3D emission,
// deliberately NOT shared with prod code so it cannot drift along with it.
class RenderParityTest {

    private val overlayZBias = 0.02f

    private fun legacyFacesToTris(
        faces: List<Face>,
        yaw: Float, pitch: Float, scale: Float, cx: Float, cy: Float, k: Float,
    ): List<Tri> {
        val tris = ArrayList<Tri>(faces.size * 2)
        for (f in faces) {
            val r0 = rotate(f.p0, yaw, pitch); val s0 = project(r0, scale, cx, cy)
            val ru = rotate(f.pu, yaw, pitch); val su = project(ru, scale, cx, cy)
            val rv = rotate(f.pv, yaw, pitch); val sv = project(rv, scale, cx, cy)
            val p3 = Vec3(f.pu.x + f.pv.x - f.p0.x, f.pu.y + f.pv.y - f.p0.y, f.pu.z + f.pv.z - f.p0.z)
            val r3 = rotate(p3, yaw, pitch); val s3 = project(r3, scale, cx, cy)
            val uv = f.uv
            val tu0 = uv.u * k; val tv0 = uv.v * k
            val tu1 = (uv.u + uv.w) * k; val tv1 = (uv.v + uv.h) * k
            val zb = if (f.layer) overlayZBias else 0f
            val v0 = Vtx(s0.x, s0.y, r0.z + zb, tu0, tv0)
            val vu = Vtx(su.x, su.y, ru.z + zb, tu1, tv0)
            val v3 = Vtx(s3.x, s3.y, r3.z + zb, tu1, tv1)
            val vv = Vtx(sv.x, sv.y, rv.z + zb, tu0, tv1)
            tris.add(Tri(v0, vu, v3, opaque = true))
            tris.add(Tri(v0, v3, vv, opaque = true))
        }
        return tris
    }

    // Deterministic noisy skin with cut-out holes across the second-layer
    // regions, so the parity sweep exercises the alpha-tested overlay path,
    // not just solid geometry.
    private fun syntheticSkin(): Texture {
        val rnd = Random(42)
        val px = IntArray(64 * 64) { (0xFF shl 24) or rnd.nextInt(0x1000000) }
        for (y in 0 until 16) for (x in 32 until 64) {
            if ((x + y) % 2 == 0) px[y * 64 + x] = 0          // hat: checkerboard holes
        }
        for (y in 32 until 64) for (x in 0 until 64) {
            if ((x / 3 + y / 2) % 3 == 0) px[y * 64 + x] = 0  // jacket/sleeves/pants: sparse holes
        }
        return Texture(px, 64, 64)
    }

    @Test fun `identity-pose rig rasterizes bit-identical to the flat renderer`() {
        val tex = syntheticSkin()
        val cases = listOf(
            Triple(SkinModel.Classic, false, "classic"),
            Triple(SkinModel.Slim, false, "slim"),
            Triple(SkinModel.Classic, true, "legacy"),
        )
        for ((model, legacy, label) in cases) {
            val figure = buildFigure(model, legacy)
            val rig = buildRig(model, legacy, tex)
            rig.apply(Pose.IDENTITY)
            for ((w, h) in listOf(64 to 96, 120 to 200)) {
                val scale = minOf(h / 42f, w / 22f); val cx = w / 2f; val cy = h / 2f
                for (yaw in listOf(0f, 0.5f, 2.2f, -1.1f)) {
                    for (pitch in listOf(0f, 0.3f, -0.6f)) {
                        val expected = rasterize(legacyFacesToTris(figure, yaw, pitch, scale, cx, cy, 1f), tex, w, h)
                        val batch = collectTriBatches(rig.root, yaw, pitch, scale, cx, cy).single()
                        val actual = rasterize(batch.tris, tex, w, h)
                        assertTrue(
                            expected.contentEquals(actual),
                            "pixel diff: $label ${w}x$h yaw=$yaw pitch=$pitch " +
                                "(${expected.indices.count { expected[it] != actual[it] }} px)",
                        )
                    }
                }
            }
        }
    }
}
