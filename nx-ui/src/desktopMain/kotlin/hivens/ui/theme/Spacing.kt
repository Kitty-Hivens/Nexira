package hivens.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one spacing scale. Gaps, paddings and inter-element arrangement pull
 * from here instead of picking a fresh dp per call site, so sibling surfaces
 * stay in step and drift (the old 3/5/7/9/14/22dp strays) has a single place
 * to be corrected.
 *
 * A 2-based geometric-ish step: each token is a deliberate rung, not an
 * arbitrary number. Reach for the nearest rung rather than reintroducing an
 * off-scale value; a genuinely new need is a new named token here, not a
 * literal at the call site.
 *
 * These are base dp. The global density knob (customization densityScale,
 * applied as a [androidx.compose.ui.unit.Density] override at the shell root)
 * multiplies them at layout time, so the scale composes with user density
 * without any per-token arithmetic.
 */
object Spacing {
    /** Hairline gap -- icon-to-label, chip inner padding. */
    val xxs: Dp = 2.dp
    /** Tight gap -- dense rows, badge padding. */
    val xs: Dp = 4.dp
    /** The default small gap -- most inter-element spacing. */
    val sm: Dp = 8.dp
    /** Component inner padding -- card content, list rows. */
    val md: Dp = 12.dp
    /** Section padding -- panel edges, dialog content. */
    val lg: Dp = 16.dp
    /** Block separation -- between grouped sections. */
    val xl: Dp = 24.dp
    /** Page-level breathing room. */
    val xxl: Dp = 32.dp
}
