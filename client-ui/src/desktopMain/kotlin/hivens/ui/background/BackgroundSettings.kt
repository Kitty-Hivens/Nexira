package hivens.ui.background

import kotlinx.serialization.Serializable
import java.io.File

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
    /**
     * Decode a video wallpaper on the GPU when a device is available
     * (Skinema HwAccel.AUTO), falling back to software per file otherwise.
     * On by default: a 4K wallpaper is decoded in fixed-function silicon
     * instead of saturating the CPU. Set false to force software decode
     * (an escape hatch for a driver that opens but glitches mid-stream).
     */
    val hardwareDecode: Boolean = true,
)

/**
 * The custom background can actually be drawn: it is enabled, has a path, and the
 * file still exists. Gate transparency / "show the wallpaper" on this rather than
 * [BackgroundSettings.enabled] alone -- a deleted image left the app transparent
 * over a blank (white) window.
 */
fun BackgroundSettings.hasUsableImage(): Boolean =
    enabled && !imagePath.isNullOrBlank() && File(imagePath).exists()

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
