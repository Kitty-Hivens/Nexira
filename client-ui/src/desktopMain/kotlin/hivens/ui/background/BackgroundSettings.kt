package hivens.ui.background

import kotlinx.serialization.Serializable

/**
 * Persistent settings for the custom background wallpaper.
 * Stored in {configDir}/background.json.
 */
@Serializable
data class BackgroundSettings(
    val enabled: Boolean = false,
    val imagePath: String? = null,
    val blurRadius: Float = 0f,
    val darkenAmount: Float = 0.4f,
    val opacity: Float = 1.0f,
    val saturation: Float = 0.0f,
    val scaleMode: ScaleMode = ScaleMode.COVER,
    val alignX: Float = 0.5f,
    val alignY: Float = 0.5f,
    val parallaxIntensity: Float = 0.0f,
    val vignetteIntensity: Float = 0.2f,
    val tintColor: String? = null,
    val tintOpacity: Float = 0.0f
)

@Serializable
enum class ScaleMode { COVER, CONTAIN, STRETCH, ORIGINAL, TILE }
