package hivens.ui.widgets.themepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxInk
import hivens.ui.theme.ThemeLibrary
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

private const val SURFACE = "theme.picker"

// theme.picker surface composable. AppLayout routes Screen.ThemePicker here. Provides
// LocalThemePickerContext for the child widgets and lays out two slots side by side
// (grid and preview). The title and Apply are surface chrome rather than widgets:
// without them the screen loses its whole purpose.
@Composable
fun ThemePickerSurface(
    library: ThemeLibrary,
    isDarkTheme: Boolean,
    onThemeSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val themes = library.all
    // Keyless remember: the pending selection survives an outside change of the
    // active theme. Only Apply commits it.
    val selected = remember { mutableStateOf(library.active) }

    val ctx = remember(themes, selected, isDarkTheme, onBack) {
        ThemePickerContext(
            themes   = themes,
            selected = selected,
            isDark   = isDarkTheme,
            onBack   = onBack,
        )
    }

    PuppetScreen("ThemePicker")
    PuppetClick("themePicker.back") { onBack() }
    PuppetClick("themePicker.apply") { onThemeSelected(selected.value.id) }
    themes.forEach { theme ->
        PuppetClick("themePicker.select.${theme.id}") { selected.value = theme }
    }

    CompositionLocalProvider(LocalThemePickerContext provides ctx) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // On a panel of its own, like the grid and the preview under it: the title
            // is text, and on the bare page it lay over whatever the wallpaper had there.
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    // No back arrow of its own. The window frame already carries one,
                    // enabled by the same history this screen would pop.
                    Text(
                        text       = s.themePickerTitle,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color      = NxInk.main,
                    )
                    Flexible("theme_picker_apply_btn", FlexibleKind.Button) {
                        NxButton(
                            label = s.themePickerApply,
                            onClick = { onThemeSelected(selected.value.id) },
                            style = NxButtonStyle.Primary,
                        )
                    }
                }
            }
            // Grid is the editable panel. The preview reads the same selection, so
            // removing the preview widget hides it without breaking selection.
            Row(
                modifier              = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SlotRenderer(SurfaceId(SURFACE), SlotId("grid"), Modifier.weight(2f).fillMaxHeight())
                SlotRenderer(SurfaceId(SURFACE), SlotId("preview"), Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
