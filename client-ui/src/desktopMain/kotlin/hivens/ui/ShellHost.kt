package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import hivens.config.Branding
import hivens.core.platform.OS
import hivens.launcher.platform.AppRelauncher
import hivens.ui.bootstrap.GuiBootstrap
import hivens.ui.bootstrap.RecoveryEntry
import hivens.ui.chrome.OPENING_FLOOR
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.screens.ConsoleWindow
import hivens.ui.threshold.BootOutcome
import hivens.ui.threshold.BootStage
import hivens.ui.threshold.ThresholdOverlay
import hivens.ui.threshold.ThresholdPalette
import hivens.window.RESTORE_WINDOW_SIZE
import hivens.window.SavedWindow
import hivens.window.WindowManagerProbe
import hivens.window.currentWorkAreas
import hivens.window.fillUnpainted
import hivens.window.openingFrame
import hivens.window.screenWorkArea
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource
import java.awt.Color as AwtColor

/**
 * Late-bound window callbacks. The window is created BEFORE Koin exists, so
 * its close / key handlers cannot capture shell services at creation time;
 * the shell content assigns the real handlers once it mounts. Plain vars,
 * not State -- the window reads them at event time, not composition time.
 */
class WindowChromeHooks(defaultClose: () -> Unit) {
    var onCloseRequest: () -> Unit = defaultClose
    /** Sees every key before anything focused does. */
    var onPreviewKey: (KeyEvent) -> Boolean = { false }
    /** Sees a key after everything focused has left it. */
    var onKey: (KeyEvent) -> Boolean = { false }
}

/**
 * The main window, owned from the first frame to process exit, and once the boot
 * is done the console window beside it. Boot happens
 * BEHIND it: while the boot thread runs, the [ThresholdOverlay] covers the
 * canvas; when [outcomeFlow] flips to Ready the shell content mounts UNDER
 * the still-opaque overlay (masking its expensive first composition), and
 * the overlay's flood transition reveals it. Content switches, the window
 * never remaps -- the whole point on XWayland, where window recreation is
 * a visible flash.
 *
 * Window-creation-time values that normally live behind Koin (undecorated
 * chrome, locale) come from the pre-Koin [GuiBootstrap.PreBoot.peek].
 *
 * The console is a window of the application in its own right, a sibling of the
 * main window here rather than a part of its content: hiding the main window to
 * the tray leaves it open, and quitting ends the application and both windows with
 * it. It draws in the look the main shell publishes to [ShellLook].
 */
