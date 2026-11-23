package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import hivens.look.surface.api.PlaneAnswer
import hivens.look.surface.api.PlaneMaterial
import hivens.look.surface.api.PlateAnswer
import hivens.look.surface.api.Surface
import hivens.look.surface.api.SurfaceAnswers
import hivens.look.surface.api.SurfaceKind

/** What a widget's own plate takes for a field its node leaves empty, see [hivens.look.surface.api.PlateAnswer]. */
typealias PlateSurface = hivens.look.surface.api.PlateAnswer

/**
 * What the surface axis answers here for a widget's own plate where its node leaves a field empty.
 * See [hivens.look.surface.api.SurfaceAnswers.plate].
 */
val plateSurface: PlateSurface
    @Composable @ReadOnlyComposable
    get() = Surface.plate

/**
 * nx's planes as the surface axis's answers: what the nx surface provider answers, and what [NxThemed]
 * draws previews and tests in. Made once, so a role is the same value wherever it is read.
 */
fun nxSurfaceAnswers(): SurfaceAnswers = NxSurface.ANSWERS

/**
 * The nx planes. Depth is relative: a panel and a card one step above what holds them, a field one
 * toward the page, and what floats at the top of the ladder or one above its holder if that is higher.
 * Chrome frames what it holds in the step above it and adds no depth to it. Every body is opaque, and
 * every plane but the page and chrome has a bevelled edge of its own material. A widget's plate takes
 * no edge where its node names none.
 */
private object NxSurface {
    val ANSWERS: SurfaceAnswers = SurfaceAnswers.build {
        plane = { kind, holder, floating ->
            val step = when (kind) {
                SurfaceKind.Page -> 0
                SurfaceKind.Chrome -> holder.step
                SurfaceKind.Panel, SurfaceKind.Card -> holder.step + 1
                SurfaceKind.Field -> holder.step - 1
                SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice -> maxOf(floating, holder.step + 1)
            }
            val chrome = kind == SurfaceKind.Chrome
            PlaneAnswer(
                depth = if (chrome) holder.step + 1 else step,
                contentDepth = step,
                opacity = 1f,
                edgeDp = if (kind == SurfaceKind.Page || chrome) 0f else EDGE_DP,
                material = Bevel,
            )
        }
        plate = PlateAnswer(opacity = null, edgeDp = 0f)
    }

    /** The bevel's width. */
    private const val EDGE_DP = 1f
}

/** A flat body whose edge is a bevel of its own material, see [bevelHairline]. */
private object Bevel : PlaneMaterial() {
    override fun edge(colour: Color): Color = bevelHairline(colour)
    override fun toString(): String = "Bevel"
}
