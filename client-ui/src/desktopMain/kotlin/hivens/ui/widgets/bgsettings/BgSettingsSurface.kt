package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import hivens.core.data.UiStyle
import hivens.ui.background.BackgroundSettings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxIconButton
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

private const val SURFACE = "bg.settings"

// Island widths -- bounded so sliders never run to the monitor edge (Rule 6 / D08)
// and the live wallpaper breathes in the channel between them (Rule 3 gap).
private val PANEL_WIDTH = 380.dp
private val THEME_PANEL_WIDTH = 320.dp

/**
 * Appearance studio. AppLayout routes Screen.BackgroundSettings here. Two islands over
 * the live wallpaper: the wallpaper controls (the `controls` slot -- enable + image +
 * scale + position + effects + loop + tint + reset widgets) at the start, and the theme
 * axis ([AppearanceThemeIsland] -- dark/light, UI style, theme picker) at the end.
 *
 * There is no in-screen preview: the app's [hivens.ui.background.CustomBackground]
 * already renders behind the whole shell (AppShell), so this screen stays transparent
 * apart from the islands and the LIVE UI is the preview -- editing a wallpaper knob or
 * the theme updates the real background + palette at full size (Monet seeds the scheme
 * from the wallpaper), with no second video pipeline.
 *
 * The controls slot rides a `verticalScroll` on its own modifier: the knob stack is
 * genuinely tall (~15 widgets). A Lazy-list widget dropped into this slot would crash
 * measurement -- documented trade-off, recoverable via reset; the default controls
 * carry no such widget.
 */
@Composable
fun BgSettingsSurface(
    currentSettings: BackgroundSettings,
    onSettingsChanged: (BackgroundSettings) -> Unit,
    onBack: () -> Unit,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    uiStyle: UiStyle,
    onUiStyleChanged: (UiStyle) -> Unit,
    onOpenThemePicker: () -> Unit,
) {
    val s = LocalStrings.current
    val settings = remember { mutableStateOf(currentSettings) }

    val update: (BackgroundSettings.() -> BackgroundSettings) -> Unit = remember(onSettingsChanged) {
        { block ->
            settings.value = settings.value.block()
            onSettingsChanged(settings.value)
        }
    }

    val ctx = remember(settings, update) { BgSettingsContext(settings = settings, update = update) }

    PuppetScreen("BackgroundSettings")
    PuppetClick("background.back") { onBack() }
    PuppetToggle("background.enabled", settings.value.enabled) { update { copy(enabled = it) } }
    PuppetClick("background.clearImage", enabled = settings.value.imagePath != null) {
        update { copy(imagePath = null, enabled = false) }
    }
    PuppetClick("background.reset") {
        settings.value = BackgroundSettings()
        onSettingsChanged(settings.value)
    }

    CompositionLocalProvider(LocalBgSettingsContext provides ctx) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NxIconButton(NxIcon.ArrowBack, s.navBack, onClick = onBack)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text       = s.backgroundTitle,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color      = NxTheme.colors.textPrimary,
                    )
                    Text(
                        text  = s.backgroundSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Two islands over the live wallpaper: wallpaper tuning at the start,
            // the theme axis at the end, the real background (= the preview) breathing
            // in the channel between them.
            Row(Modifier.fillMaxSize()) {
                NxSurface(NxSurfaceLevel.Floating, Modifier.width(PANEL_WIDTH).fillMaxHeight()) {
                    SlotRenderer(
                        SurfaceId(SURFACE),
                        SlotId("controls"),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        spacing  = 16.dp,
                    )
                }

                Spacer(Modifier.weight(1f))

                AppearanceThemeIsland(
                    isDarkTheme       = isDarkTheme,
                    onToggleDarkTheme = onToggleDarkTheme,
                    uiStyle           = uiStyle,
                    onUiStyleChanged  = onUiStyleChanged,
                    onOpenThemePicker = onOpenThemePicker,
                    modifier          = Modifier.width(THEME_PANEL_WIDTH).fillMaxHeight(),
                )
            }
        }
    }
}
