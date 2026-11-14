package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import coil3.ComponentRegistry
import hivens.ui.background.BackgroundSettings
import hivens.ui.background.CustomBackground
import hivens.ui.chrome.ExtraButton
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.awtOnX11
import hivens.ui.chrome.extraButton
import hivens.ui.chrome.isFrom
import hivens.ui.chrome.resendAsSidewaysWheel
import hivens.ui.layers.LocalWindowLayers
import hivens.ui.layers.WindowLayers
import hivens.ui.render.SvgImageDecoder
import hivens.ui.theme.WallpaperTone
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent

@Composable
internal actual fun NavigationInputs(backStack: NavBackStack) {
    // Mouse side buttons (back/forward) -> history navigation. Compose's pointer
    // layer only surfaces primary/secondary/tertiary on this platform, so listen at
    // the AWT level where the thumb buttons still arrive. The AWT event thread is
    // the Compose UI thread in Compose Desktop, so mutating the NavBackStack here is
    // on the right thread.
    //
    // Which number is which depends on the toolkit (see extraButton): on X11 the
    // sideways wheel arrives here too, and goes back to the window as a wheel.
    //
    // A toolkit listener hears every window the process has, so history moves only
    // for a press in this one. Unfiltered, a thumb press in the separate console
    // window paged the main window's history behind it. The sideways wheel goes back
    // to whichever window it came from.
    //
    // Back is back, as Escape and the phone's gesture are: what the window's dismiss
    // stack has open closes first, an editor step included, and history moves only
    // when nothing is. Forward has nothing to close, and does nothing while a modal
    // holds the window, rather than move the page behind it.
    val mainWindow by rememberUpdatedState(LocalComposeWindow.current)
    val layers by rememberUpdatedState(LocalWindowLayers.current)
    DisposableEffect(Unit) {
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = AWTEventListener { ev ->
            if (ev is MouseEvent && ev.id == MouseEvent.MOUSE_PRESSED) {
                when (extraButton(ev.button, awtOnX11)) {
                    ExtraButton.Back        -> if (ev.isFrom(mainWindow)) thumbBack(layers, backStack)
                    ExtraButton.Forward     -> if (ev.isFrom(mainWindow)) thumbForward(layers, backStack)
                    ExtraButton.ScrollLeft  -> resendAsSidewaysWheel(ev, toRight = false)
                    ExtraButton.ScrollRight -> resendAsSidewaysWheel(ev, toRight = true)
                    ExtraButton.None        -> Unit
                }
            }
        }
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
}

/** The mouse's back button: closes the top of [layers]' dismiss stack, or moves [backStack] back when nothing is open. */
internal fun thumbBack(layers: WindowLayers?, backStack: NavBackStack) {
    val stack = layers?.dismiss
    if (stack != null && stack.handlesBack) stack.dismissTop() else backStack.back()
}

/** The mouse's forward button: moves [backStack] forward unless a modal holds the window. */
internal fun thumbForward(layers: WindowLayers?, backStack: NavBackStack) {
    if (layers?.modalOpen != true) backStack.forward()
}

@Composable
internal actual fun WallpaperLayer(
    settings: BackgroundSettings,
    mousePosProvider: () -> Offset,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
) {
    CustomBackground(
        settings         = settings,
        mousePosProvider = mousePosProvider,
        onTone           = onTone,
        onAudioVolume    = onAudioVolume,
    )
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(SvgImageDecoder.Factory())
}
