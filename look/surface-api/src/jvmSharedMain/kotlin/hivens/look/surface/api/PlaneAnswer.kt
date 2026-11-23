package hivens.look.surface.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * One plane as the look answers it: the step of the ladder its body is drawn in ([depth]) and the
 * step what it holds counts from ([contentDepth]), how much of its body covers what is behind it
 * ([opacity], 0 to 1), how far it blurs what shows through ([blurDp]), how wide its edge is ([edgeDp],
 * zero for none), and what it is made of ([material]).
 *
 * The colour of a step is the colour axis's, which whatever draws the plane reads and hands the
 * material. Nothing here moves a layout: a plane's size and its padding are its own.
 */
@Immutable
class PlaneAnswer(
    val depth: Int,
    val contentDepth: Int,
    val opacity: Float,
    val blurDp: Float,
    val edgeDp: Float,
    val material: PlaneMaterial,
) {
    override fun equals(other: Any?): Boolean =
        other is PlaneAnswer && other.depth == depth && other.contentDepth == contentDepth && other.opacity == opacity &&
            other.blurDp == blurDp && other.edgeDp == edgeDp && other.material == material

    override fun hashCode(): Int = listOf(depth, contentDepth, opacity, blurDp, edgeDp, material).hashCode()

    override fun toString(): String =
        "PlaneAnswer(depth=$depth, contentDepth=$contentDepth, opacity=$opacity, blurDp=$blurDp, edgeDp=$edgeDp, material=$material)"
}

/**
 * What a plane is made of: how its body is drawn and what colour its edge takes. Drawn by itself, the
 * way a stroke draws itself, so a look can make its planes of anything, a flat fill, a texture, a
 * gradient, a glass of its own.
 *
 * [Flat] is the plain answer. A provider answers a material of its own by extending this class.
 */
@Immutable
abstract class PlaneMaterial {

    /**
     * Draws the body over the whole of the plane's bounds in [body], its colour at its opacity, after
     * [backdrop], the blur of what is behind it, where there is one to draw.
     */
    open fun DrawScope.drawBody(body: Color, backdrop: (DrawScope.() -> Unit)?) {
        backdrop?.invoke(this)
        drawRect(body)
    }

    /** The colour of the edge of a plane whose colour, before its opacity, is [colour]. */
    abstract fun edge(colour: Color): Color

    companion object {
        /** A flat fill, its edge the plane's own colour. */
        val Flat: PlaneMaterial = object : PlaneMaterial() {
            override fun edge(colour: Color): Color = colour
            override fun toString(): String = "PlaneMaterial.Flat"
        }
    }
}

/** Draws [material]'s body, see [PlaneMaterial.drawBody]. */
fun DrawScope.drawPlaneBody(material: PlaneMaterial, body: Color, backdrop: (DrawScope.() -> Unit)?) {
    with(material) { drawBody(body, backdrop) }
}

/**
 * What a widget's own plate takes for a field its node leaves empty: its opacity (null where the
 * plane's kind decides it), how far it blurs ([blurDp]) and how wide its edge is ([edgeDp]). A value
 * set on the node is stronger. The plate's corners are the shape axis's.
 */
@Immutable
class PlateAnswer(
    val opacity: Float?,
    val blurDp: Float,
    val edgeDp: Float,
) {
    override fun equals(other: Any?): Boolean =
        other is PlateAnswer && other.opacity == opacity && other.blurDp == blurDp && other.edgeDp == edgeDp

    override fun hashCode(): Int = listOf(opacity, blurDp, edgeDp).hashCode()

    override fun toString(): String = "PlateAnswer(opacity=$opacity, blurDp=$blurDp, edgeDp=$edgeDp)"
}
