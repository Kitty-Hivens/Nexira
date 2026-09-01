package hivens.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What a surface gets when nothing has said otherwise: corners, depth and the
 * surface defaults, beside [Spacing] on the gap axis and [NxColors] on the colour
 * one.
 *
 * A fallback, not a destination. The configuration a widget carries is its own --
 * `SurfaceSpec` for the plane it sits on, props for what it draws inside -- and
 * this is what the resolver reaches for when the widget names nothing. A value
 * that can only be read from here is a value nobody can change, which is a gap to
 * close rather than a design.
 *
 * These were fields of a `StyleSpec` handed down a composition local, because
 * there were two of them and a screen had to read whichever was active. Two global
 * looks were the wrong shape for the same reason: they decided for every widget at
 * once what each should be able to decide for itself.
 *
 * It stays an object rather than a literal at each call site because the reads
 * that remain have no other way to agree: [cardCorner] alone answers thirty-six of
 * them. Agreement is what a fallback is for.
 *
 * These are base dp, like [Spacing]: the density override at the shell root scales
 * them at layout time.
 */
object Form {
    /** Corner rounding on card-shaped surfaces -- the dominant shape in the tree. */
    val cardCorner: Dp = 12.dp

    /** Corner rounding on buttons, pills and chips. */
    val buttonCorner: Dp = 8.dp

    /** Corner rounding on floating panels: the editor's palette, props and presets. */
    val panelCorner: Dp = 14.dp

    /** Cast-shadow depth for those same floating panels. A plane flat on the page
     *  passes nothing; this is for the ones that genuinely hover. */
    val panelElevation: Dp = 18.dp

    /**
     * How far a plane blurs what is behind it when it names no radius of its own.
     *
     * Applies to the library's own surfaces. A widget that names nothing gets no
     * blur at all -- see the note in `WidgetSurface`, where a null radius means
     * none rather than this value.
     */
    val surfaceBlur: Dp = 18.dp

    /** Hairline weight where a component asks for the token rather than passing
     *  its own. [hivens.ui.surface.NxSurface] carries its own default. */
    val cardBorder: Dp = 0.dp

    /** Whether decorative effects -- a pulsating glow, a soft halo -- render. */
    const val softGlow: Boolean = true

    /**
     * Geometry of the toggle primitive. Colour arrives from the palette, so this
     * carries only the shell: how wide the track is, how big the thumb, and what
     * the two corners do.
     */
    object Switch {
        val trackWidth: Dp = 44.dp
        val trackHeight: Dp = 24.dp
        val thumbSize: Dp = 18.dp
        val trackCorner: Dp = 12.dp
        val thumbCorner: Dp = 9.dp
    }

    /**
     * Shell of the badge primitive, same split: the tone owns colour, this owns
     * how tall the badge sits and how far the label is from its edge.
     *
     * The height is deliberately close to the label's own line box. A badge
     * annotates a value, so it must not out-measure the value; Material's chip
     * metric is built for a labelLarge body and reads as a small button around a
     * labelSmall one.
     */
    object Badge {
        val height: Dp = 22.dp
        val horizontalPadding: Dp = 9.dp
        /** Space between the state dot and the label. */
        val gap: Dp = 6.dp
        val dotSize: Dp = 7.dp
        val corner: CornerSize = CornerSize(50)
        fun shape(): RoundedCornerShape = RoundedCornerShape(corner)
    }

    /**
     * Material 3's [Shapes] bundle, so `Card`, `Button`, dialogs and sheets round
     * the way the rest of the interface does without a shape override per call.
     *
     * Anchored to the two source corners: the whole M3 stack moves when they do.
     */
    fun materialShapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(buttonCorner / 2),
        small      = RoundedCornerShape(buttonCorner),
        medium     = RoundedCornerShape(cardCorner),
        large      = RoundedCornerShape(cardCorner + 4.dp),
        extraLarge = RoundedCornerShape(cardCorner * 2),
    )
}
