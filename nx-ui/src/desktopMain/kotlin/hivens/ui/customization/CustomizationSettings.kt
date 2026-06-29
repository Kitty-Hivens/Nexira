package hivens.ui.customization

import kotlinx.serialization.Serializable

/**
 * Persistent customization layer that sits on top of the active theme
 * and bg settings. Stored in {configDir}/customization.json.
 *
 * Default state is a no-op -- every field is null / 1.0 / false so an
 * unconfigured user sees the same UI as before customization existed.
 *
 * [accentOverride] re-seeds the primary accent; the remaining fields
 * tune density, glass intensity, and the nav-rail selection.
 */
@Serializable
data class CustomizationSettings(
    val densityScale: Float = 1.0f,
    val glassIntensity: Float = 1.0f,
    val accentOverride: String? = null,

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
    /**
     * Whether nav entries show the interaction highlight (the hover/press state
     * layer). On (default) keeps the Material feedback; off renders a clean rail
     * where the active item is marked only by [navSelectionStyle] -- a natural
     * fit for the minimal LeftBar / Dot / None selections.
     */
    val navHoverHighlight: Boolean = true,
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
