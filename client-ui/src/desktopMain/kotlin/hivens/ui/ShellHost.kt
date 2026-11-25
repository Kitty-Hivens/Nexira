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
import hivens.core.platform.OS
import hivens.launcher.platform.AppRelauncher
import hivens.ui.bootstrap.GuiBootstrap
import hivens.ui.bootstrap.RecoveryEntry
import hivens.ui.chrome.OPENING_FLOOR
import hivens.ui.chrome.size
import hivens.ui.chrome.titleIn
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.icon
import hivens.ui.i18n.NexiraStrings
import hivens.ui.screens.ConsoleWindow
import hivens.ui.theme.NxTheme
import hivens.ui.threshold.BootOutcome
import hivens.ui.threshold.BootStage
import hivens.ui.threshold.ThresholdOverlay
import hivens.ui.threshold.ThresholdPalette
import hivens.widget.model.CompositionName
import hivens.widget.model.Frame
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
 * Window-creation-time values that normally live behind Koin come from before
 * it: the frame the composition asks for from the document
 * ([GuiBootstrap.PreBoot.document]), whether the person wants the system's frame
 * instead from the pre-Koin [GuiBootstrap.PreBoot.peek], and the
 * words and the look from what the modules compiled in answered before the boot
 * ([GuiBootstrap.PreBoot.boot]). The boot screen is drawn day or night as that look
 * answers whether it is dark. Once the boot is ready,
 * the shell and the console each draw themselves inside the boot's own Koin,
 * which is never registered globally.
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

    // What the composition asks of its window. The person's own choice of the system's
    // frame is stronger than a composition that would draw one.
    val frame = remember { pre.document.startComposition?.frame ?: Frame() }
    val consoleFrame = remember { pre.document.compositions[CompositionName("console")]?.frame ?: Frame() }
    val undecorated = !frame.systemChrome && pre.peek.useCustomChrome && WindowManagerProbe.allowsCustomFrame
    // Opens the way it was left: maximised, or as a window with the frame it had.
    // A first run opens the way the frame asks, and under a window manager that places
    // windows itself it is born at the size that will be (see [openingFrame]).
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
            restore   = frame.size,
            floor     = OPENING_FLOOR,
            placeable = WindowManagerProbe.allowsCustomFrame,
            firstMaximized = frame.opensMaximized,
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
    // The window speaks and is drawn before any module's keys are up, so it reads what the
    // modules compiled in answered before the boot, which the pre-boot started working out off
    // this thread (see PreBoot.boot), and the shell's fallbacks for what none answered.
    val boot = remember { pre.boot.join() }
    val bootWords = remember(boot) { ShellLookValues.Words.atBoot(boot) }
    val thresholdStrings = remember(bootWords) { bootWords.strings(NexiraStrings.NAMESPACE) }
    val look = remember { ShellLook(words = bootWords) }
    // The look the boot window opens in, the person's choice as the look manager answered it before
    // the boot. The shell's own look takes its place inside once it mounts.
    val bootLook = remember(boot) { ShellLookValues.Look.atBoot(boot) }

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
        title          = frame.titleIn(thresholdStrings),
        // Replace the OS title bar with our own -- but ONLY where the window
        // manager leaves the frame to the window (WindowManagerProbe). One that
        // manages every window itself draws no title bar to replace, and an
        // undecorated AWT window there ignores its external fullscreen state
        // (_NET_WM_STATE_FULLSCREEN), so super+F breaks.
        // Peeked pre-Koin because flipping undecorated later recreates the
        // AWT peer -- a visible flash.
        undecorated    = undecorated,
        resizable      = frame.resizable,
        icon           = windowIcon,
        onPreviewKeyEvent = { chrome.onPreviewKey(it) },
        onKeyEvent        = { chrome.onKey(it) },
    ) {
        if (maximizeFromInside) {
            LaunchedEffect(Unit) {
                window.maximizedBounds = screenWorkArea(window.graphicsConfiguration)
                // Through the state, not the frame: Compose applies the state's
                // placement to the frame, and a frame maximised behind its back
                // is put back to the Floating the state still says.
                windowState.placement = WindowPlacement.Maximized
            }
        }
        bootLook.Provide {
            // Day or night for the boot screen, as the look answered it before the boot.
            val bootDark = NxTheme.isDark
            // What shows where Skia has not drawn yet, until the shell's theme takes
            // over (see AppShell): the boot screen's own background, not white.
            LaunchedEffect(Unit) {
                val field = (if (bootDark) ThresholdPalette.Dark else ThresholdPalette.Light).field
                window.fillUnpainted(AwtColor(field.toArgb(), false))
            }
            Box(Modifier.fillMaxSize()) {
                (outcome as? BootOutcome.Ready)?.let { ready ->
                    AppShellContent(
                        boot            = ready.result,
                        windowState     = windowState,
                        visibleState    = visibleState,
                        chrome          = chrome,
                        opening         = opening,
                        frame           = frame,
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
                        dark      = bootDark,
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
    }
    // Koin is up once the boot is ready, and the console asks it for its service.
    (outcome as? BootOutcome.Ready)?.let { ready -> ConsoleWindow(look, consoleFrame, ready.result.koin) }
}
