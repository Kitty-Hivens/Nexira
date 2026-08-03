package hivens.ui.render3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Pins the depth-buffered rasterizer: occlusion order, UV interpolation, and the
// translucent-over-opaque composite -- the three properties the painter's-algorithm
// renderer could not guarantee. Pure data in / data out, no canvas.
class RasterTest {

    // A full [size] x [size] quad at a constant depth + constant UV (two triangles).
    private fun quad(z: Float, tu: Float, tv: Float, opaque: Boolean, size: Float = 4f): List<Tri> {
        val a = Vtx(0f, 0f, z, tu, tv); val b = Vtx(size, 0f, z, tu, tv)
        val c = Vtx(size, size, z, tu, tv); val d = Vtx(0f, size, z, tu, tv)
        return listOf(Tri(a, b, c, opaque), Tri(a, c, d, opaque))
    }

    @Test
    fun `the nearer opaque triangle occludes the farther one`() {
        val tex = Texture(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), width = 2, height = 1)
        val far = quad(z = 0f, tu = 0.5f, tv = 0.5f, opaque = true)    // red, depth 0
        val near = quad(z = 1f, tu = 1.5f, tv = 0.5f, opaque = true)   // blue, depth 1 (nearer)
        val out = rasterize(far + near, tex, 4, 4)
        assertEquals(0xFF0000FF.toInt(), out[2 * 4 + 2])               // centre = blue
    }

    @Test
    fun `UV interpolates to the right texel`() {
        // 2x2 texture: TL red, TR green, BL blue, BR white.
        val tex = Texture(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()),
            width = 2, height = 2,
        )
        // One quad maps the whole texture across a 2x2 output (corner UVs 0..2).
        val a = Vtx(0f, 0f, 0f, 0f, 0f); val b = Vtx(2f, 0f, 0f, 2f, 0f)
        val c = Vtx(2f, 2f, 0f, 2f, 2f); val d = Vtx(0f, 2f, 0f, 0f, 2f)
        val out = rasterize(listOf(Tri(a, b, c, true), Tri(a, c, d, true)), tex, 2, 2)
        assertEquals(0xFFFF0000.toInt(), out[0])   // (0,0) red
        assertEquals(0xFF00FF00.toInt(), out[1])   // (1,0) green
        assertEquals(0xFF0000FF.toInt(), out[2])   // (0,1) blue
        assertEquals(0xFFFFFFFF.toInt(), out[3])   // (1,1) white
    }

    @Test
    fun `a translucent overlay composites over the opaque base`() {
        val tex = Texture(intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt()), width = 2, height = 1)
        val base = quad(z = 0f, tu = 0.5f, tv = 0.5f, opaque = true)     // opaque red
        val coat = quad(z = 1f, tu = 1.5f, tv = 0.5f, opaque = false)    // half-alpha blue, in front
        val out = rasterize(base + coat, tex, 4, 4)
        val px = out[2 * 4 + 2]
        val a = (px ushr 24) and 0xFF; val r = (px ushr 16) and 0xFF
        val g = (px ushr 8) and 0xFF; val b = px and 0xFF
        assertEquals(255, a)
        assertTrue(r in 120..135, "r=$r")   // ~127: red showing through the half-alpha coat
        assertEquals(0, g)
        assertTrue(b in 120..135, "b=$b")   // ~128: blue coat
    }

    @Test
    fun `a translucent overlay behind the opaque base is occluded`() {
        val tex = Texture(intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt()), width = 2, height = 1)
        val base = quad(z = 0f, tu = 0.5f, tv = 0.5f, opaque = true)
        val behind = quad(z = -1f, tu = 1.5f, tv = 0.5f, opaque = false)
        val out = rasterize(base + behind, tex, 4, 4)
        assertEquals(0xFFFF0000.toInt(), out[2 * 4 + 2])   // stays red
    }

    // ── batched form ─────────────────────────────────────────────────────────

    @Test
    fun `batches share one depth buffer -- the nearer object wins across batches`() {
        val red = Texture(intArrayOf(0xFFFF0000.toInt()), width = 1, height = 1)
        val blue = Texture(intArrayOf(0xFF0000FF.toInt()), width = 1, height = 1)
        val far = TriBatch(quad(z = 0f, tu = 0.5f, tv = 0.5f, opaque = true), red)
        val near = TriBatch(quad(z = 1f, tu = 0.5f, tv = 0.5f, opaque = true), blue)
        // Depth decides, not batch order.
        assertEquals(0xFF0000FF.toInt(), rasterize(listOf(far, near), 4, 4)[2 * 4 + 2])
        assertEquals(0xFF0000FF.toInt(), rasterize(listOf(near, far), 4, 4)[2 * 4 + 2])
    }

    @Test
    fun `translucent triangles sort globally across batches`() {
        val white = Texture(intArrayOf(0xFFFFFFFF.toInt()), width = 1, height = 1)
        val red = Texture(intArrayOf(0x80FF0000.toInt()), width = 1, height = 1)
        val blue = Texture(intArrayOf(0x800000FF.toInt()), width = 1, height = 1)
        val base = TriBatch(quad(z = 0f, tu = 0.5f, tv = 0.5f, opaque = true), white)
        val redCoat = TriBatch(quad(z = 1f, tu = 0.5f, tv = 0.5f, opaque = false), red)
        val blueCoat = TriBatch(quad(z = 2f, tu = 0.5f, tv = 0.5f, opaque = false), blue)
        // Far-to-near composition must come out the same whichever way the
        // batch list is ordered -- the sort is global, not per batch.
        val a = rasterize(listOf(base, redCoat, blueCoat), 4, 4)
        val b = rasterize(listOf(base, blueCoat, redCoat), 4, 4)
        assertTrue(a.contentEquals(b), "batch order changed the translucent composite")
        // Blue is the near coat, so the final pixel leans blue over red.
        val px = a[2 * 4 + 2]
        assertTrue((px and 0xFF) > ((px ushr 16) and 0xFF), "expected blue over red, got ${px.toUInt().toString(16)}")
    }

    @Test
    fun `the single-texture overload equals a one-batch call`() {
        val tex = Texture(intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt()), width = 2, height = 1)
        val tris = quad(z = 0f, tu = 0.5f, tv = 0.5f, opaque = true) +
            quad(z = 1f, tu = 1.5f, tv = 0.5f, opaque = false)
        assertTrue(
            rasterize(tris, tex, 4, 4).contentEquals(rasterize(listOf(TriBatch(tris, tex)), 4, 4)),
        )
    }

    // ── SSAA resolve ─────────────────────────────────────────────────────────

    @Test
    fun `downsample averages a silhouette edge in premultiplied space`() {
        // 2x2 block: two opaque red subpixels + two transparent. The resolved
        // pixel must be RED at half alpha -- straight averaging would produce
        // a half-DARKENED red instead (transparent black bleeding in).
        val src = intArrayOf(
            0xFFFF0000.toInt(), 0x00000000,
            0xFFFF0000.toInt(), 0x00000000,
        )
        val out = downsample(src, 2, 2, 2)
        assertEquals(1, out.size)
        val px = out[0]
        assertEquals(0x80, (px ushr 24) and 0xFF)
        assertEquals(0xFF, (px ushr 16) and 0xFF, "red channel must stay full")
        assertEquals(0, px and 0xFFFF)
    }

    @Test
    fun `downsample keeps uniform blocks and fully transparent blocks intact`() {
        val green = 0xFF00FF00.toInt()
        val src = intArrayOf(green, green, 0, 0, green, green, 0, 0)   // 4x2
        val out = downsample(src, 4, 2, 2)
        assertEquals(2, out.size)
        assertEquals(green, out[0])
        assertEquals(0, out[1])
    }

    @Test
    fun `downsample with factor 1 is the identity`() {
        val src = intArrayOf(1, 2, 3, 4)
        assertTrue(downsample(src, 2, 2, 1).contentEquals(src))
    }
}
