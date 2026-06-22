package hivens.ui.widgets.sample

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.services.MusicPlayerService
import hivens.widget.api.useService
import hivens.widget.model.InjectService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlin.io.path.name

// Mini transport for the cross-widget music service. Reads
// MusicPlayerService from the registry; if no provider is currently
// mounted (the user removed the MusicPlayerWidget, or hasn't dropped
// one yet) shows a muted disabled state instead of disappearing or
// crashing. Demonstrates the bidirectional read+write loop end to
// end: sliding the volume here moves it on the main player, tapping
// pause here flips the main player's transport, both backed by the
// same AudioPlayer Koin singleton.
@Widget(
    id          = "home.new.playback.mini",
    displayName = "widget.home.new.playback.mini",
)
@InjectService(MusicPlayerService::class)
@Composable
fun PlaybackMiniControlWidget(instance: WidgetInstance) {
    val service: MusicPlayerService? = useService()

    if (service == null) {
        DisabledPlaceholder()
        return
    }

    val state by service.state.collectAsState()
    val volume by service.volume.collectAsState()
    val s = LocalStrings.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Symbol(icon = NxIcon.MusicNote,
            contentDescription = null,
            tint               = NxTheme.colors.primary,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text       = currentTitleShort(state, s),
            style      = MaterialTheme.typography.bodyMedium,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))

        val isPlaying = state is PlaybackState.Playing
        // Idle (no file ever loaded) intentionally disables play here:
        // the file-picker lives on the main MusicPlayer widget. The
        // mini-control drives existing playback, it does not bootstrap
        // it. Open the main widget once, pick a track, then the mini
        // can transport it from anywhere on the surface.
        val canTransport = state is PlaybackState.Playing || state is PlaybackState.Paused || state is PlaybackState.Ready
        TransportButton(
            icon        = if (isPlaying) NxIcon.Pause else NxIcon.PlayArrow,
            enabled     = canTransport,
            onClick     = { if (isPlaying) service.pause() else service.play() },
            description = if (isPlaying) s.audioPause else s.audioPlay,
        )
        Spacer(Modifier.width(10.dp))
        MiniVolumeBar(
            value         = volume,
            onValueChange = { service.setVolume(it) },
            modifier      = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun DisabledPlaceholder() {
    val s = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(NxTheme.colors.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Symbol(icon = NxIcon.VolumeOff,
            contentDescription = null,
            tint               = NxTheme.colors.textSecondary.copy(alpha = 0.55f),
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text  = s.audioNoPlayerHere,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text  = s.audioAddMusicPlayer,
            style = MaterialTheme.typography.labelSmall,
            color = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
        )
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
        Symbol(icon = icon,
            contentDescription = description,
            tint               = tint,
            modifier           = Modifier.size(16.dp),
        )
    }
}

// Smaller cousin of MusicPlayerWidget's VolumeBar, scoped down so it
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

private fun currentTitleShort(state: PlaybackState, s: AppStrings): String = when (state) {
    PlaybackState.Idle       -> s.audioNoFile
    is PlaybackState.Ready   -> state.file.name
    is PlaybackState.Playing -> state.file.name
    is PlaybackState.Paused  -> state.file.name
    is PlaybackState.Error   -> state.file.name
}
