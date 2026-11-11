package hivens.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import hivens.ui.i18n.LocalStrings
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Theme
import hivens.ui.utils.ConsoleSettingsStore
import org.koin.compose.koinInject

/** The console in a window of its own: the same [ConsoleContent] the pack page embeds, under the active theme. */
@Composable
fun ConsoleWindow(
    isDarkTheme: Boolean,
    onClose: () -> Unit,
    theme: Theme,
) {
    val title = LocalStrings.current.consoleTitle
    // Collected here rather than by the shell that hosts this window: the store
    // publishes on every reported slider value, and the shell root is the last
    // composable that should be repainting for it.
    val settingsStore: ConsoleSettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val windowState = rememberWindowState(width = 960.dp, height = 620.dp)

    Window(
        onCloseRequest = onClose,
        state          = windowState,
        title          = title,
        alwaysOnTop    = false,
        undecorated    = false,
    ) {
        // NxTheme provides the scheme and the Material colorScheme cut from
        // it, and child composables ask it for colours. The caller hands in
        // the active theme, so this separate window matches the main shell.
        NxTheme(theme = theme, dark = isDarkTheme) {
            NxSurface(SurfaceKind.Page, Modifier.fillMaxSize(), shape = RectangleShape) {
                ConsoleContent(settings = settings, onSettingsChange = settingsStore::update)
            }
        }
    }
}
