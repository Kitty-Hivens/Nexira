package hivens.ui.render3d

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// A small CPU software rasterizer with a per-pixel depth buffer -- the reusable
// 3D primitive the painter's-algorithm renderer lacked. It takes screen-space
// triangles (already projected: x/y in pixels, z = depth with larger = nearer)
// carrying native-texel UVs plus an ARGB texture, and produces one ARGB buffer
// with correct per-pixel occlusion -- so nested geometry (head + hat) and
// coplanar seams order right at every angle, not by guessing each face's order
// from its centroid. Compose-free + deterministic, so it is unit-testable
// without a canvas (the same discipline as the skin geometry/projection).
//
// Texture coordinates are in the texture's NATIVE texels (the caller scales the
// 1x UV by the skin's k = width/64). Colours are straight (non-premultiplied)
// ARGB, matching BufferedImage TYPE_INT_ARGB so the result blits directly.

/** A triangle vertex in screen space: pixel [x],[y]; [z] depth (larger = nearer
 *  the viewer); [tu],[tv] texture coordinate in the texture's native texels. */
data class Vtx(val x: Float, val y: Float, val z: Float, val tu: Float, val tv: Float)

/** A textured triangle. [opaque] true = the depth-writing pass (the base layer);
 *  false = a translucent overlay, depth-tested read-only and alpha-composited
 *  back-to-front over what is already there. */
data class Tri(val a: Vtx, val b: Vtx, val c: Vtx, val opaque: Boolean)

/** Triangles that sample one shared [texture] -- the unit a multi-object scene
 *  feeds the rasterizer per object/material. */
data class TriBatch(val tris: List<Tri>, val texture: Texture)

/** ARGB texture addressed in native texels; out-of-range reads clamp to the edge. */
class Texture(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val stride: Int = width,
    val offset: Int = 0,
) {
    fun argb(tx: Int, ty: Int): Int =
        pixels[offset + ty.coerceIn(0, height - 1) * stride + tx.coerceIn(0, width - 1)]
}

/**
 * Rasterizes [batches] over one shared [outW] x [outH] straight-ARGB buffer
 * with one shared depth buffer -- a multi-object scene where each object
 * carries its own texture. Opaque triangles draw first, batch by batch
 * (depth-test + write, alpha-tested against [alphaCutoff]; the shared depth
 * buffer makes the batch order irrelevant except for exact depth ties, which
 * go to the first-drawn); translucent ones draw after, sorted far-to-near
 * GLOBALLY across all batches, depth-tested read-only and alpha-composited.
 * Returns the row-major ARGB pixels (0 = transparent). Caller is responsible
 * for back-face culling.
 */
fun rasterize(
    batches: List<TriBatch>,
    outW: Int,
    outH: Int,
    alphaCutoff: Int = 8,
): IntArray {
    val color = IntArray(outW * outH)
    val depth = FloatArray(outW * outH) { Float.NEGATIVE_INFINITY }
    for (b in batches) {
        for (t in b.tris) {
            if (t.opaque) drawTri(t, color, depth, outW, outH, b.texture, alphaCutoff, opaque = true)
        }
    }
    batches.asSequence()
        .flatMap { b -> b.tris.asSequence().filterNot { it.opaque }.map { it to b.texture } }
        .sortedBy { (t, _) -> t.a.z + t.b.z + t.c.z }   // far (small z) first
        .forEach { (t, tex) -> drawTri(t, color, depth, outW, outH, tex, alphaCutoff, opaque = false) }
    return color
}

/** Single-texture convenience: one batch. */
fun rasterize(
    tris: List<Tri>,
    tex: Texture,
    outW: Int,
    outH: Int,
    alphaCutoff: Int = 8,
): IntArray = rasterize(listOf(TriBatch(tris, tex)), outW, outH, alphaCutoff)

/**
 * Box-downsamples straight-ARGB [src] ([srcW] x [srcH], both multiples of
 * [factor]) by an integer [factor] -- the resolve step of supersampled
 * anti-aliasing. Colour channels average in PREMULTIPLIED space (weighted by
 * subpixel alpha): a silhouette pixel covering 2 opaque + 2 transparent
 * subpixels must come out as the opaque colour at half alpha, not a
 * half-darkened colour -- straight averaging bleeds the transparent black
 * into every edge.
 */
