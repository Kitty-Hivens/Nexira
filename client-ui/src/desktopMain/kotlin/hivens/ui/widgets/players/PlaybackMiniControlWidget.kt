package hivens.ui.widgets.players

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.familyForText
import hivens.ui.widgets.services.MusicPlayerService
import hivens.widget.model.Widget
import org.koin.compose.koinInject

/**
 * The transport as one strip, for a surface that wants the controls without the
 * object.
 *
 * The smallest of the player kinds, and it reads the same [MusicPlayerService] the
 * others do, so a surface carrying a full player and this strip has two views of
 * one playback rather than two players arguing over a device.
 *
 * It used to take that contract out of the widget service registry and fall back
 * to the process player when nothing was mounted. Both halves reached the same
 * object: every provider wrapped the singleton, and so did the fallback, so the
 * round trip decided which wrapper was asked and nothing a listener could hear.
 * The contract is app-provided now and the question does not arise.
 */
@Widget(
    id          = "home.new.playback.mini",
    displayName = "widget.home.new.playback.mini",
    drawsOwnSurface = true,
)
@Composable
fun PlaybackMiniControlWidget() {
    val service: MusicPlayerService = koinInject()

    val state by service.state.collectAsState()
    val volume by service.volume.collectAsState()
    val track by service.track.collectAsState()
    val queue by service.queue.collectAsState()
    val scope = rememberCoroutineScope()

    val openTracks = rememberAudioFilesPicker(scope) { service.open(it) }

    PlaybackMiniControl(
        state       = state,
        track       = track,
        volume      = volume,
        queueSize   = queue.size,
        onPick      = openTracks,
        onPlayPause = { if (state is PlaybackState.Playing) service.pause() else service.play() },
        onVolume    = { service.setVolume(it) },
        onSkipNext  = { service.skipToNext() },
        onSkipPrev  = { service.skipToPrevious() },
        onSeek      = { service.seek(it) },
    )
}

/**
 * The strip itself, over plain data, so it renders off-screen without the service
 * registry, a Koin graph or an audio device.
 */
@Composable
internal fun PlaybackMiniControl(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    queueSize: Int,
    onPick: () -> Unit,
    onPlayPause: () -> Unit,
    onVolume: (Float) -> Unit,
    onSkipNext: () -> Unit = {},
    onSkipPrev: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val idle = state is PlaybackState.Idle
    val loaded = !idle && state !is PlaybackState.Error
    val duration = durationMsOf(state)

    NxSurface(NxSurfaceLevel.Floating, modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // One threshold per element, none of them consulting another, which is
            // the rule the cards pay for. Widest first: the volume track, then the
            // skips. The name and the measure never go, because between them they
            // are the whole widget.
            val showVolume = maxWidth >= 300.dp
            val showSkips = queueSize > 1 && maxWidth >= 240.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .openWhenEmpty(idle, s.audioPickTrack, onPick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Symbol(
                        icon               = NxIcon.MusicNote,
                        contentDescription = null,
                        tint               = NxTheme.colors.primary,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    // Off the file's tags, so the face is picked per string; see
                    // familyForText.
                    val name = playerTitle(state, track, s)
                    Text(
                        text       = name,
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = NxTheme.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = familyForText(name),
                        modifier   = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))

                    if (showSkips) {
                        MiniGlyph(NxIcon.SkipPrevious, s.audioSkipPrevious, loaded, onSkipPrev)
                        Spacer(Modifier.width(2.dp))
                    }
                    val isPlaying = state is PlaybackState.Playing
                    TransportButton(
                        icon        = if (isPlaying) NxIcon.Pause else NxIcon.PlayArrow,
                        enabled     = loaded,
                        onClick     = onPlayPause,
                        description = if (isPlaying) s.audioPause else s.audioPlay,
                    )
                    if (showSkips) {
                        Spacer(Modifier.width(2.dp))
                        MiniGlyph(NxIcon.SkipNext, s.audioSkipNext, loaded, onSkipNext)
                    }
                    if (showVolume) {
                        Spacer(Modifier.width(10.dp))
                        MiniVolumeBar(
                            value         = volume,
                            onValueChange = onVolume,
                            modifier      = Modifier.width(110.dp),
                        )
                    }
                }

                // Where the track is, under the row rather than inside it, and it
                // answers a press like every other measure here. It used to be the
                // library's progress drawing with no gesture on it, which is the
                // complaint the cards were rebuilt over: a measure you can see and
                // cannot move.
                val seekFraction: ((Float) -> Unit)? =
                    if (loaded && duration > 0L) {
                        { at -> onSeek((at * duration).toLong()) }
                    } else {
                        null
                    }
                PlaybackScrubber(
                    fraction       = progressFraction(state),
                    enabled        = seekFraction != null,
                    onSeekFraction = { seekFraction?.invoke(it) },
                    modifier       = Modifier.fillMaxWidth(),
                    // The progress accent rather than the plain one, because the
                    // volume track sits ten points above this and the two must not
                    // read as one control. That distinction was the whole reason
                    // this line used to be the library's progress drawing, and it
                    // survives the drawing being replaced by something seekable.
                    accent         = NxTheme.colors.progressAccent,
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: IconKey,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    val bg = if (enabled) NxTheme.colors.primary else NxTheme.colors.surfaceVariant.copy(alpha = 0.4f)
    val tint = if (enabled) NxTheme.colors.onPrimary else NxTheme.colors.textSecondary.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            icon               = icon,
            contentDescription = description,
            tint               = tint,
            modifier           = Modifier.size(16.dp),
        )
    }
}

