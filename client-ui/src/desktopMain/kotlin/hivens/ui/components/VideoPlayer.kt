package hivens.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import java.nio.file.Path

/**
 * Interactive video player over a LOCAL file (Skinema is local-only -- a URL goes
 * through [VideoMedia]/[rememberCachedVideo] first). Draws frames via Skinema's
 * [VideoSurface] and overlays transport chrome -- play/pause, a seek scrubber on
 * the real seek, a timecode, a volume control and a fullscreen affordance.
 * Controls reveal on hover and stay up while paused.
 *
 * @param onRequestFullscreen when set, shows a fullscreen button calling it.
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
    onRequestFullscreen: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    // Hardware decode where a device is available (AUTO falls back to software
    // per file), so a 4K clip is not decoded on the CPU.
    val player = remember(path, loop, audio) { SkinemaPlayer(path = path, loop = loop, audio = audio, hardware = HwAccel.AUTO) }
    DisposableEffect(player) { onDispose { player.close() } }

    val state = rememberPlayerState(player)
    val isPlaying = state == SkinemaPlayer.State.Playing
    val ended = state == SkinemaPlayer.State.Ended

    var muted by remember(player) { mutableStateOf(startMuted) }
    var volume by remember(player) { mutableStateOf(1f) }
    LaunchedEffect(player, muted, volume) { player.setVolume(if (muted) 0f else volume) }

    // Skinema starts playing on open; honor autoPlay=false with one pause.
    LaunchedEffect(player) { if (!autoPlay) player.pause() }

    var positionNanos by remember(player) { mutableStateOf(0L) }
    var scrubbing by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) positionNanos = player.positionNanos()
            delay(200)
        }
    }
    val durationNanos = player.durationNanos ?: 0L

    // Reveal controls on hover; keep them up whenever not actively playing.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val controlsShown = showControls && (hovered || !isPlaying)

    Box(modifier.hoverable(hover)) {
        VideoSurface(player, Modifier.fillMaxSize(), scale = scale)

        when (state) {
            SkinemaPlayer.State.Opening, SkinemaPlayer.State.Seeking ->
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
            is SkinemaPlayer.State.Failed ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.videoError, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
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
                showFullscreen = onRequestFullscreen != null,
                onPlayPause   = {
                    when {
                        isPlaying -> player.pause()
                        ended     -> player.seek(0L)
                        else      -> player.resume()
                    }
                },
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
                onFullscreen  = { onRequestFullscreen?.invoke() },
                modifier      = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Full-window video over a scrim, dismissed by the close button, a backdrop click
 * or Esc. Takes a URL ([VideoMedia] resolves it from the on-disk cache -- a hit
 * after the inline view) and always plays with sound + controls.
 */
@Composable
fun FullscreenVideo(url: String, posterUrl: String? = null, onDismiss: () -> Unit) {
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
    showFullscreen: Boolean,
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
            if (showFullscreen) {
                VideoIconButton(icon = NxIcon.OpenInFull, desc = s.videoFullscreen, onClick = onFullscreen, size = 30.dp)
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

// Skip step for the +/-10s controls.
private const val SKIP_NANOS = 10_000_000_000L

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
