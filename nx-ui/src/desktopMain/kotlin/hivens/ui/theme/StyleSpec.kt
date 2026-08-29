package hivens.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Form and motion tokens that change WHEN the user picks a different UI
 * variant. Sits alongside [NxColors] -- colors travel through
 * palette presets, while shape / surface / motion travel through style.
 *
 * The split lets a single palette (say Celestia Dark) be rendered with
 * very different geometry (CelestiaStyle's soft glass-card look vs
 * BrutStyle's sharp-edged flat panels) without forcing the user to pick
 * one combined preset. Two axes, independent choice.
 *
 * Initial token set is intentionally small -- only the dimensions where
 * the Celestia <-> Brut visual difference is load-bearing. Add tokens
 * as the second variant pressures more of the component layer.
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

/**
 * Default style -- matches the current NxTheme aesthetic: rounded
 * corners, glass cards with alpha, soft glow on focus / hover. This is
 * what existing code sees if it switches to LocalStyle.current.
 */
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

/**
 * Sharp alternative to Celestia: near-square corners, opaque
 * surfaces, hard borders, motion off, glow off. Same palette, no
 * soft styling.
 */
val BrutStyle = StyleSpec(
    cardCorner          = 2.dp,
    cardBorder          = 1.dp,
    cardSurface         = CardSurface.Flat,
    buttonCorner        = 0.dp,
    animationMultiplier = 0.0f,
    softGlowEnabled     = false,
    // Brut does not blur. The form axis owns this now, so a sharp, flat variant
    // is sharp and flat all the way down rather than soft behind every plane.
    surfaceBlur         = 0.dp,
    panelElevation      = 0.dp,
    panelCorner         = 2.dp,
    switchStyle         = SwitchStyleSpec.Square,
    badgeStyle          = BadgeStyleSpec.Square,
)

val LocalStyle = staticCompositionLocalOf { CelestiaStyle }
