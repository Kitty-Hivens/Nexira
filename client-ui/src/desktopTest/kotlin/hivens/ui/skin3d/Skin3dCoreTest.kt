package hivens.ui.skin3d

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Skin3dCoreTest {

    private val halfPi = (PI / 2).toFloat()

    private fun close(a: Float, b: Float, eps: Float = 1e-3f) = kotlin.math.abs(a - b) <= eps
    private fun assertPt(expected: Pt2, actual: Pt2, eps: Float = 1e-3f) {
        assertTrue(close(expected.x, actual.x, eps) && close(expected.y, actual.y, eps),
            "expected $expected, got $actual")
    }

    // ── rotate / project ──────────────────────────────────────────────────────

    @Test fun `identity rotation leaves a point unchanged`() {
        val p = rotate(Vec3(1f, 2f, 3f), 0f, 0f)
        assertTrue(close(p.x, 1f) && close(p.y, 2f) && close(p.z, 3f))
    }

    @Test fun `yaw of 90 degrees turns the front (+z) toward +x`() {
        val p = rotate(Vec3(0f, 0f, 1f), halfPi, 0f)
        assertTrue(close(p.x, 1f), "x=${p.x}")
        assertTrue(close(p.y, 0f) && close(p.z, 0f), "p=$p")
    }

    @Test fun `orthographic project flips model +Y to screen up`() {
        val s = project(Vec3(0f, 1f, 0f), scale = 10f, centerX = 100f, centerY = 100f)
        assertPt(Pt2(100f, 90f), s)
    }

    // ── faceAffine ──────────────────────────────────────────────────────────────

    @Test fun `faceAffine maps the UV rect corners onto O, O+U, O+V`() {
        val o = Pt2(10f, 20f); val u = Pt2(80f, 0f); val v = Pt2(0f, 40f)
        val uv = UvRect(8f, 8f, 8f, 4f)
        val a = faceAffine(o, u, v, uv)
        assertPt(o,                       a.map(uv.u, uv.v))                 // top-left
        assertPt(Pt2(o.x + u.x, o.y + u.y), a.map(uv.u + uv.w, uv.v))        // top-right
        assertPt(Pt2(o.x + v.x, o.y + v.y), a.map(uv.u, uv.v + uv.h))        // bottom-left
        assertPt(Pt2(o.x + u.x + v.x, o.y + u.y + v.y), a.map(uv.u + uv.w, uv.v + uv.h))
    }

    // ── culling + painter sort ───────────────────────────────────────────────

    @Test fun `a head-on front face is front-facing and its back is culled`() {
        // front (+z) and back (-z) of the same unit-ish quad.
        val front = Face(Vec3(-4f, 4f, 4f), Vec3(4f, 4f, 4f), Vec3(-4f, -4f, 4f), UvRect(8f, 8f, 8f, 8f), false)
        val back  = Face(Vec3(4f, 4f, -4f), Vec3(-4f, 4f, -4f), Vec3(4f, -4f, -4f), UvRect(24f, 8f, 8f, 8f), false)
        val out = projectFaces(listOf(front, back), yaw = 0f, pitch = 0f, scale = 10f, centerX = 100f, centerY = 100f)
        assertEquals(1, out.size, "back face must be culled head-on")
        assertEquals(8f, out.single().uv.u, "the surviving face is the front one")
    }

    @Test fun `a box shows at most three faces from any angle -- consistent winding`() {
        // Inverted winding on any face would let a back face through, pushing
        // the visible count above 3 (a cube never shows more than three faces).
        val box = Box(-4f, -4f, -4f, 4f, 4f, 4f, u = 0f, v = 0f, w = 8f, h = 8f, d = 8f).faces()
        for (yaw in listOf(0f, 0.6f, 1.3f, 2.5f, 4.0f, -0.7f)) {
            for (pitch in listOf(-0.5f, 0f, 0.4f, 1.0f)) {
                val visible = projectFaces(box, yaw, pitch, 10f, 100f, 100f).size
                assertTrue(visible in 1..3, "cube shows 1..3 faces, got $visible at yaw=$yaw pitch=$pitch")
            }
        }
    }

    @Test fun `a generic viewing angle shows exactly three box faces`() {
        val box = Box(-4f, -4f, -4f, 4f, 4f, 4f, u = 0f, v = 0f, w = 8f, h = 8f, d = 8f).faces()
        assertEquals(3, projectFaces(box, 0.6f, 0.35f, 10f, 100f, 100f).size)
    }

    @Test fun `frontFacing is winding-sensitive`() {
        assertTrue(frontFacing(Pt2(80f, 0f), Pt2(0f, 80f)))
        assertFalse(frontFacing(Pt2(-80f, 0f), Pt2(0f, 80f)))
    }

    @Test fun `painter sort puts the nearer face last`() {
        // Two front-facing quads at different depths; nearer (larger z) sorts last.
        val far  = Face(Vec3(-4f, 4f, 2f), Vec3(4f, 4f, 2f), Vec3(-4f, -4f, 2f), UvRect(1f, 1f, 8f, 8f), false)
        val near = Face(Vec3(-4f, 4f, 6f), Vec3(4f, 4f, 6f), Vec3(-4f, -4f, 6f), UvRect(2f, 2f, 8f, 8f), false)
        val out = projectFaces(listOf(near, far), yaw = 0f, pitch = 0f, scale = 10f, centerX = 100f, centerY = 100f)
        assertEquals(2, out.size)
        assertEquals(1f, out.first().uv.u, "farther face drawn first")
        assertEquals(2f, out.last().uv.u, "nearer face drawn last")
    }

    // ── figure geometry ──────────────────────────────────────────────────────

    @Test fun `classic 64x64 figure has the full base + overlay box set`() {
        // 6 base boxes + 6 overlay boxes, 6 faces each.
        assertEquals(72, buildFigure(SkinModel.Classic, legacy = false).size)
    }

    @Test fun `legacy 64x32 figure has only the hat overlay`() {
        // 6 base boxes + hat overlay only = 7 boxes.
        assertEquals(42, buildFigure(SkinModel.Classic, legacy = true).size)
    }

    @Test fun `head front face maps to the canonical (8,8)-(16,16) skin region`() {
        val headFront = buildFigure().firstOrNull { it.uv == UvRect(8f, 8f, 8f, 8f) }
        assertNotNull(headFront, "head front UV (8,8,8,8) must exist")
    }

    @Test fun `arm width follows the model -- classic 4, slim 3`() {
        // The right-arm front face sits at UV (44,20) with width = arm width.
        fun armFrontWidth(m: SkinModel): Float =
            buildFigure(m).first { it.uv.u == 44f && it.uv.v == 20f }.uv.w
        assertEquals(4f, armFrontWidth(SkinModel.Classic))
        assertEquals(3f, armFrontWidth(SkinModel.Slim))
    }

    // ── slim detection ───────────────────────────────────────────────────────

    @Test fun `guessModel reads an opaque arm column as Classic, transparent as Slim`() {
        assertEquals(SkinModel.Classic, guessModel { _, _ -> 255 })
        assertEquals(SkinModel.Slim, guessModel { _, _ -> 0 })
    }
}
