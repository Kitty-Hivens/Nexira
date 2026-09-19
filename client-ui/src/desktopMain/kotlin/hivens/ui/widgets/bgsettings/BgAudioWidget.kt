package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import hivens.ui.background.BackgroundMediaKind
import hivens.ui.background.backgroundMediaKind
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxSlider
import hivens.ui.nx.NxToggle
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The wallpaper's own soundtrack, the level it plays at, and whether the player
// widgets are pointed at it. One widget, because the three share a fate: a level
// means nothing until the sound is on, and the link means nothing until there is
// something to hear.
//
// The gate sits on the first of them and the rest inherit it. A still wallpaper has
// no audio stream to decode and no playhead for a transport to address, so neither
// setting has anything to act on, and both are withheld with the reason rather than
// accepting a click that would do nothing.
@Widget(id = "bg.audio", displayName = "widget.bg.audio")
@Composable
fun BgAudioWidget() {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    // Asked of the decoders rather than of the file name, off the UI thread,
    // because that is what answering it costs. Null until known, and unknown reads
    // as not-yet rather than as a still: withholding the control for one frame is
    // invisible, and offering it wrongly is a click that does nothing.
    val path = settings.imagePath
    val moves by produceState<Boolean?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { backgroundMediaKind(File(it)) == BackgroundMediaKind.TimeBased } }
    }
    val available = moves == true

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NxToggle(
            label           = s.backgroundAudio,
            checked         = settings.audio && available,
            description     = if (moves == false) s.backgroundAudioStill else s.backgroundAudioDesc,
            icon            = if (settings.audio && available) NxIcon.VolumeUp else NxIcon.VolumeOff,
            enabled         = available,
            accent          = NxTheme.colors.primary,
            onCheckedChange = { ctx.update { copy(audio = it, linkToPlayers = linkToPlayers && it) } },
        )

        if (settings.audio && available) {
            val level = settings.audioVolume
            NxSlider(
                label         = s.backgroundAudioVolume,
                value         = level,
                range         = 0f..1f,
                valueText     = "%.0f%%".format(level * 100),
                onValueChange = { ctx.update { copy(audioVolume = it) } },
            )

            // Only under the sound, because that is the requirement rather than a
            // layout choice: a wall the transport drives but nobody hears is a
            // scrubber over a decoration.
            NxToggle(
                label           = s.backgroundLink,
                checked         = settings.linkToPlayers,
                description     = s.backgroundLinkDesc,
                icon            = NxIcon.MusicNote,
                accent          = NxTheme.colors.primary,
                onCheckedChange = { ctx.update { copy(linkToPlayers = it) } },
            )
        }
    }
}
