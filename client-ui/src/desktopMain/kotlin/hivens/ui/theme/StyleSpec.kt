package hivens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Form and motion tokens that change WHEN the user picks a different UI
 * variant. Sits alongside [CelestiaColors] -- colors travel through
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
 * Default style -- matches the current CelestiaTheme aesthetic: rounded
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
)

val LocalStyle = staticCompositionLocalOf { CelestiaStyle }

/**
 * Layer per-token user overrides on top of this preset. Null fields
 * in [overrides] keep the preset's value; non-null fields drift the
 * named token only. Editor-4 wires this into the AppShell theme
 * resolution chain so the user can fine-tune corner radius, border
 * weight, animation speed, etc. without abandoning the active
 * UiStyle preset entirely.
 */
fun StyleSpec.applyOverrides(
    overrides: hivens.ui.customization.StyleOverrides?,
): StyleSpec {
    if (overrides == null) return this
    return copy(
        cardCorner          = overrides.cardCornerDp?.let { it.dp } ?: cardCorner,
        cardBorder          = overrides.cardBorderDp?.let { it.dp } ?: cardBorder,
        buttonCorner        = overrides.buttonCornerDp?.let { it.dp } ?: buttonCorner,
        animationMultiplier = overrides.animationMultiplier ?: animationMultiplier,
        softGlowEnabled     = overrides.softGlowEnabled ?: softGlowEnabled,
    )
}
