package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.background.BackgroundSettings
import hivens.ui.nx.NxSlider
import hivens.ui.theme.NxTheme

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text          = text,
        style         = MaterialTheme.typography.labelSmall,
        fontWeight    = FontWeight.Bold,
        color         = NxTheme.colors.primary,
        letterSpacing = 1.sp,
    )
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

// Every background slider was the same five lines: take the surface context, read
// one float off the live settings, hand the change back through update. Nine
// widgets carried a private copy of that wiring, so the way a slider reaches the
// settings was written nine times and could drift eight ways.
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
    val ctx = LocalBgSettingsContext.current
    val settings by ctx.settings
    val value = settings.read()
    NxSlider(
        label         = label,
        value         = value,
        range         = range,
        valueText     = format(value),
        onValueChange = { next -> ctx.update { write(next) } },
    )
}
