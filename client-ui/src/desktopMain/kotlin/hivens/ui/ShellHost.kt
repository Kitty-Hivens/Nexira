package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import hivens.config.Branding
import hivens.ui.bootstrap.GuiBootstrap
import hivens.ui.chrome.IS_TILING_WM
import hivens.ui.chrome.screenWorkArea
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.threshold.BootOutcome
import hivens.ui.threshold.BootStage
import hivens.ui.threshold.ThresholdOverlay
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource

/**
 * Late-bound window callbacks. The window is created BEFORE Koin exists, so
 * its close / key handlers cannot capture shell services at creation time;
 * the shell content assigns the real handlers once it mounts. Plain vars,
 * not State -- the window reads them at event time, not composition time.
 */
class WindowChromeHooks(defaultClose: () -> Unit) {
    var onCloseRequest: () -> Unit = defaultClose
    var onPreviewKey: (KeyEvent) -> Boolean = { false }
}

/**
 * The ONE window, owned from the first frame to process exit. Boot happens
 * BEHIND it: while the boot thread runs, the [ThresholdOverlay] covers the
 * canvas; when [outcomeFlow] flips to Ready the shell content mounts UNDER
 * the still-opaque overlay (masking its expensive first composition), and
 * the overlay's flood transition reveals it. Content switches, the window
 * never remaps -- the whole point on XWayland, where window recreation is
 * a visible flash.
 *
 * Window-creation-time values that normally live behind Koin (undecorated
 * chrome, locale) come from the pre-Koin [GuiBootstrap.PreBoot.peek].
 */
@Composable
fun ApplicationScope.ShellHost(
    pre: GuiBootstrap.PreBoot,
    outcomeFlow: StateFlow<BootOutcome?>,
    stageFlow: StateFlow<BootStage>,
) {
    val outcome by outcomeFlow.collectAsState()

    // Placement policy by chrome:
    //  - tiling WM: the compositor owns geometry/fullscreen; Floating + platform
    //    default lets it place us (forcing MAXIMIZED_BOTH fights it).
    //  - undecorated custom chrome (Win / mac / floating-DE): open "maximized" as
    //    Floating sized to the work area, so we never hit AWT's broken undecorated
    //    MAXIMIZED_BOTH (racy glyph, taskbar overlap, wrong monitor -- see
    //    WindowMaximizer). Compose applies size/position while still !visible, so
    //    there is no remap/flash behind the boot overlay.
    //  - decorated non-tiling (custom chrome off): the OS frame's native maximize
    //    is correct, so keep MAXIMIZED_BOTH.
    val undecorated = pre.peek.useCustomChrome && !IS_TILING_WM
    val openArea = remember(undecorated) {
        if (undecorated) screenWorkArea(
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration,
        ) else null
    }
    val windowState = rememberWindowState(
        placement = if (undecorated || IS_TILING_WM) WindowPlacement.Floating else WindowPlacement.Maximized,
        position  = if (openArea != null) WindowPosition.Absolute(openArea.x.dp, openArea.y.dp)
                    else WindowPosition.PlatformDefault,
        // Restore/default floating size for the non-undecorated cases (ignored under
        // MAXIMIZED_BOTH, overridden by a tiling WM); the library default otherwise.
        size      = if (openArea != null) DpSize(openArea.width.dp, openArea.height.dp)
                    else DpSize(800.dp, 600.dp),
    )
    val visibleState = remember { mutableStateOf(true) }
    val chrome = remember { WindowChromeHooks(defaultClose = { exitApplication() }) }
    val windowIcon = painterResource(Res.drawable.icon)
    val thresholdStrings = remember { stringsFor(AppLocale.fromTag(pre.peek.locale)) }

    // A recovery restart re-enters with boot already done: skip the
    // threshold entirely, no black frame.
    var thresholdDone by remember { mutableStateOf(outcomeFlow.value is BootOutcome.Ready) }

    Window(
        onCloseRequest = { chrome.onCloseRequest() },
        state          = windowState,
        visible        = visibleState.value,
        title          = Branding.TITLE,
        // Replace the OS title bar with our own -- but ONLY where the OS draws
        // one. A tiling WM draws no title bar for tiled windows (nothing to
        // hide), and an undecorated AWT window there ignores the WM's external
        // fullscreen state (_NET_WM_STATE_FULLSCREEN), so super+F breaks.
        // Peeked pre-Koin because flipping undecorated later recreates the
        // AWT peer -- a visible flash.
        undecorated    = undecorated,
        resizable      = true,
        icon           = windowIcon,
        onPreviewKeyEvent = { chrome.onPreviewKey(it) },
    ) {
        Box(Modifier.fillMaxSize()) {
            (outcome as? BootOutcome.Ready)?.let { ready ->
                AppShellContent(
                    boot            = ready.result,
                    windowState     = windowState,
                    visibleState    = visibleState,
                    chrome          = chrome,
                    openedMaximized = undecorated,
                    exitApp         = { exitApplication() },
                )
            }
            if (!thresholdDone) {
                ThresholdOverlay(
                    stageFlow = stageFlow,
                    outcome   = outcome,
                    strings   = thresholdStrings,
                    logsDir   = pre.core.initialPaths.logsDir,
                    dark      = pre.peek.isDarkTheme,
                    onQuit    = { exitApplication() },
                    onDone    = { thresholdDone = true },
                )
            }
        }
    }
}
