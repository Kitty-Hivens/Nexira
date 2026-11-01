package hivens.ui.render3d

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

// The row spans are an optimisation and must change no pixel: the same scene goes through the
// rasterizer and through a plain bounding box scan, and the buffers are compared exactly.
class RasterSpanTest {

    private fun randomScene(seed: Int, size: Int): List<TriBatch> {
        val rnd = Random(seed)
        val pixels = IntArray(16 * 16) { (rnd.nextInt() and 0x00FFFFFF) or (if (rnd.nextInt(5) == 0) 0x40000000 else 0xFF000000.toInt()) }
        val tex = Texture(pixels, 16, 16)
        fun vtx() = Vtx(
            rnd.nextFloat() * size * 1.4f - size * 0.2f,
            rnd.nextFloat() * size * 1.4f - size * 0.2f,
            rnd.nextFloat() * 10f,
            rnd.nextFloat() * 16f,
            rnd.nextFloat() * 16f,
        )
        val tris = List(400) {
            val shrink = rnd.nextFloat()
            val a = vtx()
            fun near(v: Vtx) = if (shrink < 0.3f) v.copy(x = a.x + (v.x - a.x) * 0.05f, y = a.y + (v.y - a.y) * 0.05f) else v
            Tri(a, near(vtx()), near(vtx()), rnd.nextInt(4) != 0)
        }
        return listOf(TriBatch(tris, tex))
    }

    @Test
    fun `row spans leave every pixel as the full scan puts it`() {
        for (seed in 1..12) {
            val size = if (seed % 2 == 0) 200 else 640
            val batches = randomScene(seed, size)
            val fast = IntArray(size * size)
            rasterizeInto(batches, size, size, fast, FloatArray(size * size))
            val slow = IntArray(size * size)
            referenceInto(batches, size, size, slow, FloatArray(size * size))
            assertContentEquals(slow, fast, "seed $seed")
        }
    }

    private fun referenceInto(batches: List<TriBatch>, w: Int, h: Int, color: IntArray, depth: FloatArray) {
        color.fill(0)
        depth.fill(Float.NEGATIVE_INFINITY)
        for (b in batches) for (t in b.tris) if (t.opaque) ref(t, color, depth, w, h, b.texture, true)
        batches.flatMap { b -> b.tris.filterNot { it.opaque }.map { it to b.texture } }
            .sortedBy { (t, _) -> t.a.z + t.b.z + t.c.z }
            .forEach { (t, tex) -> ref(t, color, depth, w, h, tex, false) }
    }

    private fun ref(t: Tri, color: IntArray, depth: FloatArray, w: Int, h: Int, tex: Texture, opaque: Boolean) {
        var a = t.a; var b = t.b; var c = t.c
        var area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        if (area == 0f) return
        if (area < 0f) { val tmp = b; b = c; c = tmp; area = -area }
        val inv = 1f / area
        val tlA = tl(b, c); val tlB = tl(c, a); val tlC = tl(a, b)
        val minX = max(0, floor(min(a.x, min(b.x, c.x))).toInt())
        val maxX = min(w - 1, ceil(max(a.x, max(b.x, c.x))).toInt())
        val minY = max(0, floor(min(a.y, min(b.y, c.y))).toInt())
        val maxY = min(h - 1, ceil(max(a.y, max(b.y, c.y))).toInt())
        for (py in minY..maxY) {
            val fy = py + 0.5f
            for (px in minX..maxX) {
                val fx = px + 0.5f
                val la = ((b.x - fx) * (c.y - fy) - (b.y - fy) * (c.x - fx)) * inv
                val lb = ((c.x - fx) * (a.y - fy) - (c.y - fy) * (a.x - fx)) * inv
                val lc = 1f - la - lb
                if (cov(la, tlA) && cov(lb, tlB) && cov(lc, tlC)) {
                    val z = la * a.z + lb * b.z + lc * c.z
                    val idx = py * w + px
                    if (z > depth[idx]) {
                        val src = tex.argb((la * a.tu + lb * b.tu + lc * c.tu).toInt(), (la * a.tv + lb * b.tv + lc * c.tv).toInt())
                        val sa = (src ushr 24) and 0xFF
                        if (opaque) {
                            if (sa >= 8) { color[idx] = src; depth[idx] = z }
                        } else if (sa > 0) {
                            color[idx] = if (sa == 0xFF) src else blend(src, color[idx])
                        }
                    }
                }
            }
        }
    }

    private fun tl(p: Vtx, q: Vtx) = (q.y < p.y) || (q.y == p.y && q.x > p.x)

    private fun cov(weight: Float, topLeft: Boolean) = weight > 0f || (weight == 0f && topLeft)

    private fun blend(src: Int, dst: Int): Int {
        val sa = (src ushr 24) and 0xFF
        val saf = sa / 255f
        val keep = 1f - saf
        val da = (dst ushr 24) and 0xFF
        val ao = (sa + da * keep).toInt().coerceIn(0, 255)
        val ro = (((src ushr 16) and 0xFF) * saf + ((dst ushr 16) and 0xFF) * keep).toInt().coerceIn(0, 255)
        val go = (((src ushr 8) and 0xFF) * saf + ((dst ushr 8) and 0xFF) * keep).toInt().coerceIn(0, 255)
        val bo = ((src and 0xFF) * saf + (dst and 0xFF) * keep).toInt().coerceIn(0, 255)
        return (ao shl 24) or (ro shl 16) or (go shl 8) or bo
    }
}
