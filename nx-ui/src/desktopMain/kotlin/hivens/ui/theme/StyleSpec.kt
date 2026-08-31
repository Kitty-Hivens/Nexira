package hivens.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The form and motion tokens the interface is drawn with: corners, surface
 * treatment, elevation, motion, and the geometry of the switch and badge
 * primitives. Colour is [NxColors]; everything that is not colour is here.
 *
 * It was a switchable axis with a second variant beside the default, on the
 * argument that one palette should render under different geometry. What that
 * bought was one alternative nobody chose, maintained across every render test,
 * while the numbers it existed to vary stayed unreachable to anyone but us.
 *
 * What remains is a token set, not a switch, and that distinction is the point:
 * these values are read from a hundred-odd call sites, and a token is the only
 * reason they agree on what a corner is. Inlining them would end the axis by
 * destroying the consistency it was carrying.
 */
data class StyleSpec(
    /** Corner rounding on card-shaped surfaces (the dominant Compose shape). */
    val cardCorner: Dp,
    /** Outline weight on cards; 0.dp means no border, surfaces lean on fill alpha. */
    val cardBorder: Dp,
    /** Whether cards render as glass (alpha + blur) or as flat opaque surfaces. */
    val cardSurface: CardSurface,
    /** Corner rounding on buttons / pills / chips. */
    val buttonCorner: Dp,
    /** Multiplier on animation durations. 1.0 = base, 0.0 = no animation, >1 = slower. */
    val animationMultiplier: Float,
    /** Whether decorative effects (pulsating glow, soft shadow) render at all. */
    val softGlowEnabled: Boolean,
    /**
     * How far a plane blurs what is behind it when it names no radius of its own.
     *
     * It was a constant inside a preset before, so the form axis could not reach it:
     * a sharp, flat variant still blurred like a soft one. Zero here is a style that
     * does not blur at all, which is what Brut is for.
     */
    val surfaceBlur: Dp,
    /** Drop-shadow depth on floating panels (editor palette, prop and preset
     *  panels). Celestia lifts them off the canvas; Brut keeps everything flat
     *  at 0.dp, so the whole surface stack reads as one plane. */
    val panelElevation: Dp,
    /** Corner rounding on those floating panels. Tracks the card look:
     *  soft on Celestia, near-square on Brut. */
    val panelCorner: Dp,
    /** Form of the toggle/switch primitive (NxSwitch). Colours stay on the palette
     *  axis; this carries only the skinnable geometry. */
    val switchStyle: SwitchStyleSpec = SwitchStyleSpec.Pill,
    /** Form of the badge primitive (NxMetaChip / NxSourceBadge). Same split as
     *  [switchStyle]: the tone owns colour, this owns the shell. */
    val badgeStyle: BadgeStyleSpec = BadgeStyleSpec.Pill,
) {
    /**
     * Build a Material 3 [Shapes] bundle from this style. Driven by
     * [cardCorner] and [buttonCorner] so M3 `Card`, `Button`,
     * `OutlinedCard`, `OutlinedButton`, dialogs, sheets etc. pick the
     * active style's corners without per-call-site shape overrides.
     *
     * Sizes follow the M3 hierarchy (extraSmall -> chips, small ->
     * buttons, medium -> cards, large -> dialogs, extraLarge ->
     * sheets). Anchored to the two source values so Brut collapses
     * the whole shape stack to near-square in one step.
     */
    fun toMaterialShapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(buttonCorner / 2),
        small      = RoundedCornerShape(buttonCorner),
        medium     = RoundedCornerShape(cardCorner),
        large      = RoundedCornerShape(cardCorner + 4.dp),
        extraLarge = RoundedCornerShape(cardCorner * 2),
    )

    /** Apply [animationMultiplier] to a base duration. 0.0 short-circuits to 1ms
     *  so frameworks that reject zero-duration animations still accept it. */
    fun animationDurationMs(baseMs: Int): Int =
        (baseMs * animationMultiplier).toInt().coerceAtLeast(1)
}

enum class CardSurface { Glass, Flat }

/**
 * Skinnable geometry of the toggle primitive ([hivens.ui.nx.NxSwitch]).
 * Colours come from the palette (theme primary / outline), so a skin only swaps the
 * track/thumb dimensions + corners -- pill, square, or anything in between. First
 * component pulled into the component-style ("skin") layer; more follow.
 */
data class SwitchStyleSpec(
    val trackWidth: Dp,
    val trackHeight: Dp,
    val thumbSize: Dp,
    val trackCorner: Dp,
    val thumbCorner: Dp,
) {
    companion object {
        /** Rounded iOS/Material-ish pill -- the Celestia look. */
        val Pill = SwitchStyleSpec(trackWidth = 44.dp, trackHeight = 24.dp, thumbSize = 18.dp, trackCorner = 12.dp, thumbCorner = 9.dp)
        /** Hard-edged rectangle -- the Brut look. */
        val Square = SwitchStyleSpec(trackWidth = 42.dp, trackHeight = 22.dp, thumbSize = 16.dp, trackCorner = 3.dp, thumbCorner = 2.dp)
    }
}

/**
 * Skinnable geometry of the badge primitive ([hivens.ui.nx.NxMetaChip] and its
 * source-badge sibling). Colour arrives from the tone, so a skin only swaps the
 * shell: how tall the badge sits, how far the label is from the edge, and what
 * the corner does.
 *
 * Height is deliberately close to the label's own line box. A badge annotates a
 * value, so it must not out-measure the value; Material's chip metric is built
 * for a labelLarge body and reads as a small button when it wraps labelSmall.
 */
data class BadgeStyleSpec(
    val height: Dp,
    val horizontalPadding: Dp,
    /** Space between the state dot and the label. */
    val gap: Dp,
    val dotSize: Dp,
    val corner: CornerSize,
) {
    fun shape(): RoundedCornerShape = RoundedCornerShape(corner)

    companion object {
        /** Full pill -- the Celestia look, and what the Library card's status badges already drew. */
        val Pill = BadgeStyleSpec(
            height            = 22.dp,
            horizontalPadding = 9.dp,
            gap               = 6.dp,
            dotSize           = 7.dp,
            corner            = CornerSize(50),
        )
        /** Hard-edged tag -- the Brut look. */
        val Square = BadgeStyleSpec(
            height            = 22.dp,
            horizontalPadding = 8.dp,
            gap               = 6.dp,
            dotSize           = 7.dp,
            corner            = CornerSize(0.dp),
        )
    }
}

/** Rounded corners, translucent planes, soft glow on focus and hover. */
val CelestiaStyle = StyleSpec(
    cardCorner          = 12.dp,
    cardBorder          = 0.dp,
    cardSurface         = CardSurface.Glass,
    buttonCorner        = 8.dp,
    animationMultiplier = 1.0f,
    softGlowEnabled     = true,
    surfaceBlur         = 18.dp,
    panelElevation      = 18.dp,
    panelCorner         = 14.dp,
)

val LocalStyle = staticCompositionLocalOf { CelestiaStyle }
