package hivens.ui.widgets.sample.players

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.audio.AudioPlayer
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCycleToggle
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxPanelGroup
import hivens.ui.nx.NxPopoverPanel
import hivens.ui.nx.NxSlider
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.familyForText
import hivens.ui.theme.seedFromImage
import hivens.ui.widgets.sample.durationMsOf
import hivens.ui.widgets.sample.progressFraction
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.widget.api.provideService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The cover rules the card, but never carries the text.
 *
 * An album's own lettering usually lives inside its artwork, so a caption laid
 * over the picture fights it. Here the picture keeps its own square and the CARD
 * borrows its colour instead, seeded through the same quantizer the wallpaper
 * palette uses: the widget takes on the record it is playing without any of the
 * record's own type being covered up.
 *
 * That borrowed colour is the whole of the shape, which is why the transport is
 * bare glyphs on it rather than a filled disc, and why there are no timecodes: the
 * bar under the words is the measure, and three numbers on a tinted plane are
 * three things competing with the tint. What the card leaves out lives behind the
 * overflow in the corner.
 *
 * A sibling of the cover player rather than a replacement for it. Both are led by
 * the artwork and they answer differently: that one plates its play control and
 * prints the time, this one dissolves into the record.
 */
@Serializable
data class SeededPlayerProps(
    @PropLabel("widget.home.new.player.seeded.showAlbum") val showAlbum: Boolean = true,
    /**
     * How much of the cover's colour the card takes. The concept's own value is
     * 0.24: enough that two records look like different widgets, little enough
     * that the text keeps its contrast on either palette.
     */
    @PropLabel("widget.home.new.player.seeded.tint") @PropRange(0.0, 0.6)
    val tint: Float = 0.24f,
)

@Widget(
    id = "home.new.player.seeded",
    displayName = "widget.home.new.player.seeded",
    propsClass = SeededPlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun SeededPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<SeededPlayerProps>()
    val player: AudioPlayer = koinInject()
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val repeat by player.repeat.collectAsState()
    val queue by player.queue.collectAsState()
    val track by player.track.collectAsState()
    val scope = rememberCoroutineScope()

    val musicService = remember(player) { MusicPlayerServiceImpl(player) }
    provideService(MusicPlayerService::class, instance.instanceId, musicService)

    val openTracks = rememberAudioFilesPicker(scope) { player.open(it) }

    SeededPlayerCard(
        state       = state,
        track       = track,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showAlbum   = p.showAlbum,
        tint        = p.tint,
        onPick      = openTracks,
        onPlayPause = { if (state is PlaybackState.Playing) player.pause() else player.play() },
        onStop      = { player.stop() },
        onVolume    = { player.setVolume(it) },
        onRepeat    = { player.setRepeat(it) },
        onSkipNext  = { player.skipToNext() },
        onSkipPrev  = { player.skipToPrevious() },
        onSeek      = { player.seek(it) },
    )
}

/**
 * The card over plain data, so it renders off-screen across palettes without a
 * Koin graph or a decode thread behind it.
 */
