package hivens.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import hivens.ui.ShellLook
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.LocalCustomChromeAvailable
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowCapabilities
import hivens.ui.chrome.LocalWindowFrame
import hivens.ui.chrome.LocalWindowHide
import hivens.ui.chrome.LocalWindowHost
import hivens.ui.chrome.LocalWindowState
import hivens.ui.customization.LocalCustomization
import hivens.ui.host.DesktopWindowBackend
import hivens.ui.host.HostedWindow
import hivens.ui.host.WindowCommand
import hivens.ui.host.WindowId
import hivens.ui.host.WindowOwner
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.LocaleProvider
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxTheme
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetScope
import hivens.window.WindowHost
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.koinInject
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The console in a window of its own: the same [ConsoleContent] the pack page embeds,
 * open while the console service says it is.
 *
 * A window of the application beside the main one, not inside the main window's
 * composition, so it lives until it is closed or the launcher quits, whatever the
 * main window does meanwhile: hiding the main window to the tray leaves it open.
 * It is an independent window in the host's registry, with its own [WindowHost], its
 * own window level, its own layers and dismiss stack, and the window locals of its
 * own window. It draws in the look the main shell decided, read through [look].
 * Its frame is the system's, so nothing of the app's frame is offered in it.
 */
@Composable
fun ConsoleWindow(look: ShellLook) {
    val console: GameConsoleService = koinInject()
    if (!console.shouldShowConsole) return
    val appLevel: WidgetScope = koinInject()
    // Collected here rather than by the main shell: the store publishes on every
    // reported slider value, and nothing else should repaint for it.
    val settingsStore: ConsoleSettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsState()
    val windowState = rememberWindowState(width = 960.dp, height = 620.dp)
    val layers = rememberWindowLayers()
    val close = remember(console) { { console.hide() } }

    LocaleProvider(locale = look.locale) {
        Window(
            onCloseRequest    = close,
            state             = windowState,
            title             = LocalStrings.current.consoleTitle,
            alwaysOnTop       = false,
            undecorated       = false,
            // The window's own dismiss stack hears every key first and last, as the
            // main window's does. Escape that nothing in the console used closes
            // the topmost of its layers.
            onPreviewKeyEvent = layers.dismiss::onPreviewKeyEvent,
            onKeyEvent        = layers.dismiss::onKeyEvent,
        ) {
            // Attached once the window is on screen. In its first composition the
            // window already has a native handle but is not mapped yet, and the
            // window manager marks only a mapped window as one it manages, so an
            // earlier attach finds nothing to read what it allows from.
            val hosts = remember { MutableStateFlow<WindowHost?>(null) }
            DisposableEffect(window) {
                fun attach() {
                    if (hosts.value != null || !window.isShowing || window.windowHandle == 0L) return
                    hosts.value = WindowHost.attach(window = window, handle = window.windowHandle, customFrame = false, firstRestore = null)
                }
                val opened = object : WindowAdapter() {
                    override fun windowOpened(e: WindowEvent) = attach()
                }
                window.addWindowListener(opened)
                attach()
                onDispose {
                    window.removeWindowListener(opened)
                    hosts.value?.close()
                    hosts.value = null
                }
            }
            val host by hosts.collectAsState()
            val scope = rememberCoroutineScope()
            val backend = remember(hosts) {
                DesktopWindowBackend(hosts, scope, WindowCommand.entries.toSet(), onClose = close)
            }
            val hostState = backend.state.collectAsState()
            val capabilities by remember(hostState) { derivedStateOf { hostState.value.allowed } }

            CompositionLocalProvider(
                LocalScope                 provides appLevel,
                LocalCustomization         provides look.customization,
                LocalComposeWindow         provides window,
                LocalWindowHost            provides host,
                LocalWindowState           provides windowState,
                LocalChromeClose           provides close,
                LocalWindowHide            provides close,
                LocalUseCustomChrome       provides false,
                LocalCustomChromeAvailable provides false,
                LocalWindowFrame           provides null,
                LocalWindowCapabilities    provides capabilities,
            ) {
                HostedWindow(WindowId.Console, WindowOwner.Launcher, backend) {
                    // Around the theme, as in the main window: the layers belong to the
                    // window, and a layer takes its theme from where it is opened.
                    LayerHost(layers, Modifier.fillMaxSize()) {
                        NxTheme(theme = look.theme, dark = look.dark, uiFamily = look.uiFamily) {
                            NxSurface(SurfaceKind.Page, Modifier.fillMaxSize(), shape = RectangleShape) {
                                ConsoleContent(settings = settings, onSettingsChange = settingsStore::update)
                            }
                        }
                    }
                }
            }
        }
    }
}
