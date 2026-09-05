package hivens.ui.components

import androidx.compose.foundation.background
import hivens.ui.diag.SkinemaGate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.hivens.skinema.compose.VideoScale
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.player.VideoPlayer as SkinemaPlayer
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("VideoPlayer")

/**
 * Interactive video player over a LOCAL file (Skinema is local-only -- a URL goes
 * through [VideoMedia]/[rememberVideoResolution] first). Draws frames via Skinema's
 * [VideoSurface] and overlays transport chrome -- play/pause, a seek scrubber on
 * the real seek, a timecode, a volume control and a fullscreen affordance.
 * Clicking the picture toggles playback; the controls reveal on hover, hide
 * after a moment of stillness and stay up whenever playback is not running.
 *
 * @param handoff carries the position, volume and play state between two players
 *   over the same file -- what makes the swap into and out of fullscreen a
 *   continuation rather than a restart.
 * @param onRequestFullscreen when set, shows a fullscreen button calling it.
 * @param onExitFullscreen when set, shows the same control inverted -- the way
 *   back belongs next to the way in, not only in a corner cross that reads as
 *   "stop watching".
 */
@Composable
fun VideoPlayer(
    path: Path,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    loop: Boolean = false,
    audio: Boolean = true,
    startMuted: Boolean = false,
    showControls: Boolean = true,
    scale: VideoScale = VideoScale.Fit,
    handoff: VideoHandoff? = null,
    onRequestFullscreen: (() -> Unit)? = null,
    onExitFullscreen: (() -> Unit)? = null,
) {
    // Skinema disabled by boot recovery -> render the same unavailable chrome the
    // Failed state shows, without constructing the native player.
    if (!SkinemaGate.enabled) {
        VideoUnavailable(modifier)
        return
    }
    val video = rememberInlineVideo(path, loop, audio)
    // Same chrome again when the natives themselves would not load: the engine
    // is built during composition, so an unhandled failure there is the window
    // going down rather than one video refusing to play.
    val player = video.player ?: run {
        VideoUnavailable(modifier)
        return
    }

    val state = rememberPlayerState(player)
    val isPlaying = state == SkinemaPlayer.State.Playing
    val ended = state == SkinemaPlayer.State.Ended

    var muted by remember(player) { mutableStateOf(handoff?.muted ?: startMuted) }
    var volume by remember(player) { mutableStateOf(handoff?.volume ?: 1f) }
    LaunchedEffect(player, muted, volume) {
        player.setVolume(if (muted) 0f else volume)
        handoff?.let { it.volume = volume; it.muted = muted }
    }

    // Land where the previous player stood before anything is heard: skinema
    // opens playing, and the seek is queued behind the open on its own thread.
    LaunchedEffect(player) {
        handoff?.positionNanos?.takeIf { it > 0L }?.let { player.seek(it, exact = false) }
        if (!(handoff?.playing ?: autoPlay)) player.pause()
    }

    var positionNanos by remember(player) { mutableStateOf(handoff?.positionNanos ?: 0L) }
    var scrubbing by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionNanos = player.positionNanos()
                handoff?.positionNanos = positionNanos
            }
            // Only settled states are worth carrying: an Opening player is not
            // paused, it has not started, and recording that would hand the next
            // player a false "was stopped".
            when (player.state) {
                SkinemaPlayer.State.Playing -> handoff?.playing = true
                SkinemaPlayer.State.Paused  -> handoff?.playing = false
                else                        -> Unit
            }
            delay(200.milliseconds)
        }
    }
    val durationNanos = player.durationNanos ?: 0L

    val togglePlayback = {
        when {
            isPlaying -> player.pause()
            ended     -> player.seek(0L)
            else      -> player.resume()
        }
    }

    // Reveal on hover, hide after a beat of stillness, and stay up whenever
    // playback is not running -- controls parked over the picture for the whole
    // runtime were the complaint, and a pointer resting on the frame is not a
    // request to keep them.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var lastPointerNanos by remember(player) { mutableStateOf(0L) }
    var pointerIdle by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player, lastPointerNanos, isPlaying) {
        pointerIdle = false
        if (isPlaying) {
            delay(CONTROLS_IDLE_MS.milliseconds)
            pointerIdle = true
        }
    }
    val controlsShown = showControls && (!isPlaying || (hovered && !pointerIdle))

    Box(
        modifier
            .hoverable(hover)
            // Play/pause by clicking the frame is the first thing anyone tries.
            // No indication: a ripple over a moving picture is noise, and the
            // transport buttons consume their own clicks before this sees them.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = togglePlayback,
            )
            .pointerInput(player) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) lastPointerNanos = System.nanoTime()
                    }
                }
            },
    ) {
        VideoSurface(player, Modifier.fillMaxSize(), scale = scale)

        when (state) {
            SkinemaPlayer.State.Opening, SkinemaPlayer.State.Seeking ->
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
            is SkinemaPlayer.State.Failed -> VideoUnavailable(Modifier.fillMaxSize())
            else -> {}
        }

        if (controlsShown && state !is SkinemaPlayer.State.Failed) {
            VideoControls(
                isPlaying     = isPlaying,
                ended         = ended,
                positionNanos = positionNanos,
                durationNanos = durationNanos,
                muted         = muted,
                volume        = volume,
                showVolume    = audio,
                fullscreen    = when {
                    onExitFullscreen != null    -> FullscreenControl.Exit
                    onRequestFullscreen != null -> FullscreenControl.Enter
                    else                        -> FullscreenControl.None
                },
                onPlayPause   = togglePlayback,
                onSkipBack    = { player.seekBy(-SKIP_NANOS, exact = false) },
                onSkipForward = { player.seekBy(SKIP_NANOS, exact = false) },
                onScrubStart  = { scrubbing = true },
                onScrub       = { frac ->
                    positionNanos = (frac * durationNanos).toLong()
                    if (durationNanos > 0) player.seek((frac * durationNanos).toLong(), exact = false)
                },
                onScrubEnd    = { frac ->
                    if (durationNanos > 0) player.seek((frac * durationNanos).toLong(), exact = true)
                    scrubbing = false
                },
                onToggleMute  = { muted = !muted },
                onVolume      = { v -> volume = v; if (v > 0f) muted = false },
                onFullscreen  = { (onExitFullscreen ?: onRequestFullscreen)?.invoke() },
                modifier      = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * One skinema player over one file, released when the composition that asked for
 * it goes away.
 *
 * A [RememberObserver] rather than a `DisposableEffect`, because the decode
 * thread starts inside the constructor, i.e. during composition: a composition
 * abandoned before it applies never runs its effects, and the player made for it
 * would be left running with nothing holding a reference to close it.
 *
 * The close itself is handed to the app scope. It joins the decode thread, which
 * may be in the middle of a seek run, and the swap between the inline player and
 * the fullscreen one disposes this player while the user is waiting for the
 * other -- exactly where that join would be felt as a frozen window.
 */
private class InlineVideo(
    path: Path,
    loop: Boolean,
    audio: Boolean,
    private val closeScope: CoroutineScope,
) : RememberObserver {

    /**
     * Hardware decode where a device is available (AUTO falls back to software
     * per file), so a 4K clip is not decoded on the CPU.
     *
     * Null when the media natives will not load. That failure is a
     * [LinkageError] rather than an exception, and it lands during composition,
     * so it used to leave the caller with nothing to catch it.
     */
    val player: SkinemaPlayer? = try {
        SkinemaPlayer(path = path, loop = loop, audio = audio, hardware = HwAccel.AUTO)
    } catch (e: LinkageError) {
        log.error("Video natives unavailable for {}", path, e)
        null
    }

    @Volatile
    private var released = false

    override fun onRemembered() = Unit

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    private fun release() {
        if (released) return
        released = true
        val engine = player ?: return
        closeScope.launch(Dispatchers.IO) { engine.close() }
    }
}

/** What a video shows when there is no engine to play it: the disabled module, absent natives, a file that failed. */
@Composable
private fun VideoUnavailable(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text  = LocalStrings.current.videoError,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun rememberInlineVideo(path: Path, loop: Boolean, audio: Boolean): InlineVideo {
    val closeScope = koinInject<CoroutineScope>()
    return remember(path, loop, audio) { InlineVideo(path, loop, audio, closeScope) }
}

/**
 * Full-window video over a scrim, dismissed by the close button, a backdrop click
 * or Esc. Takes a URL ([VideoMedia] resolves it from the on-disk cache -- a hit
 * after the inline view) and always plays with sound + controls.
 */
@Composable
fun FullscreenVideo(
    url: String,
    posterUrl: String? = null,
    handoff: VideoHandoff? = null,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    Popup(
        alignment        = Alignment.Center,
        onDismissRequest = onDismiss,
        properties       = PopupProperties(focusable = true),
    ) {
        val focus = remember { FocusRequester() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { onDismiss(); true } else false
                },
        ) {
            VideoMedia(
                url          = url,
                thumbUrl     = posterUrl,
                modifier     = Modifier.fillMaxSize(),
                autoPlay     = true,
                loop         = false,
                audio        = true,
                showControls = true,
                scale        = VideoScale.Fit,
                handoff      = handoff,
                // The way back sits next to the way in, on the transport itself;
                // the corner cross stays for "I am done watching".
                onExitFullscreen = onDismiss,
                // Nothing to fall back to inside a full-window overlay: stopping
                // the download closes it.
                onCancelled  = onDismiss,
            )
            VideoIconButton(
                icon     = NxIcon.Close,
                desc     = s.videoExitFullscreen,
                onClick  = onDismiss,
                size     = 40.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun VideoControls(
    isPlaying: Boolean,
    ended: Boolean,
    positionNanos: Long,
    durationNanos: Long,
    muted: Boolean,
    volume: Float,
    showVolume: Boolean,
    fullscreen: FullscreenControl,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onVolume: (Float) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val frac = if (durationNanos > 0L) (positionNanos.toFloat() / durationNanos).coerceIn(0f, 1f) else 0f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MediaSlider(fraction = frac, onStart = onScrubStart, onChange = onScrub, onEnd = onScrubEnd, modifier = Modifier.fillMaxWidth())
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            VideoIconButton(icon = NxIcon.Replay10, desc = s.videoSkipBack, onClick = onSkipBack, size = 30.dp)
            VideoIconButton(
                icon    = if (isPlaying) NxIcon.Pause else NxIcon.PlayArrow,
                desc    = when { isPlaying -> s.audioPause; ended -> s.videoReplay; else -> s.audioPlay },
                onClick = onPlayPause,
            )
            VideoIconButton(icon = NxIcon.Forward10, desc = s.videoSkipForward, onClick = onSkipForward, size = 30.dp)
            Text(
                text  = "${formatTime(positionNanos)} / ${formatTime(durationNanos)}",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            if (showVolume) {
                VideoIconButton(
                    icon    = volumeIconFor(muted, volume),
                    desc    = if (muted) s.videoUnmute else s.videoMute,
                    onClick = onToggleMute,
                    size    = 30.dp,
                )
                MediaSlider(fraction = if (muted) 0f else volume, onChange = onVolume, modifier = Modifier.width(72.dp))
            }
            when (fullscreen) {
                FullscreenControl.Enter ->
                    VideoIconButton(icon = NxIcon.OpenInFull, desc = s.videoFullscreen, onClick = onFullscreen, size = 30.dp)
                FullscreenControl.Exit ->
                    VideoIconButton(icon = NxIcon.CloseFullscreen, desc = s.videoExitFullscreen, onClick = onFullscreen, size = 30.dp)
                FullscreenControl.None -> Unit
            }
        }
    }
}

/**
 * Thin track + draggable handle reporting a 0..1 fraction. Drives both the
 * scrubber (with start/end so the player seeks inexact-while-dragging,
 * exact-on-release) and the volume bar (change only).
 */
@Composable
private fun MediaSlider(
    fraction: Float,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onChange: (Float) -> Unit,
    onEnd: (Float) -> Unit = {},
) {
    var widthPx by remember { mutableStateOf(1) }
    Box(
        modifier = modifier
            .height(16.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consumed so the picture's play/pause click does not also
                    // fire: a tap on the scrubber is a seek, not a pause.
                    down.consume()
                    onStart()
                    var frac = (down.position.x / widthPx).coerceIn(0f, 1f)
                    onChange(frac)
                    // finally: a cancelled drag must still release (commit the exact seek).
                    try {
                        drag(down.id) { change ->
                            frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                            onChange(frac)
                            change.consume()
                        }
                    } finally {
                        onEnd(frac)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White))
        if (widthPx > 1) {
            val handle: Dp = 10.dp
            val half = with(LocalDensity.current) { handle.toPx() / 2f }
            val xPx = (fraction * widthPx - half).toInt()
            Box(
                Modifier.offset { IntOffset(xPx, 0) }.size(handle).clip(CircleShape).background(Color.White),
            )
        }
    }
}

@Composable
private fun VideoIconButton(
    icon: IconKey,
    desc: String,
    onClick: () -> Unit,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Which way the transport's fullscreen control points, if it is there at all. */
private enum class FullscreenControl { None, Enter, Exit }

/**
 * What survives one player being replaced by another over the same file: the
 * position on screen, the volume, and whether it was running. Held by whoever
 * owns the swap (a widget going fullscreen and back), so both players see the
 * same one.
 *
 * Deliberately not observable state -- the fields are read when a player opens
 * and written as it runs, and nothing composes off them. Making them snapshot
 * state would recompose the transport at the write rate for no gain.
 */
@Stable
class VideoHandoff {
    var positionNanos: Long = 0L
        internal set
    var volume: Float = 1f
        internal set
    var muted: Boolean = false
        internal set
    var playing: Boolean = true
        internal set
}

// Skip step for the +/-10s controls.
private const val SKIP_NANOS = 10_000_000_000L

// How long a still pointer keeps the transport up over a running picture.
private const val CONTROLS_IDLE_MS = 2_500L

private fun volumeIconFor(muted: Boolean, volume: Float): IconKey = when {
    muted || volume <= 0.001f -> NxIcon.VolumeOff
    volume < 0.34f            -> NxIcon.VolumeMute
    volume < 0.67f            -> NxIcon.VolumeDown
    else                      -> NxIcon.VolumeUp
}

private fun formatTime(nanos: Long): String {
    val totalSec = (nanos / 1_000_000_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