@Composable
internal fun SeededPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    repeat: RepeatMode,
    queueSize: Int,
    showAlbum: Boolean,
    tint: Float,
    onPick: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolume: (Float) -> Unit,
    onRepeat: (RepeatMode) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val palette = NxTheme.colors
    val loaded = state !is PlaybackState.Idle && state !is PlaybackState.Error
    val duration = durationMsOf(state)
    var menuOpen by remember { mutableStateOf(false) }

    val fill = rememberSeededFill(track?.artwork, tint)

    NxSurface(
        level     = NxSurfaceLevel.Floating,
        modifier  = modifier.fillMaxWidth(),
        shape     = MaterialTheme.shapes.medium,
        fillColor = fill,
    ) {
        // Measured before it is composed, for the same reason the cover player is:
        // a widget's width is the slot's call and not the designer's, and a row of
        // fixed measurements holds at the width it was drawn at and breaks below it.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val art = maxWidth >= 240.dp
            val album = showAlbum && maxWidth >= 300.dp
            val skips = queueSize > 1 && maxWidth >= 260.dp
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The concept's own height, and only while the art is in the
                        // row: without it there is nothing 88dp tall to make room
                        // for, and a fixed height would be an empty band.
                        .then(if (art) Modifier.height(112.dp) else Modifier)
                        .padding(if (art) 12.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (art) {
                        CoverSquare(track?.artwork, onPick)
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        val title = playerTitle(state, track, s)
                        Text(
                            text       = title,
                            style      = MaterialTheme.typography.titleMedium,
                            color      = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(title),
                            // Room for the overflow, which floats over this corner.
                            modifier   = Modifier.padding(end = 28.dp),
                        )
                        val artist = track?.artist ?: playerStatus(state, s)
                        Text(
                            text       = artist,
                            style      = MaterialTheme.typography.bodySmall,
                            color      = palette.textSecondary,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(artist),
                        )
                        track?.album?.takeIf { album }?.let { name ->
                            Text(
                                text       = name,
                                style      = MaterialTheme.typography.labelSmall,
                                color      = palette.textSecondary.copy(alpha = 0.7f),
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis,
                                fontFamily = familyForText(name),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (skips) {
                                Glyph(NxIcon.SkipPrevious, s.audioSkipPrevious, 18.dp, palette.textSecondary, loaded, onSkipPrev)
                            }
                            Glyph(
                                icon    = if (state is PlaybackState.Playing) NxIcon.Pause else NxIcon.PlayArrow,
                                name    = if (state is PlaybackState.Playing) s.audioPause else s.audioPlay,
                                size    = 22.dp,
                                tint    = palette.textPrimary,
                                enabled = loaded,
                                onClick = onPlayPause,
                            )
                            if (skips) {
                                Glyph(NxIcon.SkipNext, s.audioSkipNext, 18.dp, palette.textSecondary, loaded, onSkipNext)
                            }
                            Spacer(Modifier.width(10.dp))
                            // At rest this is the concept's 3dp bar and nothing else.
                            // It thickens and grows a handle under the pointer, so the
                            // measure is seekable without the card being covered in
                            // controls while it is only being looked at.
                            PlaybackScrubber(
                                fraction       = progressFraction(state),
                                enabled        = loaded && duration > 0L,
                                onSeekFraction = { onSeek((it * duration).toLong()) },
                                modifier       = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    NxIconButton(
                        icon               = NxIcon.MoreVert,
                        contentDescription = s.packCardMore,
                        onClick            = { menuOpen = true },
                    )
                    NxPopoverPanel(
                        expanded         = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        title            = s.audioPlaybackOptions,
                        footer           = {
                            NxButton(
                                label   = s.audioOpenFile,
                                onClick = { menuOpen = false; onPick() },
                                style   = NxButtonStyle.Tertiary,
                                icon    = NxIcon.FolderOpen,
                                compact = true,
                            )
                            NxButton(
                                label   = s.audioStop,
                                onClick = { menuOpen = false; onStop() },
                                style   = NxButtonStyle.Tertiary,
                                icon    = NxIcon.Stop,
                                enabled = loaded,
                                compact = true,
                            )
                        },
                    ) {
                        NxSlider(
                            label         = s.audioVolume,
                            value         = volume,
                            range         = 0f..1f,
                            valueText     = "${(volume * 100).toInt()}%",
                            onValueChange = onVolume,
                            compact       = true,
                        )
                        NxPanelGroup(label = s.audioRepeat) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NxCycleToggle(
                                    states  = repeatStates(s),
                                    index   = repeatIndex(repeat),
                                    onCycle = { onRepeat(REPEAT_ORDER[it]) },
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text  = repeatAnswer(repeat, s),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card's body colour, borrowed from the cover.
 *
 * Off the composition thread, and only when the picture changes. The quantizer
 * reads EVERY pixel of the artwork into an int array before it starts scoring, so
 * an 800 square cover is a couple of megabytes and a Celebi pass: called from the
 * composable it is a dropped frame on every track change, which is precisely the
 * moment the widget is being looked at.
 *
 * Null artwork keeps the plain tonal body, so the card is the library's surface
 * until there is a record to take after.
 */
@Composable
private fun rememberSeededFill(artwork: ImageBitmap?, tint: Float): Color {
    val base = NxTheme.colors.surfaceContainer
    val seed by produceState<Int?>(initialValue = null, artwork) {
        value = artwork?.let { withContext(Dispatchers.Default) { seedFromImage(it) } }
    }
    return seed?.let { lerp(base, Color(it), tint.coerceIn(0f, 1f)) } ?: base
}

/** The picture, and a way in when there is none: the square is the biggest target on the card. */
@Composable
private fun CoverSquare(artwork: ImageBitmap?, onPick: () -> Unit, side: Dp = 88.dp) {
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            .size(side)
            .clip(MaterialTheme.shapes.small)
            .background(NxTheme.colors.primary.copy(alpha = 0.18f))
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(
                bitmap             = artwork,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Symbol(
                icon               = NxIcon.MusicNote,
                contentDescription = s.audioPickTrack,
                tint               = NxTheme.colors.primary,
                modifier           = Modifier.size(side * 0.34f),
            )
        }
    }
}

/**
 * One transport glyph, unplated.
 *
 * [NxIconButton] draws nothing behind the glyph at rest and raises its round state
 * layer only under the pointer, which is exactly what this shape wants: bare on
 * the tinted body, and answering when touched. It also names itself on hover,
 * which a glyph with no label has to.
 */
@Composable
private fun Glyph(
    icon: IconKey,
    name: String,
    size: Dp,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NxIconButton(
        icon               = icon,
        contentDescription = name,
        onClick            = onClick,
        tint               = tint,
        enabled            = enabled,
        iconSize           = size,
        fill               = 1f,
        weight             = 500,
    )
}
