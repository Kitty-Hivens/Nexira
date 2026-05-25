package hivens.ui.customization

import kotlinx.serialization.Serializable

/**
 * Persistent customization layer that sits on top of the active theme
 * and bg settings. Stored in {configDir}/customization.json.
 *
 * Default state is a no-op -- every field is null / 1.0 / false so an
 * unconfigured user sees the same UI as before customization existed.
 *
 * [experimentalColorOverridesEnabled] gates [colorOverrides] -- by
 * default only [accentOverride] is exposed. Flipping the experimental
 * flag unlocks per-role color picking which is easy to misuse into
 * unreadable combinations.
 */
@Serializable
data class CustomizationSettings(
    val densityScale: Float = 1.0f,
    val glassIntensity: Float = 1.0f,
    val accentOverride: String? = null,
    val experimentalColorOverridesEnabled: Boolean = false,
    val colorOverrides: Map<String, String> = emptyMap(),
)

/**
 * Per-role keys for [CustomizationSettings.colorOverrides]. Stable
 * strings so existing customization.json files keep working across
 * theme refactors.
 */
object ColorRole {
    const val PRIMARY    = "primary"
    const val SECONDARY  = "secondary"
    const val BACKGROUND = "background"
    const val SURFACE    = "surface"
    const val SUCCESS    = "success"
    const val ERROR      = "error"
    const val OUTLINE    = "outline"
}
