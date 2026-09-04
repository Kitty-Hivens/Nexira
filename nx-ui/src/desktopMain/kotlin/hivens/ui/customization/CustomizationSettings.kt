package hivens.ui.customization

import kotlinx.serialization.Serializable

/**
 * Persistent customization layer that sits on top of the active theme
 * and bg settings. Stored in {configDir}/customization.json.
 *
 * Default state is a no-op -- every field is null / 1.0 / false so an
 * unconfigured user sees the same UI as before customization existed.
 *
 * [accentOverride] re-seeds the primary accent, the remaining fields decide
 * whether surfaces blur and how the nav-rail selection is drawn.
 *
 * A glass-intensity multiplier used to live here too. It scaled the tint helper
 * every screen mixed its own planes with -- so it moved thirty places and none of
 * the library's own surfaces -- and no screen ever wrote it: it was reachable only
 * by hand-editing this file. Those thirty places name their colour directly now.
 *
 * A global density multiplier went the same way. It multiplied the density the
 * window already carries, which the compositor sets, so it was a second answer to
 * a question the system had answered, and nothing ever wrote it either. The axis
 * actually in use is per widget: content scales to the footprint the layout gave
 * it, in [hivens.ui.widgets.AdaptiveWidget]. A future per-widget property could
 * hand a widget its own density, which is a different thing from a window-wide
 * zoom. What a global control would want is the font scale, which the shell
 * deliberately passes through untouched.
 */
@Serializable
data class CustomizationSettings(
    val accentOverride: String? = null,

    /**
     * Whether a surface blurs what is behind it at all.
     *
     * A backdrop filter cannot be cached: the destination it reads may change on any
     * frame and Skia has no way to know that it did not. That is cheap on a GPU and
     * measurably not free without one, so it is worth being able to switch off in one
     * place rather than per surface. Off leaves every other surface property alone --
     * the plane keeps its body, its opacity and its shape, it just stops filtering.
     */
    val surfaceBlur: Boolean = true,

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
 * selection differently. [Square] follows the button corner;
 * [None] shows selection through the icon tint alone.
 */
@Serializable
enum class NavSelectionStyle { Pill, Square, Circle, LeftBar, Dot, None }
