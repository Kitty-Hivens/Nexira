package hivens.ui.widgets.themepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.CustomTheme
import hivens.ui.theme.ThemePresets
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

private const val SURFACE = "theme.picker"

// theme.picker surface composable. AppLayout routes
// Screen.ThemePicker here. Provides LocalThemePickerContext for
// child widgets, lays out two side-by-side slots (grid + preview),
// keeps header chrome (back button, title, Apply) on the surface
// itself rather than as widgets -- those three controls are
// per-screen invariants the user cannot meaningfully remove without
// losing access to the screen's whole purpose.
@Composable
fun ThemePickerSurface(
    currentTheme: CustomTheme,
    onThemeSelected: (CustomTheme) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val themes = remember { ThemePresets.getAll() }
    // Keyless remember: the local pending selection survives an
    // external currentTheme change (system theme sync, preset
    // load mid-edit). The legacy screen had the same shape; only
    // an explicit Apply commits the selection upstream.
    val selectedTheme = remember { mutableStateOf(currentTheme) }

    val ctx = remember(themes, selectedTheme, onThemeSelected, onBack) {
        ThemePickerContext(
            themes        = themes,
            selectedTheme = selectedTheme,
            onApply       = onThemeSelected,
            onBack        = onBack,
        )
    }

    PuppetScreen("ThemePicker")
    PuppetClick("themePicker.back") { onBack() }
    PuppetClick("themePicker.apply") { onThemeSelected(selectedTheme.value) }
    themes.forEach { theme ->
        PuppetClick("themePicker.select.${theme.name}") { selectedTheme.value = theme }
    }

    CompositionLocalProvider(LocalThemePickerContext provides ctx) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Header chrome: back + title left, apply right.
            Row(
                modifier              = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Symbol(icon = NxIcon.ArrowBack,
                            contentDescription = s.navBack,
                            tint               = NxTheme.colors.primary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = s.themePickerTitle,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color      = NxTheme.colors.textPrimary,
                    )
                }
                Flexible("theme_picker_apply_btn", FlexibleKind.Button) {
                    NxButton(
                        label = s.themePickerApply,
                        onClick = { onThemeSelected(selectedTheme.value) },
                        style = NxButtonStyle.Primary,
                    )
                }
            }
            // Body: two side-by-side slots. Grid is the editable
            // panel; preview reads the same selectedTheme via the
            // surface context so removing the preview widget hides
            // the panel but does not break selection.
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
