package hivens.look.surface.api

/**
 * The planes of the base answers: what Compose itself draws where nobody chose, so a window drawn with
 * no surface provider has planes of no look in particular. A plain surface in the colour of what holds
 * it, opaque, with no edge, and the page at the bottom of the ladder.
 */
internal object Neutral {
    fun plane(kind: SurfaceKind, holder: Plane): PlaneAnswer {
        val step = if (kind == SurfaceKind.Page) 0 else holder.step
        return PlaneAnswer(depth = step, contentDepth = step, opacity = 1f, edgeDp = 0f, material = PlaneMaterial.Flat)
    }

    /** A widget's plate: the kind's opacity and no edge. */
    val PLATE = PlateAnswer(opacity = null, edgeDp = 0f)
}
