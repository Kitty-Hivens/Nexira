package hivens.ui.background

import androidx.compose.runtime.Composable
import hivens.ui.diag.SkinemaGate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaCanvas
import dev.hivens.skinema.audio.PcmSink
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.player.WhenUnwatched
import dev.hivens.skinema.skiko.VideoFrameImage
import hivens.ui.audio.AudioOutput
import hivens.ui.audio.PlaybackRouter
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.WallpaperSession
import hivens.ui.theme.seedFromRgba
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import org.koin.compose.getKoin
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
 *
 * [takeFrame] is called exactly once per draw and its result is not kept: the
 * drawing thread's most recent read is the one reference that keeps an image
 * alive past the frame that superseded it, so a second read in the same draw
 * would let the first be closed underneath.
 */
internal class VideoFramePainter(
    private val takeFrame: () -> Image?,
    private val displaySize: Size,
    private val rotationDegrees: Int,
    private val frameStamp: () -> Int,
) : Painter() {

    override val intrinsicSize: Size get() = displaySize

    override fun DrawScope.onDraw() {
        frameStamp() // snapshot read -- a new frame invalidates this draw
        val image = takeFrame() ?: return
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
 * Where the wallpaper had got to, carried across a re-open.
 *
 * Whether the picture sounds, how it loops and how it decodes are all constructor
 * arguments, so changing any of them rebuilds the player. Without this the
 * wallpaper went back to its first frame every time somebody flipped the sound,
 * which for a switch a person is expected to try is the wrong answer even though
 * re-opening is the right mechanism.
 *
 * The video widget already carries position, volume and play state across the
 * swap it makes going fullscreen, for the same reason. A wallpaper has no volume
 * to carry (the level is applied to the running player) and no play state (it
 * plays), so one number is the whole of it.
 *
 * Held by the file rather than by the player, so a different wallpaper begins at
 * its own beginning instead of at the last one's offset.
 */
private class BackgroundResume {
    @Volatile
    var positionNanos: Long = 0L
}

/**
 * A Skinema player over one wallpaper file, plus the Skia image its frames land
 * in. Both hold resources the JVM will not reclaim on its own -- a decode thread
 * and a pacer thread, native FFmpeg state, one raster image, and a stream on the
 * sound server when the wallpaper is one that sounds -- and all of them are
 * released together in [release].
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
    audio: Boolean,
    output: AudioOutput?,
    hardware: HwAccel,
    unwatched: WhenUnwatched,
    private val closeScope: CoroutineScope,
) : RememberObserver {

    /**
     * Null when the media natives will not load: a bundle that is missing, or
     * from another FFmpeg line, fails as a [LinkageError] rather than an
     * exception, and this runs during composition, so it used to take the
     * wallpaper down with the window. The caller draws nothing instead, which
     * is what it already does for a file that fails to decode.
     */
    val player: VideoPlayer? = openBackgroundPlayer(file, loop, audio, output, hardware, unwatched)

    private val frames = VideoFrameImage()

    @Volatile
    private var released = false

    /**
     * The frame to draw, null before the first one lands and after release.
     *
     * Called once per draw, from the thread that draws. The reclaim gives back
     * the borrow taken by the previous draw, so the superseded image is freed
     * at the start of this frame rather than at the start of the next one: at
     * 4K that is ~33 MB of native memory held one frame less.
     */
    fun takeFrame(): Image? {
        frames.reclaim()
        return frames.image
    }

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
        val engine = player ?: return
        closeScope.launch(Dispatchers.IO) { engine.close() }
    }
}

/**
 * Opens a wallpaper's player, and owns its stream until skinema does.
 *
 * The sink is named rather than passed inline, for the reason the music player
 * gives about its own: skinema closes the stream it was handed and can only do
 * that once it has one, so a constructor that throws leaves the stream ours to
 * close. A silent wallpaper asks for no stream at all, which is every wallpaper
 * until somebody turns the sound on.
 */
private fun openBackgroundPlayer(
    file: File,
    loop: Boolean,
    audio: Boolean,
    output: AudioOutput?,
    hardware: HwAccel,
    unwatched: WhenUnwatched,
): VideoPlayer? {
    val sink: PcmSink? = if (audio) output?.sink() else null
    return try {
        VideoPlayer(path = file.toPath(), loop = loop, audio = audio, sink = sink, hardware = hardware, unwatched = unwatched)
    } catch (e: LinkageError) {
        runCatching { sink?.close() }
        log.error("Background media natives unavailable for {}", file.absolutePath, e)
        null
    }
}

