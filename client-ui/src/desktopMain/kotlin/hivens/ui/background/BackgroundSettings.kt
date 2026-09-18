package hivens.ui.background

import kotlinx.serialization.SerialName
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
    /**
     * How the image fills the window, on the wire.
     *
     * A string rather than the enum, for the reason the layout format already
     * gives about itself: an enum on the wire read by a build that does not know
     * the value has to either throw or guess. Throwing lost the whole record and
     * guessing wrote the guess back, so one launch of an older build destroyed a
     * choice a newer one had made. A string is carried through verbatim, and the
     * value this build does not know survives for the build that does.
     *
     * Read it through [scaleMode] and write it through [withScaleMode].
     */
    @SerialName("scaleMode") val scaleModeWire: String = ScaleMode.COVER.name,
    val alignX: Float = 0.5f,
    val alignY: Float = 0.5f,
    val parallaxIntensity: Float = 0.0f,
    val vignetteIntensity: Float = 0.2f,
    val tintColor: String? = null,
    val tintOpacity: Float = 0.0f,
    val animationSpeedMultiplier: Float = 1.0f,
    /** Loop semantics on the wire. A string for the reason given on [scaleModeWire]. */
    @SerialName("loopMode") val loopModeWire: String = BackgroundLoopMode.UseCodec.name,
    /**
     * Decode a video wallpaper on the GPU when a device is available
     * (Skinema HwAccel.AUTO), falling back to software per file otherwise.
     * On by default: a 4K wallpaper is decoded in fixed-function silicon
     * instead of saturating the CPU. Set false to force software decode
     * (an escape hatch for a driver that opens but glitches mid-stream).
     */
    val hardwareDecode: Boolean = true,
) {
    /** The scale this build understands, or the default when the file names one it does not. */
    val scaleMode: ScaleMode get() = parseScaleMode(scaleModeWire)

    /** The loop semantics this build understands, or the default. */
    val loopMode: BackgroundLoopMode get() = parseLoopMode(loopModeWire)

    fun withScaleMode(mode: ScaleMode): BackgroundSettings = copy(scaleModeWire = mode.name)

    fun withLoopMode(mode: BackgroundLoopMode): BackgroundSettings = copy(loopModeWire = mode.name)
}

/** Case and surrounding space are forgiven: this file is editable by hand. */
fun parseScaleMode(value: String): ScaleMode =
    ScaleMode.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: ScaleMode.COVER

fun parseLoopMode(value: String): BackgroundLoopMode =
    BackgroundLoopMode.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        ?: BackgroundLoopMode.UseCodec

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
