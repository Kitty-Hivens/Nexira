package hivens.look.colour.nx.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.i18n.api.strings
import hivens.themes.api.ThemesKeys
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxField
import hivens.ui.nx.NxSection
import hivens.ui.nx.NxToggle
import hivens.ui.surface.NxCard
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import androidx.compose.ui.graphics.Color

// The selected theme, drawing a piece of the launcher by itself: a section with a
// switch, a field, chips and buttons, a card inside it. Real primitives in the real
// theme, through the same function the launcher draws with, so the preview and the
// result cannot differ. Under it, what the theme is made of: its steps and its
// colours, as data rather than as names of places they were supposed to go.
//
// Reads only, never writes. Safe to remove from the surface: the grid still picks
// and the header button still applies. Drawn only while nx draws the colour.
@Widget(
    id = "theme.picker.preview",
    displayName = "widget.theme.picker.preview",
    minWidth = 240, minHeight = 300,
    prefWidth = 320, prefHeight = 432,
    maxWidth = 720, maxHeight = 760,
    needs = [ThemesKeys.ACTIVE_ID, ThemesKeys.DARK_ID, "theme.picker.selection"],
)
@Composable
fun ThemePickerPreviewWidget() {
    if (!nxDrawsColour()) return
    val active = rememberScopedSource(ThemesKeys.Active).valueOrNull() ?: return
    val dark = rememberScopedSource(ThemesKeys.Dark).valueOrNull() ?: return
    val selection = rememberScopedSource(ThemeSelectionSource).valueOrNull() ?: return
    val s = strings(ColourNxControls.ID)
    val theme = selection.or(active)
    val scheme = theme.scheme(dark)
    val face = MaterialTheme.typography.bodyMedium.fontFamily

    Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.large)) {
        InTheme(theme, dark, face) {
            Column(
                modifier            = Modifier.fillMaxSize().background(NxColor.page).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text       = s.text("themePickerPreview"),
                    style      = MaterialTheme.typography.bodySmall,
                    color      = NxColor.lead(),
                    fontWeight = FontWeight.Bold,
                )
                NxSection(title = theme.label()) {
                    NxToggle(
                        label           = s.text("settingsDarkTheme"),
                        description     = s.text("settingsDarkThemeDesc"),
                        checked         = true,
                        onCheckedChange = {},
                    )
                    NxField(value = "", onValueChange = {}, placeholder = s.text("browseSearchPlaceholder"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NxChoiceChip(label = s.text("themePickerSelected"), selected = true, onToggle = {})
                        NxChoiceChip(label = s.text("themePickerBtnOutlined"), selected = false, onToggle = {})
                    }
                    NxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(theme.label(), color = NxInk.main, fontWeight = FontWeight.Medium)
                            Text(s.text("settingsDarkThemeDesc"), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NxButton(label = s.text("themePickerBtnSample"), onClick = {}, style = NxButtonStyle.Primary, compact = true)
                        NxButton(label = s.text("themePickerBtnOutlined"), onClick = {}, style = NxButtonStyle.Secondary, compact = true)
                    }
                }
                Strip(scheme.steps)
                Strip(scheme.colors)
            }
        }
    }
}

/** A run of swatches in the order the theme gives them. */
@Composable
private fun Strip(colours: List<Color>) {
    Row(Modifier.fillMaxWidth().height(18.dp).clip(MaterialTheme.shapes.extraSmall)) {
        colours.forEach { colour -> Box(Modifier.weight(1f).fillMaxSize().background(colour)) }
    }
}
