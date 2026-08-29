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
