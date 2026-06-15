package hivens.ui.customization

import kotlinx.serialization.Serializable

/**
 * Persistent customization layer that sits on top of the active theme
 * and bg settings. Stored in {configDir}/customization.json.
 *
 * Default state is a no-op -- every field is null / 1.0 / false so an
 * unconfigured user sees the same UI as before customization existed.
 *
 * [experimentalColorOverridesEnabled] gates [colorOverrides] +
 * [styleOverrides] -- by default only [accentOverride] is exposed.
 * Flipping the experimental flag unlocks per-role color picking and
 * per-token style tweaks; both are easy to misuse into unreadable
 * combinations.
 */
@Serializable
data class CustomizationSettings(
    val densityScale: Float = 1.0f,
    val glassIntensity: Float = 1.0f,
    val accentOverride: String? = null,
    val experimentalColorOverridesEnabled: Boolean = false,
    val colorOverrides: Map<String, String> = emptyMap(),
    val styleOverrides: StyleOverrides = StyleOverrides(),

    /**
     * How the active item in the left navigation rail is highlighted.
     * [NavSelectionStyle.Pill] (default) keeps the original Material capsule
     * behind the icon; the other variants change only the selection
     * decoration, never the rail's geometry or spacing.
     */
    val navSelectionStyle: NavSelectionStyle = NavSelectionStyle.Pill,
    /**
     * Optional hex color for the nav selection decoration and the active
     * icon. Null keeps the theme accent (primary), so it tracks the palette
     * and [accentOverride] by default.
     */
    val navSelectionAccent: String? = null,
    /**
     * When true, unselected nav entries render the outlined icon variant and
     * only the active entry stays filled -- a lighter rail. Off keeps every
     * icon filled (the original look).
     */
    val navSelectionOutlineIcons: Boolean = false,
)

/**
 * Decoration drawn behind / around the active left-rail icon. Shape-only --
 * the rail geometry and spacing are unchanged; each variant just renders the
 * selection differently. [Square] follows the active style's button corner
 * (soft under Celestia, square under Brut); [None] shows selection through the
 * icon tint alone.
 */
@Serializable
enum class NavSelectionStyle { Pill, Square, Circle, LeftBar, Dot, None }

/**
 * Per-role keys for [CustomizationSettings.colorOverrides]. Stable
 * strings so existing customization.json files keep working across
 * theme refactors. Editor-4 extends the role set with text tokens,
 * the glass alpha float, and the severity accents.
 */
object ColorRole {
    const val PRIMARY          = "primary"
    const val SECONDARY        = "secondary"
    const val BACKGROUND       = "background"
    const val SURFACE          = "surface"
    const val SUCCESS          = "success"
    const val ERROR            = "error"
    const val OUTLINE          = "outline"
    // editor-4 additions
    const val TEXT_PRIMARY     = "textPrimary"
    const val TEXT_SECONDARY   = "textSecondary"
    const val GLASS_ALPHA      = "glassAlpha"        // float, special-cased in theme
    const val PROGRESS_ACCENT  = "progressAccent"
    const val WARN_ACCENT      = "warnAccent"
    const val CRITICAL_ACCENT  = "criticalAccent"
    // Source/origin brand colors (overridable; the decorative ramp is not).
    const val ORIGIN_SMARTYCRAFT = "originSmartycraft"
    const val ORIGIN_MIRROR      = "originMirror"
    const val ORIGIN_MODRINTH    = "originModrinth"
    const val ORIGIN_LOCAL       = "originLocal"
}

/**
 * Per-token style overrides layered on top of the active UiStyle
 * preset (Celestia / Brut). Every field is nullable -- null means
 * "keep the preset's value". The user picking Brut still sees Brut
 * as their base; overrides drift individual fields off the base.
 *
 * Applied via [hivens.ui.theme.StyleSpec.applyOverrides] in the
 * theme resolution chain at AppShell.
 */
@Serializable
data class StyleOverrides(
    val cardCornerDp: Float? = null,
    val cardBorderDp: Float? = null,
    val buttonCornerDp: Float? = null,
    val animationMultiplier: Float? = null,
    val softGlowEnabled: Boolean? = null,
)
