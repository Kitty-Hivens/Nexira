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
    val tintOpacity: Float = 0.0f,
    val animationSpeedMultiplier: Float = 1.0f,
    val loopMode: BackgroundLoopMode = BackgroundLoopMode.UseCodec,
)

@Serializable
enum class ScaleMode { COVER, CONTAIN, STRETCH, ORIGINAL, TILE }

/**
 * Loop semantics for multi-frame backgrounds (GIF / APNG / animated
 * WebP). Static formats ignore this -- there is nothing to loop.
 *
 * UseCodec: honor the codec's own repetitionCount field (-1 = forever,
 *           N = N additional plays after the first). Default.
 * LoopForever: ignore the codec, play indefinitely.
 * PlayOnce: ignore the codec, freeze on the last frame after one pass.
 */
@Serializable
enum class BackgroundLoopMode { UseCodec, LoopForever, PlayOnce }
