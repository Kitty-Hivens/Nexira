package hivens.ui.host

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import hivens.i18n.api.LocalTranslator
import hivens.ui.CompositionContent
import hivens.ui.ShellLook
import hivens.ui.ShellLookValues
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.LocalCustomChromeAvailable
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowCapabilities
import hivens.ui.chrome.LocalWindowFrame
import hivens.ui.chrome.LocalWindowHide
import hivens.ui.chrome.LocalWindowHost
import hivens.ui.chrome.LocalWindowState
import hivens.ui.chrome.minimum
import hivens.ui.chrome.size
import hivens.ui.chrome.titleIn
import hivens.ui.customization.LocalCustomization
import hivens.ui.i18n.nexiraStrings
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.read
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxTheme
import hivens.widget.api.WidgetScope
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Document
import hivens.window.WindowHost
import hivens.window.WindowManagerProbe
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.koinInject
import org.koin.core.KoinApplication
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * A window for each composition of [document] that [CompositionWindows] has open, beside the main window: the
 * console, say. Each is a window of the application in its own right, so it lives until it is closed or the launcher
 * quits, whatever the main window does meanwhile, and hiding the main window to the tray leaves it open.
 *
 * [koin] is the launcher's graph, which they are drawn inside (see [HostKoin]).
 */
@Composable
fun OpenedWindows(document: Document, look: ShellLook, koin: KoinApplication) {
    // Inside the launcher's graph here rather than by the caller, so no caller can open
    // a window without the graph it injects from.
    HostKoin(koin) {
        val windows: CompositionWindows = koinInject()
        val open by windows.open.collectAsState()
        open.forEach { name ->
            val composition = document.compositions[name] ?: return@forEach
            key(name) {
                CompositionWindow(name, composition, WindowId(name.value), WindowOwner.Launcher, windows, look)
            }
        }
    }
}

/**
 * A window for each composition [windows] has open, owned by the composition this is drawn in: a window of its own
 * for each, which goes when that composition goes, whether it closes or the window showing it does. Drawn inside the
 * owner, so the window reads what the owner reads and its level stands under the owner's.
 */
@Composable
fun OwnedCompositionWindows(document: Document, windows: CompositionWindows) {
    val open by windows.open.collectAsState()
    if (open.isEmpty()) return
    // A composition drawn where the host has no window for it, a test say, owns nothing it could be held to.
    val opener = LocalHostWindow.current ?: return
    open.forEach { name ->
        val composition = document.compositions[name] ?: return@forEach
        key(name) {
            CompositionWindow(name, composition, WindowId("${opener.composition}/${name.value}"), WindowOwner.Composition(opener.composition), windows, look = null)
        }
    }
}

/**
 * The window [composition] is shown in, [id] in the host's registry and held by [owner], with its own [WindowHost],
 * its own window level, its own layers and dismiss stack, and the window locals of its own window. It draws in the
 * answers of every axis of the look, read off the app level as the main window reads them. A window apart from any
 * composition is drawn beside the main window and takes the words, the face and the customization the main shell
 * decided through [look], and the kernel's locals. One a composition owns is drawn inside it, has a null [look], and
 * takes all of that from where it is drawn. The frame is what the composition asks of the window. Nothing of the main
 * window's frame is offered in it, so a composition that asks for no system frame draws its own.
 */
@Composable
private fun CompositionWindow(
    name: CompositionName,
    composition: Composition,
    id: WindowId,
    owner: WindowOwner,
    windows: CompositionWindows,
    look: ShellLook?,
) {
    val frame = composition.frame
    val appLevel: WidgetScope = koinInject()
    val windowState = rememberWindowState(
        placement = if (frame.opensMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        width     = frame.size.width.dp,
        height    = frame.size.height.dp,
    )
    val layers = rememberWindowLayers()
    val close = remember(windows, name) { { windows.close(name) } }
    val ownFrame = !frame.systemChrome && WindowManagerProbe.allowsCustomFrame

    Apart(look) {
        Window(
            onCloseRequest    = close,
            state             = windowState,
            title             = frame.titleIn(nexiraStrings()),
            alwaysOnTop       = false,
            undecorated       = ownFrame,
            resizable         = frame.resizable,
            // The window's own dismiss stack hears every key first and last, as the
            // main window's does. Escape that nothing in it used closes the topmost
            // of its layers.
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
                    hosts.value = WindowHost.attach(window = window, handle = window.windowHandle, customFrame = ownFrame, firstRestore = null, restoreSize = frame.size)
                        .also { host -> frame.minimum?.let(host::keepMinimumSize) }
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
            // Asked for again while it is open: it comes to the front.
            LaunchedEffect(windows, name, backend) {
                windows.raises.collect { raised -> if (raised == name) backend.raise() }
            }
            val hostState = backend.state.collectAsState()
            val capabilities by remember(hostState) { derivedStateOf { hostState.value.allowed } }

            // Apart from any composition, the kernel's locals around the window's level: the
            // app level among them is what the window's own level is made under. One a
            // composition owns stands under the owner's level, which it has from where it is.
            Kernel(look) {
            CompositionLocalProvider(
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
                appLevel.read(ShellLookValues.Look).Provide {
                    HostedWindow(id, owner, backend) {
                        // Around the theme, as in the main window: the layers belong to the
                        // window, and a layer takes its theme from where it is opened.
                        LayerHost(layers, Modifier.fillMaxSize()) {
                            Themed(look) {
                                NxSurface(SurfaceKind.Page, Modifier.fillMaxSize(), shape = RectangleShape) {
                                    CompositionContent(name, composition.start)
                                }
                            }
                        }
                    }
                }
            }
            } // end Kernel
        }
    }
}

/** The words and the customization the main shell decided, for a window apart from any composition. One drawn inside a composition has them already. */
@Composable
private fun Apart(look: ShellLook?, content: @Composable () -> Unit) {
    if (look == null) {
        content()
    } else {
        CompositionLocalProvider(LocalTranslator provides look.words, LocalCustomization provides look.customization, content = content)
    }
}

/** The bridge to the look for a window apart. One a composition owns is drawn in the bridge of its owner already. */
@Composable
private fun Themed(look: ShellLook?, content: @Composable () -> Unit) {
    if (look == null) content() else NxTheme(interfaceText = look.interfaceText, content = content)
}

@Composable
private fun Kernel(look: ShellLook?, content: @Composable () -> Unit) {
    if (look == null) content() else KernelLocals(content)
}
