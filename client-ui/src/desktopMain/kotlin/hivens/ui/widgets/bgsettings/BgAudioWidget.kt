package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxSlider
import hivens.ui.nx.NxToggle
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget

// The wallpaper's own soundtrack and the level it plays at, as one widget: the
// level means nothing until the sound is on, which is the same fate the tint
// intensity shares with its colour. A still wallpaper ignores both, the way it
// ignores the loop mode, because there is no stream in a picture that does not
// move.
@Widget(id = "bg.audio", displayName = "widget.bg.audio")
@Composable
fun BgAudioWidget() {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NxToggle(
            label           = s.backgroundAudio,
            checked         = settings.audio,
            description     = s.backgroundAudioDesc,
            icon            = if (settings.audio) NxIcon.VolumeUp else NxIcon.VolumeOff,
            accent          = NxTheme.colors.primary,
            onCheckedChange = { ctx.update { copy(audio = it) } },
        )

        if (settings.audio) {
            val level = settings.audioVolume
            NxSlider(
                label         = s.backgroundAudioVolume,
                value         = level,
                range         = 0f..1f,
                valueText     = "%.0f%%".format(level * 100),
                onValueChange = { ctx.update { copy(audioVolume = it) } },
            )
        }
    }
}
