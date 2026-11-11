package hivens.ui.surface

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star

/**
 * Outlines `RoundedCornerShape` cannot describe: a corner with SMOOTHING -- a
 * squircle, where the curve eases into the straight edge instead of meeting it at a
 * tangent -- and stars and regular polygons.
 *
 * Both are cut from a [RoundedPolygon]. The path is built from its cubics by hand:
 * the desktop artifact carries no Compose-Path bridge (that helper is Android-only),
 * and the cubics are the whole outline in order, so there is nothing else to it.
 */
private fun RoundedPolygon.toPath(): Path {
    val path = Path()
    if (cubics.isEmpty()) return path
    path.moveTo(cubics.first().anchor0X, cubics.first().anchor0Y)
    cubics.forEach { path.cubicTo(it.control0X, it.control0Y, it.control1X, it.control1Y, it.anchor1X, it.anchor1Y) }
    path.close()
    return path
}

/**
 * A rectangle whose four corners round independently, each with the same [smoothing].
 *
 * Built from the four vertices rather than the library's `rectangle` helper, which
 * takes one rounding for the whole shape: a plane may be square on one side and round
 * on the other, and that is what the per-corner fields exist for.
 *
 * Radii are dp and are resolved against the measured footprint, so they mean the same
 * thing here as they do in a `RoundedCornerShape` -- a corner does not grow when the
 * plane does. Each is capped at half the shorter side, past which a rounding has no
 * room and the library's own clamp would silently choose something else.
 */
data class SmoothedRectShape(
    val topStartDp: Float,
    val topEndDp: Float,
    val bottomEndDp: Float,
    val bottomStartDp: Float,
    val smoothing: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cap = minOf(size.width, size.height) / 2f
        fun px(dp: Float) = (dp * density.density).coerceIn(0f, cap)
        val s = smoothing.coerceIn(0f, 1f)
        val polygon = RoundedPolygon(
            vertices = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height),
            perVertexRounding = listOf(
                CornerRounding(px(topStartDp), s),
                CornerRounding(px(topEndDp), s),
                CornerRounding(px(bottomEndDp), s),
                CornerRounding(px(bottomStartDp), s),
            ),
        )
        return Outline.Generic(polygon.toPath())
    }
}

/**
 * A star or regular polygon stretched to fill the plane.
 *
 * Unlike a rounded rectangle this outline is inherently relative -- its radii are
 * fractions of the footprint, not lengths -- so the polygon is normalised into the
 * unit square once and scaled to the measured size. It does not preserve aspect: a
 * star in a wide box is a wide star, which is what a plane filling its widget does.
 */
data class PolygonShape(
    /** 3 or more. A star's count is its spikes; a polygon's is its sides. */
    val points: Int,
    /** How far the notches of a star fall in, as a fraction. 1 makes it a polygon. */
    val innerRadius: Float,
    /** Corner rounding, as a fraction of the radius. */
    val rounding: Float,
    val smoothing: Float,
) : Shape {
    private val unit: Path by lazy {
        val n = points.coerceAtLeast(3)
        val corner = CornerRounding(rounding.coerceIn(0f, 1f), smoothing.coerceIn(0f, 1f))
        val polygon = if (innerRadius >= 1f) {
            RoundedPolygon(numVertices = n, rounding = corner)
        } else {
            RoundedPolygon.star(
                numVerticesPerRadius = n,
                innerRadius = innerRadius.coerceIn(0.05f, 0.95f),
                rounding = corner,
            )
        }
        polygon.normalized().toPath()
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val scaled = Path().apply { addPath(unit) }
        scaled.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(scaled)
    }
}

/** Which pair of ends a [ChamferedRectShape] cuts. */
enum class ChamferAxis { Horizontal, Vertical }

/**
 * A rectangle with one or both ends cut to a point.
 *
 * The cut is a LENGTH, not a fraction, which is the whole reason this is not a
 * [PolygonShape]. A regular hexagon normalised into the footprint stretches its
 * diagonals with the box, so a four-letter label and a forty-character one end
 * up with different geometry although they are the same element. Here the point
 * is [startCutDp] deep whatever the box does, which is what makes the form usable
 * for a tooltip or a tag whose width follows its text.
 *
 * Both cuts give a long hexagon, one gives a tag pointing that way, neither gives
 * a plain rectangle, so the family is one shape rather than three. Each cut is
 * capped at half the cut axis, past which the two points would cross.
 *
 * [roundingDp] is worth one warning. Rounding a corner of a rectangle cuts across
 * it and the edges still reach the bounds, but rounding a POINT pulls the apex
 * back along the cut axis, by `r * (1 / sin(half angle) - 1)`, so the sharper the
 * point the more it retreats. A 12dp cut on a 20dp half height gives back about
 * 0.56 of the radius at each end. The cross axis is untouched, so a plane using
 * this shape still meets its box top and bottom and stands off it at the points.
 */
data class ChamferedRectShape(
    val startCutDp: Float,
    val endCutDp: Float,
    /** Vertex rounding in dp, resolved like [SmoothedRectShape]'s so it does not grow with the plane. */
    val roundingDp: Float = 0f,
    val smoothing: Float = 0f,
    val axis: ChamferAxis = ChamferAxis.Horizontal,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val along = if (axis == ChamferAxis.Horizontal) size.width else size.height
        val across = if (axis == ChamferAxis.Horizontal) size.height else size.width
        val cap = along / 2f
        val start = (startCutDp * density.density).coerceIn(0f, cap)
        val end = (endCutDp * density.density).coerceIn(0f, cap)
        val corner = CornerRounding(
            (roundingDp * density.density).coerceAtLeast(0f),
            smoothing.coerceIn(0f, 1f),
        )

        // Built along the cut axis and mapped out of it, so the vertical case is
        // the same six points read the other way round rather than a second body.
        val mid = across / 2f
        val pts = buildList {
            if (start > 0f) add(0f to mid)
            add(start to 0f)
            add(along - end to 0f)
            if (end > 0f) add(along to mid)
            add(along - end to across)
            add(start to across)
        }
        val flat = FloatArray(pts.size * 2)
        pts.forEachIndexed { i, (u, v) ->
            val x = if (axis == ChamferAxis.Horizontal) u else v
            val y = if (axis == ChamferAxis.Horizontal) v else u
            flat[i * 2] = x
            flat[i * 2 + 1] = y
        }
        val polygon = RoundedPolygon(vertices = flat, perVertexRounding = List(pts.size) { corner })
        return Outline.Generic(polygon.toPath())
    }
}
