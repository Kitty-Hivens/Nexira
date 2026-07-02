package hivens.ui.skin3d

import hivens.ui.scene3d.Pt2
import hivens.ui.scene3d.UvRect
import hivens.ui.scene3d.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // ── figure geometry ──────────────────────────────────────────────────────

    @Test fun `box faces wind uniformly -- texture UxV points inward on every face`() {
        // Replaces the retired projected-culling tests: with the TL/TR/BL corner
        // convention (V runs down the texture), cross(pu-p0, pv-p0) points INTO
        // the box on an outward face. A face wound the other way would flip that
        // sign and, once rendered, sample its texture mirrored.
        val box = Box(-4f, -4f, -4f, 4f, 4f, 4f, u = 0f, v = 0f, w = 8f, h = 8f, d = 8f)
        for (f in box.faces()) {
            val ux = f.pu.x - f.p0.x; val uy = f.pu.y - f.p0.y; val uz = f.pu.z - f.p0.z
            val vx = f.pv.x - f.p0.x; val vy = f.pv.y - f.p0.y; val vz = f.pv.z - f.p0.z
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            // Box is centred on the origin, so the face centre doubles as the
            // outward direction.
            val cx = f.p0.x + (ux + vx) * 0.5f
            val cy = f.p0.y + (uy + vy) * 0.5f
            val cz = f.p0.z + (uz + vz) * 0.5f
            val dot = nx * cx + ny * cy + nz * cz
            assertTrue(dot < 0f, "face ${f.uv} winds outward (dot=$dot), expected inward UxV")
        }
    }

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

    @Test fun `head base box maps all six faces to the canonical skin regions`() {
        // Guards the unwrap UVs -- a wrong head face (the reported neck-underside
        // bug class) samples a random texture region.
        val base = buildFigure().filterNot { it.layer }
        fun has(uv: UvRect, where: String) =
            assertTrue(base.any { it.uv == uv }, "head $where face must map to $uv")
        has(UvRect(8f, 0f, 8f, 8f), "top")
        // Bottom samples the same (16,0)-(24,8) region but V-flipped (origin at the far
        // edge, negative height) -- the MC down face is reversed vs the top, and mapping
        // it front-to-back rendered the underside back-to-front (the reported bug).
        has(UvRect(16f, 8f, 8f, -8f), "bottom (neck underside)")
        has(UvRect(0f, 8f, 8f, 8f), "right")
        has(UvRect(8f, 8f, 8f, 8f), "front")
        has(UvRect(16f, 8f, 8f, 8f), "left")
        has(UvRect(24f, 8f, 8f, 8f), "back")
    }

    @Test fun `the head overlay stays flush at the neck -- no z-fighting band into the torso`() {
        // Hat (head overlay) bottom samples the (48,0)-(56,8) region V-flipped, so the
        // UV is (48,8,8,-8). With the seam fix it sits at the head's bottom plane y=8,
        // not inflated 0.5 down into the body, so it cannot overlap and z-fight the
        // jacket overlay at the neck.
        val hatBottom = buildFigure().single { it.layer && it.uv == UvRect(48f, 8f, 8f, -8f) }
        assertEquals(8f, hatBottom.p0.y, "hat bottom sits at the neck plane")
        assertEquals(8f, hatBottom.pu.y)
        assertEquals(8f, hatBottom.pv.y)
    }

    @Test fun `the body overlay is flush at the neck seam`() {
        // Body overlay top = UV (20,32,8,4); flush at y=8 under the head so it does
        // not bulge up to meet the inflated hat.
        val bodyTop = buildFigure().single { it.layer && it.uv == UvRect(20f, 32f, 8f, 4f) }
        assertEquals(8f, bodyTop.p0.y, "body overlay top flush at the neck plane")
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
        assertEquals(SkinModel.Classic, guessModel(64, 64) { _, _ -> 255 })
        assertEquals(SkinModel.Slim, guessModel(64, 64) { _, _ -> 0 })
    }

    @Test fun `guessModel forces Classic for legacy 64x32`() {
        // The legacy layout predates the Slim model; whatever the sniff
        // region's texels carry there, it must never read as Slim.
        assertEquals(SkinModel.Classic, guessModel(64, 32) { _, _ -> 0 })
    }

    @Test fun `guessModel never samples an undersized texture out of bounds`() {
        // A valid-but-undersized PNG (e.g. a 48x48 upload from the skin
        // endpoint) must default to Classic without touching texels past the
        // edge -- the accessor trips on any out-of-bounds read.
        val model = guessModel(48, 48) { x, y ->
            check(x in 0 until 48 && y in 0 until 48) { "sampled ($x,$y) outside 48x48" }
            0
        }
        assertEquals(SkinModel.Classic, model)
    }

    @Test fun `guessModel scales the sniff column for HD skins`() {
        // 128x128 (k = 2): the 4th arm column sits at raw x = 108..111.
        assertEquals(SkinModel.Classic, guessModel(128, 128) { x, _ -> if (x >= 108) 255 else 0 })
        assertEquals(SkinModel.Slim,    guessModel(128, 128) { x, _ -> if (x >= 108) 0 else 255 })
    }
}
