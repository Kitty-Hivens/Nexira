package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.ThemeMode
import hivens.ui.background.BackgroundSettings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.surface.NxSurface
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.ui.theme.Theme
import hivens.ui.surface.SurfaceKind

private const val SURFACE = "bg.settings"

// Island widths -- bounded so sliders never run to the monitor edge (Rule 6 / D08)
// and the live wallpaper breathes in the channel between them (Rule 3 gap).
private val PANEL_WIDTH = 380.dp
private val THEME_PANEL_WIDTH = 320.dp

/**
 * Appearance studio. AppLayout routes Screen.BackgroundSettings here. Two islands over
 * the live wallpaper: the wallpaper controls (the `controls` slot -- enable + image +
 * scale + position + effects + loop + tint + reset widgets) at the start, and the theme
 * axis ([AppearanceThemeIsland]: dark/light, its source, the theme picker) at the end.
 *
 * No in-screen title or back button: the top-bar breadcrumb names the screen and drives
 * navigation, as on the other surfaces. There is also no preview -- the app's
 * [hivens.ui.background.CustomBackground] renders behind the whole shell, so the screen
 * stays transparent apart from the islands and the LIVE UI is the preview: editing a
 * wallpaper knob or the theme updates the real background and interface at full size,
 * with no second video pipeline.
 */
@Composable
fun BgSettingsSurface(
    currentSettings: BackgroundSettings,
    onSettingsChanged: (BackgroundSettings) -> Unit,
    onBack: () -> Unit,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    systemThemeAvailable: Boolean,
    activeTheme: Theme,
    surfaceBlur: Boolean,
    onSurfaceBlurChanged: (Boolean) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChanged: (Boolean) -> Unit,
    onOpenThemePicker: () -> Unit,
) {
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
    PuppetToggle("background.surfaceBlur", surfaceBlur, onValueChange = onSurfaceBlurChanged)
    PuppetToggle("background.reduceMotion", reduceMotion, onValueChange = onReduceMotionChanged)
    PuppetClick("background.reset") {
        settings.value = BackgroundSettings()
        onSettingsChanged(settings.value)
    }

    CompositionLocalProvider(LocalBgSettingsContext provides ctx) {
        Row(Modifier.fillMaxSize().padding(16.dp)) {
            NxSurface(SurfaceKind.Panel, Modifier.width(PANEL_WIDTH).fillMaxHeight()) {
                // Scrolls because the slot says so (the bundled layout sets it), not
                // because this screen wraps it: the kernel is what knows how each
                // widget is measured once the column has no end.
                SlotRenderer(
                    SurfaceId(SURFACE),
                    SlotId("controls"),
                    modifier       = Modifier.fillMaxSize(),
                    spacing        = 16.dp,
                    contentPadding = PaddingValues(20.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            AppearanceThemeIsland(
                isDarkTheme          = isDarkTheme,
                onToggleDarkTheme    = onToggleDarkTheme,
                themeMode            = themeMode,
                onThemeModeChanged   = onThemeModeChanged,
                systemThemeAvailable = systemThemeAvailable,
                activeTheme          = activeTheme,
                surfaceBlur          = surfaceBlur,
                onSurfaceBlurChanged = onSurfaceBlurChanged,
                reduceMotion         = reduceMotion,
                onReduceMotionChanged = onReduceMotionChanged,
                onOpenThemePicker    = onOpenThemePicker,
                modifier             = Modifier.width(THEME_PANEL_WIDTH).fillMaxHeight(),
            )
        }
    }
}
