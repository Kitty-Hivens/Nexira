package hivens.ui.render3d

import kotlin.math.abs
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
    fun argb(tx: Int, ty: Int): Int {
        val x = if (tx < 0) 0 else if (tx >= width) width - 1 else tx
        val y = if (ty < 0) 0 else if (ty >= height) height - 1 else ty
        return pixels[offset + y * stride + x]
    }
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
    val depth = FloatArray(outW * outH)
    rasterizeInto(batches, outW, outH, color, depth, alphaCutoff)
    return color
}

/**
 * Reused-buffer form of [rasterize]: rasterizes into caller-owned [color] and
 * [depth] (each at least outW*outH long) instead of allocating them. Both are
 * cleared first -- color to 0 (transparent), depth to -inf -- so an animating
 * view can hand back the same two arrays every frame and allocate nothing on
 * the hot path. The [color] contents are identical to what [rasterize] returns.
 */
fun rasterizeInto(
    batches: List<TriBatch>,
    outW: Int,
    outH: Int,
    color: IntArray,
    depth: FloatArray,
    alphaCutoff: Int = 8,
) {
    val n = outW * outH
    color.fill(0, 0, n)
    depth.fill(Float.NEGATIVE_INFINITY, 0, n)
    for (b in batches) {
        for (t in b.tris) {
            if (t.opaque) drawTri(t, color, depth, outW, outH, b.texture, alphaCutoff, opaque = true)
        }
    }
    batches.asSequence()
        .flatMap { b -> b.tris.asSequence().filterNot { it.opaque }.map { it to b.texture } }
        .sortedBy { (t, _) -> t.a.z + t.b.z + t.c.z }   // far (small z) first
        .forEach { (t, tex) -> drawTri(t, color, depth, outW, outH, tex, alphaCutoff, opaque = false) }
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
    val out = IntArray((srcW / factor) * (srcH / factor))
    downsampleInto(src, srcW, srcH, factor, out)
    return out
}

/**
 * Reused-buffer form of [downsample] for [factor] > 1: box-resolves [src]
 * ([srcW] x [srcH]) into caller-owned [out] (at least (srcW/factor) *
 * (srcH/factor) long). Same math as [downsample]; lets an animating view reuse
 * the resolve buffer across frames instead of allocating it every frame.
 */
fun downsampleInto(src: IntArray, srcW: Int, srcH: Int, factor: Int, out: IntArray) {
    val outW = srcW / factor
    val outH = srcH / factor
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

    // Row spans cut the bounding box down to where an edge test can still pass. They are worked out
    // in doubles with a margin well above the float error of the per-pixel test below, and only for
    // triangles big enough that the error is small against a weight, so every pixel the exact test
    // would cover is still visited and the output is unchanged.
    val spans = area >= SPAN_MIN_AREA
    val ia = inv.toDouble()
    val slopeA = ia * (b.y - c.y)
    val slopeB = ia * (c.y - a.y)
    val slopeC = -(slopeA + slopeB)
    val tolA = SPAN_TOLERANCE + 2.0 * abs(slopeA)
    val tolB = SPAN_TOLERANCE + 2.0 * abs(slopeB)
    val tolC = SPAN_TOLERANCE + 2.0 * abs(slopeC)

    var py = minY
    while (py <= maxY) {
        val fy = py + 0.5f
        var rowFrom = minX
        var rowTo = maxX
        if (spans) {
            val u = (c.y - fy).toDouble()
            val v = (b.y - fy).toDouble()
            val interceptA = ia * (b.x * u - v * c.x)
            val u2 = (a.y - fy).toDouble()
            val v2 = (c.y - fy).toDouble()
            val interceptB = ia * (c.x * u2 - v2 * a.x)
            val interceptC = 1.0 - interceptA - interceptB
            var span = packSpan(rowFrom, rowTo)
            span = narrow(span, interceptA, slopeA, tolA)
            span = narrow(span, interceptB, slopeB, tolB)
            span = narrow(span, interceptC, slopeC, tolC)
            rowFrom = spanFrom(span)
            rowTo = spanTo(span)
        }
        var px = rowFrom
        while (px <= rowTo) {
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

/** A triangle below this area is scanned over its whole bounding box: it is cheap there, and the float error of its edge test is large against a weight. */
private const val SPAN_MIN_AREA = 64f

/** How far below zero a weight may sit and still be scanned, in weight units. */
private const val SPAN_TOLERANCE = 0.05

private fun packSpan(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xFFFFFFFFL)

private fun spanFrom(span: Long): Int = (span shr 32).toInt()

private fun spanTo(span: Long): Int = span.toInt()

/**
 * Cuts [span] to the pixels where `intercept + slope * (px + 0.5)` is not below `-tolerance`. An
 * empty result has from above to, which the scan loop skips.
 */
private fun narrow(span: Long, intercept: Double, slope: Double, tolerance: Double): Long {
    val from = spanFrom(span)
    val to = spanTo(span)
    if (from > to) return span
    if (abs(slope) < FLAT_SLOPE) {
        return if (intercept + slope * (from + 0.5) < -tolerance) packSpan(1, 0) else span
    }
    val edge = (-tolerance - intercept) / slope - 0.5
    return if (slope > 0.0) packSpan(max(from.toDouble(), ceil(edge)).toInt(), to)
    else packSpan(from, min(to.toDouble(), floor(edge)).toInt())
}

private const val FLAT_SLOPE = 1e-12

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
