package hivens.ui.background

import androidx.compose.runtime.Composable
import hivens.ui.diag.SkinemaGate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
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
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.VideoFrameImage
import hivens.ui.theme.seedFromRgba
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import org.koin.compose.koinInject
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
    private val currentImage: () -> Image?,
    private val displaySize: Size,
    private val rotationDegrees: Int,
    private val frameStamp: () -> Int,
) : Painter() {

    override val intrinsicSize: Size get() = displaySize

    override fun DrawScope.onDraw() {
        frameStamp() // snapshot read -- a new frame invalidates this draw
        val image = currentImage() ?: return
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
 * A silent Skinema player over one wallpaper file, plus the Skia image its
 * frames land in. Both hold resources the JVM will not reclaim on its own -- a
 * decode thread and a pacer thread, native FFmpeg state, one raster image --
 * and both are released together in [release].
 *
 * A [RememberObserver] rather than a `DisposableEffect`, because the decode
 * thread starts inside the constructor, i.e. during composition: a composition
 * that is abandoned before it applies never runs its effects, and the player
 * created for it would then be running with nothing left holding a reference to
 * close it. [onAbandoned] is the only callback that covers that.
 */
private class BackgroundVideo(
    file: File,
    loop: Boolean,
    hardware: HwAccel,
    private val closeScope: CoroutineScope,
) : RememberObserver {

    val player = VideoPlayer(path = file.toPath(), loop = loop, audio = false, hardware = hardware)

    private val frames = VideoFrameImage()

    @Volatile
    private var released = false

    /** The frame on screen; null before the first one lands and after release. */
    val image: Image? get() = frames.image

    /**
     * Copies a decoded frame into the Skia image. Ignored once released: the
     * pump is cancelled asynchronously, and an update landing after the close
     * would allocate a native image with nothing left to free it.
     */
    fun update(slot: VideoPlayer.FrameSlot) {
        if (!released) frames.update(slot.width, slot.height, slot.rgba)
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    private fun release() {
        if (released) return
        released = true
        // The Skia image belongs to the thread that draws it, so it goes here and
        // now; the player's close joins a decode thread that may be mid-seek, and
        // waiting for that on the thread painting the next wallpaper is a freeze
        // over a change the user just made.
        frames.close()
        closeScope.launch(Dispatchers.IO) { player.close() }
    }
}

/**
 * Opens [file] as a looping, silent Skinema player and pumps its frames on the
 * Compose frame clock, returning a [VideoFramePainter] once the first frame has
 * decoded (null before that, and on [VideoPlayer.State.Failed], so the caller
 * draws nothing -- the same gate the still path uses while it decodes).
 *
 * The player holds a decode thread and native memory; it is released when [file]
 * or a playback setting changes, and when the background leaves the composition.
 */
@Composable
internal fun rememberSkinemaFrame(
    file: File,
    speedMultiplier: Float,
    loopMode: BackgroundLoopMode,
    hardwareDecode: Boolean,
    onSeed: (Int) -> Unit = {},
): VideoFramePainter? {
    // Skinema disabled by boot recovery -> no animated background (same draw-
    // nothing contract as the decode-failure gate below).
    if (!SkinemaGate.enabled) return null
    // Keyed on the decode policy and the loop mode as well as the file: both are
    // constructor arguments, so neither takes effect until the player re-opens,
    // and keying only on the file left the loop setting inert until the wallpaper
    // itself changed.
    val closeScope = koinInject<CoroutineScope>()
    val video = remember(file, hardwareDecode, loopMode) {
        BackgroundVideo(
            file = file,
            // The background loops unless the user pinned it to a single pass.
            loop = loopMode != BackgroundLoopMode.PlayOnce,
            // 4K on the CPU is brutal; AUTO offloads to the GPU and falls back
            // to software per file when no device opens.
            hardware = if (hardwareDecode) HwAccel.AUTO else HwAccel.OFF,
            closeScope = closeScope,
        )
    }
    val player = video.player

    var frameStamp by remember(video) { mutableIntStateOf(0) }
    var displaySize by remember(video) { mutableStateOf<Size?>(null) }

    // The animation-speed slider maps to playback rate; Skinema clamps to
    // [0.5, 4]x internally.
    LaunchedEffect(player, speedMultiplier) { player.setRate(speedMultiplier) }

    LaunchedEffect(video) {
        var seedSent = false
        while (true) {
            withFrameNanos { }
            player.acquireFrame()?.let { slot ->
                video.update(slot)
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
    return remember(video, size) {
        VideoFramePainter(
            currentImage    = { video.image },
            displaySize     = size,
            rotationDegrees = player.rotationDegrees,
            frameStamp      = { frameStamp },
        )
    }
}
