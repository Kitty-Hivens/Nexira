package hivens.module.osusb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import hivens.widget.api.rememberProps
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Plays an osu! storyboard: the declarative sprite timeline that ships beside a
 * beatmap.
 *
 * This module exists as a question rather than as a feature. A storyboard is
 * about as demanding as decorative motion gets -- tens of sprites, thousands of
 * typed tweens, nested loops, additive blending, per-sprite colour -- and every
 * line of it here is written against the published widget kernel and nothing
 * else. Where the kernel could not carry something, the gap is named in the
 * module's README rather than worked around by reaching into the launcher.
 */

@Serializable
data class StoryboardProps(
    /** Beatmap folder holding the `.osb`, the `.osu` files and the sprite images. */
    val folder: String = "",
    /** Where playback starts, in milliseconds of the track. */
    val startMs: Int = 0,
    /** Playback rate. 1 is real time. */
    val speed: Float = 1f,
    /** Restart from [startMs] when the timeline runs out. */
    val loop: Boolean = true,
    /** Letterbox colour behind the storyboard. */
    val letterbox: Boolean = true,
)

/** Storyboard space is 640x480, and a widescreen beatmap draws out to 854 wide. */
private const val SB_W = 640f
private const val SB_H = 480f
private const val SB_WIDE = 854f

internal class Loaded(
    val storyboard: Storyboard,
    val images: Map<String, ImageBitmap>,
)

private fun decode(file: File): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()

/** Resolves a storyboard path against the beatmap folder, case-insensitively. */
private fun resolve(dir: File, path: String): File? {
    val direct = File(dir, path)
    if (direct.isFile) return direct
    val parts = path.split('/')
    var cur = dir
    for ((idx, part) in parts.withIndex()) {
        val next = cur.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        if (idx == parts.lastIndex) return next.takeIf { it.isFile }
        cur = next
    }
    return null
}

internal suspend fun load(folder: String): Loaded? = withContext(Dispatchers.IO) {
    val dir = File(folder)
    val sb = loadStoryboard(dir) ?: return@withContext null
    val images = HashMap<String, ImageBitmap>()
    for (sprite in sb.sprites) {
        if (images.containsKey(sprite.path)) continue
        val file = resolve(dir, sprite.path) ?: continue
        decode(file)?.let { images[sprite.path] = it }
    }
    Loaded(sb, images)
}

@Widget(
    id = "osusb.storyboard",
    displayName = "osusb.storyboard",
    propsClass = StoryboardProps::class,
    surface = """{"fill":"","padding":{"all":0.0}}""",
    drawsOwnSurface = true,
)
@Composable
fun OsuStoryboardWidget(instance: WidgetInstance) {
    val props = instance.rememberProps<StoryboardProps>()
    var loaded by remember(props.folder) { mutableStateOf<Loaded?>(null) }
    var nowMs by remember(props.folder) { mutableLongStateOf(props.startMs.toLong()) }

    LaunchedEffect(props.folder) {
        loaded = if (props.folder.isBlank()) null else load(props.folder)
    }

    val sb = loaded
    LaunchedEffect(sb, props.speed, props.loop, props.startMs) {
        if (sb == null) return@LaunchedEffect
        var last = 0L
        val rate = props.speed.coerceIn(0.05f, 8f)
        while (true) {
            withFrameNanos { frame ->
                if (last != 0L) {
                    val delta = (frame - last) / 1_000_000f * rate
                    var next = nowMs + delta.toLong()
                    if (next > sb.storyboard.duration) {
                        next = if (props.loop) props.startMs.toLong() else sb.storyboard.duration.toLong()
                    }
                    nowMs = next
                }
                last = frame
            }
        }
    }

    Box(Modifier.fillMaxSize().background(if (props.letterbox) Color.Black else Color.Transparent)) {
        if (sb == null) return@Box
        val state = remember { SpriteState() }
        Canvas(Modifier.fillMaxSize()) {
            drawStoryboard(sb, nowMs.toInt(), state)
        }
    }
}

/**
 * Draws one frame.
 *
 * Sprites are already in declaration order inside their layer, and the layer
 * order is the enum's, so a single sorted pass reproduces osu's compositing
 * without building a scene graph per frame.
 */
internal fun DrawScope.drawStoryboard(loaded: Loaded, now: Int, state: SpriteState) {
    val scale = size.height / SB_H
    val originX = size.width / 2f - (SB_W / 2f) * scale
    val originY = 0f
    val widescreenGuard = (SB_WIDE - SB_W) / 2f * scale

    for (layer in SbLayer.entries) {
        for (sprite in loaded.storyboard.sprites) {
            if (sprite.layer != layer) continue
            if (!evaluate(sprite, now, state)) continue
            val image = loaded.images[sprite.path] ?: continue

            val (ax, ay) = originFractions(sprite.origin)
            val w = image.width.toFloat()
            val h = image.height.toFloat()
            val px = originX + state.x * scale
            val py = originY + state.y * scale
            // Off-screen by more than the widescreen margin is not worth a draw call.
            if (px < -widescreenGuard - w * scale || px > size.width + widescreenGuard + w * scale) continue

            val sx = state.scaleX * scale * (if (state.flipH) -1f else 1f)
            val sy = state.scaleY * scale * (if (state.flipV) -1f else 1f)
            if (sx == 0f || sy == 0f) continue

            val tint = if (state.red != 1f || state.green != 1f || state.blue != 1f) {
                ColorFilter.tint(Color(state.red, state.green, state.blue), BlendMode.Modulate)
            } else {
                null
            }

            withTransform({
                translate(px, py)
                if (state.rotation != 0f) rotate(state.rotation * 180f / PI.toFloat(), Offset.Zero)
                scale(sx, sy, Offset.Zero)
            }) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset((-ax * w).roundToInt(), (-ay * h).roundToInt()),
                    dstSize = IntSize(image.width, image.height),
                    alpha = state.alpha.coerceIn(0f, 1f),
                    colorFilter = tint,
                    blendMode = if (state.additive) BlendMode.Plus else BlendMode.SrcOver,
                )
            }
        }
    }
}
