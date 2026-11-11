package hivens.ui.background

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import dev.hivens.skinema.compose.VideoFrameHolder
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.player.WhenUnwatched
import hivens.ui.theme.WallpaperTone
import hivens.ui.theme.coloursFromRgba
import hivens.ui.theme.luminanceOfArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("SkinemaWallpaper")

/** The player's close joins its threads, so it goes off the thread that composes. */
private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * A player over one wallpaper file and the holder its frames land in. Both are released together,
 * from [RememberObserver] rather than an effect because the player starts inside the constructor,
 * during composition, and an abandoned composition never runs its effects.
 */
private class WallpaperPlayer(file: File, loop: Boolean, audio: Boolean) : RememberObserver {

    val player: VideoPlayer? = try {
        VideoPlayer(
            input = MediaInput.File(file.toPath()),
            loop = loop,
            audio = audio,
            hardware = HwAccel.AUTO,
            unwatched = WhenUnwatched.Freeze,
        )
    } catch (e: Exception) {
        log.error("The player does not open {}", file.absolutePath, e)
        null
    }

    val frames = VideoFrameHolder()

    @Volatile
    private var released = false

    override fun onRemembered() = Unit

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    private fun release() {
        if (released) return
        released = true
        frames.close()
        val engine = player ?: return
        closeScope.launch { engine.close() }
    }
}

/** Draws the holder's current frame. [stamp] is read in the draw scope so a new frame invalidates it. */
private class FramePainter(
    private val frames: VideoFrameHolder,
    private val displaySize: Size,
    private val stamp: () -> Int,
) : Painter() {

    override val intrinsicSize: Size get() = displaySize

    override fun DrawScope.onDraw() {
        val _ = stamp()
        val frame = frames.frameForDraw() ?: return
        with(frame) { drawInto(Rect(Offset.Zero, size)) }
    }
}

/**
 * Video and animated images through skinema's Android engine: Media3 on the phone's own decoders.
 * Null until the first frame lands, and for a file the player cannot play, so the background draws
 * nothing meanwhile.
 */
@Composable
internal fun rememberSkinemaWallpaper(
    file: File,
    settings: BackgroundSettings,
    onTone: (WallpaperTone) -> Unit,
): Painter? {
    val wallpaper = remember(file, settings.audio) {
        WallpaperPlayer(file, loop = settings.loopMode != BackgroundLoopMode.PlayOnce, audio = settings.audio)
    }
    val player = wallpaper.player ?: return null

    var stamp by remember(wallpaper) { mutableIntStateOf(0) }
    var displaySize by remember(wallpaper) { mutableStateOf<Size?>(null) }

    LaunchedEffect(player, settings.animationSpeedMultiplier) { player.setRate(settings.animationSpeedMultiplier) }
    LaunchedEffect(player, settings.loopMode) { player.loop = settings.loopMode != BackgroundLoopMode.PlayOnce }
    LaunchedEffect(player, settings.audio, settings.audioVolume) {
        if (settings.audio) player.setVolume(settings.audioVolume.coerceIn(0f, 1f))
    }

    LaunchedEffect(wallpaper) {
        while (true) {
            withFrameNanos { }
            if (!wallpaper.frames.update(player)) continue
            if (displaySize == null) {
                val first = wallpaper.frames.copyPixels() ?: continue
                displaySize = Size(first.width.toFloat(), first.height.toFloat())
                val colours = withContext(Dispatchers.Default) { coloursFromRgba(first.rgba, first.width, first.height) }
                if (colours.isNotEmpty()) {
                    onTone(WallpaperTone(colours = colours, avgLuminance = luminanceOfArgb(colours.first())))
                }
            }
            stamp++
        }
    }

    val state = rememberPlayerState(player)
    if (state is VideoPlayer.State.Failed) {
        LaunchedEffect(player) { log.error("The wallpaper failed to play: {}", file.absolutePath, state.cause) }
        return null
    }

    val size = displaySize ?: return null
    return remember(wallpaper, size) { FramePainter(wallpaper.frames, size) { stamp } }
}
