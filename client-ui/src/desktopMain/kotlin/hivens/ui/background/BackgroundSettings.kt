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
    /**
     * Decode and play the wallpaper's own audio track.
     *
     * Off, and that is a decision rather than a starting value. A wallpaper is a
     * picture, and one that begins making noise because the file picked happened
     * to carry a soundtrack is not what anybody asked for. Switched on, the
     * background opens the file's audio stream and the launcher has a second
     * voice beside the music player.
     *
     * [animationSpeedMultiplier] reaches the same player as a playback rate, so a
     * speed other than 1 shifts this sound's tempo along with the picture. One
     * setting keeps one meaning: a branch that quietly stopped applying the speed
     * once the sound was on would be a second rule with nothing on screen to say
     * so.
     */
    val audio: Boolean = false,
    /**
     * Linear 0..1 for the wallpaper's own sound and nothing else. The music
     * player carries its own level, so turning the wall down leaves a track where
     * it was.
     */
    val audioVolume: Float = 1.0f,
    /**
     * Hand the clip on the wall to the player widgets, so the transport, the
     * position and the media keys address it.
     *
     * Requires [audio], and the control says so rather than accepting the click:
     * a wall the transport drives but nobody hears is a scrubber over a
     * decoration. It also requires a wallpaper that moves at all, since a still
     * has no playhead, no duration and no position, and there is nothing for a
     * session to be.
     *
     * Off, like [audio], and for a second reason on top of the first. Switched
     * on, the wallpaper stops being decoration and becomes a track, which changes
     * what a pause means: it stops the picture as well as the sound, because
     * keeping the animation running over stopped audio is the incoherent half.
     * Nobody should meet that without having asked for it.
     */
    val linkToPlayers: Boolean = false,
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
