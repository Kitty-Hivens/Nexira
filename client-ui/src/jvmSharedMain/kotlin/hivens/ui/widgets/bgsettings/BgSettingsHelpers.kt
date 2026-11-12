package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import hivens.ui.background.BackgroundSettings
import hivens.ui.nx.NxSectionHeader
import hivens.ui.nx.NxSlider
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull

/**
 * The library's section header, so a heading on this surface is set like a heading
 * anywhere else.
 *
 * This was a private copy at labelSmall with a letter-spacing of its own, which put
 * the headings of the appearance studio at a different size from every other block
 * of settings in the launcher. The copy predates [NxSectionHeader] having fifteen
 * readers, and there is nothing here it was expressing that the shared one does not.
 */
@Composable
internal fun SectionTitle(text: String) {
    NxSectionHeader(text)
}

// Enum picker: an accent section title over a wrapping row of NxChoiceChips.
// Mirrors the settings screens' PickerBlock so scale/loop-mode read the same.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BgPicker(title: String, chips: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            content               = chips,
        )
    }
}

/**
 * The wallpaper settings as they are now, and the change that writes them. The
 * value is the background manager's own rather than a copy taken by the screen:
 * the player's volume and a transcode finishing after the screen closed change it
 * too, and a copy put back what they had changed the next time a control moved.
 */
internal class BgSettings(
    val value: BackgroundSettings,
    val update: (BackgroundSettings.() -> BackgroundSettings) -> Unit,
)

/** What a background widget reads and writes, or null where either has no answer. */
@Composable
internal fun rememberBgSettings(): BgSettings? {
    val value = rememberScopedSource(Sources.Background).valueOrNull() ?: return null
    val update = rememberScopedCommand(Commands.UpdateBackground) ?: return null
    return BgSettings(value, update)
}

// Every background slider was the same five lines: read one float off the live
// settings, hand the change back through update. Nine widgets carried a private
// copy of that wiring, so the way a slider reaches the settings was written nine
// times and could drift eight ways.
//
// What actually distinguishes one slider from another stays at the call site: the
// label, the range it moves through, how its number reads, and the field it is.
@Composable
internal fun BgSlider(
    label: String,
    range: ClosedFloatingPointRange<Float>,
    read: BackgroundSettings.() -> Float,
    format: (Float) -> String,
    write: BackgroundSettings.(Float) -> BackgroundSettings,
) {
    val bg = rememberBgSettings() ?: return
    val value = bg.value.read()
    NxSlider(
        label         = label,
        value         = value,
        range         = range,
        valueText     = format(value),
        onValueChange = { next -> bg.update { write(next) } },
    )
}
