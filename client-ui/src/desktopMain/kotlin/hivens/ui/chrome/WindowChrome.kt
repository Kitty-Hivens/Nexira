package hivens.ui.chrome

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowState
import kotlin.math.roundToInt

/**
 * Window-chrome plumbing for the custom (undecorated) title bar.
 *
 * Window-scoped handles -- the [WindowState], the AWT window, the close action --
 * live in AppShell's `Window {}` (a FrameWindowScope) and are NOT reachable from a
 * widget rendered deep under SlotRenderer. AppShell provides them through these
 * locals so the top-bar widgets (caption buttons, drag) can act on the window.
 */
val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }
val LocalComposeWindow = staticCompositionLocalOf<ComposeWindow?> { null }

/** The window's real close path (tray-hide-or-exit + chaos dialog). Default no-op
 *  so a widget previewed outside the window does not crash. */
val LocalChromeClose = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Whether the in-app top bar replaces the OS chrome (undecorated window). Mirrors
 * SettingsData.useCustomChrome, provided from AppShell. When false the OS title
 * bar is shown, so the bar's caption buttons / window-drag must stand down (the
 * OS handles minimize/maximize/close/move). The breadcrumb still renders -- it is
 * app content, not window chrome. Default true (the breadcrumb-only widget preview).
 */
val LocalUseCustomChrome = staticCompositionLocalOf { true }

/**
 * Whether the caption buttons (minimize / maximize / close) should show by
 * default. On a tiling WM the window manager owns those actions and an in-app
 * copy is redundant clutter, so the default hides them there; on Windows / macOS
 * / a floating DE the user expects them. [WindowControlsMode] lets the user force
 * either way regardless of detection.
 */
@kotlinx.serialization.Serializable
enum class WindowControlsMode { Auto, Show, Hide }

fun WindowControlsMode.resolved(): Boolean = when (this) {
    WindowControlsMode.Show -> true
    WindowControlsMode.Hide -> false
    WindowControlsMode.Auto -> !IS_TILING_WM
}

private val TILING_WM_TOKENS = listOf(
    "hyprland", "sway", "i3", "river", "bspwm", "dwm", "qtile", "wayfire",
    "hikari", "awesome", "xmonad", "herbstluft", "spectrwm", "leftwm", "niri",
)

/** Best-effort tiling-WM detection (Linux only). Floating DEs and Windows/macOS
 *  return false, so caption buttons default on there. */
val IS_TILING_WM: Boolean = run {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return@run false
    val env = System.getenv()
    if (!env["HYPRLAND_INSTANCE_SIGNATURE"].isNullOrBlank()) return@run true
    if (!env["SWAYSOCK"].isNullOrBlank()) return@run true
    if (!env["I3SOCK"].isNullOrBlank()) return@run true
    val desktop = buildString {
        append(env["XDG_CURRENT_DESKTOP"].orEmpty()); append(' ')
        append(env["XDG_SESSION_DESKTOP"].orEmpty()); append(' ')
        append(env["DESKTOP_SESSION"].orEmpty())
    }.lowercase()
    TILING_WM_TOKENS.any { it in desktop }
}

/**
 * Drags the OS window when the user drags this region (the bar's empty space).
 * Replaces FrameWindowScope.WindowDraggableArea, which a deep widget can't reach:
 * moves the AWT window by the pointer delta (works under X11 / XWayland).
 * Double-click toggles maximize via [onDoubleClick]. No-op when [window] is null.
 */
fun Modifier.windowDragArea(window: ComposeWindow?, onDoubleClick: (() -> Unit)? = null): Modifier {
    // On a tiling WM the compositor owns window position (and maximize) -- a
    // client-side AWT move fights it. Leave moving to the WM there, same as the
    // resize grips. Drag-to-move only makes sense for OS-floating windows.
    if (window == null || IS_TILING_WM) return this
    return this
        .pointerInput(window, onDoubleClick) {
            detectTapGestures(onDoubleTap = { onDoubleClick?.invoke() })
        }
        .pointerInput(window) {
            detectDragGestures { change, drag ->
                change.consume()
                val p = window.location
                window.setLocation(p.x + drag.x.roundToInt(), p.y + drag.y.roundToInt())
            }
        }
}