@Composable
fun ApplicationScope.ShellHost(
    pre: GuiBootstrap.PreBoot,
    outcomeFlow: StateFlow<BootOutcome?>,
    stageFlow: StateFlow<BootStage>,
    isRestart: Boolean = false,
) {
    val outcome by outcomeFlow.collectAsState()

    val undecorated = pre.peek.useCustomChrome && WindowManagerProbe.allowsCustomFrame
    // Opens the way it was left: maximised, or as a window with the frame it had.
    // A first run, and a window manager that places windows itself, open
    // maximised and born at the size that will be (see [openingFrame]).
    //
    // The OS owns the maximised geometry per its own rules -- never a
    // hand-computed frame -- and a tiling compositor assigns the frame regardless
    // of what we ask for.
    //
    // The size still matters before any of that happens: Compose draws one frame
    // before showing the window, sized to this request, so asking for less than
    // the window becomes leaves the rest of the frame bare white. That is what a
    // tiled launcher did with the old 1100x720 -- see [initialWindowSize].
    val opening = remember {
        val areas = currentWorkAreas()
        openingFrame(
            saved     = SavedWindow.read(pre.core.initialPaths.dataDir),
            workAreas = areas,
            primary   = areas.firstOrNull() ?: screenWorkArea(null),
            restore   = RESTORE_WINDOW_SIZE,
            floor     = OPENING_FLOOR,
            placeable = WindowManagerProbe.allowsCustomFrame,
        )
    }
    // Windows is the one system where an app decides how big its maximised window
    // is: it asks the app, and AWT answers with the whole monitor for a frame that
    // has no border of its own, taskbar included. maximizedBounds is that answer,
    // and it has to be in place before the window is maximised, so there the
    // window is created as a window over its work area and maximised from inside
    // (below). Elsewhere the window manager works the size out, and asking for it
    // at creation is the state it reads before the window maps.
    val maximizeFromInside = opening.maximized && OS.isWindows
    val windowState = rememberWindowState(
        placement = if (opening.maximized && !maximizeFromInside) WindowPlacement.Maximized else WindowPlacement.Floating,
        position  = if (WindowManagerProbe.allowsCustomFrame) WindowPosition(opening.bounds.x.dp, opening.bounds.y.dp)
                    else WindowPosition.PlatformDefault,
        size      = DpSize(opening.bounds.width.dp, opening.bounds.height.dp),
    )
    val visibleState = remember { mutableStateOf(true) }
    val chrome = remember { WindowChromeHooks(defaultClose = { exitApplication() }) }
    val windowIcon = painterResource(Res.drawable.icon)
    val thresholdStrings = remember { stringsFor(AppLocale.fromTag(pre.peek.locale)) }
    val look = remember { ShellLook(dark = pre.peek.isDarkTheme, locale = AppLocale.fromTag(pre.peek.locale)) }

    // A recovery restart with boot already done skips the threshold entirely
    // (no black frame). The restart flag is EXPLICIT, not inferred from the
    // outcome: a first boot that finishes before this first composition must
    // still play the threshold -- the readout always shows -- and must keep
    // masking the shell's expensive first composition. A restart that crashed
    // MID-boot (outcome still null) also keeps the threshold, or the window
    // would sit empty until boot lands.
    var thresholdDone by remember { mutableStateOf(isRestart && outcomeFlow.value is BootOutcome.Ready) }

    Window(
        onCloseRequest = { chrome.onCloseRequest() },
        state          = windowState,
        visible        = visibleState.value,
        title          = Branding.TITLE,
        // Replace the OS title bar with our own -- but ONLY where the window
        // manager leaves the frame to the window (WindowManagerProbe). One that
        // manages every window itself draws no title bar to replace, and an
        // undecorated AWT window there ignores its external fullscreen state
        // (_NET_WM_STATE_FULLSCREEN), so super+F breaks.
        // Peeked pre-Koin because flipping undecorated later recreates the
        // AWT peer -- a visible flash.
        undecorated    = undecorated,
        resizable      = true,
        icon           = windowIcon,
        onPreviewKeyEvent = { chrome.onPreviewKey(it) },
        onKeyEvent        = { chrome.onKey(it) },
    ) {
        // What shows where Skia has not drawn yet, until the shell's theme takes
        // over (see AppShell): the boot screen's own background, not white.
        LaunchedEffect(Unit) {
            val field = (if (pre.peek.isDarkTheme) ThresholdPalette.Dark else ThresholdPalette.Light).field
            window.fillUnpainted(AwtColor(field.toArgb(), false))
        }
        if (maximizeFromInside) {
            LaunchedEffect(Unit) {
                window.maximizedBounds = screenWorkArea(window.graphicsConfiguration)
                // Through the state, not the frame: Compose applies the state's
                // placement to the frame, and a frame maximised behind its back
                // is put back to the Floating the state still says.
                windowState.placement = WindowPlacement.Maximized
            }
        }
        Box(Modifier.fillMaxSize()) {
            (outcome as? BootOutcome.Ready)?.let { ready ->
                AppShellContent(
                    boot            = ready.result,
                    windowState     = windowState,
                    visibleState    = visibleState,
                    chrome          = chrome,
                    opening         = opening,
                    look            = look,
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
                    onRecovery = {
                        RecoveryEntry.requestOnNextBoot(pre.core.initialPaths.dataDir)
                        AppRelauncher.relaunch().also { relaunched -> if (relaunched) exitApplication() }
                    },
                )
            }
        }
    }
    // Koin is up once the boot is ready, and the console asks it for its service.
    if (outcome is BootOutcome.Ready) ConsoleWindow(look)
}