fun downsample(src: IntArray, srcW: Int, srcH: Int, factor: Int): IntArray {
    if (factor <= 1) return src
    val outW = srcW / factor
    val outH = srcH / factor
    val out = IntArray(outW * outH)
    val n = factor * factor
    var oy = 0
    while (oy < outH) {
        var ox = 0
        while (ox < outW) {
            var sumA = 0; var sumRA = 0; var sumGA = 0; var sumBA = 0
            var sy = oy * factor
            val syEnd = sy + factor
            while (sy < syEnd) {
                var i = sy * srcW + ox * factor
                val iEnd = i + factor
                while (i < iEnd) {
                    val px = src[i]
                    val a = (px ushr 24) and 0xFF
                    if (a != 0) {
                        sumA += a
                        sumRA += ((px ushr 16) and 0xFF) * a
                        sumGA += ((px ushr 8) and 0xFF) * a
                        sumBA += (px and 0xFF) * a
                    }
                    i++
                }
                sy++
            }
            out[oy * outW + ox] = if (sumA == 0) 0 else {
                val a = (sumA + n / 2) / n
                val r = (sumRA + sumA / 2) / sumA
                val g = (sumGA + sumA / 2) / sumA
                val b = (sumBA + sumA / 2) / sumA
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            ox++
        }
        oy++
    }
    return out
}

private fun drawTri(
    t: Tri,
    color: IntArray,
    depth: FloatArray,
    w: Int,
    h: Int,
    tex: Texture,
    alphaCutoff: Int,
    opaque: Boolean,
) {
    var a = t.a; var b = t.b; var c = t.c
    var area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    if (area == 0f) return                       // degenerate
    if (area < 0f) { val tmp = b; b = c; c = tmp; area = -area }   // normalize to a positive winding
    val inv = 1f / area
    // Top-left fill rule: a pixel exactly on a shared edge is owned by ONE triangle,
    // so the translucent pass never double-composites a seam (and opaque leaves no gap).
    val tlA = edgeTopLeft(b, c); val tlB = edgeTopLeft(c, a); val tlC = edgeTopLeft(a, b)

    val minX = max(0, floor(min(a.x, min(b.x, c.x))).toInt())
    val maxX = min(w - 1, ceil(max(a.x, max(b.x, c.x))).toInt())
    val minY = max(0, floor(min(a.y, min(b.y, c.y))).toInt())
    val maxY = min(h - 1, ceil(max(a.y, max(b.y, c.y))).toInt())

    var py = minY
    while (py <= maxY) {
        val fy = py + 0.5f
        var px = minX
        while (px <= maxX) {
            val fx = px + 0.5f
            val la = ((b.x - fx) * (c.y - fy) - (b.y - fy) * (c.x - fx)) * inv
            val lb = ((c.x - fx) * (a.y - fy) - (c.y - fy) * (a.x - fx)) * inv
            val lc = 1f - la - lb
            if (covered(la, tlA) && covered(lb, tlB) && covered(lc, tlC)) {
                val z = la * a.z + lb * b.z + lc * c.z
                val idx = py * w + px
                // Strict depth test, both passes: the inflated overlay is already
                // nearer than the base so it wins, but a FLUSH seam overlay (coplanar
                // with the base -- e.g. the hat bottom over the head bottom) must NOT
                // overdraw the base, or two textures composite at the seam.
                if (z > depth[idx]) {
                    val tu = la * a.tu + lb * b.tu + lc * c.tu
                    val tv = la * a.tv + lb * b.tv + lc * c.tv
                    val src = tex.argb(tu.toInt(), tv.toInt())
                    val sa = (src ushr 24) and 0xFF
                    if (opaque) {
                        if (sa >= alphaCutoff) { color[idx] = src; depth[idx] = z }
                    } else if (sa > 0) {
                        color[idx] = over(src, color[idx])
                    }
                }
            }
            px++
        }
        py++
    }
}

// Top-left rule for a positive-winding edge p->q (screen Y grows down): a left edge
// goes upward (q.y < p.y), a top edge is horizontal running right (q.y == p.y, q.x > p.x).
private fun edgeTopLeft(p: Vtx, q: Vtx): Boolean = (q.y < p.y) || (q.y == p.y && q.x > p.x)

/** A pixel is covered if it is strictly inside the edge, or on it when the edge is top-left. */
private fun covered(weight: Float, topLeft: Boolean): Boolean = weight > 0f || (weight == 0f && topLeft)

/** Straight-alpha source-over composite of [src] onto [dst]. */
private fun over(src: Int, dst: Int): Int {
    val sa = (src ushr 24) and 0xFF
    if (sa == 0xFF) return src
    if (sa == 0) return dst
    val saf = sa / 255f
    val keep = 1f - saf
    val sr = (src ushr 16) and 0xFF; val sg = (src ushr 8) and 0xFF; val sb = src and 0xFF
    val da = (dst ushr 24) and 0xFF
    val dr = (dst ushr 16) and 0xFF; val dg = (dst ushr 8) and 0xFF; val db = dst and 0xFF
    val ao = (sa + da * keep).toInt().coerceIn(0, 255)
    val ro = (sr * saf + dr * keep).toInt().coerceIn(0, 255)
    val go = (sg * saf + dg * keep).toInt().coerceIn(0, 255)
    val bo = (sb * saf + db * keep).toInt().coerceIn(0, 255)
    return (ao shl 24) or (ro shl 16) or (go shl 8) or bo
}