/**
 * Opens [file] as a looping Skinema player and pumps its frames on the Compose
 * frame clock, returning a [VideoFramePainter] once the first frame has decoded
 * (null before that, and on [VideoPlayer.State.Failed], so the caller draws
 * nothing -- the same gate the still path uses while it decodes).
 *
 * Silent unless [audio] says otherwise, which is the wallpaper's own setting and
 * off by default.
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
    audio: Boolean,
    audioVolume: Float,
    link: Boolean,
    onAudioVolume: (Float) -> Unit,
    onSeed: (Int) -> Unit = {},
): VideoFramePainter? {
    // Skinema disabled by boot recovery -> no animated background (same draw-
    // nothing contract as the decode-failure gate below).
    if (!SkinemaGate.enabled) return null
    // Optional, and read as optional, the same way the music player reads it: what
    // the named output buys is a mixer row that says Nexira rather than an
    // anonymous JVM, and losing that must never cost the sound itself. Resolved in
    // a remember rather than through koinInject so the absence is an answer here
    // instead of a throw out of composition.
    val koin = getKoin()
    val output = remember(koin) { koin.getOrNull<AudioOutput>() }
    // Outlives the player on purpose: it exists to survive the re-open that a
    // constructor argument forces, so it is keyed on the file and on nothing else.
    val resume = remember(file) { BackgroundResume() }
    // Keyed on what the player cannot be told after it is built: the file, the
    // decode policy and whether it carries sound. Those three reach the
    // constructor and nowhere else, so changing one means a new player, and
    // [BackgroundResume] is what keeps the picture where it was across that.
    //
    // The loop mode and the loudness are NOT keys, and for the same reason: both
    // are settable on a running player. Keying on the loop is what this used to
    // do, and skinema's own note on the property names the cost -- a consumer
    // offering a repeat button had to choose between the button and the position.
    val closeScope = koinInject<CoroutineScope>()
    val video = remember(file, hardwareDecode, audio, link) {
        BackgroundVideo(
            file = file,
            // The background loops unless the user pinned it to a single pass.
            loop = loopMode != BackgroundLoopMode.PlayOnce,
            audio = audio,
            output = output,
            // 4K on the CPU is brutal; AUTO offloads to the GPU and falls back
            // to software per file when no device opens.
            hardware = if (hardwareDecode) HwAccel.AUTO else HwAccel.OFF,
            // Freeze stops the clock while nobody takes the picture, which is
            // right for decoration: a minimised window should cost nothing. It is
            // wrong for a track, because minimising would then stop the music. It
            // also removes an ambiguity the link creates, where a gap in the frame
            // pump reads as Paused and is indistinguishable from the real thing now
            // that a real pause freezes the picture too.
            unwatched = if (link) WhenUnwatched.KeepTime else WhenUnwatched.Freeze,
            closeScope = closeScope,
        )
    }
    // No engine means the natives did not load; same draw-nothing contract as
    // the decode-failure gate below.
    val player = video.player ?: return null

    var frameStamp by remember(video) { mutableIntStateOf(0) }
    var displaySize by remember(video) { mutableStateOf<Size?>(null) }

    // The animation-speed slider maps to playback rate; Skinema clamps to
    // [0.5, 4]x internally.
    LaunchedEffect(player, speedMultiplier) { player.setRate(speedMultiplier) }

    // The wall as something that plays, and whether it is what the players are
    // pointed at. Attached whenever it sounds, so a linked wall has a session
    // waiting the instant the link is flipped rather than one frame later;
    // ownership is the narrower question and is asked separately.
    val session = koinInject<WallpaperSession>()
    val router = koinInject<PlaybackRouter>()
    DisposableEffect(video, audio) {
        if (audio) {
            session.attach(
                player = player,
                file = file.toPath(),
                volume = audioVolume,
                repeat = repeatOf(loopMode),
                persistVolume = onAudioVolume,
            )
        }
        onDispose { if (audio) session.detach() }
    }
    // Ownership, and nothing else, so the session can be attached and silent at the
    // same time. The wall only owns what it can be heard doing.
    DisposableEffect(router, audio, link) {
        router.setWallpaperOwns(audio && link)
        onDispose { router.setWallpaperOwns(false) }
    }
    // The settings moved, not the transport, so these report rather than write
    // back: the appearance panel already persists, and echoing it would write the
    // same value a second time through a debounce that is still in flight.
    LaunchedEffect(session, audioVolume) { session.reportVolume(audioVolume) }
    LaunchedEffect(session, loopMode) { session.reportRepeat(repeatOf(loopMode)) }

    // A property write rather than a new player, which is what the property exists
    // for. Read on the decode thread at the end of a lap, so a change lands at the
    // next end of stream and never inside one: turning the loop off part way
    // through still finishes the lap, and turning it on still wraps at the end of
    // the one playing.
    LaunchedEffect(player, loopMode) {
        player.loop = loopMode != BackgroundLoopMode.PlayOnce
    }

    // Settable on a running player, which is why it is an effect and not a
    // constructor argument: turning the wallpaper down must not restart its
    // picture. Applied only where there is a pipeline to apply it to.
    LaunchedEffect(player, audio, audioVolume) {
        if (audio) player.setVolume(audioVolume.coerceIn(0f, 1f))
    }

    LaunchedEffect(video) {
        var resumed = false
        var seedSent = false
        while (true) {
            // A poll, not a frame clock. withFrameNanos here woke this loop once
            // per vsync and, like skinema's own player-state poll above, held
            // Compose's frame clock open so the window redrew at the monitor's
            // refresh. delay lets the clock sleep between frames; a new frame still
            // bumps the stamp and invalidates the draw, so the wallpaper renders on
            // its own cadence rather than the display's. Sixty hertz of polling is
            // comfortably above any wallpaper's frame rate.
            delay(WALLPAPER_POLL_MS)
            // Put the picture back where the previous player left it, once there is
            // a decoder to ask. Zero is a first open on this file, which starts
            // where the file does and needs no seek.
            //
            // Inexact for the reason the transport's own seek carries in full: an
            // exact landing on a GPU-decoded file intermittently fails to download
            // its frame, and skinema ends the player on that. A wallpaper put back
            // roughly where it was is the whole requirement here anyway.
            if (!resumed && player.state != VideoPlayer.State.Opening) {
                resumed = true
                resume.positionNanos.takeIf { it > 0L }?.let { player.seek(it, exact = false) }
            }
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
            // Read from the pump rather than on the way out. The close is
            // asynchronous and joins a decode thread, so asking a player being torn
            // down where it had got to races the thread that owns the clock. A zero
            // is a player without one yet and is not a position.
            player.positionNanos().takeIf { it > 0L }?.let { resume.positionNanos = it }
        }
    }

    // Polled slowly, on purpose, and not through skinema's rememberPlayerState.
    // That helper reads the player's state inside a withFrameNanos loop, i.e. once
    // per vsync, which keeps Compose's frame clock awake for as long as a video
    // wallpaper is up: the window then re-rasterises at the monitor's refresh
    // (measured at 96% of the render engine on a 210 Hz display) to serve a video
    // that changes a couple of dozen times a second. The only thing read off the
    // state here is whether the decode failed, which is a rare, early, one-shot
    // fact, so a quarter-second poll catches it without holding the clock open.
    // With this gone the sole remaining invalidation is the frame stamp the pump
    // bumps, so the wallpaper renders when a frame lands rather than every vsync.
    var state by remember(player) { mutableStateOf(player.state) }
    LaunchedEffect(player) {
        while (true) {
            state = player.state
            delay(250)
        }
    }
    val failed = state
    if (failed is VideoPlayer.State.Failed) {
        LaunchedEffect(player) { log.error("Background media failed to play: {}", file.absolutePath, failed.cause) }
        return null
    }

    val size = displaySize ?: return null
    return remember(video, size) {
        VideoFramePainter(
            takeFrame       = { video.takeFrame() },
            displaySize     = size,
            rotationDegrees = player.rotationDegrees,
            frameStamp      = { frameStamp },
        )
    }
}

/**
 * The wallpaper's loop, as the transport names it.
 *
 * A wallpaper turns the lap or it does not, which is one bit, while a repeat mode
 * has three states because a music queue has an end to wrap. There is no queue
 * here, so [RepeatMode.Queue] has nothing to go round and never appears: a lap that
 * repeats IS the one-entry case that mode already collapses into.
 */
private fun repeatOf(mode: BackgroundLoopMode): RepeatMode =
    if (mode == BackgroundLoopMode.PlayOnce) RepeatMode.Off else RepeatMode.One

private const val WALLPAPER_POLL_MS = 16L
