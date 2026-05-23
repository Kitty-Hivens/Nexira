package hivens.ui.theme

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
)

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
 * Sharp / brutal alternative. Hard corners, hard borders, no glass, no
 * decorative glow. Sits in the same palette as Celestia but with the
 * Compose-default soft styling stripped out. Reads as the user's "жёсткий
 * интерфейс" personal lean from the 2026-05-23 Atelier conversation;
 * meant to feel sober and dense rather than warm and breathing.
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
