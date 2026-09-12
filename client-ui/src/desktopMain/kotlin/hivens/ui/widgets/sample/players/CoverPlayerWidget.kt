package hivens.ui.widgets.sample.players

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
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
import hivens.ui.widgets.sample.durationMsOf
import hivens.ui.widgets.sample.elapsedLabel
import hivens.ui.widgets.sample.progressFraction
import hivens.ui.widgets.sample.totalLabel
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import hivens.widget.api.provideService
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.ProvidesService
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The cover-led player: the album art carries the card, the words sit beside it,
 * and the transport is one row along the bottom.
 *
 * One of several player kinds rather than a replacement for the others. They all
 * bind to the same [AudioPlayer] singleton through [MusicPlayerService], so two
 * of them on one surface are two views of one playback and not two players
 * fighting over the audio device.
 *
 * Everything but play and seek lives behind the overflow in the top-right
 * corner, which is the one part of this shape that is always free. A transport
 * row that also carried open-file, stop and a volume track left no room for the
 * thing the widget is for, which is the picture.
 */
@Serializable
data class CoverPlayerProps(
    @PropLabel("widget.home.new.player.cover.showAlbum") val showAlbum: Boolean = true,
)

@Widget(
    id = "home.new.player.cover",
    displayName = "widget.home.new.player.cover",
    propsClass = CoverPlayerProps::class,
    drawsOwnSurface = true,
)
@ProvidesService(MusicPlayerService::class)
@Composable
fun CoverPlayerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<CoverPlayerProps>()
    val player: AudioPlayer = koinInject()
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val repeat by player.repeat.collectAsState()
    val queue by player.queue.collectAsState()
    val track by player.track.collectAsState()
    val scope = rememberCoroutineScope()

    val musicService = remember(player) { MusicPlayerServiceImpl(player) }
    provideService(MusicPlayerService::class, instance.instanceId, musicService)

    val openTrack = rememberAudioFilesPicker(scope) { player.open(it) }

    CoverPlayerCard(
        state       = state,
        track       = track,
        volume      = volume,
        repeat      = repeat,
        queueSize   = queue.size,
        showAlbum   = p.showAlbum,
        onPick      = openTrack,
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
 * Koin graph or a decode thread behind it -- the same split the older player card
 * and the activity pill use.
 */
@Composable
internal fun CoverPlayerCard(
    state: PlaybackState,
    track: TrackInfo?,
    volume: Float,
    repeat: RepeatMode,
    /** How many entries are queued, which is what decides whether skips are drawn. */
    queueSize: Int,
    showAlbum: Boolean,
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
    val loaded = state !is PlaybackState.Idle && state !is PlaybackState.Error
    val duration = durationMsOf(state)
    var menuOpen by remember { mutableStateOf(false) }

    NxSurface(NxSurfaceLevel.Floating, modifier.fillMaxWidth()) {
      // The card is measured before it is composed, because a widget's width is
      // not the designer's choice: a free-canvas placement goes down to 48dp and
      // a cube-grid cell is the slot less its gutters over the column count. A
      // row of fixed measurements plus one weighted gap holds at the width it was
      // drawn at and breaks below it, which is issue #662 on the older card. So
      // the parts drop in order of how much they carry: the second timecode
      // first, then the first, then the art.
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The ladder, with the skips IN it rather than beside it. Adding two more
        // controls to the row without moving these numbers is what clipped the
        // total timecode at 320dp: the parts still dropped in order, but each
        // threshold had been measured against a row that was two buttons narrower.
        //
        // The order of sacrifice, widest first: the second timecode, then the
        // first, then the art. The skips outrank both timecodes when a queue
        // exists, because the bar already carries the measure and nothing else
        // carries "go to the next track"; so their room is added to what the
        // timecodes ask for instead of taken out of it.
        val medium = maxWidth >= 240.dp
        val skips = queueSize > 1 && maxWidth >= 260.dp
        val skipRoom = if (skips) SKIP_ROOM else 0.dp
        val showTotal = maxWidth >= 260.dp + skipRoom
        val wide = maxWidth >= 340.dp + skipRoom
        val coverSide = if (maxWidth >= 300.dp) 104.dp else 72.dp
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(if (medium) 14.dp else 10.dp)) {
                if (medium) {
                    CoverArt(track, onPick, coverSide)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    val title = playerTitle(state, track, s)
                    Text(
                        text       = title,
                        style      = MaterialTheme.typography.titleMedium,
                        color      = NxTheme.colors.textPrimary,
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
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = NxTheme.colors.textSecondary,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        fontFamily = familyForText(artist),
                    )
                    val album = track?.album
                    if (showAlbum && album != null) {
                        Text(
                            text       = album,
                            style      = MaterialTheme.typography.bodySmall,
                            color      = NxTheme.colors.textSecondary.copy(alpha = 0.8f),
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            fontFamily = familyForText(album),
                        )
                    }

                    // A fixed gap, not a weighted one: weight inside a Column
                    // needs a bounded height, and nothing above pins this card's,
                    // so the spacer took the whole window on the first render.
                    // The card's height is the taller of the art and the words.
                    Spacer(Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.fillMaxWidth(),
                    ) {
                        // Nothing to skip to means neither is drawn: a control that
                        // is permanently inert teaches the reader to stop trusting
                        // the row it sits in.
                        if (skips) {
                            SkipKey(NxIcon.SkipPrevious, s.audioSkipPrevious, loaded, onSkipPrev)
                            Spacer(Modifier.width(2.dp))
                        }
                        TransportKey(
                            playing = state is PlaybackState.Playing,
                            enabled = loaded,
                            onClick = onPlayPause,
                        )
                        if (skips) {
                            Spacer(Modifier.width(2.dp))
                            SkipKey(NxIcon.SkipNext, s.audioSkipNext, loaded, onSkipNext)
                        }
                        if (wide) {
                            Spacer(Modifier.width(12.dp))
                            Timecode(elapsedLabel(state))
                        }
                        Spacer(Modifier.width(8.dp))
                        PlaybackScrubber(
                            fraction       = progressFraction(state),
                            enabled        = loaded && duration > 0L,
                            onSeekFraction = { onSeek((it * duration).toLong()) },
                            modifier       = Modifier.weight(1f),
                        )
                        if (showTotal) {
                            Spacer(Modifier.width(8.dp))
                            Timecode(totalLabel(state))
                        }
                    }
                }
            }

        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                NxIconButton(
                    icon               = NxIcon.MoreVert,
                    contentDescription = s.packCardMore,
                    onClick            = { menuOpen = true },
                )
                // A panel and not a menu. What lives behind this button is a set of
                // settings read together -- how loud, what happens at the end of the
                // track -- and a menu is a list of verbs that closes on the first
                // click: the volume slider inside one had no width to be aimed at and
                // shut the menu the moment it was touched.
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
                            // The answer in words beside the glyph rather than under
                            // it: the panel has the width, and a ring the reader has
                            // to hover to identify is a ring they stop trusting.
                            Text(
                                text  = repeatAnswer(repeat, s),
                                style = MaterialTheme.typography.bodySmall,
                                color = NxTheme.colors.textPrimary,
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
 * A timecode never wraps. The older card left maxLines and softWrap unset, and
 * below 345dp the label broke into a column of one digit per line and doubled the
 * card's height, which is the visible half of #662.
 */
@Composable
private fun Timecode(text: String) {
    if (text.isEmpty()) return
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = NxTheme.colors.textSecondary,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The art, and a click target when there is none: an empty player has to say how
 * to fill it, and the square is the largest thing on the card.
 */
@Composable
private fun CoverArt(track: TrackInfo?, onPick: () -> Unit, side: Dp) {
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            // A fixed square, not a proportional one: fillMaxHeight plus
            // aspectRatio in a Row whose height nothing pins takes every pixel
            // it can and turns the whole card into a square, which is how the
            // first render of this came out.
            .size(side)
            .clip(RoundedCornerShape(12.dp))
            .background(NxTheme.colors.primary.copy(alpha = 0.16f))
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = track?.artwork
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
                modifier           = Modifier.size(side * 0.33f),
            )
        }
    }
}

/**
 * What the pair of skips costs the row: two 30dp buttons and the gaps around them.
 * A measured constant rather than a guess, because the ladder above is arithmetic
 * over it and a wrong number here clips a timecode instead of dropping it.
 */
private val SKIP_ROOM = 68.dp

/**
 * A skip, drawn bare beside the filled play key.
 *
 * Unfilled and unplated on purpose: the play control is the card's single anchor,
 * and three filled discs in a row make the reader look for the difference between
 * them instead of at the one that matters.
 */
@Composable
private fun SkipKey(icon: IconKey, name: String, enabled: Boolean, onClick: () -> Unit) {
    NxIconButton(
        icon               = icon,
        contentDescription = name,
        onClick            = onClick,
        enabled            = enabled,
        iconSize           = 18.dp,
    )
}

/** The one filled control on the card, so the eye has a single anchor. */
@Composable
private fun TransportKey(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (enabled) NxTheme.colors.primary
                else NxTheme.colors.surfaceVariant.copy(alpha = 0.4f),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            icon               = if (playing) NxIcon.Pause else NxIcon.PlayArrow,
            contentDescription = if (playing) s.audioPause else s.audioPlay,
            tint               = if (enabled) Color.White else NxTheme.colors.textSecondary.copy(alpha = 0.4f),
            fill               = 1f,
            weight             = 500,
            modifier           = Modifier.size(20.dp),
        )
    }
}