/**
 * A skip, drawn bare beside the one filled key.
 *
 * Unplated for the reason the cover player's are: three filled discs in a row make
 * a reader look for the difference between them instead of at the one that matters.
 */
@Composable
private fun MiniGlyph(icon: IconKey, name: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            icon               = icon,
            contentDescription = name,
            tint               = NxTheme.colors.textPrimary.copy(alpha = if (enabled) 0.7f else 0.3f),
            fill               = 1f,
            weight             = 500,
            modifier           = Modifier.size(16.dp),
        )
    }
}

// Smaller cousin of the player cards' volume control, scoped down so it
// fits inside a single transport row. Same gesture model.
@Composable
private fun MiniVolumeBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var pressing by remember { mutableStateOf(false) }
    val active = isHovered || pressing

    val trackHeight by animateDpAsState(
        targetValue   = if (active) 4.dp else 3.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "mini-vol-track",
    )
    // Always faintly visible so the handle is findable; full opacity on hover/drag.
    val thumbAlpha by animateFloatAsState(
        targetValue   = if (active) 1f else 0.65f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "mini-vol-thumb-alpha",
    )

    var widthPx by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .height(20.dp)
            .hoverable(interaction)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressing = true
                    // finally: a cancelled drag must still release the pressed
                    // state, or the thumb sticks enlarged.
                    try {
                        val w = size.width.coerceAtLeast(1).toFloat()
                        onValueChange((down.position.x / w).coerceIn(0f, 1f))
                        drag(down.id) { change ->
                            val newValue = (change.position.x / w).coerceIn(0f, 1f)
                            onValueChange(newValue)
                            change.consume()
                        }
                    } finally {
                        pressing = false
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(NxTheme.colors.outline.copy(alpha = 0.20f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(value)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(NxTheme.colors.primary),
        )
        if (widthPx > 0 && thumbAlpha > 0.01f) {
            val thumbHalfPx = with(LocalDensity.current) { 8.dp.toPx() / 2f }
            val xPx = (value * widthPx - thumbHalfPx).toInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx, 0) }
                    .size(8.dp)
                    .graphicsLayer { alpha = thumbAlpha }
                    .clip(CircleShape)
                    .background(NxTheme.colors.primary),
            )
        }
    }
}
