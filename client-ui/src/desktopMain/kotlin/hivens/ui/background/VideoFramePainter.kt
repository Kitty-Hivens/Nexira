package hivens.ui.background

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaCanvas
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.VideoFrameImage
import hivens.ui.theme.seedFromRgba
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("SkinemaBackground")

/**
 * Draws the current frame of a Skinema [VideoPlayer] as a [Painter], so the
 * background's existing `Image(painter, contentScale, alignment, modifier)`
 * applies every wallpaper setting to video and animated images exactly as it
 * does to a still: [intrinsicSize] reports the displayed frame size for
 * ContentScale/alignment, and [rotationDegrees] is applied at draw time.
 *
 * [frameStamp] is read inside [onDraw] so the pump bumping it invalidates the
 * draw scope -- the same snapshot-read trick VideoSurface uses.
 */
internal class VideoFramePainter(
    private val frames: VideoFrameImage,
    private val displaySize: Size,
    private val rotationDegrees: Int,
    private val frameStamp: () -> Int,
) : Painter() {

    override val intrinsicSize: Size get() = displaySize

    override fun DrawScope.onDraw() {
        frameStamp() // snapshot read -- a new frame invalidates this draw
        val image = frames.image ?: return
        val w = size.width
        val h = size.height
        drawIntoCanvas { canvas ->
            val nc = canvas.skiaCanvas
            nc.save()
            if (rotationDegrees != 0) nc.rotate(rotationDegrees.toFloat(), w / 2f, h / 2f)
            // The rect the image draws into BEFORE the rotation transform (its
            // storage orientation): the displayed bounds for upright frames,
            // sides swapped around the same center for quarter turns.
            val dst = if (rotationDegrees % 180 == 0)
                Rect.makeWH(w, h)
            else
                Rect.makeXYWH((w - h) / 2f, (h - w) / 2f, h, w)
            nc.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                dst,
                SamplingMode.LINEAR,
                null,
                true,
            )
            nc.restore()
        }
    }
}

/**
 * Opens [file] as a looping, silent Skinema player and pumps its frames on the
 * Compose frame clock, returning a [VideoFramePainter] once the first frame has
 * decoded (null before that, and on [VideoPlayer.State.Failed], so the caller
 * draws nothing -- the same gate the still path uses while it decodes).
 *
 * The player holds a decode thread and native memory; it is closed on dispose
 * (when [file] changes or the background leaves the composition).
 */
@Composable
internal fun rememberSkinemaFrame(
    file: File,
    speedMultiplier: Float,
    loopMode: BackgroundLoopMode,
    onSeed: (Int) -> Unit = {},
): VideoFramePainter? {
    val player = remember(file) {
        VideoPlayer(
            path  = file.toPath(),
            // The background loops unless the user pinned it to a single pass.
            loop  = loopMode != BackgroundLoopMode.PlayOnce,
            audio = false,
        )
    }
    DisposableEffect(player) { onDispose { player.close() } }

    val frames = remember(player) { VideoFrameImage() }
    DisposableEffect(frames) { onDispose { frames.close() } }

    var frameStamp by remember(player) { mutableIntStateOf(0) }
    var displaySize by remember(player) { mutableStateOf<Size?>(null) }

    // The animation-speed slider maps to playback rate; Skinema clamps to
    // [0.5, 4]x internally.
    LaunchedEffect(player, speedMultiplier) { player.setRate(speedMultiplier) }

    LaunchedEffect(player) {
        var seedSent = false
        while (true) {
            withFrameNanos { }
            player.acquireFrame()?.let { slot ->
                frames.update(slot.width, slot.height, slot.rgba)
                if (displaySize == null) {
                    val rot = player.rotationDegrees
                    displaySize = if (rot % 180 == 0)
                        Size(slot.width.toFloat(), slot.height.toFloat())
                    else
                        Size(slot.height.toFloat(), slot.width.toFloat())
                }
                // Seed the Material-You palette from the first decoded frame (once).
                if (!seedSent) seedFromRgba(slot.rgba, slot.width, slot.height)?.let { seedSent = true; onSeed(it) }
                frameStamp++
            }
        }
    }

    val state = rememberPlayerState(player)
    if (state is VideoPlayer.State.Failed) {
        LaunchedEffect(player) { log.error("Background media failed to play: {}", file.absolutePath, state.cause) }
        return null
    }

    val size = displaySize ?: return null
    return remember(player, size) {
        VideoFramePainter(frames, size, player.rotationDegrees) { frameStamp }
    }
}
